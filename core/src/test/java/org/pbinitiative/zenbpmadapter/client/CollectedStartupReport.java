/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;

/**
 * The startup report of a test: what the platform would fold into its block at the end of the
 * start, kept in a list a test reads back.
 */
public final class CollectedStartupReport implements StartupReport {

    private final List<Finding> findings = new CopyOnWriteArrayList<>();

    @Override
    public void notice(
        final StartupTopic topic,
        final String scope,
        final String message
    ) {
        findings.add(new Finding("notice", topic, scope, message));
    }

    @Override
    public void warn(
        final StartupTopic topic,
        final String scope,
        final String message
    ) {
        findings.add(new Finding("warn", topic, scope, message));
    }

    @Override
    public void error(
        final StartupTopic topic,
        final String scope,
        final String message
    ) {
        findings.add(new Finding("error", topic, scope, message));
    }

    @Override
    public void refuse(
        final StartupTopic topic,
        final String scope,
        final String message
    ) {
        findings.add(new Finding("refuse", topic, scope, message));
    }

    /**
     * Everything reported, in the order reported.
     *
     * @return The findings
     */
    public List<Finding> findings() {
        return List.copyOf(findings);
    }

    /**
     * One finding as it was reported.
     *
     * @param level <code>notice</code>, <code>warn</code>, <code>error</code> or
     *            <code>refuse</code>
     * @param topic Where its fix lies
     * @param scope What it is about
     * @param message The text
     */
    public record Finding(
        String level,
        StartupTopic topic,
        String scope,
        String message
    ) {
    }

}
