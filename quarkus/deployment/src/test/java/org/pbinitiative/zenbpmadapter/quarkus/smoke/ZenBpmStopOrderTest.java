/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Stopping a Quarkus application stops every started workflow module before the clients of its
 * adapter id close: the module subscribed its job type while the application started, and the
 * factory, which hears the stop on its own logger, closes without a module it had to stop. The engine
 * is a server standing in for ZenBPM which takes every file; the job stream addresses a port nothing
 * listens on, so the subscription stays with the stream.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmStopOrderTest {

    private static final FactoryLog CLOSING = new FactoryLog();

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withAModuleWhichSubscribes()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", StandInEngine.addressOfOneTakingEveryFile())
            .overrideConfigKey("vanillabp.adapters.zenbpm.grpc-address", "localhost:1")
            .overrideConfigKey("vanillabp.adapters.zenbpm.name-clash-avoidance", "use-prefix")
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_SHIPPING)
            .assertLogRecords(records -> {
                final var log = SmokeApplication.logOf(records);
                assertTrue(log.contains(
                        "ZenBPM[zenbpm]: workflow module 'smoke-app' subscribed the job type(s) smoke-app__Shipping__ship"),
                        log);
            })
            // the test's log records end where the application begins to stop, so what the
            // factory says while it closes is heard on its own logger
            .setBeforeAllCustomizer(CLOSING::listen)
            .setAfterUndeployListener(() -> {
                final var said = CLOSING.stopListening();
                assertAll(
                        () -> assertTrue(said
                                .stream()
                                .anyMatch(message -> message.startsWith(
                                        "ZenBPM[zenbpm]: closing the REST client of the engine at ")),
                                said::toString),
                        () -> assertTrue(said
                                .stream()
                                .noneMatch(message -> message.contains("still subscribe their job types")),
                                said::toString));
            });

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("A started module is open at the factory while the application runs, and is stopped before the factory closes")
    void aStartedModuleIsStoppedBeforeTheFactoryCloses() {
        assertEquals(Set.of("smoke-app"), registry
                .getFactory("zenbpm")
                .getOpenWorkflowModules());
    }

}
