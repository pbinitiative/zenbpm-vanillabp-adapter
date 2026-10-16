/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;

/**
 * A boolean attribute of a BPMN element, read as the engine reads it. The engine decodes such an
 * attribute with Go's <code>strconv.ParseBool</code> after trimming it, so <code>1</code>,
 * <code>t</code> and <code>TRUE</code> are true as well, and a spelling it does not know, a blank
 * value included, makes it refuse the whole file. An attribute which is there but empty is false,
 * whatever the engine assumes where the attribute is missing.
 */
final class ZenBpmBooleans {

    private static final Set<String> TRUE = Set.of("1", "t", "T", "TRUE", "true", "True");

    private static final Set<String> FALSE = Set.of("0", "f", "F", "FALSE", "false", "False");

    private ZenBpmBooleans() {
    }

    /**
     * The value of a boolean attribute.
     *
     * @param element The element
     * @param name The attribute
     * @param absent What the engine assumes where the attribute is missing
     * @return The value; <code>absent</code> where the attribute is missing or spelt in a way the
     *         engine refuses, <code>false</code> where it is empty
     */
    static boolean valueOf(
        final Element element,
        final String name,
        final boolean absent
    ) {
        if (!element.hasAttribute(name)) {
            return absent;
        }
        final var value = element.getAttribute(name);
        if (value.isEmpty()) {
            return false;
        }
        return parse(value).orElse(absent);
    }

    /**
     * Whether an attribute holds something the engine cannot read as a boolean.
     *
     * @param element The element
     * @param name The attribute
     * @return <code>true</code> where the attribute is there, not empty, and no boolean
     */
    static boolean isUnreadable(
        final Element element,
        final String name
    ) {
        final var value = element.getAttribute(name);
        return !value.isEmpty() && parse(value).isEmpty();
    }

    private static Optional<Boolean> parse(final String value) {
        final var trimmed = value.trim();
        if (TRUE.contains(trimmed)) {
            return Optional.of(true);
        }
        if (FALSE.contains(trimmed)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

}
