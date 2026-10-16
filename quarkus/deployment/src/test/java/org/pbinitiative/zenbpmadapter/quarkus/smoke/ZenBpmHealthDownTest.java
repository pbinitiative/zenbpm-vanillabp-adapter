/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * What the health of a ZenBPM adapter id says on Quarkus when nothing listens on its address: DOWN,
 * naming the address, the request timeout and the job stream. The health is the one the platform
 * asks the adapter's deployment service for. An adapter id which is not configured is UNKNOWN, see
 * {@code ZenBpmStartupValidationTest}; that an engine which takes the connection and never answers
 * is DOWN within the request timeout is shown in core, by the tests of <code>ZenBpmHealth</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmHealthDownTest {

    private static final String ADDRESS_KEY = "vanillabp.adapters.zenbpm.rest-address";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey(ADDRESS_KEY, "http://localhost:%d".formatted(OneFreePortPerJvm.getPort()))
            .overrideConfigKey("vanillabp.adapters.zenbpm.request-timeout", "PT1S");

    @Inject
    @Any
    Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

    @Test
    @DisplayName("An address nothing listens on is DOWN, naming the address, the request timeout and the job stream")
    void anAddressNothingListensOnIsDown() {
        // the address as the application has it: the test class is loaded once more for the application
        final var address = ConfigProvider
                .getConfig()
                .getValue(ADDRESS_KEY, String.class);
        final var deploymentService = SmokeApplication.only(deploymentServices, ZenBpmDeploymentService.class);

        final var health = assertTimeoutPreemptively(Duration.ofSeconds(5), deploymentService::checkHealth);

        assertAll(
                () -> assertEquals(AdapterHealth.Status.DOWN, health.status()),
                () -> assertEquals("zenbpm", health.adapterId()),
                () -> assertEquals(address, health
                        .details()
                        .get("address"), health::toString),
                () -> assertEquals("PT1S", health
                        .details()
                        .get("requestTimeout"), health::toString),
                () -> assertNotNull(health
                        .details()
                        .get("jobStream"), health::toString));
    }

}
