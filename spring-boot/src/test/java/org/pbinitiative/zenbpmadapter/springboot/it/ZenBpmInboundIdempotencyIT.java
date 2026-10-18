/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.springboot.it.idempotency.IdempotencyItApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.idempotency.PrintingGate;
import org.pbinitiative.zenbpmadapter.springboot.it.idempotency.Ticket;
import org.pbinitiative.zenbpmadapter.springboot.it.idempotency.TicketRepository;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * What a booted Spring Boot application does with a job the engine hands out more than once,
 * against the pinned engine: the renewal of its lock keeps a long handler from being delivered a
 * second time; where the lock is lost, as it is where the partition's leader changes, which holds
 * the locks in its memory alone, a second delivery runs beside the first, and both the adapter and
 * the platform name it; and a delivery which comes after one that committed is answered from the
 * platform's record, without running the handler again, and the completion it sends ends the job.
 * <p>
 * The application {@link IdempotencyItApplication} runs with <code>idempotency-it.yaml</code>: a
 * job-timeout of two seconds, so its lock is renewed every second, and two handler slots. It reaches
 * the engine's REST API through a {@link RefusingEngineProxy}, which refuses the extensions of a
 * lock or the completions of a job where a case asks for it, and passes everything else on; the job
 * stream goes to the engine directly. One engine serves the class, so every case uses tickets of
 * its own, and each case ends with the proxy refusing nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = IdempotencyItApplication.class, properties = "spring.config.name=idempotency-it")
@DirtiesContext
class ZenBpmInboundIdempotencyIT {

    /**
     * How long a case waits for what the engine or the application does. Each step takes
     * milliseconds or a few job-timeouts of two seconds, so the limit only turns a step which never
     * happens into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * The job-timeout of <code>idempotency-it.yaml</code>.
     */
    private static final Duration JOB_TIMEOUT = Duration.ofSeconds(2);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static final RefusingEngineProxy PROXY = new RefusingEngineProxy(ENGINE::restAddress);

    @Autowired
    private ProcessService<Ticket> processService;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ZenBpmClientRegistry clients;

    @AfterEach
    void startTheProxyAfresh() {
        PROXY.startAfresh();
    }

    @AfterAll
    static void stopTheProxy() {
        PROXY.close();
    }

