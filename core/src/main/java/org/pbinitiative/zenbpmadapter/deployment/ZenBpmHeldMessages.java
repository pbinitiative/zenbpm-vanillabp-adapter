/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.DeployedProcess;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmFindings;
import org.pbinitiative.zenbpmadapter.client.api.ProcessDefinitionRef;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelReader;

import io.vanillabp.integration.spi.startup.StartupTopic;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads which messages the models the engine holds for the processes one workflow module deployed
 * wait for and start with, and which of those processes have a message start event, so phase one
 * of a correlation and of a start by a message can judge a message name without asking the engine,
 * see decision 42 in the repository's DECISIONS.md, and the lookup of <code>scan</code> knows
 * which processes to read.
 * <p>
 * Every version counts for the messages waited for, because a workflow still running may run on
 * an older one and wait for a message a later version renamed or dropped. For the same reason every
 * version counts for the processes which have a message start event: a workflow a message started
 * on an older version still runs after a newer one dropped the event. Only the newest version
 * counts for the messages a process starts with, because the engine hands a message to the start
 * events of the newest version alone: it removes the start subscriptions of the one before when a
 * newer one is deployed. The version this start deployed is the newest one, and it is read from the
 * BPMN the deployment got back. The engine counts versions from one, so only a process whose
 * deployed version is a later one has others, which cost one request for the list and one per
 * version, at every start of the application, a model read for another question of the start
 * excepted, see decision 56 in the repository's DECISIONS.md. The names are those the engine
 * holds, so scoped where the deployment scoped them.
 * <p>
 * A process whose versions cannot be read is named in the result and in a warning of the start
 * about the infrastructure; the names read until then count all the same, because each was read
 * from a model the engine holds.
 * <p>
 * Read again, which phase one does before it refuses a name, every version is read from the
 * engine, because another node may have deployed a newer one since this start.
 */
@Slf4j
final class ZenBpmHeldMessages {

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final ZenBpmFindings findings;

    /**
     * The reader of one adapter id.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id, whose engine holds the models
     * @param findings Where the start is told of a process whose versions could not be read
     */
    ZenBpmHeldMessages(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final ZenBpmFindings findings
    ) {
        this.adapterId = adapterId;
        this.factory = factory;
        this.findings = findings;
    }

    /**
     * The messages of every version of the processes a workflow module deployed.
     *
     * @param workflowModuleId The workflow module, which finished deploying
     * @param deployed The definitions the deployment ended up with
     * @return The messages, with what could not be read
     */
    ModuleMessages of(
        final String workflowModuleId,
        final Collection<DeployedProcess> deployed
    ) {
        final var scopedIds = new ArrayList<String>();
        final var awaited = new TreeSet<String>();
        final var starting = new TreeSet<String>();
        final var startedByMessage = new TreeSet<String>();
        final var unread = new ArrayList<String>();
        for (final var process : deployed) {
            scopedIds.add(process.scopedBpmnProcessId());
            try {
                read(workflowModuleId, process.version(), process.scopedBpmnProcessId(), process.xml(), awaited,
                        starting, startedByMessage);
                if (process.version() > 1) {
                    readTheOtherVersions(workflowModuleId, process, awaited, startedByMessage);
                }
            } catch (final RuntimeException e) {
                log.debug("ZenBPM[{}]: the models the engine holds as '{}' could not be read", adapterId, process
                        .scopedBpmnProcessId(), e);
                unread.add("the versions of process '%s' (%s)".formatted(process.scopedBpmnProcessId(), ZenBpmErrors
                        .withoutAdapterId(adapterId, e)));
            }
        }
        if (!unread.isEmpty()) {
            findings.warn(StartupTopic.INFRASTRUCTURE, ZenBpmFindings.ofModule(adapterId, workflowModuleId), """
                    What the engine holds could not be read: %s. So a correlation of a message which the models \
                    read do not wait for is not refused when the application calls: it is published, and the outbox \
                    repeats it for as long as nothing waits for it. Where the engine did not answer, check that it \
                    does; the next start of the application reads them again.""".formatted(String.join(" and ",
                    unread)));
        }
        return new ModuleMessages(scopedIds, awaited, starting, startedByMessage, unread);
    }

    /**
     * The messages of every version the engine holds now of processes a workflow module deployed in
     * this start.
     *
     * @param workflowModuleId The workflow module
     * @param scopedBpmnProcessIds The processes, as the engine knows them
     * @return The messages, with what could not be read
     */
    ModuleMessages again(
        final String workflowModuleId,
        final List<String> scopedBpmnProcessIds
    ) {
        final var awaited = new TreeSet<String>();
        final var starting = new TreeSet<String>();
        final var startedByMessage = new TreeSet<String>();
        final var unread = new ArrayList<String>();
        for (final var scopedBpmnProcessId : scopedBpmnProcessIds) {
            try {
                final var rest = factory.rest();
                final var versions = rest.processDefinitions(scopedBpmnProcessId, false);
                final var newest = versions
                        .stream()
                        .mapToInt(ProcessDefinitionRef::version)
                        .max()
                        .orElse(0);
                for (final var version : versions) {
                    final var isTheNewest = version.version() == newest;
                    read(workflowModuleId, version.version(), scopedBpmnProcessId, factory.heldBpmnOf(version
                            .key()), awaited, isTheNewest ? starting : new TreeSet<>(), startedByMessage);
                }
            } catch (final RuntimeException e) {
                log.debug("ZenBPM[{}]: the models the engine holds as '{}' could not be read again", adapterId,
                        scopedBpmnProcessId, e);
                unread.add("the versions of process '%s' (%s)".formatted(scopedBpmnProcessId, ZenBpmErrors
                        .withoutAdapterId(adapterId, e)));
            }
        }
        return new ModuleMessages(scopedBpmnProcessIds, awaited, starting, startedByMessage, unread);
    }

    // an older version holds no start subscription, so what it starts with does not count, but
    // whether it has a message start event does
    private void readTheOtherVersions(
        final String workflowModuleId,
        final DeployedProcess process,
        final Set<String> awaited,
        final Set<String> startedByMessage
    ) {
        for (final var version : factory
                .rest()
                .processDefinitions(process.scopedBpmnProcessId(), false)) {
            if (version.key() == process.key()) {
                continue;
            }
            read(workflowModuleId, version.version(), process.scopedBpmnProcessId(), factory.heldBpmnOf(version
                    .key()), awaited, new TreeSet<>(), startedByMessage);
        }
    }

    private static void read(
        final String workflowModuleId,
        final int version,
        final String scopedProcessId,
        final String bpmn,
        final Set<String> awaited,
        final Set<String> starting,
        final Set<String> startedByMessage
    ) {
        ZenBpmModelReader
                .read(workflowModuleId, "version %d of process '%s'".formatted(version, scopedProcessId),
                        new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)))
                .forEach(model -> {
                    awaited.addAll(model.catchMessages());
                    starting.addAll(model.startMessages());
                    if (!model
                            .messageStarts()
                            .isEmpty()) {
                        startedByMessage.add(scopedProcessId);
                    }
                });
    }

}
