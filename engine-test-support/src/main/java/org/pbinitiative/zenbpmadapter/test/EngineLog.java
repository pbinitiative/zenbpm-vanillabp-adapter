/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.test;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.OutputFrame;

/**
 * Hands every line the engine container prints to the logger {@value #LOGGER_NAME}, at DEBUG.
 * <p>
 * The container is removed with the run, so without this a failing test leaves nothing of the
 * engine it talked to. The lines are logged, not printed, so they go the same way as every other
 * line of a test: {@code SuppressOutputExtension} holds them back while the test is green and
 * replays them when it fails. At DEBUG, because the engine is chatty and its log is wanted only
 * where somebody asked for it: the {@code logback-test.xml} of a module switches the logger on,
 * and a module which does not want the lines raises its level.
 */
public final class EngineLog {

    /**
     * The logger the engine's lines are written to. Standard output and standard error of the
     * container both end up here, because the engine writes its own levels into each line.
     */
    public static final String LOGGER_NAME = "zenbpm-engine";

    private static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    private EngineLog() {
        // static helper
    }

    /**
     * A log consumer for one engine container.
     *
     * @return What {@code withLogConsumer(...)} of the container takes
     */
    public static Consumer<OutputFrame> consumer() {

        return frame -> {
            final var line = frame.getUtf8StringWithoutLineEnding();
            if (!line.isEmpty()) {
                LOGGER.debug(line);
            }
        };

    }

}
