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
 */
public final class Variables {

    private Variables() {
    }

    static Map<String, Object> unmodifiable(
        final Map<String, Object> variables
    ) {

        return Collections.unmodifiableMap(new LinkedHashMap<>(variables));

    }

}
