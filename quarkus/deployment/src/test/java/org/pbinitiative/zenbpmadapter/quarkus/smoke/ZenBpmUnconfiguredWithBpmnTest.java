/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An adapter id without an address boots on Quarkus as long as its workflow module has no BPMN
 * file. Once the module has one, the start ends with the message of the validation, which names the
 * key to set: the adapter reads the file, which needs no engine, and the first step which needs one
 * refuses.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmUnconfiguredWithBpmnTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withBpmnFiles()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_BPMN)
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertTrue(message.startsWith(
                        "ZenBPM adapter 'zenbpm' is not configured: 'vanillabp.adapters.zenbpm.rest-address' is missing."),
                        message);
            });

    @Test
    @DisplayName("An adapter id without an address and a workflow module with BPMN files end the start, naming the key")
    void anUnconfiguredAdapterIdWithBpmnFilesEndsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}
