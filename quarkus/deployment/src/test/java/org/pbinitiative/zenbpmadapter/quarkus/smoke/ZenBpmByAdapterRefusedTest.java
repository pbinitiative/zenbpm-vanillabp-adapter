/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow module with a BPMN file and no name-clash-avoidance configured ends the start on
 * Quarkus: the default is <code>by-adapter</code>, which ZenBPM cannot serve, and the platform's
 * message names <code>use-prefix</code>, <code>none</code> and the key to set.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmByAdapterRefusedTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withBpmnFiles()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_BPMN)
            .assertException(failure -> assertEquals("""
                    ZenBPM has no isolation mechanism of its own, so the name-clash-avoidance mode 'by-adapter' \
                    cannot be served by adapter 'zenbpm' - but it applies to workflow module 'smoke-app' (nothing \
                    is configured, so the default applies). Choose explicitly:
                      vanillabp.adapters.zenbpm.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers
                      vanillabp.adapters.zenbpm.name-clash-avoidance: none         # your identifiers are unique already
                    The same key may be set per workflow module and workflow \
                    (vanillabp.workflow-modules.smoke-app.adapters.zenbpm.name-clash-avoidance).""", SmokeApplication
                    .rootMessageOf(failure)));

    @Test
    @DisplayName("A workflow module with BPMN files and no name-clash-avoidance configured ends the start, naming use-prefix, none and the key")
    void nothingConfiguredEndsTheStartNamingBothWaysOut() {
        // never runs: the start fails, and the extension checks how
    }

}
