/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * A complete adapter id boots on Quarkus with nothing of this adapter in the block at the end of
 * the start, and its clients are closed when the application stops. The smoke application names
 * <code>vanillabp.outbox.housekeeping.zone</code>, because the platform reports a missing zone in
 * the same section wherever the JVM runs on UTC, as on CI.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmCompleteAdapterIdTest {

    private static final FactoryLog CLOSING = new FactoryLog();

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .assertLogRecords(records -> {
                final var log = SmokeApplication.logOf(records);
                assertAll(
                        () -> assertTrue(StartupBlock.isWritten(log), log),
                        () -> assertEquals("", StartupBlock.configuration(log), log),
                        () -> assertFalse(log.contains("zenbpm adapter '"), log));
            })
            // the test's log records end where the application begins to stop, so what the
            // factory says while it closes is heard on its own logger
            .setBeforeAllCustomizer(CLOSING::listen)
            .setAfterUndeployListener(() -> {
                final var said = CLOSING.stopListening();
                assertTrue(said.contains(
                        "ZenBPM[zenbpm]: closing the REST client of the engine at http://localhost:8080"),
                        said::toString);
            });

    @Inject
    ZenBpmClientRegistry registry;

    @Inject
    @Any
    Instance<List<MigratableProcessService<Object>>> processServices;

    @Test
    @DisplayName("A complete adapter id has clients, and its client id starts with the application's name")
    void aCompleteAdapterIdHasClients() {
        final var factory = registry.getFactory("zenbpm");

        assertAll(
                () -> assertTrue(factory.isConfigured()),
                () -> assertTrue(factory
                        .clientId()
                        .startsWith("quarkus-smoke-test-zenbpm-"), factory.clientId()));
    }

    @Test
    @DisplayName("A use of a complete adapter id says that it is not built yet")
    void aUseSaysThatItIsNotBuiltYet() {
        final var use = assertThrows(RuntimeException.class, () -> SmokeApplication.startAWorkflow(processServices));

        assertEquals("ZenBPM adapter 'zenbpm': phase one of START_WORKFLOW is implemented in a later story", use
                .getMessage());
    }

}
