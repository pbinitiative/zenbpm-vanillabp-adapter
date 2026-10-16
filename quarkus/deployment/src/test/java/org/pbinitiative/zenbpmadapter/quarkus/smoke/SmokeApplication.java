/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;

import org.jboss.logmanager.ExtLogRecord;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;

import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.PhaseOperation;
import jakarta.enterprise.inject.Instance;

/**
 * The application the extension tests boot: the ZenBPM adapter on Quarkus, without an engine. It
 * keeps the platform's JDBC outbox and delivery log on an in-memory database, so the start runs as
 * it runs in production, deployment included. Its workflow module <code>smoke-app</code> has no
 * BPMN file, so the platform deploys nothing to the adapter and calls none of the steps this
 * version of the adapter refuses, except in the tests which add one.
 * <p>
 * The platform's BPMS double is a test dependency of this module, for the tests of an application
 * with an adapter id of another type: beside a ZenBPM adapter id, or without one. Every other test
 * leaves it out of the application: on Quarkus every adapter extension on the classpath has to be
 * configured, so the double would make each test configure an adapter id it is not about.
 */
final class SmokeApplication {

    private static final String BPMS_DOUBLE_GROUP_ID = "io.vanillabp";

    private static final String BPMS_DOUBLE_ARTIFACT_ID = "bpms-double-quarkus";

    /**
     * Where the BPMN file of {@link #withBpmnFiles()} and {@link #withTheBpmsDoubleAndBpmnFiles()}
     * is: one process, the one of the sample workflow service. The file is named after the
     * process, because the BPMS double takes the process id from the file name.
     */
    static final String WITH_BPMN = "classpath*:with-bpmn";

    /**
     * Where the BPMN file of {@link #withBpmnFiles(String)} and
     * {@link #withTheBpmsDoubleAndBpmnFiles(String)} with this directory is: the process of the
     * sample workflow service with a script task and an instantiating receive task, which ZenBPM
     * cannot run.
     */
    static final String WITH_REFUSED_BPMN = "classpath*:with-bpmn-refused";

    private SmokeApplication() {
    }

    /**
     * A test of the smoke application without the BPMS double, which keeps every log record of its
     * start.
     *
     * @return The test, to be given the keys of the case
     */
    static QuarkusExtensionTest extensionTest() {
        return withoutTheBpmsDouble(withTheBpmsDouble());
    }

    /**
     * A test of the smoke application without the BPMS double whose workflow module has one BPMN
     * file, see {@link #WITH_BPMN}.
     *
     * @return The test, to be given the keys of the case
     */
    static QuarkusExtensionTest withBpmnFiles() {
        return withBpmnFiles("with-bpmn");
    }

    /**
     * A test of the smoke application without the BPMS double whose workflow module has the BPMN
     * file of the sample workflow service in the given directory.
     *
     * @param directory The directory below the test resources
     * @return The test, to be given the keys of the case
     */
    static QuarkusExtensionTest withBpmnFiles(final String directory) {
        return withoutTheBpmsDouble(withTheBpmsDoubleAndBpmnFiles(directory));
    }

    /**
     * A test of the smoke application with the platform's BPMS double as a second adapter type,
     * which keeps every log record of its start.
     *
     * @return The test, to be given the keys of the case
     */
    static QuarkusExtensionTest withTheBpmsDouble() {
        return new QuarkusExtensionTest()
                .setArchiveProducer(() -> archive())
                .setLogRecordPredicate(record -> true);
    }

    /**
     * A test of the smoke application with the platform's BPMS double as a second adapter type
     * whose workflow module has one BPMN file, see {@link #WITH_BPMN}.
     *
     * @return The test, to be given the keys of the case
     */
    static QuarkusExtensionTest withTheBpmsDoubleAndBpmnFiles() {
        return withTheBpmsDoubleAndBpmnFiles("with-bpmn");
    }

    /**
     * A test of the smoke application with the platform's BPMS double as a second adapter type
     * whose workflow module has the BPMN file of the sample workflow service in the given
     * directory.
     *
     * @param directory The directory below the test resources
     * @return The test, to be given the keys of the case
     */
    static QuarkusExtensionTest withTheBpmsDoubleAndBpmnFiles(final String directory) {
        return new QuarkusExtensionTest()
                .setArchiveProducer(() -> archive().addAsResource("%s/SampleWorkflowService.bpmn".formatted(
                        directory)))
                .setLogRecordPredicate(record -> true);
    }

