/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * The ZenBPM adapter is found on Quarkus. The application names its adapter id only in
 * <code>prioritized-adapters</code>, as <code>zenbpm</code>, so the id is the type and it has no
 * section of its own: the platform derives it, and the adapter has to find the same id for its
 * services and for its registry. The whole start runs, the deployment included; the workflow module
 * has no BPMN file, so nothing reaches the steps this version of the adapter refuses.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmAdapterDiscoveryTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            // the extension is a feature of its own, which Quarkus names when it started
            .assertLogRecords(records -> {
                final var log = SmokeApplication.logOf(records);
                assertTrue(log
                        .lines()
                        .anyMatch(line -> line.startsWith("Installed features: [") && line.contains(
                                "vanillabp-zenbpm")),
                        log);
            });

    // the platform's shape on Quarkus: one list per adapter type, one element per adapter id
    @Inject
    @Any
    Instance<List<MigratableProcessService<Object>>> processServices;

    @Inject
    @Any
    Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

    @Inject
    ZenBpmClientRegistry registry;

    @Inject
    SampleWorkflowService sampleWorkflowService;

    @Inject
    StartupReport startupReport;

    @Test
    @DisplayName("The adapter id is the type, and it has a process service, a deployment service and a factory")
    void theAdapterIsDiscovered() {
        final var deploymentService = zenBpm(deploymentServices, ZenBpmDeploymentService.class);
        final var processService = zenBpm(processServices, ZenBpmProcessService.class);

        assertAll(
                () -> assertEquals(List.of("zenbpm"), deploymentService
                        .stream()
                        .map(AdapterDeploymentService::getAdapterId)
                        .toList()),
                () -> assertEquals("zenbpm", deploymentService
                        .getFirst()
                        .getAdapterType()),
                () -> assertEquals(List.of("zenbpm"), processService
                        .stream()
                        .map(MigratableProcessService::getAdapterId)
                        .toList()),
                () -> assertEquals(List.of("zenbpm"), registry
                        .factories()
                        .stream()
                        .map(ZenBpmClientFactory::getAdapterId)
                        .toList()));
    }

    @Test
    @DisplayName("The application's process service is the platform's, and the adapter id is wired into it")
    void theApplicationGetsThePlatformsProcessService() {
        final var processService = assertInstanceOf(ProcessServiceBaseCdiBean.class, sampleWorkflowService
                .processService());

        assertEquals("zenbpm", processService
                .getMigrationProcessService()
                .getAdapters()
                .get("zenbpm"));
    }

    @Test
    @DisplayName("The deployment service reports into the platform's startup report, not into one of its own")
    void theDeploymentServiceReportsIntoThePlatformsBlock() {
        final var deploymentService = zenBpm(deploymentServices, ZenBpmDeploymentService.class).getFirst();

        assertSame(startupReport, deploymentService.getStartupReport());
    }

    @Test
    @DisplayName("The adapter's services stand in a list of their own, which holds nothing else")
    void theAdapterHasAListOfItsOwn() {
        assertAll(
                () -> assertEquals(1, listsHolding(deploymentServices, ZenBpmDeploymentService.class)),
                () -> assertEquals(1, listsHolding(processServices, ZenBpmProcessService.class)));
    }

    // the platform's way to collect the lists of every adapter type on the classpath
    private static <T> List<T> zenBpm(
        final Instance<? extends List<?>> lists,
        final Class<T> type
    ) {
        return lists
                .stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    // a list with an element of the type holds no element of another type
    private static long listsHolding(
        final Instance<? extends List<?>> lists,
        final Class<?> type
    ) {
        return lists
                .stream()
                .filter(Objects::nonNull)
                .filter(list -> list
                        .stream()
                        .anyMatch(type::isInstance))
                .peek(list -> assertTrue(list
                        .stream()
                        .allMatch(type::isInstance), list::toString))
                .count();
    }

}
