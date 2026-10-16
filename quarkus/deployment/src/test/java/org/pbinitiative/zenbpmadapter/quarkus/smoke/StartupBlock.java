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

    private static final Pattern HEAD_LINE = Pattern
            .compile("^VanillaBP \\S+ looked at this application and found \\d+ things? worth a look\\. .*$");

    private static final Pattern END_OF_A_TOPIC = Pattern.compile("^([A-Z][A-Z ]+ \\(\\d+\\)|-{20,})$");

    private StartupBlock() {
    }

    /**
     * Whether the start wrote the block. A test which reads that a topic is empty checks this too,
     * because the topic of a block which was never written is empty as well.
     *
     * @param log What the start wrote
     * @return <code>true</code> where the log holds the head line of the block
     */
    static boolean isWritten(final String log) {
        return log
                .lines()
                .anyMatch(line -> HEAD_LINE
                        .matcher(line)
                        .matches());
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
