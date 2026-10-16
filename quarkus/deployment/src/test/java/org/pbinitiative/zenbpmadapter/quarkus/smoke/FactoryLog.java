/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jboss.logmanager.ExtLogRecord;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;

/**
 * What the client factories say, heard on their own logger for as long as a test listens, the
 * stop of the application included, which the log records of a Quarkus extension test do not
 * reach.
 */
final class FactoryLog extends Handler {

    private final Logger logger = Logger.getLogger(ZenBpmClientFactory.class.getName());

    private final List<String> messages = new CopyOnWriteArrayList<>();

    @Override
    public void publish(final LogRecord record) {
        messages.add(record instanceof ExtLogRecord extended ? extended.getFormattedMessage() : record.getMessage());
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    /**
     * Starts to listen.
     */
    void listen() {
        messages.clear();
        logger.addHandler(this);
    }

    /**
     * Stops to listen.
     *
     * @return What the factories said in the meantime
     */
    List<String> stopListening() {
        logger.removeHandler(this);
        return List.copyOf(messages);
    }

}
