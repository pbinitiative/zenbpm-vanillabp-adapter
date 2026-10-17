/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The variables of a command carry the values the aggregate shares and its id, which no attribute
 * and no sync model can take away.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmSharedValuesTest {

    @Test
    @DisplayName("The shared values are written in their order, and the id as text after them")
    void theIdFollowsTheSharedValuesAsText() {
        final var shared = new LinkedHashMap<String, Object>();
        shared.put("amount", 1000);
        shared.put("customer", null);

        final var variables = ZenBpmSharedValues.forCommand("orderId", 42L, shared);

        assertAll(
                () -> assertEquals(List.of("amount", "customer", "orderId"), List.copyOf(variables.keySet())),
                () -> assertEquals("42", variables.get("orderId")),
                () -> assertEquals(1000, variables.get("amount")));
    }

    @Test
    @DisplayName("The id wins over an attribute of the same name, and is still the last variable")
    void theIdWinsOverAnAttributeOfItsName() {
        final var shared = new LinkedHashMap<String, Object>();
        shared.put("orderId", "an attribute");
        shared.put("amount", 1000);

        final var variables = ZenBpmSharedValues.forCommand("orderId", "order-1", shared);

        assertEquals(List.of(Map.entry("amount", 1000), Map.entry("orderId", "order-1")), List.copyOf(variables
                .entrySet()));
    }

    @Test
    @DisplayName("An aggregate which shares nothing, as one marked @NoSyncWithBPMS, still carries its id, and the variables cannot be changed")
    void anAggregateWhichSharesNothingStillCarriesItsId() {
        final var variables = ZenBpmSharedValues.forCommand("orderId", "order-1", Map.of());

        assertAll(
                () -> assertEquals(Map.of("orderId", "order-1"), variables),
                () -> assertThrows(UnsupportedOperationException.class, () -> variables.put("other", 1)));
    }

}
