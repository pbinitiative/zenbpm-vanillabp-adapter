/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * An application of two adapter ids which does not use the Micrometer extension, as this module's
 * tests do not: it boots as it would with it, and the adapter registers no meter binder, so its
 * deployment services measure nothing. That the meters exist where the application uses the
 * extension is shown by the prod-mode test of the module <code>quarkus/integration-tests</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmWithoutMicrometerTest {

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
    Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

    @Inject
    @Any
    Instance<ZenBpmMetrics> metrics;

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("Without the Micrometer extension the application boots with a deployment service and a factory per adapter id, the adapter registers no meter binder, and each deployment service measures nothing")
    void withoutMicrometerTheApplicationBootsAndMeasuresNothing() {
        final var zenBpmDeploymentServices = deploymentServices
                .stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(ZenBpmDeploymentService.class::isInstance)
                .map(ZenBpmDeploymentService.class::cast)
                .toList();

        assertAll(
                () -> assertFalse(isPresent("io.micrometer.core.instrument.MeterRegistry"),
                        "the application must not see Micrometer"),
                () -> assertEquals(List.of("zenbpm-new", "zenbpm-old"), zenBpmDeploymentServices
                        .stream()
                        .map(ZenBpmDeploymentService::getAdapterId)
                        .sorted()
                        .toList()),
                () -> assertEquals(2, registry
                        .factories()
                        .size()),
                () -> assertTrue(metrics.isUnsatisfied(), "no meter binder without Micrometer"),
                () -> assertTrue(zenBpmDeploymentServices
                        .stream()
                        .allMatch(deploymentService -> deploymentService.getMetrics() == ZenBpmMetrics.NONE)));
    }

    private static boolean isPresent(final String className) {
        try {
            Class.forName(className, false, Thread
                    .currentThread()
                    .getContextClassLoader());
            return true;
        } catch (final ClassNotFoundException e) {
            return false;
        }
    }

}
