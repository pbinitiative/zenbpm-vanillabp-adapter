/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A time to live is sent in whole seconds, the form the engine reads back exactly, and only where
 * the engine keeps it as it is. {@link ZenBpmRestClientContractIT} has the pinned engine read the
 * form.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmTimeToLiveTest {

    @Test
    @DisplayName("A time to live is sent as whole seconds, from none at all to the longest the engine takes")
    void aTimeToLiveIsSentAsWholeSeconds() {
        assertAll(
                () -> assertEquals("2592000s", ZenBpmTimeToLive.engineForm(Duration.ofDays(30))),
                () -> assertEquals("1s", ZenBpmTimeToLive.engineForm(Duration.ofSeconds(1))),
                () -> assertEquals("0s", ZenBpmTimeToLive.engineForm(Duration.ZERO)),
                () -> assertEquals("9223372036s", ZenBpmTimeToLive.engineForm(ZenBpmTimeToLive.LONGEST)),
                () -> assertEquals(Duration.parse("PT2562047H47M16S"), ZenBpmTimeToLive.LONGEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PT-1S", "PT0.5S", "PT1.000000001S", "PT2562047H47M17S"
    })
    @DisplayName("A time to live the engine would not keep as it is cannot be sent, which the validation prevents")
    void aTimeToLiveTheEngineWouldChangeCannotBeSent(final String timeToLive) {
        final var refusal = assertThrows(IllegalArgumentException.class, () -> ZenBpmTimeToLive.engineForm(Duration
                .parse(timeToLive)));

        assertEquals("The engine cannot keep a history-time-to-live of %s".formatted(Duration.parse(timeToLive)),
                refusal.getMessage());
    }

}
