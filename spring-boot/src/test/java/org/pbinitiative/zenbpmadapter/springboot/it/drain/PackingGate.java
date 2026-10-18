/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.drain;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Holds the packing of a parcel on the node the test names, until the test lets it go on, and says
 * which parcels are held. One for every application the test boots, because the test closes and
 * boots applications while a handler of an earlier one still waits here.
 */
public final class PackingGate {

    /**
     * How long a held packing waits at most, so a test which fails before it lets the handler go
     * does not keep a handler thread for the rest of the run.
     */
    private static final Duration LONGEST_HOLD = Duration.ofSeconds(60);

    /**
     * The node whose packing is held, by parcel.
     */
    private static final Map<String, String> HELD_ON = new ConcurrentHashMap<>();

    /**
     * What a held packing does once it may go on, by parcel: <code>true</code> to throw.
     */
    private static final Map<String, Boolean> THROWS = new ConcurrentHashMap<>();

    private static final Map<String, CountDownLatch> RELEASES = new ConcurrentHashMap<>();

    private static final Set<String> WAITING = ConcurrentHashMap.newKeySet();

    private PackingGate() {
    }

    /**
     * Holds the packing of a parcel on a node.
     *
     * @param parcelId The parcel
     * @param node The node whose handler waits
     */
    public static void hold(
        final String parcelId,
        final String node
    ) {
        HELD_ON.put(parcelId, node);
        RELEASES.put(parcelId, new CountDownLatch(1));
    }

    /**
     * Lets the held packing of a parcel go on.
     *
     * @param parcelId The parcel
     * @param thenThrow Whether the handler throws then, instead of packing
     */
    public static void release(
        final String parcelId,
        final boolean thenThrow
    ) {
        THROWS.put(parcelId, thenThrow);
        RELEASES
                .getOrDefault(parcelId, new CountDownLatch(1))
                .countDown();
    }

    /**
     * Whether the packing of a parcel waits in its handler right now.
     *
     * @param parcelId The parcel
     * @return <code>true</code> while the handler waits
     */
    public static boolean isWaiting(final String parcelId) {
        return WAITING.contains(parcelId);
    }

    /**
     * Waits where the packing of a parcel is held on this node, and says whether the handler throws
     * then.
     *
     * @param parcelId The parcel
     * @param node This node
     * @return <code>true</code> where the handler is to throw
     */
    static boolean passOn(
        final String parcelId,
        final String node
    ) {
        if (!node.equals(HELD_ON.get(parcelId))) {
            return false;
        }
        WAITING.add(parcelId);
        try {
            RELEASES
                    .get(parcelId)
                    .await(LONGEST_HOLD.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
        } finally {
            WAITING.remove(parcelId);
        }
        return THROWS.getOrDefault(parcelId, false);
    }

}
