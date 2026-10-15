/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The ZenBPM adapter is found on Spring Boot. The application names its adapter id only in
 * <code>prioritized-adapters</code>, as <code>zenbpm</code>, so the id is the type and it has no
 * section of its own: the platform derives it, and the adapter has to find the same id for its
 * beans and for its registry. The whole start runs, the deployment included; the workflow module has
 * no BPMN file, so nothing reaches the steps this version of the adapter refuses.
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(classes = SmokeTestApplication.class, properties = "vanillabp.prioritized-adapters=zenbpm")
class ZenBpmAdapterDiscoveryTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("The adapter id is the type, and it has a process service, a deployment service and a factory")
    void theAdapterIsDiscovered() {
        final var deploymentService = context.getBean("ZenBpm_DeploymentService_zenbpm", ZenBpmDeploymentService.class);
        final var processService = context.getBean("ZenBpm_ProcessService_zenbpm", ZenBpmProcessService.class);

        assertAll(
                () -> assertEquals("zenbpm", deploymentService.getAdapterId()),
                () -> assertEquals("zenbpm", deploymentService.getAdapterType()),
                () -> assertEquals("zenbpm", processService.getAdapterId()),
                () -> assertEquals(List.of("zenbpm"), context
                        .getBean(ZenBpmClientRegistry.class)
                        .factories()
                        .stream()
                        .map(ZenBpmClientFactory::getAdapterId)
                        .toList()));
    }

    @Test
    @DisplayName("The platform finds both services through its streams of element beans, and no list bean exists")
    void thePlatformFindsTheElementBeans() {
        final var listBeans = Arrays
                .stream(context.getBeanNamesForType(List.class))
                .filter(name -> name
                        .toLowerCase(Locale.ROOT)
                        .contains("zenbpm"))
                .toList();

        assertAll(
                () -> assertEquals(List.of("zenbpm"), context
                        .getBeanProvider(AdapterDeploymentService.class)
                        .stream()
                        .map(AdapterDeploymentService::getAdapterId)
                        .toList()),
                () -> assertEquals(List.of("zenbpm"), context
                        .getBeanProvider(MigratableProcessService.class)
                        .stream()
                        .map(MigratableProcessService::getAdapterId)
                        .toList()),
                () -> assertEquals(List.of(), listBeans));
    }

    @Test
    @DisplayName("The application's process service is the platform's, which the adapter's services are wired into")
    void theApplicationGetsThePlatformsProcessService() {
        assertSame(context.getBean("VanillaBP_ProcessService_%s".formatted(Aggregate.class.getName())), context
                .getBean(SampleWorkflowService.class)
                .processService());
    }

    @Test
    @DisplayName("The deployment service reports into the platform's startup report, not into one of its own")
    void theDeploymentServiceReportsIntoThePlatformsBlock() {
        final var deploymentService = context.getBean(ZenBpmDeploymentService.class);

        assertSame(context.getBean(StartupReport.class), deploymentService.getStartupReport());
    }

    @Test
    @DisplayName("The persistence of the smoke application fails loudly, so a test which starts to persist hears about it")
    void theSmokeApplicationPersistsNothing() {
        final var persistence = new SmokeTestApplication().noPersistenceForTheSampleAggregate();

        assertAll(
                () -> assertSame(Object.class, persistence.getAggregateClass()),
                () -> assertNull(persistence.getAggregateIdType()),
                () -> assertThrows(UnsupportedOperationException.class, () -> persistence.save(new Aggregate())),
                () -> assertThrows(UnsupportedOperationException.class, () -> persistence.getAggregateId(
                        new Aggregate())),
                () -> assertThrows(UnsupportedOperationException.class, () -> persistence.loadById("order-1")));
    }

}
