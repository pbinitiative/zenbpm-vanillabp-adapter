/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.pbinitiative.zenbpmadapter.quarkus.runtime.VanillaBpZenBpmProperties;

import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * The keys an application may set below the adapter level are read from the four levels on Quarkus,
 * and the most specific level which sets a key wins: task &gt; workflow &gt; workflow module &gt;
 * adapter, and the default where no level sets it. The application sets each key at every level it
 * has, and each case asks for a place where a different level is the most specific one.
 * <code>accept-unscoped-identifiers</code> has two levels, the workflow module and the adapter.
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
            .overrideConfigKey("vanillabp.adapters.zenbpm.job-timeout", "PT4M")
            .overrideConfigKey(MODULE
                    + ".adapters.zenbpm.job-timeout", "PT3M")
            .overrideConfigKey(WORKFLOW
                    + ".adapters.zenbpm.job-timeout", "PT2M")
            .overrideConfigKey(WORKFLOW
                    + ".tasks.ship.adapters.zenbpm.job-timeout", "PT1M")
            .overrideConfigKey("vanillabp.adapters.zenbpm.retry-backoff", "PT4S")
            .overrideConfigKey("vanillabp.adapters.zenbpm.history-time-to-live", "PT4H")
            .overrideConfigKey("vanillabp.adapters.zenbpm.accept-unscoped-identifiers", "true")
            .overrideConfigKey(MODULE
                    + ".adapters.zenbpm.accept-unscoped-identifiers", "false")
            .overrideConfigKey(MODULE
                    + ".adapters.zenbpm.retry-backoff", "PT3S")
            .overrideConfigKey(MODULE
                    + ".adapters.zenbpm.history-time-to-live", "PT3H")
            .overrideConfigKey(WORKFLOW
                    + ".adapters.zenbpm.retry-backoff", "PT2S")
            .overrideConfigKey(WORKFLOW
                    + ".adapters.zenbpm.history-time-to-live", "PT2H")
            .overrideConfigKey(WORKFLOW
                    + ".tasks.ship.adapters.zenbpm.retry-backoff", "PT1S");

    @Inject
    @Any
    Instance<List<MigratableProcessService<Object>>> processServices;

    @Inject
    @Any
    Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

    @Test
    @DisplayName("job-timeout is read from the most specific of the four levels which sets it, and the deployment service resolves a task's the same way")
    void jobTimeoutThroughTheFourLevels() {
        final var overlay = overlay();
        final ZenBpmDeploymentService deploymentService = SmokeApplication.only(deploymentServices,
                ZenBpmDeploymentService.class);

        assertAll(
                () -> assertEquals(Duration.ofMinutes(1), overlay.jobTimeoutFor("smoke-app", "SampleWorkflowService",
                        "ship", "zenbpm")),
                () -> assertEquals(Duration.ofMinutes(2), overlay.jobTimeoutFor("smoke-app", "SampleWorkflowService",
                        "pack", "zenbpm")),
                () -> assertEquals(Duration.ofMinutes(3), overlay.jobTimeoutFor("smoke-app", "OtherWorkflow", "ship",
                        "zenbpm")),
                () -> assertEquals(Duration.ofMinutes(4), overlay.jobTimeoutFor("other-module",
                        "SampleWorkflowService", "ship", "zenbpm")),
                () -> assertEquals(ZenBpmAdapterConfiguration.DEFAULT_JOB_TIMEOUT, overlay.jobTimeoutFor("smoke-app",
                        "SampleWorkflowService", "ship", "other")),
                () -> assertEquals(Duration.ofMinutes(1), deploymentService.jobTimeoutOf("smoke-app",
                        "SampleWorkflowService", "ship")),
                () -> assertEquals(Duration.ofMinutes(4), deploymentService.jobTimeoutOf("other-module",
                        "SampleWorkflowService", "ship")));
    }

    @Test
    @DisplayName("retry-backoff is read from the most specific of the four levels which sets it, nothing where no level sets it, and the deployment service resolves a task's the same way")
    void retryBackoffThroughTheFourLevels() {
        final var overlay = overlay();
        final ZenBpmDeploymentService deploymentService = SmokeApplication.only(deploymentServices,
                ZenBpmDeploymentService.class);

        assertAll(
                () -> assertEquals(Optional.of(Duration.ofSeconds(1)), overlay.retryBackoffFor("smoke-app",
                        "SampleWorkflowService", "ship", "zenbpm")),
                () -> assertEquals(Optional.of(Duration.ofSeconds(2)), overlay.retryBackoffFor("smoke-app",
                        "SampleWorkflowService", "pack", "zenbpm")),
                () -> assertEquals(Optional.of(Duration.ofSeconds(3)), overlay.retryBackoffFor("smoke-app",
                        "OtherWorkflow", "ship", "zenbpm")),
                () -> assertEquals(Optional.of(Duration.ofSeconds(4)), overlay.retryBackoffFor("other-module",
                        "SampleWorkflowService", "ship", "zenbpm")),
                () -> assertEquals(Optional.empty(), overlay.retryBackoffFor("smoke-app", "SampleWorkflowService",
                        "ship", "other")),
                () -> assertEquals(Optional.of(Duration.ofSeconds(1)), deploymentService.retryBackoffOf("smoke-app",
                        "SampleWorkflowService", "ship")),
                () -> assertEquals(Optional.of(Duration.ofSeconds(4)), deploymentService.retryBackoffOf(
                        "other-module", "SampleWorkflowService", "ship")));
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
    @DisplayName("The process service sends the history-time-to-live of the workflow, else of its module, else of the adapter id")
    void theProcessServiceSendsTheResolvedHistoryTimeToLive() {
        final ZenBpmProcessService<?> processService = SmokeApplication.only(processServices,
                ZenBpmProcessService.class);

        assertAll(
                () -> assertEquals(Optional.of(Duration.ofHours(2)), processService.historyTimeToLiveOf("smoke-app",
                        "SampleWorkflowService")),
                () -> assertEquals(Optional.of(Duration.ofHours(3)), processService.historyTimeToLiveOf("smoke-app",
                        "OtherWorkflow")),
                () -> assertEquals(Optional.of(Duration.ofHours(4)), processService.historyTimeToLiveOf(
                        "other-module", "SampleWorkflowService")));
    }

    @Test
    @DisplayName("accept-unscoped-identifiers is read from the workflow module where it sets the key, else from the adapter")
    void acceptUnscopedIdentifiersThroughItsTwoLevels() {
        final var configuration = overlay().configurationOf("zenbpm");

        assertAll(
                () -> assertEquals(Optional.empty(), configuration.keyAcceptingUnscopedIdentifiers("zenbpm",
                        "smoke-app")),
                () -> assertEquals(Optional.of("vanillabp.adapters.zenbpm.accept-unscoped-identifiers"), configuration
                        .keyAcceptingUnscopedIdentifiers("zenbpm", "other-module")));
    }

    @Test
    @DisplayName("The adapter's own values reach the configuration, and the values below it pass the validation")
    void theAdapterLevelReachesTheConfiguration() {
        final var configuration = overlay().configurationOf("zenbpm");

        assertAll(
                () -> assertEquals(Duration.ofMinutes(4), configuration.getJobTimeout()),
                () -> assertEquals(Duration.ofSeconds(4), configuration.getRetryBackoff()),
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
