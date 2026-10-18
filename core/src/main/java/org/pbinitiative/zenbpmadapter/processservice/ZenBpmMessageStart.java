/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.MessageStartLookup;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Starting a workflow by a message, in both phases.
 * <p>
 * <b>Phase one</b> refuses every start where <code>message-start-lookup</code> is
 * <code>refuse</code>, naming the key and its other values. Otherwise it judges the name by the
 * models the engine holds for the processes the workflow module deployed, without asking the
 * engine whether a start event takes it: a name the newest version of none of those processes
 * starts with is refused, because the engine hands a message to the start events of the newest
 * version only. The core refused a message which starts another process of the application
 * already; this check adds what the engine holds, a newer version another node deployed
 * included, which is why the versions are read again before a name is refused, as for a
 * correlation, see decision 42 in the repository's DECISIONS.md. Where not every model is known,
 * the check says nothing.
 * <p>
 * <b>Phase two</b> publishes the message under the name the engine holds it by, without a
 * correlation key, with the values the aggregate shares and its id, as a start by request sends
 * them. The engine answers without the key of the instance it started, so nothing is reported to
 * the core here: the start marker behind the message start event reports the key, which the core
 * writes into the start row of the aggregate. The engine loses starts when it takes two messages of
 * one start event at the same moment: some it answers <code>201</code> and starts nothing, others
 * <code>404</code>. So this node publishes the start messages of one name one after the other. A
 * <code>404</code> is no reason to wait as for a correlation: it says that no start event takes the
 * message, or that a start on another node took the subscription at that moment. So it is an
 * ordinary failure, repeated with the backoff of the outbox and blocked after its attempts, see
 * decision 47 in the repository's DECISIONS.md.
 *
 * @param <A> The workflow aggregate type
 */
@Slf4j
final class ZenBpmMessageStart<A> {

    /**
     * The key of the platform which bounds how often the outbox repeats a start which failed.
     */
    private static final String BLOCK_AFTER_ATTEMPTS = "vanillabp.outbox.block-after-attempts";

    /**
     * One lock per name of a start message, as the engine holds it, shared by every adapter id of
     * the application: the engine starts nothing for a second message of one start event which
     * arrives while it takes the first, see decision 47 in the repository's DECISIONS.md.
     */
    private static final Map<String, ReentrantLock> PUBLISHING = new ConcurrentHashMap<>();

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final AdapterCollaborators collaborators;

    private final Function<PhaseTwoRequest<A>, Map<String, Object>> variables;

    /**
     * The handler of the starts by a message of one adapter id.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id
     * @param collaborators What the platform hands to the adapter id
     * @param variables The values the aggregate of a request shares, and its id, as a start sends
     *            them
     */
    ZenBpmMessageStart(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final AdapterCollaborators collaborators,
        final Function<PhaseTwoRequest<A>, Map<String, Object>> variables
    ) {
        this.adapterId = adapterId;
        this.factory = factory;
        this.collaborators = collaborators;
        this.variables = variables;
    }

    /**
     * Phase one: refuses every start under <code>message-start-lookup: refuse</code>, and a
     * message the newest version of no process of the workflow module starts with, without asking
     * the engine whether a start event takes it.
     *
     * @param request The request
     * @throws IllegalStateException With the message of the validation where the adapter id has no
     *             clients, and naming the key where <code>message-start-lookup</code> is
     *             <code>refuse</code>
     * @throws IllegalArgumentException Where every model the engine holds now for the module's
     *             processes is known and the newest version of none of them starts with the
     *             message
     */
    void checkTheStart(final PhaseOneRequest<A> request) {
        final var unconfigured = factory.unconfiguredMessage();
        if (unconfigured.isPresent()) {
            throw new IllegalStateException(unconfigured.get());
        }
        if (factory
                .configuration()
                .getMessageStartLookup() == MessageStartLookup.REFUSE) {
            throw refusedByTheConfiguration(request);
        }
        final var workflowModuleId = request.workflowModuleId();
        final var scopedName = scopedNameOf(workflowModuleId, request.messageName());
        final var kept = ZenBpmKnownMessages.everyModelOf(factory, workflowModuleId,
                ZenBpmDeployedProcesses::messagesOf);
        if (kept.isEmpty() || startsWith(kept.get(), scopedName)) {
            return;
        }
        // another node may have deployed a newer version since this start, so a refusal rests only
        // on the models the engine holds now
        final var now = ZenBpmKnownMessages.everyModelOf(factory, workflowModuleId,
                ZenBpmDeployedProcesses::messagesReadAgain);
        if (now.isEmpty() || startsWith(now.get(), scopedName)) {
            return;
        }
        throw noStartEventOf(request, scopedName, now.get());
    }

