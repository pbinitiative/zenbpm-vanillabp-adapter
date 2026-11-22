/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * The check for unknown keys sees keys, not ids. The sections of adapter ids and of workflow
 * modules are maps, and the mapping of this adapter takes any name in them. So a key of the adapter
 * below an adapter id which does not exist, a misspelt one for example, or below a workflow module
 * which does not exist, passes the check, and nobody reads it. Spring Boot does the same.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmKeyBelowAnUnknownIdTest {

    private static final String BELOW_A_MISSPELT_ADAPTER_ID = "vanillabp.workflow-modules.smoke-app.adapters.zenbmp.job-timeout";

    private static final String BELOW_AN_UNKNOWN_WORKFLOW_MODULE = "vanillabp.workflow-modules.no-such-module.adapters.zenbpm.job-timeout";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey(BELOW_A_MISSPELT_ADAPTER_ID, "PT1M")
            .overrideConfigKey(BELOW_AN_UNKNOWN_WORKFLOW_MODULE, "PT2M");

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("A key of the adapter below an adapter id or a workflow module which does not exist is not refused")
    void aKeyBelowAnIdWhichDoesNotExistIsNotRefused() {
        // the application of this class started with both keys set
        final var config = ConfigProvider.getConfig();

        assertAll(
                () -> assertEquals(Duration.ofMinutes(1), config.getValue(BELOW_A_MISSPELT_ADAPTER_ID, Duration.class)),
                () -> assertEquals(Duration.ofMinutes(2), config.getValue(BELOW_AN_UNKNOWN_WORKFLOW_MODULE,
                        Duration.class)),
                () -> assertEquals(List.of("zenbpm"), registry
                        .factories()
                        .stream()
                        .map(ZenBpmClientFactory::getAdapterId)
                        .toList()));
    }

}
