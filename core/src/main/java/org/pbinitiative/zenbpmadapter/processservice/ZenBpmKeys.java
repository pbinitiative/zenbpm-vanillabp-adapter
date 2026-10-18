/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.util.OptionalLong;

import org.pbinitiative.zenbpmadapter.client.ZenBpmApiException;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;

/**
 * What an id the application or the platform hands over says about the engine: whether it is a key
 * the engine hands out at all, and whether the engine holds the partition the key names.
 */
final class ZenBpmKeys {

    /**
     * Where a key of the engine carries the partition which holds it: bits 12 to 21.
     */
    private static final int PARTITION_SHIFT = 12;

    private static final long PARTITION_MASK = 0x3FF;

    private ZenBpmKeys() {
    }

    /**
     * The key an id names. Every key the engine hands out is a positive number, so anything else
     * is the id of another BPMS.
     *
     * @param id The id
     * @return The key, empty where the id is no key of this engine
     */
    static OptionalLong keyOf(final String id) {
        if (id == null) {
            return OptionalLong.empty();
        }
        try {
            final var key = Long.parseLong(id);
            return key > 0 ? OptionalLong.of(key) : OptionalLong.empty();
        } catch (final NumberFormatException notANumber) {
            return OptionalLong.empty();
        }
    }

    /**
     * The partition a key names.
     *
     * @param key The key
     * @return The partition
     */
    static int partitionOf(final long key) {
        return (int) ((key >>> PARTITION_SHIFT) & PARTITION_MASK);
    }

    /**
     * Whether a failed read of a key was about a partition the engine does not have. A
     * <code>502</code> is all the engine answers for a key of a partition it does not have, and
     * also for a partition which has no leader right now, so the partitions it reports tell the two
     * apart; a status which does not come, or names no partition at all, leaves the failure as it
     * was, because an engine always has a partition and one which names none has not said which,
     * see decision 32 in the repository's DECISIONS.md.
     *
     * @param rest The REST client which read the key
     * @param key The key
     * @param failure What the read failed with; a failure of the read of the status is added to it
     * @return Whether the engine does not have the key's partition
     */
    static boolean isAKeyOfAPartitionTheEngineDoesNotHave(
        final ZenBpmRestClient rest,
        final long key,
        final RuntimeException failure
    ) {
        if (!(failure instanceof ZenBpmApiException refusal) || (refusal.getStatus() != 502)) {
            return false;
        }
        try {
            final var partitions = rest
                    .status()
                    .partitions();
            return !partitions.isEmpty() && !partitions.contains(partitionOf(key));
        } catch (final RuntimeException statusFailure) {
            failure.addSuppressed(statusFailure);
            return false;
        }
    }

}
