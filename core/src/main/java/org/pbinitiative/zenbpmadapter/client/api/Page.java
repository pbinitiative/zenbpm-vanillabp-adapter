/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.util.List;

/**
 * One page of a search.
 * <p>
 * {@link #totalCount()} is what counts, never the size of {@link #items()}. A search over the
 * engine's partitions applies the page size to every partition, so one page holds up to that many
 * items per partition. The total is the sum of the totals of all partitions. Why a count is never
 * taken from a transferred page: see decision 14 in the repository's DECISIONS.md.
 *
 * @param items The items of this page
 * @param totalCount How many items match the search in all
 * @param last Whether no later page holds further items
 * @param <T> The type of the items
 */
public record Page<T>(
    List<T> items,
    long totalCount,
    boolean last
) {

    /**
     * Copies the items, so the record stays as it was read.
     */
    public Page {

        items = List.copyOf(items);

    }

}
