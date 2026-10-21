/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.startup.StartupFindings;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;

/**
 * The startup report of a test: every finding kept in a list a test reads back, and handed on to
 * the platform's own report, so a test also reads it in the block the platform writes at the end
 * of the start.
 */
public final class CollectedStartupReport implements StartupReport {

    // a line which ends the entries of a topic: the heading of the next one, or the ruler
    private static final Pattern END_OF_A_TOPIC = Pattern.compile("^([A-Z][A-Z ]+ \\(\\d+\\)|-{20,})$");

    private final List<Finding> findings = new CopyOnWriteArrayList<>();

    private final StartupFindings platform = new StartupFindings();

    @Override
    public void notice(
        final StartupTopic topic,
        final String scope,
        final String message
    ) {
        findings.add(new Finding("notice", topic, scope, message));
        platform.notice(topic, scope, message);
    }

    @Override
    public void warn(
        final StartupTopic topic,
        final String scope,
        final String message
    ) {
        findings.add(new Finding("warn", topic, scope, message));
        platform.warn(topic, scope, message);
    }

    @Override
    public void error(
        final StartupTopic topic,
        final String scope,
        final String message
    ) {
        findings.add(new Finding("error", topic, scope, message));
        platform.error(topic, scope, message);
    }

    @Override
    public void refuse(
        final StartupTopic topic,
        final String scope,
        final String message
    ) {
        findings.add(new Finding("refuse", topic, scope, message));
        platform.refuse(topic, scope, message);
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
     * What the block the platform writes at the end of the start holds under the heading of a
     * topic: for each entry the line of its scopes and its indented text. A refusal is read in the
     * exception the platform ends the start with, which is drawn the same way.
     *
     * @param topic The topic
     * @return The entries of the topic, or an empty text where the block has no such heading
     */
    public String block(final StartupTopic topic) {
        final var drawn = new StringBuilder();
        final var box = platform.theBox();
        if (box != null) {
            drawn
                    .append(box)
                    .append('\n');
        }
        final var refusal = platform.theRefusal();
        if (refusal != null) {
            drawn.append(refusal.getMessage());
        }
        final var heading = Pattern.compile("^%s \\(\\d+\\)$".formatted(Pattern.quote(topic.heading())));
        final var inTopic = new boolean[1];
        return drawn
                .toString()
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

    /**
     * Whether the block shows a finding under the heading of its topic, its scope on the line
     * above its text, as the platform draws an entry.
     *
     * @param finding The finding
     * @return <code>true</code> where the block shows it so
     */
    public boolean showsInTheBlock(final Finding finding) {
        final var text = finding
                .message()
                .strip()
                .lines()
                .map("      %s"::formatted)
                .collect(Collectors.joining("\n"));
        return block(finding.topic()).contains("  %s\n%s".formatted(finding.scope(), text));
    }

    /**
     * The block as the platform draws it, for the message of a failed assertion.
     *
     * @return The box and the refusal, as far as there are any
     */
    public String drawn() {
        final var refusal = platform.theRefusal();
        return "%s\n%s".formatted(platform.theBox(), refusal == null ? "" : refusal.getMessage());
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
