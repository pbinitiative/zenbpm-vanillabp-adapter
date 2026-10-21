/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;

import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import lombok.extern.slf4j.Slf4j;

/**
 * Where an adapter id says what it finds while the application starts: the platform's startup
 * report, which collects every finding and writes them as one block at the end of the start.
 * <p>
 * A finding is something the developer of the application has to change or to know: a key which
 * cannot work, an identifier which is not kept apart, a model the engine could not hand out. It is
 * reported with its scope, the adapter id, the workflow module or the BPMN process it is about,
 * and with a text which does not name that scope, so the block can fold one finding of several
 * scopes into one entry. A property key is still written out in full, so it can be copied, and
 * where it carries an id, that finding stays one entry per scope. What the adapter reports about
 * doing its work, such as the engine it reached or the job types it subscribed, is no finding and
 * stays a line of the log. See decision 60 in the repository's DECISIONS.md.
 * <p>
 * The report decides what happens to a finding which arrives after the block was written, as one
 * found while the workflow processing of a Spring Boot application starts: it writes it into the
 * log on its own, with its scope. Where no report was handed in, as in a test which builds an
 * adapter id by hand, every finding goes into the log the same way, at the level the report would
 * use.
 */
@Slf4j
public final class ZenBpmFindings {

    private volatile StartupReport report;

    /**
     * Findings which go into the log until a report is handed in with {@link #reportTo}.
     */
    public ZenBpmFindings() {
    }

    /**
     * Findings which go to the given report.
     *
     * @param report The platform's startup report; <code>null</code> sends the findings to the log
     */
    public ZenBpmFindings(final StartupReport report) {
        this.report = report;
    }

    /**
     * The scope of a finding about an adapter id as a whole, such as its configuration.
     *
     * @param adapterId The adapter id
     * @return E.g. <code>zenbpm adapter 'orders'</code>
     */
    public static String ofAdapter(final String adapterId) {
        return "%s adapter '%s'".formatted(ZenBpmAdapter.ADAPTER_TYPE, adapterId);
    }

    /**
     * The scope of a finding about a workflow module of an adapter id.
     *
     * @param adapterId The adapter id
     * @param workflowModuleId The workflow module
     * @return E.g. <code>workflow module 'billing', zenbpm adapter 'orders'</code>
     */
    public static String ofModule(
        final String adapterId,
        final String workflowModuleId
    ) {
        return "workflow module '%s', %s".formatted(workflowModuleId, ofAdapter(adapterId));
    }

    /**
     * The scope of a finding about one BPMN process of a workflow module, as the platform writes the
     * scope of its own findings about a process.
     *
     * @param adapterId The adapter id
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The process id as the application declares it
     * @return E.g. <code>process 'Invoice' of workflow module 'billing', zenbpm adapter 'orders'</code>
     */
    public static String ofProcess(
        final String adapterId,
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        return "process '%s' of workflow module '%s', %s".formatted(bpmnProcessId, workflowModuleId, ofAdapter(
                adapterId));
    }

    /**
     * The report findings go to.
     *
     * @return The platform's startup report, <code>null</code> where findings go to the log
     */
    public StartupReport report() {
        return report;
    }

    /**
     * Sends every later finding to the given report.
     *
     * @param report The platform's startup report; <code>null</code> sends the findings to the log
     */
    public void reportTo(final StartupReport report) {
        this.report = report;
    }

    /**
     * Says something the start survives, but which still asks the developer for something.
     *
     * @param topic Where its fix lies
     * @param scope What it is about, see {@link #ofAdapter}, {@link #ofModule} and {@link #ofProcess}
     * @param message The whole message, without the scope, ending with what to do
     */
    public void notice(
        final StartupTopic topic,
        final String scope,
        final String message
    ) {
        final var to = report;
        if (to == null) {
            log.info("{}: {}", scope, message);
            return;
        }
        to.notice(topic, scope, message);
    }

    /**
     * Says something which is wrong and which the start survives.
     *
     * @param topic Where its fix lies
     * @param scope What it is about, see {@link #ofAdapter}, {@link #ofModule} and {@link #ofProcess}
     * @param message The whole message, without the scope, ending with what to do
     */
    public void warn(
        final StartupTopic topic,
        final String scope,
        final String message
    ) {
        final var to = report;
        if (to == null) {
            log.warn("{}: {}", scope, message);
            return;
        }
        to.warn(topic, scope, message);
    }

}
