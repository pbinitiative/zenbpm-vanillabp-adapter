/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;

/**
 * What the engine does with one Java type. The platform asks it while the application starts, to
 * learn whether a value survives the way to the engine and back.
 * <p>
 * A variable of the engine is a JSON value, and the engine reads every number of it as a float of
 * 64 bits, the Java <code>double</code>. So a text, a boolean and a number which fits a
 * <code>double</code> exactly come back as they went: a whole number comes back without a fraction,
 * <code>1000.0</code> as <code>1000</code>, which the platform turns into the declared number type
 * again, because it is the same number. A whole number above 2^53 and a decimal of more than 17
 * digits or with a scale do not: the engine never holds them. The platform shares an enum as the
 * name of its constant, and a date or an instant as the text of the instant, which the engine keeps
 * as text. What this class cannot place it answers with "cannot say", which never ends a start.
 * <p>
 * {@code ZenBpmValueTypesIT} sends a value of every answer through the pinned engine and reads it
 * back.
 */
public final class ZenBpmValueTypes {

    /**
     * The types which the engine hands back as they went: the texts, the boolean, the numbers
     * which a <code>double</code> holds exactly, and the texts the platform writes for a date and
     * an instant.
     */
    private static final Set<Class<?>> SURVIVE = Set.of(String.class, Boolean.class, boolean.class, Integer.class,
            int.class, Short.class, short.class, Byte.class, byte.class, Double.class, double.class, Float.class,
            float.class, Date.class, Instant.class);

    /**
     * The whole numbers which may be larger than 2^53, the largest whole number a
     * <code>double</code> holds exactly.
     */
    private static final Set<Class<?>> LARGE_WHOLE_NUMBERS = Set.of(Long.class, long.class, BigInteger.class);

    private static final Set<Class<?>> CHARACTERS = Set.of(Character.class, char.class);

    private ZenBpmValueTypes() {
    }

    /**
     * What the engine does with that type, in that direction.
     *
     * @param valueType The declared type of the value
     * @param direction Which way the value travels
     * @return The verdict for the platform's check
     */
    public static ValueTypeVerdict verdictFor(
        final Class<?> valueType,
        final ValueDirection direction
    ) {
        if (valueType == null) {
            return ValueTypeVerdict.cannotSay("no type was named");
        }
        if (valueType.isEnum() || SURVIVE.contains(valueType) || CharSequence.class.isAssignableFrom(valueType)) {
            return ValueTypeVerdict.survives();
        }
        if (LARGE_WHOLE_NUMBERS.contains(valueType)) {
            return direction == ValueDirection.TO_BPMS ? ValueTypeVerdict.changed("""
                    that the engine holds every number as a float of 64 bits, which keeps a whole number \
                    exactly up to 2^53 (9007199254740992) only: 9007199254740993 is stored as 9007199254740992, \
                    so an expression and anything reading the variable sees the rounded number""")
                    : ValueTypeVerdict.changed("""
                            that a whole number above 2^53 (9007199254740992) arrives rounded, because the \
                            engine holds every number as a float of 64 bits and never held its last digits""");
        }
        if (valueType == BigDecimal.class) {
            return direction == ValueDirection.TO_BPMS ? ValueTypeVerdict.changed("""
                    that the engine holds every number as a float of 64 bits and not the way it was written: \
                    120.50 is stored as 120.5, and a number of more than 17 digits loses the rest""")
                    : ValueTypeVerdict.changed("""
                            that the number arrives without the scale it was written with and with at most 17 \
                            digits, because the engine holds every number as a float of 64 bits""");
        }
        if (CHARACTERS.contains(valueType)) {
            return direction == ValueDirection.TO_BPMS ? ValueTypeVerdict.changed("""
                    that a character reaches the engine as a text of one character""")
                    : ValueTypeVerdict.changed("""
                            that a character comes back from the engine as a text, which VanillaBP does not \
                            turn into a character again""");
        }
        return ValueTypeVerdict.cannotSay("""
                that a %s is no JSON value of its own and reaches the engine as whatever it was serialized \
                to""".formatted(valueType.getSimpleName()));
    }

}
