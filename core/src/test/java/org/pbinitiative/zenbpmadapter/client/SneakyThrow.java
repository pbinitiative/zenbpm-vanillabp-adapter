/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

/**
 * Throws a checked exception from code which does not declare it, as Lombok's
 * <code>@SneakyThrows</code> does in a handler or a listener of an application.
 */
final class SneakyThrow {

    private SneakyThrow() {
    }

    @SuppressWarnings("unchecked")
    static <E extends Exception> void sneakily(final Exception e) throws E {
        throw (E) e;
    }

}
