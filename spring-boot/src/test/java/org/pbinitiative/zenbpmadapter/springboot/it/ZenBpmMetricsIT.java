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
        eventually(() -> (counted(registry, ZenBpmMetrics.JOBS_COMPLETED) == 3) && (gauged(registry,
                ZenBpmMetrics.SLOTS_BUSY) == 0), "the last completion to be counted and the slot to be free: %s"
                        .formatted(registry.getMetersAsString()));

        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                // the first and the second weighing, the sealing and the end marker
                () -> assertEquals(4, counted(registry, ZenBpmMetrics.JOBS_RECEIVED)),
                () -> assertEquals(1, counted(registry, ZenBpmMetrics.JOBS_REDELIVERED),
                        "the engine hands the job of the failed weighing out again, with its next delivery token"),
                () -> assertTrue(counted(registry, ZenBpmMetrics.JOBS_QUEUED) >= 1, "a job waited for the slot"),
                () -> assertEquals(3, counted(registry, ZenBpmMetrics.JOBS_COMPLETED)),
                () -> assertEquals(1, counted(registry, ZenBpmMetrics.JOBS_FAILED)),
                () -> assertTrue(counted(registry, ZenBpmMetrics.LOCK_RENEWED) >= 1,
                        "the second weighing runs longer than its job-timeout"),
                () -> assertEquals(0, counted(registry, ZenBpmMetrics.LOCK_LOST)),
                () -> assertEquals(0, counted(registry, ZenBpmMetrics.STREAM_RECONNECTS),
                        "the job stream stayed connected"),
                () -> assertEquals(0, counted(registry, ZenBpmMetrics.USER_TASK_POLLS),
                        "the module has no user task to look for"),
                () -> assertEquals(1.0, gauged(registry, ZenBpmMetrics.SLOTS_FREE)));
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
