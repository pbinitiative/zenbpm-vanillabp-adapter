/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

// The engine answers a page or a size out of range with 400, and a 400 counts as a permanent
// failure of whatever the adapter was doing. Refused here, the mistake shows up in the code which
// made it.
final class PageRange {

    private PageRange() {
    }

    static void check(
        final int page,
        final int size,
        final int maximumSize
    ) {

        if (page < 1) {
            throw new IllegalArgumentException("A page is counted from 1, but %d was asked for".formatted(page));
        }
        if ((size < 1) || (size > maximumSize)) {
            throw new IllegalArgumentException(
                    "A page holds 1 to %d items, but %d were asked for".formatted(maximumSize, size));
        }

    }

}
