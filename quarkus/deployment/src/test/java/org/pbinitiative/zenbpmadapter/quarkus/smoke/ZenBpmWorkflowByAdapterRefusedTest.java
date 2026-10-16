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
 * A workflow which sets the name-clash-avoidance mode <code>by-adapter</code> of its own, in a
 * workflow module under <code>use-prefix</code>, ends the start on Quarkus: the platform checks the
 * mode of the workflow module only, so the adapter refuses the workflow's, naming the workflow's
 * key, see decision 24 in the repository's DECISIONS.md.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmWorkflowByAdapterRefusedTest {

    private static final String KEY = "vanillabp.workflow-modules.smoke-app.workflows.SampleWorkflowService.adapters.zenbpm.name-clash-avoidance";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withBpmnFiles()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey("vanillabp.adapters.zenbpm.name-clash-avoidance", "use-prefix")
            .overrideConfigKey(KEY, "by-adapter")
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_BPMN)
            .assertException(failure -> assertEquals("""
                    ZenBPM has no isolation mechanism of its own, so the name-clash-avoidance mode 'by-adapter' \
                    cannot be served by adapter 'zenbpm' - but it is configured for workflow 'SampleWorkflowService' \
                    of workflow module 'smoke-app'. Choose explicitly:
                      %s: use-prefix   # VanillaBP prefixes the identifiers
                      %s: none         # your identifiers are unique already
                    or remove the key, so the mode of the workflow module applies.""".formatted(KEY, KEY),
                    SmokeApplication.rootMessageOf(failure)));

    @Test
    @DisplayName("A workflow setting name-clash-avoidance 'by-adapter' of its own in a module under use-prefix ends the start, naming the workflow's key")
    void byAdapterOfOneWorkflowEndsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}
