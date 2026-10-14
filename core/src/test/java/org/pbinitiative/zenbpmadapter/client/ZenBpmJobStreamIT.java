/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.eventually;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.oneServiceTask;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.uniqueBusinessKey;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The job stream against the pinned engine: a subscribed job type brings the jobs of a started
 * instance, and the stream comes back by itself after the engine was away.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmJobStreamIT {

    private static final String ADAPTER_ID = "stream";

    private static final String TIMING_THREAD_PREFIX = "zenbpm-%s-timing-".formatted(ADAPTER_ID);

    /**
     * How long a job may take from the start of its instance to the stream: the engine looks for
     * jobs to hand out about once a second.
     */
    private static final Duration DELIVERY = Duration.ofSeconds(5);

    /**
     * How long the stream may take to come back once the engine is in reach again: the longest
     * pause between two attempts, and a delivery.
     */
    private static final Duration RECONNECT = ZenBpmJobStream.PACING
            .max()
            .plus(DELIVERY);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    /**
     * The adapter id's own threads, so the jobs arrive on the timing threads the adapter runs.
     */
    private static ZenBpmExecutor executor;

    private static ScheduledExecutorService timing;

    @BeforeAll
    static void startTheTimingThreads() {
        executor = ZenBpmExecutor.of(ADAPTER_ID, ZenBpmExecutionModel.DEFAULT);
        timing = executor.timing();
    }

    @AfterAll
    static void stopTheTimingThreads() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("A job of a subscribed type arrives on a timing thread, and completing it completes the instance")
    void aJobArrivesAndItsCompletionCompletesTheInstance() {
        final var configuration = configuration(ENGINE.restAddress(), ENGINE.grpcAddress());
        final var inbox = new JobInbox();
        try (var rest = new ZenBpmRestClient(ADAPTER_ID, configuration); var stream = new ZenBpmJobStream(ADAPTER_ID, configuration, clientId(), timing)) {
            rest.deployBpmn(oneServiceTask("stream-t1", "t1"));
            stream.onJob(inbox);
            stream.subscribe("module-a", "t1", Duration.ofSeconds(30), 4);
            final var instance = start(rest, "stream-t1");

            final var arrival = inbox.next(instance.key(), DELIVERY);
            rest.completeJob(arrival.key(), Map.of("done", true));

            assertAll(
                    () -> assertEquals("t1", arrival
                            .received()
                            .job()
                            .getType()),
                    () -> assertTrue(arrival
                            .thread()
                            .startsWith(TIMING_THREAD_PREFIX), arrival.thread()),
                    () -> assertTrue(arrival
                            .received()
                            .job()
                            .getLockUntil() > 0));
            eventually(() -> rest
                    .instance(instance.key())
                    .filter(read -> read.state() == ProcessInstanceRef.State.COMPLETED));
        }
    }

    /**
     * The relay keeps the stream out of reach while the second engine starts and a job is
     * created on it, so the job is certainly created while the stream is down.
     */
    @Test
    @DisplayName("After the engine was replaced the stream comes back, and a job created while it was down arrives")
    void theStreamComesBackAfterTheEngineWasReplaced(final CapturedOutput output) {
        final var first = new EngineUnderTest();
        first.start();
        final var inbox = new JobInbox();
        try (var relay = TcpRelay.to(first.grpcAddress()); var stream = new ZenBpmJobStream(ADAPTER_ID, configuration(
                first.restAddress(), relay.address()), clientId(), timing)) {
            stream.onJob(inbox);
            stream.subscribe("module-a", "t1", Duration.ofSeconds(30), 4);
            try (var rest = new ZenBpmRestClient(ADAPTER_ID, configuration(first.restAddress(), relay.address()))) {
                rest.deployBpmn(oneServiceTask("stream-t1", "t1"));
                inbox.next(start(rest, "stream-t1").key(), DELIVERY);
            }

            relay.cut();
            first.stop();
            final var second = new EngineUnderTest();
            second.start();
            try (second; var rest = new ZenBpmRestClient(ADAPTER_ID, configuration(second.restAddress(),
                    relay.address()))) {
                rest.deployBpmn(oneServiceTask("stream-t1", "t1"));
                final var createdWhileDown = start(rest, "stream-t1");
                relay.restoreTo(second.grpcAddress());

                inbox.next(createdWhileDown.key(), RECONNECT);
            }

            assertAll(
                    () -> assertTrue(stream.reconnects() > 0),
                    () -> assertTrue(output
                            .getAllOfThisTest()
                            .contains("ZenBPM[stream]: the job stream to %s is down (".formatted(relay.address()))),
                    () -> assertTrue(output
                            .getAllOfThisTest()
                            .contains("ZenBPM[stream]: the job stream to %s is back after ".formatted(relay
                                    .address()))));
        } finally {
            first.stop();
        }
    }

    private static ZenBpmAdapterConfiguration configuration(
        final String restAddress,
        final String grpcAddress
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        configuration.setGrpcAddress(grpcAddress);
        return configuration;
    }

    private static String clientId() {
        return "stream-it-%s".formatted(UUID.randomUUID());
    }

    private static ProcessInstanceRef start(
        final ZenBpmRestClient rest,
        final String processId
    ) {
        return rest.createInstance(CreateInstanceRequest
                .latestVersionOf(processId)
                .withBusinessKey(uniqueBusinessKey()));
    }

}
