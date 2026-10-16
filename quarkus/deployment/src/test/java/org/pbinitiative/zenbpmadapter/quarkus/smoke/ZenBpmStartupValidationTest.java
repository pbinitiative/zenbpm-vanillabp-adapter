/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * An application which configures nothing boots on Quarkus: the platform derives the adapter id
 * <code>zenbpm</code> from the one adapter type on the classpath, and that id has no address. What
 * the validation found is said where the platform says it: in the block at the end of the start,
 * under the heading <code>CONFIGURATION</code>, with the scope of the adapter id and the key which
 * is missing. The block is checked once the application stopped, because the test sees the log
 * only then. A use of the adapter fails with the same words, and its health is UNKNOWN with them.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmStartupValidationTest {

    private static final String MISSING_ADDRESS = "ZenBPM adapter 'zenbpm' is not configured: 'vanillabp.adapters.zenbpm.rest-address' is missing.";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            // the block is written at the end of the start, and read once the application stopped
            .assertLogRecords(records -> {
                final var block = StartupBlock.configuration(SmokeApplication.logOf(records));
                assertAll(
                        () -> assertTrue(block.contains("  zenbpm adapter 'zenbpm'\n"), block),
                        () -> assertTrue(block.contains(MISSING_ADDRESS), block));
            });

    @Inject
    ZenBpmClientRegistry registry;

    @Inject
    @Any
    Instance<List<MigratableProcessService<Object>>> processServices;

    @Inject
    @Any
    Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

    @Test
    @DisplayName("A use of the adapter fails with the words of the warning")
    void aUseOfTheAdapterFailsWithTheSameWords() {
        final var message = registry
                .getFactory("zenbpm")
                .unconfiguredMessage()
                .orElseThrow();

        final var use = assertThrows(RuntimeException.class, () -> SmokeApplication.startAWorkflow(processServices));

        assertAll(
                () -> assertTrue(message.startsWith(MISSING_ADDRESS), message),
                () -> assertEquals(message, use.getMessage()));
    }

    @Test
    @DisplayName("The health of the adapter id is UNKNOWN, with the words of the warning")
    void theHealthIsUnknown() {
        final var health = SmokeApplication
                .only(deploymentServices, ZenBpmDeploymentService.class)
                .checkHealth();

        assertAll(
                () -> assertEquals(AdapterHealth.Status.UNKNOWN, health.status()),
                () -> assertEquals("zenbpm", health.adapterId()),
                () -> assertTrue(Objects
                        .requireNonNullElse(health.description(), "")
                        .contains("'vanillabp.adapters.zenbpm.rest-address' is missing"), health::toString));
    }

}
