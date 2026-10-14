/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.eventually;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmJobStream.Backoff;
import org.pbinitiative.zenbpmadapter.client.ZenBpmJobStream.Settings;
import org.pbinitiative.zenbpmadapter.client.grpc.ZenBpmGrpc;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.ErrorResult;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.JobStreamRequest;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.JobStreamResponse;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.LockExtended;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.StreamSubscriptionRequest;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.WaitingJob;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the job stream sends and how it reacts to what comes back, against an engine played in
 * the same JVM. That the engine takes the requests and hands out jobs for them is proven by
 * {@link ZenBpmJobStreamIT} against the pinned engine.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmJobStreamTest {

    private static final String ADAPTER_ID = "orders";

    private static final String CLIENT_ID = "shop-orders-0a1b2c3d";

    private static final String TIMING_THREAD_PREFIX = "zenbpm-%s-timing-".formatted(ADAPTER_ID);

    /**
     * Short pauses, so a test of a reconnect does not wait for the real ones.
     */
    private static final Backoff QUICK = new Backoff(Duration.ofMillis(10), Duration.ofMillis(40), Duration.ofMillis(
            100));

    private FakeJobStreamEngine engine;

    private ScheduledExecutorService timing;

    private ZenBpmJobStream stream;

    @BeforeEach
    void startTheEngine() {
        engine = new FakeJobStreamEngine().start();
        final var threads = new AtomicInteger();
        timing = Executors.newScheduledThreadPool(2, task -> new Thread(task, TIMING_THREAD_PREFIX + threads
                .incrementAndGet()));
        stream = new ZenBpmJobStream(ADAPTER_ID, CLIENT_ID, "fake-engine:9090", engine
                .channel(), timing, QUICK, ZenBpmJobStream.CLOSE_TIMEOUT);
    }

    @AfterEach
    void stopEverything() throws InterruptedException {
        stream.close();
        engine.close();
        timing.shutdownNow();
        timing.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Building a stream opens no call, the first subscription does, with the client id")
    void theFirstSubscriptionOpensTheCall() {
        assertEquals(0, engine.callCount());

        stream.subscribe("module-a", "t1", Duration.ofSeconds(90), 4);

        final var call = engine.call(0);
        assertAll(
                () -> assertEquals(CLIENT_ID, call.clientId()),
                () -> assertEquals(List.of(subscribe("t1", 90_000, 4)), call.subscriptions(1)));
    }

    @Test
    @DisplayName("Every subscription carries its lock duration and active-job cap, zero asking for the engine's default")
    void everySubscriptionCarriesItsSettings() {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 10);
        stream.subscribe("module-a", "t2", Duration.ZERO, 0);
        stream.subscribe("module-a", "t3", Duration.ofNanos(1_500_000), 1);

        assertEquals(List.of(subscribe("t1", 300_000, 10), subscribe("t2", 0, 0), subscribe("t3", 2, 1)), engine
                .call(0)
                .subscriptions(3));
    }

    @Test
    @DisplayName("Two subscriptions and one unsubscription of a type leave it subscribed, the second unsubscribes it")
    void aSharedTypeStaysSubscribedUntilTheLastSubscriberLeaves() {
        stream.subscribe("module-a", "shared", Duration.ofMinutes(5), 4);
        stream.subscribe("module-b", "shared", Duration.ofMinutes(5), 4);
        stream.subscribe("module-a", "shared", Duration.ofMinutes(5), 4);
        stream.unsubscribe("module-a", "shared");
        stream.unsubscribe("module-c", "shared");
        stream.unsubscribe("module-a", "unknown");
        stream.subscribe("module-a", "marker", Duration.ZERO, 0);

        final var call = engine.call(0);
        assertEquals(List.of(subscribe("shared", 300_000, 4), subscribe("marker", 0, 0)), call.subscriptions(2));

        stream.unsubscribe("module-b", "shared");

        assertEquals(StreamSubscriptionRequest
                .newBuilder()
                .setJobType("shared")
                .setType(StreamSubscriptionRequest.Type.TYPE_UNSUBSCRIBE)
                .build(),
                call
                        .subscriptions(3)
                        .get(2));
    }

    @Test
    @DisplayName("A second subscriber asking for other settings of a type is refused, naming both")
    void conflictingSettingsOfOneTypeAreRefused() {
        stream.subscribe("module-a", "shared", Duration.ofMinutes(5), 4);

        final var conflict = assertThrows(IllegalStateException.class, () -> stream.subscribe("module-b", "shared",
                Duration.ofMinutes(1), 4));

        assertEquals(
                "ZenBPM[orders]: job type 'shared' is subscribed by ['module-a'] with a lock of PT5M and at most 4 "
                        + "active jobs, and 'module-b' asks for a lock of PT1M and at most 4 active jobs. The engine keeps one "
                        + "lock duration and one cap of active jobs per client and job type, so every subscriber of a job type "
                        + "has to ask for the same.",
                conflict.getMessage());
        stream.subscribe("module-a", "marker", Duration.ZERO, 0);
        assertEquals(List.of(subscribe("shared", 300_000, 4), subscribe("marker", 0, 0)), engine
                .call(0)
                .subscriptions(2));
    }

    @Test
    @DisplayName("After a failed call every subscribed type is sent again on a new one, settings included")
    void aReconnectSubscribesEveryTypeAgain(final CapturedOutput output) {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        stream.subscribe("module-a", "t2", Duration.ofSeconds(30), 2);
        stream.subscribe("module-a", "gone", Duration.ZERO, 0);
        stream.unsubscribe("module-a", "gone");
        engine
                .call(0)
                .subscriptions(4);

        engine
                .call(0)
                .fail(Status.UNAVAILABLE.withDescription("connection reset"));

        final var second = engine.call(1);
        assertAll(
                () -> assertEquals(CLIENT_ID, second.clientId()),
                () -> assertEquals(List.of(subscribe("t1", 300_000, 4), subscribe("t2", 30_000, 2)), second
                        .subscriptions(2)),
                () -> assertEquals(1, stream.reconnects()));
        eventually(() -> Optional
                .of(output.getAllOfThisTest())
                .filter(printed -> printed.contains("job stream to fake-engine:9090 is back")));
        assertTrue(output
                .getAllOfThisTest()
                .contains("WARN  o.p.z.client.ZenBpmJobStream - ZenBPM[orders]: the job stream to fake-engine:9090 is "
                        + "down (UNAVAILABLE: connection reset); no job arrives until it is back. It is opened again "
                        + "after PT0.01S, then after twice as long each time, up to PT0.04S."));
    }

    @Test
    @DisplayName("An engine which is away for a while is warned about once, and reported back once it is")
    void anOutageIsWarnedAboutOnce(final CapturedOutput output) {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        engine
                .call(0)
                .subscriptions(1);

        engine.stop();
        eventually(() -> stream.reconnects() >= 3 ? Optional.of(true) : Optional.empty());
        final var callsWhileAway = engine.callCount();
        engine.start();

        final var back = engine.call(callsWhileAway);
        assertEquals(List.of(subscribe("t1", 300_000, 4)), back.subscriptions(1));
        eventually(() -> Optional
                .of(output.getAllOfThisTest())
                .filter(printed -> printed.contains("is back after")));
        assertEquals(1, occurrences(output.getAllOfThisTest(), "is down ("));
    }

    /**
     * gRPC's channel has a backoff of its own, which grows towards two minutes and would hold the
     * stream's attempts back for that long. After three seconds of failures it already waits
     * about two seconds.
     */
    @Test
    @DisplayName("An engine back in reach is found on the stream's next attempt, not after the channel's own backoff")
    void anEngineBackInReachIsFoundOnTheNextAttempt() {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        engine
                .call(0)
                .subscriptions(1);
        engine.stop();
        final var away = System.nanoTime() + Duration
                .ofSeconds(3)
                .toNanos();
        eventually(() -> System.nanoTime() > away ? Optional.of(true) : Optional.empty());
        final var callsWhileAway = engine.callCount();

        final var restarted = Instant.now();
        engine.start();
        engine.call(callsWhileAway);

        final var tookToFindIt = Duration.between(restarted, Instant.now());
        assertTrue(tookToFindIt.compareTo(Duration.ofSeconds(1)) < 0, tookToFindIt.toString());
    }

    @Test
    @DisplayName("A frame from the engine ends an outage at once")
    void aFrameEndsAnOutage(final CapturedOutput output) {
        // a call would count as back only after a minute without a frame
        stream = new ZenBpmJobStream(ADAPTER_ID, CLIENT_ID, "fake-engine:9090", engine.channel(), timing, new Backoff(
                Duration.ofMillis(10), Duration.ofMillis(40), Duration.ofMinutes(1)), ZenBpmJobStream.CLOSE_TIMEOUT);
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        engine
                .call(0)
                .fail(Status.UNAVAILABLE);
        final var second = engine.call(1);
        second.subscriptions(1);

        second.send(JobStreamResponse
                .newBuilder()
                .setLockExtended(LockExtended
                        .newBuilder()
                        .setKey(7))
                .build());

        eventually(() -> Optional
                .of(output.getAllOfThisTest())
                .filter(printed -> printed.contains("is back after")));
        assertTrue(output
                .getAllOfThisTest()
                .contains("is down (UNAVAILABLE); no job arrives"));
    }

    @Test
    @DisplayName("An engine which ends the call at once with UNKNOWN is said to know the client id already")
    void aRefusedClientIdIsReportedWithTheProperty(final CapturedOutput output) {
        engine.refuseCallsWith(Status.UNKNOWN.withDescription(
                "failed to add client: client with this id is already subscribed"));

        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        eventually(() -> stream.reconnects() >= 2 ? Optional.of(true) : Optional.empty());
        engine.refuseCallsWith(null);

        final var printed = output.getAllOfThisTest();
        assertAll(
                () -> assertTrue(printed.contains(
                        "ZenBPM[orders]: the engine at fake-engine:9090 ended the job stream as soon as it was opened "
                                + "(UNKNOWN: failed to add client: client with this id is already subscribed). It does "
                                + "that where another job stream of the same client id 'shop-orders-0a1b2c3d' is still "
                                + "open"),
                        printed),
                () -> assertTrue(printed.contains(
                        "Where 'vanillabp.adapters.orders.client-id' is set, give every node and adapter id its own, "
                                + "or remove it to derive one: <application name>-<adapter id>-<8 random hex digits>."),
                        printed),
                () -> assertEquals(1, occurrences(printed, "ended the job stream as soon as it was opened")),
                () -> assertFalse(printed.contains("is down ("), printed));
    }

    @Test
    @DisplayName("A call which fails after the engine answered on it is no refused client id")
    void aLaterUnknownIsAnOrdinaryOutage(final CapturedOutput output) {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        final var first = engine.call(0);
        first.subscriptions(1);
        first.send(job(1, "t1"));
        eventually(() -> Optional
                .of(output.getAllOfThisTest())
                .filter(printed -> printed.contains("nothing in the adapter takes jobs yet")));

        first.fail(Status.UNKNOWN.withDescription("handler failed"));

        engine
                .call(1)
                .subscriptions(1);
        assertAll(
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("is down (UNKNOWN: handler failed)")),
                () -> assertFalse(output
                        .getAllOfThisTest()
                        .contains("as soon as it was opened")));
    }

    @Test
    @DisplayName("A refused client id met later in an outage is reported once as well")
    void aRefusedClientIdLaterInAnOutageIsReported(final CapturedOutput output) {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        engine
                .call(0)
                .subscriptions(1);
        engine.refuseCallsWith(Status.UNKNOWN);

        engine
                .call(0)
                .fail(Status.UNAVAILABLE);
        eventually(() -> stream.reconnects() >= 3 ? Optional.of(true) : Optional.empty());
        engine.refuseCallsWith(null);

        final var printed = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(1, occurrences(printed, "is down (UNAVAILABLE)"), printed),
                () -> assertEquals(1, occurrences(printed, "ended the job stream as soon as it was opened (UNKNOWN)"),
                        printed));
    }

    @Test
    @DisplayName("An error answering a subscription is logged and the call opened again with every subscription")
    void anErrorAnsweringASubscriptionReopensTheCall(final CapturedOutput output) {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        stream.subscribe("module-a", "t2", Duration.ofMinutes(5), 4);
        final var first = engine.call(0);
        first.subscriptions(2);

        first.send(JobStreamResponse
                .newBuilder()
                .setError(ErrorResult
                        .newBuilder()
                        .setMessage("Failed to subscribe to job type t2"))
                .build());

        final var second = engine.call(1);
        assertAll(
                () -> assertEquals(List.of(subscribe("t1", 300_000, 4), subscribe("t2", 300_000, 4)), second
                        .subscriptions(2)),
                () -> assertTrue(eventually(() -> first.cancelled() ? Optional.of(true) : Optional.empty())),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("ERROR o.p.z.client.ZenBpmJobStream - ZenBPM[orders]: the engine at "
                                + "fake-engine:9090 refused a subscription of the job stream: code 0, 'Failed to "
                                + "subscribe to job type t2'. The engine does not say which job type it refused, so "
                                + "the job stream opens its call again and subscribes every job type again: [t1, "
                                + "t2]")),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("is down (CANCELLED: the engine refused a subscription)")));
    }

    @Test
    @DisplayName("An error answering a job request, which the stream never sends, is logged and changes nothing")
    void anErrorAnsweringAJobRequestChangesNothing(final CapturedOutput output) {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        final var call = engine.call(0);
        call.subscriptions(1);
        final var error = ErrorResult
                .newBuilder()
                .setCode(6)
                .setMessage("The job no longer waits");

        call.send(JobStreamResponse
                .newBuilder()
                .setError(error)
                .setJob(WaitingJob
                        .newBuilder()
                        .setKey(41))
                .build());
        call.send(JobStreamResponse
                .newBuilder()
                .setError(error)
                .setLockExtended(LockExtended
                        .newBuilder()
                        .setKey(42))
                .build());
        stream.subscribe("module-a", "t2", Duration.ZERO, 0);

        call.subscriptions(2);
        eventually(() -> Optional
                .of(output.getAllOfThisTest())
                .filter(printed -> printed.contains("for job 42")));
        assertAll(
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("ZenBPM[orders]: the job stream received an error answering a request it never "
                                + "sends, for job 41: code 6, 'The job no longer waits'")),
                () -> assertEquals(1, engine.callCount()),
                () -> assertEquals(0, stream.reconnects()));
    }

    @Test
    @DisplayName("A job reaches the consumer unchanged on a timing thread, with the moment it arrived")
    void aJobReachesTheConsumerOnATimingThread() throws InterruptedException {
        final var received = new LinkedBlockingQueue<ReceivedJob>();
        final var threads = new LinkedBlockingQueue<String>();
        stream.onJob(job -> {
            threads.add(Thread
                    .currentThread()
                    .getName());
            received.add(job);
        });
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        final var call = engine.call(0);
        call.subscriptions(1);
        final var sent = job(17, "t1");
        final var before = Instant.now();

        call.send(sent);

        final var job = take(received);
        assertAll(
                () -> assertEquals(sent.getJob(), job.job()),
                () -> assertEquals(1_700_000_000_000L, job
                        .job()
                        .getLockUntil()),
                () -> assertFalse(job
                        .receivedAt()
                        .isBefore(before)),
                () -> assertTrue(take(threads).startsWith(TIMING_THREAD_PREFIX)));
    }

    @Test
    @DisplayName("A consumer which throws is logged, and the next job still arrives")
    void aConsumerWhichThrowsIsLogged(final CapturedOutput output) throws InterruptedException {
        final var received = new LinkedBlockingQueue<ReceivedJob>();
        stream.onJob(job -> {
            if (job
                    .job()
                    .getKey() == 1) {
                throw new IllegalStateException("handler broke");
            }
            received.add(job);
        });
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        final var call = engine.call(0);
        call.subscriptions(1);

        call.send(job(1, "t1"));
        call.send(job(2, "t1"));

        assertEquals(2, take(received)
                .job()
                .getKey());
        eventually(() -> Optional
                .of(output.getAllOfThisTest())
                .filter(printed -> printed.contains(
                        "ZenBPM[orders]: job 1 of type 't1' could not be handed on; the engine hands it out again once "
                                + "its lock lapses")));
    }

    @Test
    @DisplayName("A job which arrives while the adapter shuts down is logged and left to its lock")
    void aJobDuringShutdownIsLeftToItsLock(final CapturedOutput output) {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        final var call = engine.call(0);
        call.subscriptions(1);
        timing.shutdown();

        call.send(job(5, "t1"));

        eventually(() -> Optional
                .of(output.getAllOfThisTest())
                .filter(printed -> printed.contains(
                        "ZenBPM[orders]: job 5 of type 't1' arrived while the adapter shuts down, so nobody runs it")));
    }

    @Test
    @DisplayName("A call lost while the adapter shuts down is not opened again")
    void noReconnectWhileShuttingDown(final CapturedOutput output) {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        final var call = engine.call(0);
        call.subscriptions(1);
        timing.shutdown();

        call.fail(Status.UNAVAILABLE);

        eventually(() -> Optional
                .of(output.getAllOfThisTest())
                .filter(printed -> printed.contains(
                        "ZenBPM[orders]: the job stream to fake-engine:9090 is not opened again, because the adapter "
                                + "shuts down")));
        stream.subscribe("module-a", "t2", Duration.ZERO, 0);
        assertEquals(1, engine.callCount());
    }

    @Test
    @DisplayName("Closing ends the call from the stream's side, and the stream takes no subscription afterwards")
    void closingEndsTheCall() {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        final var call = engine.call(0);
        call.subscriptions(1);

        stream.close();
        stream.close();

        assertTrue(call.halfClosed());
        final var refused = assertThrows(IllegalStateException.class, () -> stream.subscribe("module-a", "t2",
                Duration.ZERO, 0));
        assertEquals("ZenBPM[orders]: the job stream is closed, so 'module-a' cannot subscribe job type 't2'.", refused
                .getMessage());
        assertDoesNotThrow(() -> stream.unsubscribe("module-a", "t1"));
        assertEquals(1, engine.callCount());
    }

    @Test
    @DisplayName("Closing while the stream waits to reconnect cancels the attempt, and an attempt already due opens nothing")
    void closingStopsAPendingReconnect() {
        final var heldBack = new HeldBackReconnects();
        try {
            final var waiting = new ZenBpmJobStream(ADAPTER_ID, CLIENT_ID, "fake-engine:9090", engine
                    .channel(), heldBack, QUICK, ZenBpmJobStream.CLOSE_TIMEOUT);
            waiting.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
            engine
                    .call(0)
                    .subscriptions(1);
            engine
                    .call(0)
                    .fail(Status.UNAVAILABLE);
            final var reconnect = eventually(() -> heldBack.tasks.isEmpty()
                    ? Optional.empty()
                    : Optional.of(heldBack.tasks.get(0)));

            waiting.close();
            // the attempt started before close() and waited for it
            reconnect.run();

            assertAll(
                    () -> assertTrue(heldBack.futures
                            .get(0)
                            .isCancelled()),
                    () -> assertEquals(1, engine.callCount()),
                    () -> assertEquals(0, waiting.reconnects()));
        } finally {
            heldBack.shutdownNow();
        }
    }

    @Test
    @DisplayName("A type subscribed while the stream waits to reconnect is sent once, when it is back")
    void aTypeSubscribedWhileDownIsSentWhenBack() {
        final var heldBack = new HeldBackReconnects();
        try (var waiting = new ZenBpmJobStream(ADAPTER_ID, CLIENT_ID, "fake-engine:9090", engine
                .channel(), heldBack, QUICK, ZenBpmJobStream.CLOSE_TIMEOUT)) {
            waiting.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
            engine
                    .call(0)
                    .subscriptions(1);
            engine
                    .call(0)
                    .fail(Status.UNAVAILABLE);
            final var reconnect = eventually(() -> heldBack.tasks.isEmpty()
                    ? Optional.empty()
                    : Optional.of(heldBack.tasks.get(0)));

            waiting.subscribe("module-a", "t2", Duration.ZERO, 0);
            reconnect.run();

            final var back = engine.call(1);
            back.subscriptions(2);
            waiting.subscribe("module-b", "t3", Duration.ZERO, 0);
            assertEquals(List.of(subscribe("t1", 300_000, 4), subscribe("t2", 0, 0), subscribe("t3", 0, 0)), back
                    .subscriptions(3));
        } finally {
            heldBack.shutdownNow();
        }
    }

    @Test
    @DisplayName("A call which brought a frame is reported back once, also when it then stays open")
    void aStreamIsReportedBackOnce(final CapturedOutput output) throws InterruptedException {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        engine
                .call(0)
                .fail(Status.UNAVAILABLE);
        final var second = engine.call(1);
        second.subscriptions(1);

        second.send(JobStreamResponse.getDefaultInstance());
        eventually(() -> Optional
                .of(output.getAllOfThisTest())
                .filter(printed -> printed.contains("is back after")));
        // longer than QUICK's stableAfter, after which the call would count as back
        Thread.sleep(3 * QUICK
                .stableAfter()
                .toMillis());

        assertEquals(1, occurrences(output.getAllOfThisTest(), "is back after"));
    }

    @Test
    @DisplayName("A job which arrives after close() is not handed on")
    void aJobAfterCloseIsNotHandedOn(final CapturedOutput output) throws InterruptedException {
        final var received = new LinkedBlockingQueue<ReceivedJob>();
        final var silent = new FakeJobStreamEngine()
                .ignoreHalfClose()
                .start();
        try (silent) {
            final var closing = new ZenBpmJobStream(ADAPTER_ID, CLIENT_ID, "fake-engine:9090", silent
                    .channel(), timing, QUICK, Duration.ofSeconds(1));
            closing.onJob(received::add);
            closing.subscribe("module-a", "t1", Duration.ZERO, 0);
            final var call = silent.call(0);
            call.subscriptions(1);
            final var closer = new Thread(closing::close);
            closer.start();
            eventually(() -> call.halfClosed() ? Optional.of(true) : Optional.empty());

            call.send(job(8, "t1"));
            closer.join(5_000);

            assertAll(
                    () -> assertFalse(closer.isAlive()),
                    () -> assertTrue(received.isEmpty()),
                    () -> assertFalse(output
                            .getAllOfThisTest()
                            .contains("job 8")));
        }
    }

    @Test
    @DisplayName("A frame nobody asked for is ignored")
    void aFrameNobodyAskedForIsIgnored() {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        final var call = engine.call(0);
        call.subscriptions(1);

        call.send(JobStreamResponse.getDefaultInstance());
        stream.subscribe("module-a", "t2", Duration.ZERO, 0);

        call.subscriptions(2);
        assertAll(
                () -> assertEquals(1, engine.callCount()),
                () -> assertEquals(0, stream.reconnects()));
    }

    @Test
    @DisplayName("A call the engine ends normally is lost like a failed one")
    void aCallTheEngineEndsIsOpenedAgain(final CapturedOutput output) {
        stream.subscribe("module-a", "t1", Duration.ofMinutes(5), 4);
        final var call = engine.call(0);
        call.subscriptions(1);

        call.end();

        engine
                .call(1)
                .subscriptions(1);
        assertTrue(output
                .getAllOfThisTest()
                .contains("is down (OK: the engine ended the call)"));
    }

    @Test
    @DisplayName("Negative settings are refused before anything is sent, an endless lock is sent as the longest")
    void settingsAreChecked() {
        assertAll(
                () -> assertEquals("The lock duration PT-1S is negative; zero asks for the engine's default.",
                        assertThrows(IllegalArgumentException.class, () -> stream.subscribe("module-a", "t1", Duration
                                .ofSeconds(-1), 4)).getMessage()),
                () -> assertEquals("The cap of active jobs -1 is negative; zero asks for the engine's default.",
                        assertThrows(IllegalArgumentException.class, () -> stream.subscribe("module-a", "t1",
                                Duration.ZERO, -1)).getMessage()),
                () -> assertEquals(Long.MAX_VALUE, Settings
                        .of(Duration.ofSeconds(Long.MAX_VALUE), 0)
                        .lockDurationMs()),
                () -> assertEquals("a lock of the engine's default and the engine's default cap of active jobs",
                        Settings
                                .of(Duration.ZERO, 0)
                                .toString()),
                () -> assertEquals(0, engine.callCount()));
    }

    @Test
    @DisplayName("The pause before a reconnect doubles from the first up to the longest, cut short by up to a fifth")
    void theBackoffDoublesUpToItsLongest() {
        final var backoff = ZenBpmJobStream.BACKOFF;

        assertAll(
                () -> assertEquals(Duration.ofSeconds(1), backoff.first()),
                () -> assertEquals(Duration.ofSeconds(30), backoff.max()),
                () -> assertBetween(Duration.ofMillis(800), Duration.ofSeconds(1), backoff.delay(0)),
                () -> assertBetween(Duration.ofMillis(1_600), Duration.ofSeconds(2), backoff.delay(1)),
                () -> assertBetween(Duration.ofMillis(12_800), Duration.ofSeconds(16), backoff.delay(4)),
                () -> assertBetween(Duration.ofSeconds(24), Duration.ofSeconds(30), backoff.delay(5)),
                () -> assertBetween(Duration.ofSeconds(24), Duration.ofSeconds(30), backoff.delay(Integer.MAX_VALUE)));
    }

    @Test
    @DisplayName("Without grpc-plaintext the stream speaks TLS, which an engine without TLS refuses")
    void withoutPlaintextTheStreamSpeaksTls(final CapturedOutput output) throws IOException, InterruptedException {
        final var plaintextEngine = Grpc
                .newServerBuilderForPort(0, InsecureServerCredentials.create())
                .addService(new ZenBpmGrpc.ZenBpmImplBase() {

                    @Override
                    public StreamObserver<JobStreamRequest> jobStream(final StreamObserver<JobStreamResponse> responses) {
                        responses.onNext(job(9, "t1"));
                        return new NoRequests(responses);
                    }

                })
                .build()
                .start();
        try {
            final var received = new LinkedBlockingQueue<ReceivedJob>();
            final var plaintext = configured(plaintextEngine, true);
            final var tls = configured(plaintextEngine, false);
            try (var plain = new ZenBpmJobStream(ADAPTER_ID, plaintext, CLIENT_ID, timing); var secure = new ZenBpmJobStream(ADAPTER_ID, tls, "tls-client", timing)) {
                plain.onJob(received::add);
                plain.subscribe("module-a", "t1", Duration.ZERO, 0);
                secure.subscribe("module-a", "t1", Duration.ZERO, 0);

                assertEquals(9, take(received)
                        .job()
                        .getKey());
                eventually(() -> Optional
                        .of(output.getAllOfThisTest())
                        .filter(printed -> printed.contains("ZenBPM[orders]: the job stream to localhost:%d is down ("
                                .formatted(plaintextEngine.getPort()))));
            }
        } finally {
            plaintextEngine.shutdownNow();
            plaintextEngine.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Closing cuts the connection where the engine does not end the call in time")
    void closingDoesNotWaitForeverForTheEngine() {
        final var silent = new FakeJobStreamEngine()
                .ignoreHalfClose()
                .start();
        try (silent) {
            final var waiting = new ZenBpmJobStream(ADAPTER_ID, CLIENT_ID, "fake-engine:9090", silent
                    .channel(), timing, QUICK, Duration.ofMillis(100));
            waiting.subscribe("module-a", "t1", Duration.ZERO, 0);
            final var call = silent.call(0);
            call.subscriptions(1);

            waiting.close();

            assertTrue(eventually(() -> call.cancelled() ? Optional.of(true) : Optional.empty()));
        }
    }

    @Test
    @DisplayName("Closing on an interrupted thread cuts the connection at once and keeps the interrupt")
    void closingOnAnInterruptedThreadKeepsTheInterrupt() {
        final var silent = new FakeJobStreamEngine()
                .ignoreHalfClose()
                .start();
        try (silent) {
            final var waiting = new ZenBpmJobStream(ADAPTER_ID, CLIENT_ID, "fake-engine:9090", silent
                    .channel(), timing, QUICK, ZenBpmJobStream.CLOSE_TIMEOUT);
            waiting.subscribe("module-a", "t1", Duration.ZERO, 0);
            final var call = silent.call(0);
            call.subscriptions(1);

            Thread
                    .currentThread()
                    .interrupt();
            // not preemptively: the interrupt belongs to this thread
            assertTimeout(Duration.ofSeconds(2), waiting::close);

            assertTrue(Thread.interrupted());
        }
    }

    private static StreamSubscriptionRequest subscribe(
        final String jobType,
        final long lockDurationMs,
        final int maxActiveJobs
    ) {
        return StreamSubscriptionRequest
                .newBuilder()
                .setJobType(jobType)
                .setType(StreamSubscriptionRequest.Type.TYPE_SUBSCRIBE)
                .setLockDurationMs(lockDurationMs)
                .setMaxActiveJobs(maxActiveJobs)
                .build();
    }

    private static JobStreamResponse job(
        final long key,
        final String jobType
    ) {
        return JobStreamResponse
                .newBuilder()
                .setJob(WaitingJob
                        .newBuilder()
                        .setKey(key)
                        .setInstanceKey(key + 1000)
                        .setType(jobType)
                        .setElementId("Task_%d".formatted(key))
                        .setLockUntil(1_700_000_000_000L)
                        .setDeliveryToken(3)
                        .putHeaders("h", "v"))
                .build();
    }

    private static <T> T take(final BlockingQueue<T> queue) throws InterruptedException {
        final var taken = queue.poll(10, TimeUnit.SECONDS);
        if (taken == null) {
            throw new AssertionError("Nothing arrived within 10 seconds");
        }
        return taken;
    }

    private static int occurrences(
        final String text,
        final String part
    ) {
        return (int) Pattern
                .compile(Pattern.quote(part))
                .matcher(text)
                .results()
                .count();
    }

    private static void assertBetween(
        final Duration lowest,
        final Duration highest,
        final Duration actual
    ) {
        assertTrue((actual.compareTo(lowest) >= 0) && (actual.compareTo(highest) <= 0), "%s is not between %s and %s"
                .formatted(actual, lowest, highest));
    }

    private static ZenBpmAdapterConfiguration configured(
        final Server server,
        final boolean plaintext
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress("http://localhost:8080");
        configuration.setGrpcAddress("localhost:%d".formatted(server.getPort()));
        configuration.setGrpcPlaintext(plaintext);
        return configuration;
    }

    /**
     * The side of a call the engine reads, for an engine which reads nothing and ends the call
     * where the stream ends it.
     */
    private static final class NoRequests implements StreamObserver<JobStreamRequest> {

        private final StreamObserver<JobStreamResponse> responses;

        private NoRequests(final StreamObserver<JobStreamResponse> responses) {
            this.responses = responses;
        }

        @Override
        public void onNext(final JobStreamRequest request) {
        }

        @Override
        public void onError(final Throwable failure) {
        }

        @Override
        public void onCompleted() {
            responses.onCompleted();
        }

    }

    /**
     * Timing threads which hold every reconnect back until the test runs it, and remember the
     * futures they answered for them.
     */
    private static final class HeldBackReconnects extends ScheduledThreadPoolExecutor {

        private final List<Runnable> tasks = new CopyOnWriteArrayList<>();

        private final List<ScheduledFuture<?>> futures = new CopyOnWriteArrayList<>();

        private HeldBackReconnects() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(
            final Runnable task,
            final long delay,
            final TimeUnit unit
        ) {
            tasks.add(task);
            final var never = super.schedule(() -> {
            }, 1, TimeUnit.DAYS);
            futures.add(never);
            return never;
        }

    }

}