    @Test
    @DisplayName("A handler which runs three times its job-timeout keeps its lock: the renewal extends it every half job-timeout, so the job is delivered once and completed")
    void longHandlersKeepTheirLock(final CapturedOutput output) {
        final var ticketId = uniqueTicketId();
        final var held = JOB_TIMEOUT.multipliedBy(3);
        PrintingGate.holdFor(ticketId, held);

        final var startedAt = Instant.now();
        final var instance = start(ticketId);
        final var ended = awaitTheEndOf(instance);
        final var ranFor = Duration.between(startedAt, Instant.now());

        final var job = jobOf(instance);
        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertTrue(ranFor.compareTo(held) >= 0, ranFor::toString),
                () -> assertEquals(JobRef.State.COMPLETED, job.state()),
                () -> assertEquals(1, deliveryTokenOf(job.key())),
                () -> assertEquals(1, PrintingGate.calls(ticketId)),
                () -> assertEquals(1, ticket(ticketId).getPrints()),
                () -> assertEquals(List.of("COMPLETED"), outcomesOf(ticketId)),
                () -> assertFalse(log.contains("is lost while its handler runs"), log),
                () -> assertFalse(log.contains("could not be renewed while its handler runs"), log),
                () -> assertFalse(log.contains("Skipping the repeated delivery of task 'print'"), log));
    }

    @Test
    @DisplayName("A lost lock is named: the engine hands the job out again while its first handler runs, the adapter names the lock it could not renew, the platform the second run; a later delivery is answered from the record of the first one which committed, and its completion ends the job")
    void aLostLockIsNamed(final CapturedOutput output) {
        final var ticketId = uniqueTicketId();
        // the partition's leader holds the locks in its memory alone: a new leader knows none of
        // them, which the adapter meets as extensions nobody answers. The completions do not arrive
        // either until the case says so; a 500 is no failure the adapter repeats, so each run
        // gives up its completion at once and leaves the job to its lock
        PROXY.refuse(RefusingEngineProxy.LOCK_EXTENSION, 502, RefusingEngineProxy.ALWAYS);
        PROXY.refuse(RefusingEngineProxy.COMPLETION, 500, RefusingEngineProxy.ALWAYS);
        PrintingGate.holdUntilReleased(ticketId, 2);

        final var instance = start(ticketId);
        eventually(() -> PrintingGate.waiting(ticketId) == 2, "a second delivery of the print beside the first");
        final var job = jobOf(instance);
        final var lockLost = "the lock of job %d (task 'print', workflow aggregate '%s') could not be renewed while its handler runs"
                .formatted(job.key(), ticketId);
        eventually(() -> output
                .getAllOfThisTest()
                .contains(lockLost), "the adapter to name the lock it could not renew");
        PrintingGate.release(ticketId);
        final var ranTwice = "Two deliveries of task 'print' (BPMN process 'Idempotency' of workflow module 'deploy-it', workflow aggregate '%s') were processed at the SAME time"
                .formatted(ticketId);
        eventually(() -> output
                .getAllOfThisTest()
                .contains(ranTwice), "the platform to name the second run");
        final var reportedAt = output
                .getAllOfThisTest()
                .indexOf(ranTwice);
        final var notAnswered = "job %d (task 'print') could not be answered to the engine".formatted(job.key());
        eventually(() -> occurrences(output.getAllOfThisTest(), notAnswered) >= 2,
                "both runs to give up their completions");
        PROXY.refuseNothing();
        final var ended = awaitTheEndOf(instance);

        final var log = output.getAllOfThisTest();
        final var skipped = "Skipping the repeated delivery of task 'print' (BPMN process 'Idempotency' of workflow module 'deploy-it', aggregate '%s'): it was processed before, reporting the recorded outcome COMPLETED again"
                .formatted(ticketId);
        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(JobRef.State.COMPLETED, jobOf(instance).state()),
                () -> assertEquals(2, PrintingGate.calls(ticketId)),
                () -> assertEquals(List.of("COMPLETED"), outcomesOf(ticketId)),
                () -> assertTrue(log.contains(
                        "The engine may hand the job out again then, so a second delivery may run beside this one"),
                        log),
                () -> assertTrue(log.contains(skipped), log),
                () -> assertTrue(log.indexOf(skipped) > reportedAt, log),
                () -> assertTrue(deliveryTokenOf(job.key()) >= 3, () -> Long.toString(deliveryTokenOf(job.key()))));
    }

    @Test
    @DisplayName("A completion the engine refused once is sent again for the next delivery: the platform answers that delivery from its record, so the handler runs once, and the job is completed")
    void aRefusedCompletionIsSentAgainForTheNextDelivery(final CapturedOutput output) {
        final var ticketId = uniqueTicketId();
        // a 500 is no failure the adapter repeats, so the job is left to its lock
        PROXY.refuse(RefusingEngineProxy.COMPLETION, 500, 1);

        final var instance = start(ticketId);
        final var ended = awaitTheEndOf(instance);

        final var job = jobOf(instance);
        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(JobRef.State.COMPLETED, job.state()),
                () -> assertEquals(List.of("POST /v1/jobs/%d/complete".formatted(job.key())), PROXY.refused()),
                () -> assertTrue(deliveryTokenOf(job.key()) >= 2, () -> Long.toString(deliveryTokenOf(job.key()))),
                () -> assertEquals(1, PrintingGate.calls(ticketId)),
                () -> assertEquals(1, ticket(ticketId).getPrints()),
                () -> assertEquals(List.of("COMPLETED"), outcomesOf(ticketId)),
                () -> assertTrue(
                        log.contains("job %d (task 'print') could not be answered to the engine; it is left to its lock"
                                .formatted(job.key())),
                        log),
                () -> assertTrue(log.contains(
                        "Skipping the repeated delivery of task 'print' (BPMN process 'Idempotency' of workflow module 'deploy-it', aggregate '%s')"
                                .formatted(ticketId)),
                        log));
    }

    // the adapter reaches the engine's REST API through the proxy, its job stream directly
    @DynamicPropertySource
    static void theEngineBehindTheProxy(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", PROXY::address);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    // the ticket ids of every test are new, because all tests of the class share one engine
    private static String uniqueTicketId() {
        return "ticket-%s".formatted(UUID.randomUUID());
    }

    private long start(final String ticketId) {
        transactionTemplate.executeWithoutResult(status -> processService.startWorkflow(new Ticket(ticketId)));
        return eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(ticketId))
                .items()
                .stream()
                .findFirst(), "the workflow to start")
                .key();
    }

    private ProcessInstanceRef awaitTheEndOf(final long instanceKey) {
        return eventually(() -> rest()
                .instance(instanceKey)
                .filter(instance -> instance.state() != ProcessInstanceRef.State.ACTIVE), "the workflow to end");
    }

    private JobRef jobOf(final long instanceKey) {
        return eventually(() -> rest()
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(instanceKey))
                .items()
                .stream()
                .findFirst(), "the job of the print");
    }

    private Ticket ticket(final String ticketId) {
        return tickets
                .findById(ticketId)
                .orElseThrow();
    }

    private List<String> outcomesOf(final String ticketId) {
        return TaskDeliveryLogReader
                .of(dataSource)
                .deliveries()
                .stream()
                .filter(delivery -> ticketId.equals(delivery.aggregateId()))
                .map(TaskDeliveryLogReader.Delivery::outcome)
                .toList();
    }

    private static int occurrences(
        final String log,
        final String line
    ) {
        var found = 0;
        for (var at = log.indexOf(line); at >= 0; at = log.indexOf(line, at + 1)) {
            ++found;
        }
        return found;
    }

    // raised by the engine with every delivery of the job, read past the proxy
    private static long deliveryTokenOf(final long jobKey) {
        return ENGINE
                .json("/v1/jobs/%d".formatted(jobKey))
                .path("deliveryToken")
                .asLong();
    }

    private ZenBpmRestClient rest() {
        return clients
                .getFactory("zenbpm")
                .rest();
    }

    private static void eventually(
        final BooleanSupplier condition,
        final String what
    ) {
        eventually(() -> condition.getAsBoolean() ? Optional.of(Boolean.TRUE) : Optional.empty(), what);
    }

    private static <T> T eventually(
        final Supplier<Optional<T>> read,
        final String what
    ) {
        final var deadline = Instant
                .now()
                .plus(TIMEOUT);
        while (true) {
            final var found = read.get();
            if (found.isPresent()) {
                return found.get();
            }
            assertTrue(Instant
                    .now()
                    .isBefore(deadline), () -> "waited %s for %s".formatted(TIMEOUT, what));
            LockSupport.parkNanos(Duration
                    .ofMillis(50)
                    .toNanos());
        }
    }

}