    /**
     * A test of the smoke application with the setup of a migration: the new engine is first
     * priority and complete, the old one is nowhere first, its <code>deployment-failure</code>
     * policy is <code>warn</code>, and its address cannot work.
     *
     * @return The test, to be given the keys of the case
     */
    static QuarkusExtensionTest degradableOldEngine() {
        return extensionTest()
                .overrideConfigKey("vanillabp.prioritized-adapters", "new,old")
                .overrideConfigKey("vanillabp.adapters.new.type", "zenbpm")
                .overrideConfigKey("vanillabp.adapters.new.rest-address", "http://localhost:8080")
                .overrideConfigKey("vanillabp.adapters.old.type", "zenbpm")
                .overrideConfigKey("vanillabp.adapters.old.deployment-failure", "warn")
                .overrideConfigKey("vanillabp.adapters.old.rest-address", "ftp://old-engine:8080");
    }

    /**
     * A test of the smoke application with two adapter ids on one engine, the new one first, as in
     * a migration from one name-clash-avoidance mode to another.
     *
     * @param newMode The name-clash-avoidance mode of the new adapter id
     * @param oldMode The name-clash-avoidance mode of the old adapter id
     * @return The test, to be given what the case checks
     */
    static QuarkusExtensionTest oneEngine(
        final String newMode,
        final String oldMode
    ) {
        return extensionTest()
                .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm-new,zenbpm-old")
                .overrideConfigKey("vanillabp.adapters.zenbpm-new.type", "zenbpm")
                .overrideConfigKey("vanillabp.adapters.zenbpm-new.rest-address", "http://localhost:8080")
                .overrideConfigKey("vanillabp.adapters.zenbpm-new.name-clash-avoidance", newMode)
                .overrideConfigKey("vanillabp.adapters.zenbpm-old.type", "zenbpm")
                .overrideConfigKey("vanillabp.adapters.zenbpm-old.rest-address", "http://localhost:8080")
                .overrideConfigKey("vanillabp.adapters.zenbpm-old.name-clash-avoidance", oldMode);
    }

    /**
     * What the start wrote, as one text.
     *
     * @param records The log records of the start
     * @return The messages, one after the other
     */
    static String logOf(final List<LogRecord> records) {
        return records
                .stream()
                .map(SmokeApplication::messageOf)
                .collect(Collectors.joining("\n"));
    }

    /**
     * The messages of the exception which ended the start and of all its causes.
     *
     * @param failure What the start threw
     * @return The messages, one per line
     */
    static String messagesOf(final Throwable failure) {
        final var messages = new StringBuilder();
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            messages
                    .append(cause.getMessage())
                    .append('\n');
        }
        return messages.toString();
    }

    /**
     * The message of the exception which ended the start, from the cause which raised it.
     *
     * @param failure What the start threw
     * @return The message of the innermost cause
     */
    static String rootMessageOf(final Throwable failure) {
        var cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    /**
     * The one service of the ZenBPM adapter among those of every adapter type, collected as the
     * platform collects them: one list per adapter type.
     *
     * @param lists The list beans of every adapter type on the classpath
     * @param type The adapter's class of service
     * @param <T> The adapter's class of service
     * @return The one service of the type
     * @throws IllegalStateException Where there is none or more than one
     */
    static <T> T only(
        final Instance<? extends List<?>> lists,
        final Class<T> type
    ) {
        final var services = lists
                .stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
        if (services.size() != 1) {
            throw new IllegalStateException("expected one %s, found %s".formatted(type.getSimpleName(), services));
        }
        return services.getFirst();
    }

    /**
     * What the platform calls when the application starts a workflow, after its transaction: the
     * START_WORKFLOW handler of the adapter's process service. The smoke application has neither a
     * transaction around the call nor a persistence of its aggregate, so the test calls the handler.
     *
     * @param processServices The list beans of the process services of every adapter type
     */
    static void startAWorkflow(final Instance<? extends List<?>> processServices) {
        final ZenBpmProcessService<?> processService = only(processServices, ZenBpmProcessService.class);
        processService
                .phaseOperations()
                .get(PhaseOperation.START_WORKFLOW)
                .phaseOne(null);
    }

    private static QuarkusExtensionTest withoutTheBpmsDouble(final QuarkusExtensionTest test) {
        return test.setExcludedDependencies(Set.of(ArtifactKey.of(BPMS_DOUBLE_GROUP_ID,
                BPMS_DOUBLE_ARTIFACT_ID, "", "jar"),
                ArtifactKey.of(BPMS_DOUBLE_GROUP_ID, BPMS_DOUBLE_ARTIFACT_ID
                        + "-deployment", "", "jar")));
    }

    private static JavaArchive archive() {
        return ShrinkWrap
                .create(JavaArchive.class)
                .addClasses(Aggregate.class, SampleWorkflowService.class, SmokeApplication.class, StartupBlock.class,
                        FactoryLog.class)
                .addAsResource("application.yaml")
                .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module");
    }

    private static String messageOf(final LogRecord record) {
        if (record instanceof ExtLogRecord extended) {
            return extended.getFormattedMessage();
        }
        return record.getMessage();
    }

}
