/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.time.Duration;
import java.util.Optional;

/**
 * The <code>history-time-to-live</code> of a start in the engine's own form. The engine reads its
 * text into a duration of nanoseconds and keeps whole seconds of it, so the adapter sends whole
 * seconds, which the engine reads back exactly, and refuses at startup what the engine would
 * change, see decision 31 in the repository's DECISIONS.md.
 */
public final class ZenBpmTimeToLive {

    /**
     * The longest time to live the engine takes: the most whole seconds a duration of nanoseconds
     * of 64 bits holds, about 292 years. The engine refuses a longer one with <code>400</code>.
     */
    public static final Duration LONGEST = Duration.ofSeconds(Long.MAX_VALUE / Duration
            .ofSeconds(1)
            .toNanos());

    private ZenBpmTimeToLive() {
    }

    /**
     * The text the engine takes for a time to live, e.g. <code>2592000s</code> for
     * <code>P30D</code> and <code>0s</code> for <code>PT0S</code>, which the engine keeps as a
     * time to live of its own: it removes the ended workflow at its next clean-up.
     *
     * @param timeToLive A time to live the validation accepted
     * @return The text in whole seconds
     * @throws IllegalArgumentException Where the engine would not keep the time as it is, which
     *             the validation refuses while the application starts
     */
    public static String engineForm(final Duration timeToLive) {
        if (timeToLive.isNegative() || defectOf(timeToLive).isPresent()) {
            throw new IllegalArgumentException("The engine cannot keep a history-time-to-live of %s".formatted(
                    timeToLive));
        }
        return "%ds".formatted(timeToLive.getSeconds());
    }

    /**
     * What the engine would do instead of keeping a time to live which is not negative, and what
     * to write instead.
     *
     * @param timeToLive The time to live
     * @return The second half of a sentence which starts with the key and the value, empty where
     *         the engine keeps the time as it is
     */
    static Optional<String> defectOf(final Duration timeToLive) {
        if (timeToLive.compareTo(LONGEST) > 0) {
            return Optional.of("but the engine takes at most %s, about 292 years; write a shorter time".formatted(
                    LONGEST));
        }
        if (timeToLive.getNano() != 0) {
            return Optional.of("but the engine keeps whole seconds and would keep %s; write whole seconds".formatted(
                    Duration.ofSeconds(timeToLive.getSeconds())));
        }
        return Optional.empty();
    }

}
