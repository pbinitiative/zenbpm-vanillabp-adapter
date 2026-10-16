/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Two adapter ids of type <code>zenbpm</code>, the setup of a migration from one engine to another:
 * each id gets its own process service, deployment service and factory, all in the one list of each
 * kind the adapter produces.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmTwoAdapterIdsTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm-new,zenbpm-old")
            .overrideConfigKey("vanillabp.adapters.zenbpm-new.type", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm-new.rest-address", "http://new-engine:8080")
            .overrideConfigKey("vanillabp.adapters.zenbpm-old.type", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm-old.rest-address", "http://old-engine:8080");

    @Inject
    @Any
    Instance<List<MigratableProcessService<Object>>> processServices;

    @Inject
    @Any
    Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("Each adapter id has its own process service and deployment service")
    void eachAdapterIdHasItsOwnServices() {
        assertAll(
                () -> assertEquals(List.of("zenbpm-new", "zenbpm-old"), adapterIdsOf(deploymentServices,
                        ZenBpmDeploymentService.class)),
                () -> assertEquals(List.of("zenbpm-new", "zenbpm-old"), adapterIdsOf(processServices,
                        ZenBpmProcessService.class)));
    }

    @Test
    @DisplayName("The registry holds a factory with clients per adapter id, in the order of the ids")
    void theRegistryHoldsAFactoryPerAdapterId() {
        final var factories = registry.factories();

        assertAll(
                () -> assertEquals(List.of("zenbpm-new", "zenbpm-old"), factories
                        .stream()
                        .map(ZenBpmClientFactory::getAdapterId)
                        .toList()),
                () -> assertTrue(factories
                        .stream()
                        .allMatch(ZenBpmClientFactory::isConfigured)));
    }

    private static List<String> adapterIdsOf(
        final Instance<? extends List<?>> lists,
        final Class<?> type
    ) {
        return lists
                .stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(type::isInstance)
                .map(service -> service instanceof AdapterDeploymentService<?, ?> deploymentService
                        ? deploymentService.getAdapterId()
                        : ((MigratableProcessService<?>) service).getAdapterId())
                .sorted()
                .toList();
    }

}
