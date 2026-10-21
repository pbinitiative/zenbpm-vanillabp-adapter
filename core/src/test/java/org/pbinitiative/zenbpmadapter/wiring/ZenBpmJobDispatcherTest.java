/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ReceivedJob;
import org.pbinitiative.zenbpmadapter.client.ZenBpmExecutor;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.WaitingJob;
import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the dispatcher of an adapter id hands the jobs of the job stream to the handler slots: every
 * job runs, also where more jobs come than there are slots; a job whose lock lapsed while it waited
 * is not started; a job goes to the module which serves its instance's process with its type; a job
 * of a type no module subscribes, of a process no module serves, of an instance the engine does not
 * know or cannot answer for, of a module which stopped, and one arriving while the adapter shuts
 * down is left to its lock. Every job the stream hands over is counted, a delivery of a job the
 * engine handed out before as a redelivery, and a job which waits for a slot once as queued.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmJobDispatcherTest {

    private static final long INSTANCE = 100;

    private JobRunning jobs;

    @BeforeEach
    void startTheEngine() {
        jobs = new JobRunning(1);
        jobs.holds(INSTANCE, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(INSTANCE);
        jobs.engine.answers("POST", "/v1/jobs/41/complete", 201, null);
        jobs.engine.answers("POST", "/v1/jobs/43/complete", 201, null);
    }

    @AfterEach
    void stopTheEngine() {
        jobs.close();
    }

    @Test
    @DisplayName("More jobs than slots all run, one after the other, in the order they came, and each one which waited for the slot is counted once as queued")
    void moreJobsThanSlotsAllRun() throws InterruptedException {
        final var release = new CountDownLatch(1);
        jobs.invoker.answers(context -> {
            awaitQuietly(release);
            return WorkflowTaskOutcome.completed();
        });

        jobs.dispatcher.accept(job(41));
        jobs.dispatcher.accept(job(42));
        jobs.dispatcher.accept(job(43));
        final var whileTheFirstRuns = jobs.invoker
                .invocations()
                .size();
        release.countDown();
        JobRunning.await(() -> jobs.invoker
                .invocations()
                .size() == 3, "three invocations");
        jobs.awaitFreeSlots();

        assertAll(
                () -> assertEquals(3, jobs.counted(ZenBpmMetrics.JOBS_RECEIVED)),
                // the second was refused the slot once or twice and the third never, and neither
                // found it free when it arrived
                () -> assertEquals(2, jobs.counted(ZenBpmMetrics.JOBS_QUEUED)),
                () -> assertTrue(whileTheFirstRuns <= 1, () -> "%d invocations".formatted(whileTheFirstRuns)),
                () -> assertEquals(List.of("41", "42", "43"), jobs.invoker
                        .invocations()
                        .stream()
                        .map(invocation -> invocation
                                .context()
                                .getTaskId())
                        .toList()));
    }

    @Test
    @DisplayName("A job refused while a slot came free a moment later takes that slot at once, without waiting for the next freed one")
    void aRefusedJobTakesASlotWhichCameFreeMeanwhile() {
        final var executor = new RefusingOnce();
        final var dispatcher = new ZenBpmJobDispatcher("orders", executor, jobs.factory
                .instances(), new ZenBpmLockRenewal("orders", jobs.factory.rest(), "shop-1", executor
                        .timing(), jobs.metrics), jobs.metrics);
        dispatcher.register(jobs.handlerOf("shop", NameClashAvoidance.USE_PREFIX,
                new WiredProcess("Order", "shop__Order", Set.of(JobRunning.JOB_TYPE), true, false, Map.of(), Map
                        .of(), Map
                                .of())));

        try {
            dispatcher.accept(job(42));
            JobRunning.await(() -> jobs.invoker
                    .invocations()
                    .size() == 1, "the job to run");
        } finally {
            executor.shutdown();
        }

        assertAll(
                () -> assertEquals(2, executor.offers.get()),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_QUEUED),
                        "the slot was free before the job left the dispatcher"));
    }

    @Test
    @DisplayName("A job whose lock lapsed while it waited for a slot is not started, and an INFO says so")
    void aJobWhoseLockLapsedWhileItWaitedIsNotStarted(final CapturedOutput output) throws InterruptedException {
        final var running = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        jobs.invoker.answers(context -> {
            running.countDown();
            awaitQuietly(release);
            return WorkflowTaskOutcome.completed();
        });

        jobs.dispatcher.accept(job(41));
        assertTrue(running.await(10, TimeUnit.SECONDS));
        jobs.dispatcher.accept(JobRunning.job(42, INSTANCE, JobRunning.JOB_TYPE, Instant
                .now()
                .plusMillis(100), 7));
        Thread.sleep(300);
        release.countDown();
        jobs.awaitFreeSlots();

        assertAll(
                () -> assertEquals(1, jobs.invoker
                        .invocations()
                        .size()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("job 42 of type 'shop__Order__ship' waited for a handler slot until its lock lapsed"),
                        output::getAllOfThisTest),
                () -> assertEquals(1, jobs.counted(ZenBpmMetrics.JOBS_QUEUED)));
    }

    @Test
    @DisplayName("A job from an engine whose clock runs a job-timeout behind is started all the same: its lock is counted from its receipt")
    void aJobFromAnEngineClockBehindIsStarted() {
        final var now = Instant.now();
        final var behindTheClock = new ReceivedJob(WaitingJob
                .newBuilder()
                .setKey(42)
                .setInstanceKey(INSTANCE)
                .setType(JobRunning.JOB_TYPE)
                .setElementId("Ship")
                .setDeliveryToken(7)
                .setLockUntil(now
                        .minus(Duration.ofHours(1))
                        .toEpochMilli())
                .build(), now);

        jobs.runs(behindTheClock);

        assertEquals(1, jobs.invoker
                .invocations()
                .size());
    }

    @Test
    @DisplayName("A job goes to the module which serves the process of its instance with its type, where two modules share the type")
    void aJobGoesToTheModuleWhichServesItsProcess() {
        jobs.dispatcher.register(jobs.handlerOf("billing", NameClashAvoidance.NONE,
                new WiredProcess("Invoice", "Invoice", Set.of(JobRunning.JOB_TYPE), true, false, Map.of(), Map.of(), Map
                        .of())));
        jobs.holds(300, "Invoice", "default", null, "invoice-1", "\"id\": \"invoice-1\"");
        jobs.takesTheCommands(300);

        jobs.runs(job(300, 42));
        jobs.runs(job(INSTANCE, 43));

        assertEquals(List.of("billing/Invoice/invoice-1", "shop/Order/order-1"), jobs.invoker
                .invocations()
                .stream()
                .map(invocation -> "%s/%s/%s".formatted(invocation.workflowModuleId(), invocation.bpmnProcessId(),
                        invocation
                                .context()
                                .getWorkflowAggregateId()))
                .toList());
    }

    @Test
    @DisplayName("A job of a process no module serves with its type is left to its lock, with a WARN pointing to use-prefix")
    void aJobOfAProcessNoModuleServesIsLeftToItsLock(final CapturedOutput output) {
        jobs.holds(300, "Invoice", "default", null, "invoice-1", "\"id\": \"invoice-1\"");

        jobs.runs(job(300, 42));

        assertAll(
                () -> assertEquals(List.of(), jobs.invoker.invocations()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "job 42 of type 'shop__Order__ship' belongs to instance 300 of process 'Invoice', which no workflow module of this application serves with that type"),
                        output::getAllOfThisTest),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("'use-prefix'"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A job of a type one module subscribes, whose instance belongs to a process another module claims without serving that type, is left to its lock")
    void aJobOfAProcessWhichDoesNotServeItsTypeIsLeftToItsLock(final CapturedOutput output) {
        jobs.dispatcher.register(jobs.handlerOf("billing", NameClashAvoidance.NONE,
                new WiredProcess("Invoice", "Invoice", Set.of("invoice"), true, false, Map.of(), Map.of(), Map.of())));

        jobs.runs(JobRunning.job(42, INSTANCE, "invoice", Instant
                .now()
                .plus(Duration.ofMinutes(5)), 7));

        assertAll(
                () -> assertEquals(List.of(), jobs.invoker.invocations()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "job 42 of type 'invoice' belongs to instance 100 of process 'shop__Order', which no workflow module of this application serves with that type"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A job of an instance the engine node asked does not know is left to its lock, after the read was repeated")
    void aJobOfAnInstanceNotKnownYetIsLeftToItsLock(final CapturedOutput output) {
        jobs.engine.answers("GET", "/v1/process-instances/300", 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no\"}");

        jobs.runs(job(300, 42));

        assertAll(
                () -> assertEquals(List.of(), jobs.invoker.invocations()),
                () -> assertEquals(5, jobs
                        .bodiesOf("GET", "/v1/process-instances/300")
                        .size()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("instance 300 of job 42 (type 'shop__Order__ship') is not known"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A job of an instance the engine refuses to answer for is left to its lock, with a WARN")
    void aJobOfAnInstanceWhichCannotBeReadIsLeftToItsLock(final CapturedOutput output) {
        jobs.engine.answers("GET", "/v1/process-instances/300", 400, "{\"code\":\"BAD_REQUEST\",\"message\":\"no\"}");

        jobs.runs(job(300, 42));

        assertAll(
                () -> assertEquals(List.of(), jobs.invoker.invocations()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("the instance of job 42 (type 'shop__Order__ship') could not be read"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A job which waits while its module stops is not started any more")
    void aJobOfAModuleWhichStoppedIsNotStarted() throws InterruptedException {
        final var running = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        jobs.invoker.answers(context -> {
            running.countDown();
            awaitQuietly(release);
            return WorkflowTaskOutcome.completed();
        });

        jobs.dispatcher.accept(job(41));
        assertTrue(running.await(10, TimeUnit.SECONDS));
        jobs.dispatcher.accept(job(42));
        jobs.dispatcher.unregister("shop");
        jobs.dispatcher.unregister("shop");
        release.countDown();
        jobs.awaitFreeSlots();
        Thread.sleep(100);

        assertEquals(1, jobs.invoker
                .invocations()
                .size());
    }

    @Test
    @DisplayName("A job whose module stops while the instance of the job is read is not started, and is reported as arriving during the shutdown, not as a job nobody serves")
    void aJobWhoseModuleStopsWhileItsInstanceIsReadIsNotStarted(final CapturedOutput output) {
        jobs.engine.answers("GET", "/v1/process-instances/%d".formatted(INSTANCE), exchange -> {
            jobs.dispatcher.unregister("shop");
            FakeEngine.respond(exchange, 200, """
                    {"key": %d, "processDefinitionKey": 4503599627370498, "bpmnProcessId": "shop__Order",
                     "version": 3, "businessKey": "order-1", "createdAt": "2026-10-17T14:30:25.123Z",
                     "state": "active", "processType": "default", "variables": {"id": "order-1"}}
                    """.formatted(INSTANCE));
        });

        jobs.runs(job(42));

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(List.of(), jobs.invoker.invocations()),
                () -> assertEquals(List.of(), jobs.commands()),
                () -> assertTrue(log.contains(
                        "job 42 (task 'ship') arrived while workflow module 'shop' shuts down, so it is not started"),
                        log),
                () -> assertFalse(log.contains("'use-prefix'"), log));
    }

    @Test
    @DisplayName("A job which arrives while the adapter shuts down is left to its lock")
    void aJobArrivingWhileTheAdapterShutsDownIsLeftToItsLock() {
        jobs.factory
                .executor()
                .shutdown();

        jobs.dispatcher.accept(job(42));

        assertAll(
                () -> assertEquals(List.of(), jobs.invoker.invocations()),
                () -> assertEquals(1, jobs.counted(ZenBpmMetrics.JOBS_RECEIVED)),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_QUEUED), "nothing waits for a slot any more"));
    }

    @Test
    @DisplayName("Every job the stream hands over is counted as received, and one whose delivery token says that the engine handed it out before as redelivered, a job of a type nobody subscribes included")
    void everyJobIsCountedAndARedeliveryAsSuch() {
        final var lockUntil = Instant
                .now()
                .plus(Duration.ofMinutes(5));

        jobs.runs(JobRunning.job(41, INSTANCE, JobRunning.JOB_TYPE, lockUntil, 1));
        jobs.runs(JobRunning.job(43, INSTANCE, JobRunning.JOB_TYPE, lockUntil, 2));
        // a leader which issues no tokens, as during a rolling upgrade of the engine
        jobs.runs(JobRunning.job(44, INSTANCE, JobRunning.JOB_TYPE, lockUntil, 0));
        jobs.runs(JobRunning.job(45, INSTANCE, "somebody-elses", lockUntil, 3));

        assertAll(
                () -> assertEquals(4, jobs.counted(ZenBpmMetrics.JOBS_RECEIVED)),
                () -> assertEquals(2, jobs.counted(ZenBpmMetrics.JOBS_REDELIVERED)),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_QUEUED), "every job found the slot free"));
    }

    @Test
    @DisplayName("A job another arrival takes out of the queue while its own arrival looks for a slot, and which the slot refuses there, is counted once as queued by that arrival")
    void aJobTakenByAnotherArrivalAndRefusedIsCountedThere() {
        assertEquals(3, queuedWhileTwoArrivalsRace(false), "all three jobs waited for the slot");
    }

    @Test
    @DisplayName("A job another arrival takes out of the queue while its own arrival looks for a slot, and which the slot takes there, is not counted as queued")
    void aJobTakenByAnotherArrivalAndStartedIsNotCounted() {
        assertEquals(2, queuedWhileTwoArrivalsRace(true), "the second job got the slot at once");
    }

    // job 41 waits; while the arrival of job 42 offers 41 the slot again, job 43 arrives on a
    // second thread, whose look takes 42 out of the queue and holds the third offer until the
    // arrival of 42 looked at it
    private long queuedWhileTwoArrivalsRace(final boolean theThirdOfferTakesTheJob) {
        final var executor = new RacedOffers(theThirdOfferTakesTheJob);
        final var dispatcher = new ZenBpmJobDispatcher("orders", executor, jobs.factory
                .instances(), new ZenBpmLockRenewal("orders", jobs.factory.rest(), "shop-1", executor
                        .timing(), jobs.metrics), jobs.metrics);
        dispatcher.register(jobs.handler);
        executor.secondArrival = () -> dispatcher.accept(job(43));
        try {
            dispatcher.accept(job(41));
            dispatcher.accept(job(42));
            executor.firstArrivalLooked.countDown();
            JobRunning.await(() -> !executor.secondThread.isAlive(), "the arrival of job 43 to end");
        } finally {
            executor.firstArrivalLooked.countDown();
            executor.slotHolder.countDown();
            executor.shutdownNow();
        }
        assertEquals(3, jobs.counted(ZenBpmMetrics.JOBS_RECEIVED));
        return jobs.counted(ZenBpmMetrics.JOBS_QUEUED);
    }

    private static ReceivedJob job(final long key) {
        return job(INSTANCE, key);
    }

    private static ReceivedJob job(
        final long instanceKey,
        final long key
    ) {
        return JobRunning.job(key, instanceKey, JobRunning.JOB_TYPE, Instant
                .now()
                .plus(Duration.ofMinutes(5)), 7);
    }

    /**
     * One slot, which refuses the first handler as if it had come free only a moment after the
     * refusal.
     */
    private static final class RefusingOnce extends ZenBpmExecutor {

        private final AtomicInteger offers = new AtomicInteger();

        private RefusingOnce() {
            super("orders", 1, Executors.newSingleThreadExecutor());
        }

        @Override
        public boolean tryExecute(final Runnable handler) {
            if (offers.incrementAndGet() == 1) {
                return false;
            }
            return super.tryExecute(handler);
        }

    }

    /**
     * One slot, taken from the start, and offers whose order the test forces: the second offer
     * starts a second arrival and waits until it offers a job the slot, the third offer waits until
     * the first arrival looked, and only the third may take its job.
     */
    private static final class RacedOffers extends ZenBpmExecutor {

        private final AtomicInteger offers = new AtomicInteger();

        private final CountDownLatch slotHolder = new CountDownLatch(1);

        private final CountDownLatch secondArrivalOffers = new CountDownLatch(1);

        private final CountDownLatch firstArrivalLooked = new CountDownLatch(1);

        private final boolean theThirdOfferTakesTheJob;

        private Runnable secondArrival;

        private Thread secondThread;

        private RacedOffers(final boolean theThirdOfferTakesTheJob) {
            super("orders", 1, Executors.newSingleThreadExecutor());
            this.theThirdOfferTakesTheJob = theThirdOfferTakesTheJob;
            assertTrue(super.tryExecute(() -> awaitQuietly(slotHolder)), "the only slot is taken");
        }

        @Override
        public boolean tryExecute(final Runnable handler) {
            final var offer = offers.incrementAndGet();
            if (offer == 2) {
                secondThread = Thread
                        .ofPlatform()
                        .name("second-arrival")
                        .start(secondArrival);
                awaitQuietly(secondArrivalOffers);
            } else if (offer == 3) {
                secondArrivalOffers.countDown();
                awaitQuietly(firstArrivalLooked);
                return theThirdOfferTakesTheJob;
            }
            return false;
        }

    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
        }
    }

}
