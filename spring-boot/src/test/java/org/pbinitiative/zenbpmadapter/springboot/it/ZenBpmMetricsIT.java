/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.observability.MicrometerZenBpmMetrics;
import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;
import org.pbinitiative.zenbpmadapter.springboot.it.metrics.Crate;
import org.pbinitiative.zenbpmadapter.springboot.it.metrics.MetricsItApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.metrics.Pallet;
import org.pbinitiative.zenbpmadapter.springboot.it.metrics.PalletWorkflow;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * The adapter's meters on a booted Spring Boot application against the pinned engine, where the
 * application brings Micrometer: each counter moves when its event happens, at the engine and not
 * in a test double. The application {@link MetricsItApplication} runs with
 * <code>metrics-it.yaml</code>: one handler slot, and the process <code>Crating</code>, whose two
 * tasks run in parallel. So the second job waits for the slot, the first weighing throws and the
 * engine hands its job out again after the backoff, and the second weighing runs longer than the
 * <code>job-timeout</code> of its task, so its lock is renewed. The end of the workflow is a job as
 * well, that of the end marker the adapter puts in front of the end event.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = MetricsItApplication.class, properties = "spring.config.name=metrics-it")
@DirtiesContext
class ZenBpmMetricsIT {

    /**
     * How long the test waits for what the engine and the application do. The workflow takes about
     * five seconds, so the limit only turns a step which never happens into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Autowired
    private ProcessService<Crate> processService;

    @Autowired
    private ProcessService<Pallet> palletService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ZenBpmClientRegistry clients;

    @Autowired
    private MicrometerZenBpmMetrics metrics;

    @Test
    @DisplayName("A workflow whose two tasks share one handler slot moves the meters: four jobs received, the end marker's included, one of them a redelivery after the failure of the first weighing, a job which waited for the slot, three completed, one failed, the lock of the long weighing renewed and none lost, and the slots gauged while a handler runs")
    void theMetersFollowTheJobs() {
        final var registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
        // the binder counts from the start of the application on, the other test's jobs included
        final var before = new Counts(registry);
        final var crateId = "crate-%s".formatted(UUID.randomUUID());

        transactionTemplate.executeWithoutResult(status -> processService.startWorkflow(new Crate(crateId)));
        final var instance = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(crateId))
                .items()
                .stream()
                .filter(started -> started.parentKey() == null)
                .findFirst(), "the workflow to start")
                .key();
        eventually(() -> (gauged(registry, ZenBpmMetrics.SLOTS_BUSY) == 1) && (gauged(registry,
                ZenBpmMetrics.SLOTS_FREE) == 0), "a handler to take the only slot");
        final var ended = eventually(() -> rest()
                .instance(instance)
                .filter(read -> read.state() != ProcessInstanceRef.State.ACTIVE), "the workflow to end");
        // the job of the end marker in front of the end event is completed after the workflow ended
        eventually(() -> (before.since(ZenBpmMetrics.JOBS_COMPLETED) == 3) && (gauged(registry,
                ZenBpmMetrics.SLOTS_BUSY) == 0), "the last completion to be counted and the slot to be free: %s"
                        .formatted(registry.getMetersAsString()));

        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                // the first and the second weighing, the sealing and the end marker
                () -> assertEquals(4, before.since(ZenBpmMetrics.JOBS_RECEIVED)),
                () -> assertEquals(1, before.since(ZenBpmMetrics.JOBS_REDELIVERED),
                        "the engine hands the job of the failed weighing out again, with its next delivery token"),
                () -> assertTrue(before.since(ZenBpmMetrics.JOBS_QUEUED) >= 1, "a job waited for the slot"),
                () -> assertEquals(3, before.since(ZenBpmMetrics.JOBS_COMPLETED)),
                () -> assertEquals(1, before.since(ZenBpmMetrics.JOBS_FAILED)),
                () -> assertTrue(before.since(ZenBpmMetrics.LOCK_RENEWED) >= 1,
                        "the second weighing runs longer than its job-timeout"),
                () -> assertEquals(0, before.since(ZenBpmMetrics.LOCK_LOST)),
                () -> assertEquals(0, before.since(ZenBpmMetrics.STREAM_RECONNECTS),
                        "the job stream stayed connected"),
                () -> assertEquals(0, before.since(ZenBpmMetrics.USER_TASK_POLLS),
                        "the module has no user task to look for"),
                () -> assertEquals(1.0, gauged(registry, ZenBpmMetrics.SLOTS_FREE)));
    }

    @Test
    @DisplayName("A task left open counts as received and not as completed, neither when its handler returns nor when the application completes it later through the process service: the job meters count what the handlers answered")
    void aTaskCompletedThroughTheProcessServiceIsNotCountedAsCompleted() {
        final var registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
        final var before = new Counts(registry);
        final var palletId = "pallet-%s".formatted(UUID.randomUUID());

        transactionTemplate.executeWithoutResult(status -> palletService.startWorkflow(new Pallet(palletId)));
        final var taskId = eventually(() -> PalletWorkflow.labelTaskOf(palletId), "the labelling to be left open");
        eventually(() -> gauged(registry, ZenBpmMetrics.SLOTS_BUSY) == 0, "the handler of the labelling to return");
        final var whileOpen = new Counts(registry);
        final var receivedUntilThen = before.since(ZenBpmMetrics.JOBS_RECEIVED);
        final var completedUntilThen = before.since(ZenBpmMetrics.JOBS_COMPLETED);
        transactionTemplate.executeWithoutResult(status -> palletService.completeTask(new Pallet(palletId), taskId));
        final var instance = rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(palletId))
                .items()
                .getFirst()
                .key();
        final var ended = eventually(() -> rest()
                .instance(instance)
                .filter(read -> read.state() != ProcessInstanceRef.State.ACTIVE), "the workflow to end");
        // a job which came after the completion, as the end marker's, is counted once the engine
        // took its answer, which may be after the workflow ended
        eventually(() -> (whileOpen.since(ZenBpmMetrics.JOBS_COMPLETED) >= whileOpen.since(
                ZenBpmMetrics.JOBS_RECEIVED)) && (gauged(registry, ZenBpmMetrics.SLOTS_BUSY) == 0),
                "every job which came after the completion to be answered");

        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertTrue(receivedUntilThen >= 1, () -> Long.toString(receivedUntilThen)),
                () -> assertEquals(receivedUntilThen - 1, completedUntilThen,
                        "every job but the labelling left open, the start marker's, was completed"),
                () -> assertEquals(whileOpen.since(ZenBpmMetrics.JOBS_RECEIVED), whileOpen.since(
                        ZenBpmMetrics.JOBS_COMPLETED),
                        "after the completion through the process service, only the jobs which came after it count: %s"
                                .formatted(registry.getMetersAsString())),
                () -> assertEquals(0, before.since(ZenBpmMetrics.JOBS_FAILED)));
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", ENGINE::restAddress);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    private ZenBpmRestClient rest() {
        return clients
                .getFactory("zenbpm")
                .rest();
    }

    private static long counted(
        final SimpleMeterRegistry registry,
        final String meterName
    ) {
        return (long) registry
                .get(meterName)
                .tag(ZenBpmMetrics.TAG_ADAPTER, "zenbpm")
                .functionCounter()
                .count();
    }

    /**
     * The counters of the adapter id at one moment, to tell what moved since.
     */
    private static final class Counts {

        private final SimpleMeterRegistry registry;

        private final Map<String, Long> counts = new HashMap<>();

        private Counts(final SimpleMeterRegistry registry) {
            this.registry = registry;
            for (final var meterName : List.of(ZenBpmMetrics.JOBS_RECEIVED, ZenBpmMetrics.JOBS_REDELIVERED,
                    ZenBpmMetrics.JOBS_QUEUED, ZenBpmMetrics.JOBS_COMPLETED, ZenBpmMetrics.JOBS_FAILED,
                    ZenBpmMetrics.LOCK_RENEWED, ZenBpmMetrics.LOCK_LOST, ZenBpmMetrics.STREAM_RECONNECTS,
                    ZenBpmMetrics.USER_TASK_POLLS)) {
                counts.put(meterName, counted(registry, meterName));
            }
        }

        private long since(final String meterName) {
            return counted(registry, meterName) - counts.get(meterName);
        }

    }

    private static double gauged(
        final SimpleMeterRegistry registry,
        final String meterName
    ) {
        return registry
                .get(meterName)
                .tag(ZenBpmMetrics.TAG_ADAPTER, "zenbpm")
                .gauge()
                .value();
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
                    .ofMillis(20)
                    .toNanos());
        }
    }

}
