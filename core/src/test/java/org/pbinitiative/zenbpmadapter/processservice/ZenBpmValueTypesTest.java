/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the engine does with each type, in each direction. Every wrapper of a primitive and its
 * primitive have an answer of their own; {@link ZenBpmValueTypesIT} has the pinned engine show each
 * answer.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmValueTypesTest {

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyTypeAndDirection")
    @DisplayName("Each type is answered as the engine treats it, in each direction")
    void eachTypeIsAnsweredAsTheEngineTreatsIt(
        final Class<?> type,
        final ValueDirection direction,
        final ValueTypeVerdict.Kind expected
    ) {
        final var verdict = ZenBpmValueTypes.verdictFor(type, direction);

        assertEquals(expected, verdict.kind(), String.valueOf(verdict.explanation()));
        if (expected == ValueTypeVerdict.Kind.SURVIVES) {
            assertNull(verdict.explanation());
        } else {
            assertTrue(verdict
                    .explanation()
                    .startsWith("that "), verdict.explanation());
        }
    }

    @Test
    @DisplayName("A large whole number, a decimal and a character are explained differently on each way")
    void eachWayIsExplainedInItsOwnWords() {
        assertAll(Stream
                .of(Long.class, long.class, BigInteger.class, BigDecimal.class, Character.class, char.class)
                .map(type -> () -> assertNotEquals(ZenBpmValueTypes
                        .verdictFor(type, ValueDirection.TO_BPMS)
                        .explanation(),
                        ZenBpmValueTypes
                                .verdictFor(type, ValueDirection.FROM_BPMS)
                                .explanation(),
                        type.getName())));
    }

    @Test
    @DisplayName("The rounding of a large whole number is named with the number where it starts")
    void theRoundingIsNamedWithItsBound() {
        assertAll(
                () -> assertTrue(ZenBpmValueTypes
                        .verdictFor(Long.class, ValueDirection.TO_BPMS)
                        .explanation()
                        .contains("9007199254740993 is stored as 9007199254740992")),
                () -> assertTrue(ZenBpmValueTypes
                        .verdictFor(BigDecimal.class, ValueDirection.TO_BPMS)
                        .explanation()
                        .contains("120.50 is stored as 120.5")));
    }

    @Test
    @DisplayName("A type nobody named, and a type without a JSON value of its own, cannot be judged")
    void whatCannotBePlacedIsNotGuessed() {
        assertAll(
                () -> assertEquals(ValueTypeVerdict.cannotSay("no type was named"), ZenBpmValueTypes.verdictFor(null,
                        ValueDirection.TO_BPMS)),
                () -> assertEquals(
                        "that a UUID is no JSON value of its own and reaches the engine as whatever it was serialized to",
                        ZenBpmValueTypes
                                .verdictFor(UUID.class, ValueDirection.FROM_BPMS)
                                .explanation()));
    }

    static Stream<Arguments> everyTypeAndDirection() {
        final var survive = List.<Class<?>>of(String.class, StringBuilder.class, Boolean.class, boolean.class,
                Integer.class, int.class, Short.class, short.class, Byte.class, byte.class, Double.class,
                double.class, Float.class, float.class, Date.class, Instant.class, ValueDirection.class);
        final var changed = List.<Class<?>>of(Long.class, long.class, BigInteger.class, BigDecimal.class,
                Character.class, char.class);
        final var cannotSay = List.<Class<?>>of(Object.class, UUID.class, LocalDate.class, Map.class, List.class,
                byte[].class, Void.class);
        return Stream
                .of(ValueDirection.values())
                .flatMap(direction -> Stream.of(
                        survive
                                .stream()
                                .map(type -> Arguments.of(type, direction, ValueTypeVerdict.Kind.SURVIVES)),
                        changed
                                .stream()
                                .map(type -> Arguments.of(type, direction, ValueTypeVerdict.Kind.CHANGED)),
                        cannotSay
                                .stream()
                                .map(type -> Arguments.of(type, direction, ValueTypeVerdict.Kind.CANNOT_SAY)))
                        .flatMap(arguments -> arguments));
    }

}
