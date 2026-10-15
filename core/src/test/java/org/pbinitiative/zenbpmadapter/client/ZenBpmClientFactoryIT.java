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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.health.ZenBpmHealth;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The clients the way the platform and the deployment use them, against the pinned engine: a
 * factory built from a configuration, the engine wait, the environment info, the health check, a
 * job received through the factory's stream and completed through its REST client, and the health
 * check once the engine is gone.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmClientFactoryIT {

    private static final String ADAPTER_ID = "factory";

    /**
     * How long a job may take from the start of its instance to the stream: the engine looks for
     * jobs to hand out about once a second.
     */
    private static final Duration DELIVERY = Duration.ofSeconds(5);

    @Test
    @DisplayName("A factory waits for the engine, finds its commit tested, is UP, runs a job, and is DOWN once the engine stopped")
    void aFactoryAgainstThePinnedEngine() {
        try (var engine = new EngineUnderTest()) {
            engine.start();
            final var configuration = new ZenBpmAdapterConfiguration();
            configuration.setRestAddress(engine.restAddress());
            configuration.setGrpcAddress(engine.grpcAddress());
            configuration.setRequestTimeout(Duration.ofSeconds(2));
            final var report = new CollectedStartupReport();
            final var inbox = new JobInbox();

            try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "factory-it")) {
                ZenBpmEngineWait.awaitReady(factory);
                ZenBpmEnvironmentInfo.logOnce(factory, report);
                final var up = ZenBpmHealth.check(factory);

                assertAll(
                        () -> assertEquals(List.of(), report.findings(), "the engine's own commit is the tested one"),
                        () -> assertEquals(AdapterHealth.Status.UP, up.status()),
                        () -> assertTrue(EngineUnderTest
                                .pinnedCommit()
                                .startsWith(up
                                        .details()
                                        .get("commitAtStart")),
                                up::toString));

                final var rest = factory.rest();
                rest.deployBpmn(oneServiceTask("factory-t1", "t1"));
                factory
                        .stream()
                        .onJob(inbox);
                factory
                        .stream()
                        .subscribe("module-a", "t1", Duration.ofSeconds(30), 4);
                final var instance = rest.createInstance(CreateInstanceRequest
                        .latestVersionOf("factory-t1")
                        .withBusinessKey(uniqueBusinessKey()));
                rest.completeJob(inbox
                        .next(instance.key(), DELIVERY)
                        .key(), Map.of("done", true));

                eventually(() -> rest
                        .instance(instance.key())
                        .filter(read -> read.state() == ProcessInstanceRef.State.COMPLETED));
                eventually(() -> Optional
                        .of(ZenBpmHealth.check(factory))
                        .filter(health -> "connected".equals(health
                                .details()
                                .get("jobStream"))));

                engine.stop();
                final var down = ZenBpmHealth.check(factory);
                assertEquals(AdapterHealth.Status.DOWN, down.status(), down::toString);
            }
        }
    }

}