    /**
     * Phase two: publishes the message which starts the workflow.
     *
     * @param request The request
     * @throws IllegalStateException Where no start event of the engine took the message
     */
    void start(final PhaseTwoRequest<A> request) {
        final var rest = factory.rest();
        final var workflowModuleId = request.workflowModuleId();
        final var scopedName = scopedNameOf(workflowModuleId, request.messageName());
        final var sent = variables.apply(request);
        final var publishing = PUBLISHING.computeIfAbsent(scopedName, name -> new ReentrantLock());
        publishing.lock();
        try {
            rest.publishMessage(scopedName, null, sent);
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.nobodyWaits(e)) {
                throw e;
            }
            throw noStartEventTookIt(request, scopedName, e);
        } finally {
            publishing.unlock();
        }
        log.debug(
                "ZenBPM[{}]: published start message '{}' of workflow '{}' of workflow module '{}' for aggregate '{}'",
                adapterId, scopedName, request.bpmnProcessId(), workflowModuleId, request.workflowAggregateId());
    }

    private IllegalStateException refusedByTheConfiguration(final PhaseOneRequest<A> request) {
        return new IllegalStateException("""
                ZenBPM adapter '%s' does not start workflow '%s' of workflow module '%s' by message '%s': %s is \
                '%s'. ZenBPM gives a workflow it starts by a message no business key, and none can be set \
                afterwards (a gap of the engine which GAPS.md of zenbpm-vanillabp-adapter lists), so the adapter \
                finds such a workflow only through the instance key its start marker writes into the start row \
                of the aggregate. Set the key to '%s', the default, to start it and find it that way, or to \
                '%s' to find a running one also where the start row does not know it yet."""
                .formatted(adapterId, request.bpmnProcessId(), request.workflowModuleId(), request.messageName(),
                        ZenBpmAdapterConfiguration.propertyKey(adapterId, "message-start-lookup"),
                        MessageStartLookup.REFUSE.configured(), MessageStartLookup.START_ROW.configured(),
                        MessageStartLookup.SCAN.configured()));
    }

    // the engine hands a start message to the start events of the newest version of a process only
    private static boolean startsWith(
        final ModuleMessages known,
        final String scopedName
    ) {
        return known
                .starting()
                .contains(scopedName);
    }

    private IllegalArgumentException noStartEventOf(
        final PhaseOneRequest<A> request,
        final String scopedName,
        final ModuleMessages known
    ) {
        final var name = ZenBpmKnownMessages.nameAsWritten(request.messageName(), scopedName);
        final var hint = known
                .awaited()
                .contains(scopedName)
                        ? "A model waits for that message, so to continue a workflow which waits for it use correlateMessage instead."
                        : "Correct the name passed to startWorkflowByMessage, or start the process with that message.";
        return new IllegalArgumentException("""
                ZenBPM adapter '%s' cannot start workflow '%s' of workflow module '%s' by message %s: the newest \
                version the engine holds of the processes %s starts with none of that name, and ZenBPM hands a \
                start message to the start events of the newest version of a process only. They start with: %s. \
                %s""".formatted(adapterId, request.bpmnProcessId(), request.workflowModuleId(), name, known
                .scopedBpmnProcessIds(), startingOf(known), hint));
    }

    // a start on another node which took the subscription at the same moment is answered the same,
    // and a repetition starts the workflow there, see decision 47 in the repository's DECISIONS.md
    private IllegalStateException noStartEventTookIt(
        final PhaseTwoRequest<A> request,
        final String scopedName,
        final RuntimeException refusal
    ) {
        final var workflowModuleId = request.workflowModuleId();
        return new IllegalStateException("""
                ZenBPM adapter '%s' could not start workflow '%s' of workflow module '%s' for aggregate '%s' by \
                message %s: the engine answered that no start event takes it. Either no version the engine holds \
                starts the process with that message, which only a deployment changes: deploy workflow module \
                '%s' to the engine of this adapter id; the application deploys it while it boots, and a boot \
                whose deployment failed goes on only where '%s' is 'warn'. Or another node of the application \
                published a message of the same name at the same moment, which ZenBPM answers the same way. So \
                the start is repeated with the backoff of the outbox, which blocks its entry after '%s' \
                attempts.""".formatted(adapterId, request.bpmnProcessId(), workflowModuleId, request
                .workflowAggregateId(), ZenBpmKnownMessages.nameAsWritten(request.messageName(), scopedName),
                workflowModuleId, ZenBpmAdapterConfiguration.propertyKey(adapterId, "deployment-failure"),
                BLOCK_AFTER_ATTEMPTS), refusal);
    }

    // the models carry the scoped names, because the deployment scoped them
    private String scopedNameOf(
        final String workflowModuleId,
        final String messageName
    ) {
        return collaborators
                .scoping()
                .scopedIdentifier(workflowModuleId, messageName, adapterId);
    }

    private static Object startingOf(final ModuleMessages known) {
        return known
                .starting()
                .isEmpty() ? "none" : new TreeSet<>(known.starting());
    }

}
