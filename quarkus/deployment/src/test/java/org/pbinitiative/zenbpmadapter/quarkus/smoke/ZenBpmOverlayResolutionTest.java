/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.quarkus.runtime.VanillaBpZenBpmProperties;

import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The keys an application may set below the adapter level are read from the four levels on Quarkus,
 * and the most specific level which sets a key wins: task &gt; workflow &gt; workflow module &gt;
 * adapter, and the default where no level sets it. The application sets each key at every level it
 * has, and each case asks for a place where a different level is the most specific one.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmOverlayResolutionTest {

    private static final String MODULE = "vanillabp.workflow-modules.smoke-app";

    private static final String WORKFLOW = MODULE
            + ".workflows.SampleWorkflowService";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey("vanillabp.adapters.zenbpm.retry-backoff", "PT4S")
            .overrideConfigKey("vanillabp.adapters.zenbpm.max-redeliveries", "4")
            .overrideConfigKey("vanillabp.adapters.zenbpm.history-time-to-live", "PT4H")
            .overrideConfigKey(MODULE
                    + ".adapters.zenbpm.retry-backoff", "PT3S")
            .overrideConfigKey(MODULE
                    + ".adapters.zenbpm.max-redeliveries", "3")
            .overrideConfigKey(MODULE
                    + ".adapters.zenbpm.history-time-to-live", "PT3H")
            .overrideConfigKey(WORKFLOW
                    + ".adapters.zenbpm.retry-backoff", "PT2S")
            .overrideConfigKey(WORKFLOW
                    + ".adapters.zenbpm.max-redeliveries", "2")
            .overrideConfigKey(WORKFLOW
                    + ".adapters.zenbpm.history-time-to-live", "PT2H")
            .overrideConfigKey(WORKFLOW
                    + ".tasks.ship.adapters.zenbpm.retry-backoff", "PT1S")
            .overrideConfigKey(WORKFLOW
                    + ".tasks.ship.adapters.zenbpm.max-redeliveries", "1");

    @Test
    @DisplayName("retry-backoff is read from the most specific of the four levels which sets it")
    void retryBackoffThroughTheFourLevels() {
        final var overlay = overlay();

        assertAll(
                () -> assertEquals(Duration.ofSeconds(1), overlay.retryBackoffFor("smoke-app",
                        "SampleWorkflowService", "ship", "zenbpm")),
                () -> assertEquals(Duration.ofSeconds(2), overlay.retryBackoffFor("smoke-app",
                        "SampleWorkflowService", "pack", "zenbpm")),
                () -> assertEquals(Duration.ofSeconds(3), overlay.retryBackoffFor("smoke-app", "OtherWorkflow",
                        "ship", "zenbpm")),
                () -> assertEquals(Duration.ofSeconds(4), overlay.retryBackoffFor("other-module",
                        "SampleWorkflowService", "ship", "zenbpm")),
                () -> assertEquals(ZenBpmAdapterConfiguration.DEFAULT_RETRY_BACKOFF, overlay.retryBackoffFor(
                        "smoke-app", "SampleWorkflowService", "ship", "other")));
    }

    @Test
    @DisplayName("max-redeliveries is read from the most specific of the four levels which sets it")
    void maxRedeliveriesThroughTheFourLevels() {
        final var overlay = overlay();

        assertAll(
                () -> assertEquals(1, overlay.maxRedeliveriesFor("smoke-app", "SampleWorkflowService", "ship",
                        "zenbpm")),
                () -> assertEquals(2, overlay.maxRedeliveriesFor("smoke-app", "SampleWorkflowService", "pack",
                        "zenbpm")),
                () -> assertEquals(3, overlay.maxRedeliveriesFor("smoke-app", "OtherWorkflow", "ship", "zenbpm")),
                () -> assertEquals(4, overlay.maxRedeliveriesFor("other-module", "SampleWorkflowService", "ship",
                        "zenbpm")),
                () -> assertEquals(ZenBpmAdapterConfiguration.DEFAULT_MAX_REDELIVERIES, overlay
                        .maxRedeliveriesFor("smoke-app", "SampleWorkflowService", "ship", "other")));
    }

    @Test
    @DisplayName("history-time-to-live is read from the workflow, the workflow module or the adapter, else the engine decides")
    void historyTimeToLiveThroughItsThreeLevels() {
        final var overlay = overlay();

        assertAll(
                () -> assertEquals(Duration.ofHours(2), overlay.historyTimeToLiveFor("smoke-app",
                        "SampleWorkflowService", "zenbpm")),
                () -> assertEquals(Duration.ofHours(3), overlay.historyTimeToLiveFor("smoke-app", "OtherWorkflow",
                        "zenbpm")),
                () -> assertEquals(Duration.ofHours(4), overlay.historyTimeToLiveFor("other-module",
                        "SampleWorkflowService", "zenbpm")),
                () -> assertNull(overlay.historyTimeToLiveFor("smoke-app", "SampleWorkflowService", "other")));
    }

    @Test
    @DisplayName("The adapter's own values reach the configuration, and the values below it pass the validation")
    void theAdapterLevelReachesTheConfiguration() {
        final var configuration = overlay().configurationOf("zenbpm");

        assertAll(
                () -> assertEquals(Duration.ofSeconds(4), configuration.getRetryBackoff()),
                () -> assertEquals(4, configuration.getMaxRedeliveries()),
                () -> assertEquals(Duration.ofHours(4), configuration.getHistoryTimeToLive()),
                () -> assertEquals(ZenBpmAdapterConfiguration.ValidationResult.Kind.COMPLETE, configuration
                        .validate("zenbpm", false, false)
                        .kind()));
    }

    // read as the adapter reads it: never injected, see the javadoc of the mapping
    private static VanillaBpZenBpmProperties overlay() {
        return ConfigProvider
                .getConfig()
                .unwrap(SmallRyeConfig.class)
                .getConfigMapping(VanillaBpZenBpmProperties.class);
    }

}
