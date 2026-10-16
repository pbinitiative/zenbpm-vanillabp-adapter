/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.vanillabp.integration.spi.startup.StartupTopic;

/**
 * The block VanillaBP writes into the log at the end of a start, as a test reads it: the findings
 * of one topic, under its heading, until the next heading or the closing ruler.
 */
final class StartupBlock {

    private static final Pattern END_OF_A_TOPIC = Pattern.compile("^([A-Z][A-Z ]+ \\(\\d+\\)|-{20,})$");

    private StartupBlock() {
    }

    /**
     * The findings under the heading <code>CONFIGURATION</code>.
     *
     * @param log What the start wrote
     * @return The lines of the topic, joined, or an empty string where the block has no such topic
     */
    static String configuration(final String log) {
        final var heading = Pattern.compile("^%s \\(\\d+\\)$".formatted(Pattern.quote(StartupTopic.CONFIGURATION
                .heading())));
        final var inTopic = new boolean[1];
        return log
                .lines()
                .filter(line -> {
                    if (heading
                            .matcher(line)
                            .matches()) {
                        inTopic[0] = true;
                        return false;
                    }
                    if (inTopic[0] && END_OF_A_TOPIC
                            .matcher(line)
                            .matches()) {
                        inTopic[0] = false;
                    }
                    return inTopic[0];
                })
                .collect(Collectors.joining("\n"));
    }

}
