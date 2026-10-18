/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The keys of a start by a message are read at the adapter level of a Quarkus application and
 * validated while it starts: a <code>message-start-lookup-max-instances</code> below one ends the
 * start, naming the key and its default.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmMessageStartKeysTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:1")
            .overrideConfigKey("vanillabp.adapters.zenbpm.message-start-lookup", "scan")
            .overrideConfigKey("vanillabp.adapters.zenbpm.message-start-lookup-max-instances", "0")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertAll(
                        () -> assertTrue(message.startsWith("ZenBPM adapter 'zenbpm' is configured inconsistently:"),
                                message),
                        () -> assertTrue(message.contains(
                                "  - vanillabp.adapters.zenbpm.message-start-lookup-max-instances is 0 and has to be at least 1; the default is 1000"),
                                message));
            });

    @Test
    @DisplayName("A bound of the lookup of a start by a message below one ends the start, naming the key and its default")
    void theBoundOfTheLookupOfAMessageStartIsValidated() {
        // never runs: the start fails, and the extension checks how
    }

}
