/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Java types of the variables the engine reports, the same for the variables of an instance
 * and the input variables of a job. They are decoded from JSON:
 * <ul>
 * <li>a number written without a fraction or an exponent is a {@link Long}, or a
 * {@link java.math.BigInteger} beyond its range; every other number is a {@link Double}. The
 * engine writes a whole number without a fraction, so <code>1000.0</code> sent by the adapter
 * comes back as the {@link Long} <code>1000</code>;</li>
 * <li>a string is a {@link String}, <code>true</code> and <code>false</code> a {@link Boolean},
 * <code>null</code> is <code>null</code>;</li>
 * <li>an object is a {@link Map} of the same kinds, an array a {@link java.util.List}; neither
 * can be changed.</li>
 * </ul>
 * A date has no type of its own in JSON and is a {@link String}.
 * <p>
 * The engine stores every number as a floating-point number of 64 bits. That holds a whole number
 * exactly up to 2^53 (9,007,199,254,740,992), and a larger one comes back rounded: 2^53 + 1 is read
 * back as 2^53. From 10^21 on, the engine writes a number with an exponent, so it comes back as a
 * {@link Double}. An id which may be larger than 2^53, like a key of the engine, belongs in a
 * {@link String}.
 */
public final class Variables {

    private Variables() {
    }

    static Map<String, Object> unmodifiable(final Map<String, Object> variables) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

}
