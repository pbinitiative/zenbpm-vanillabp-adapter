/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import lombok.extern.slf4j.Slf4j;

/**
 * Correlating a message with the workflow of an aggregate, in both phases.
 * <p>
 * <b>Phase one</b> asks the models and never whether something waits: a subscription may not exist
 * yet, because the workflow has not reached the element which waits, and the engine keeps no message
 * to wait that out. A name no model of the workflow module waits for is refused where the
 * application called, because the engine would refuse every publication of it and the outbox would
 * repeat it for hours before it gave up. A name the newest version of a process starts it with is
 * refused as well, naming <code>startWorkflowByMessage</code>, also where an older version waits for
 * it, because the engine hands a message no workflow waits for under its key to a start event of the
 * same name, so the correlation would start a new workflow. The models are every version the engine
 * holds of the processes the workflow module deployed in this start, read while deploying, and read
 * again before a name is refused, because another node may have deployed a newer version since; see
 * decision 42 in the repository's DECISIONS.md. Where they are not all known, the check says
 * nothing: the module deployed no process in this start, a version could not be read, or the module
 * serves a process it brought no model for, such as the old id of a renamed process. A check which
 * cannot see every model that may carry the name never refuses.
 * <p>
 * <b>Phase two</b> publishes the message under the name the engine holds it by, with the
 * correlation id as the key where the application passed one, else the aggregate id, which is the
 * key the deployment wrote into every message a claimed workflow waits for without a key of the
 * modeller's. The variables are the values the aggregate shares now and its id, as with every
 * command. The engine answers <code>404</code> where nothing waits for the message under that key,
 * so the publication is handed back to the outbox as a {@link PhaseTwoRetryLater} for
 * <code>workflow-visibility-timeout</code>: no attempt is counted, and the outbox blocks the entry
 * once <code>vanillabp.outbox.wait-for-visibility-at-most</code> passed since it was planned. The
 * engine answers the same <code>404</code> where the message reached the waiting element and the
 * workflow failed right behind it on something it could not find, a called process which is not
 * deployed for example; the status cannot tell the two apart, so that publication is repeated as
 * well, see decision 43 in the repository's DECISIONS.md. Where the timeout is zero, the <code>404</code> is an ordinary
 * failure instead, repeated with the outbox's own backoff, as the platform treats a zero window.
 * Every other failure is thrown as it is, so the classification decides whether it is repeated.
 * The engine deduplicates no message, so a publication repeated after its answer was lost may
 * correlate twice.
 *
 * @param <A> The workflow aggregate type
 */
@Slf4j
final class ZenBpmMessageCorrelation<A> {

    /**
     * The key of the platform which bounds how long the outbox repeats a publication nothing waits
     * for.
     */
    private static final String WAIT_FOR_VISIBILITY_AT_MOST = "vanillabp.outbox.wait-for-visibility-at-most";

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final AdapterCollaborators collaborators;

    private final Function<PhaseTwoRequest<A>, Map<String, Object>> variables;

    /**
     * The handler of the correlations of one adapter id.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id
     * @param collaborators What the platform hands to the adapter id
     * @param variables The values the aggregate of a request shares, and its id, as a start sends
     *            them
     */
    ZenBpmMessageCorrelation(
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
     * Phase one: refuses a message no model of the workflow module waits for, or one the newest
     * version of a process starts it with, without asking the engine whether something waits.
     *
     * @param request The request
     * @throws IllegalStateException With the message of the validation where the adapter id has no
     *             clients
     * @throws IllegalArgumentException Where every model the engine holds now for the module's
     *             processes is known and none of them waits for the message, or the newest version
     *             of one starts with it
     */
    void checkTheMessage(final PhaseOneRequest<A> request) {
        final var unconfigured = factory.unconfiguredMessage();
        if (unconfigured.isPresent()) {
            throw new IllegalStateException(unconfigured.get());
        }
        final var workflowModuleId = request.workflowModuleId();
        final var kept = ZenBpmKnownMessages.everyModelOf(factory, workflowModuleId,
                ZenBpmDeployedProcesses::messagesOf);
        if (kept.isEmpty() || passes(kept.get(), request)) {
            return;
        }
        // another node may have deployed a newer version since this start, so a refusal rests only
        // on the models the engine holds now
        final var now = ZenBpmKnownMessages.everyModelOf(factory, workflowModuleId,
                ZenBpmDeployedProcesses::messagesReadAgain);
        if (now.isEmpty() || passes(now.get(), request)) {
            return;
        }
        throw refusal(request, now.get());
    }

    /**
     * Phase two: publishes the message.
     *
     * @param request The request
     * @throws IllegalStateException With the message of the validation where the adapter id has no
     *             clients
     * @throws PhaseTwoRetryLater Where nothing waits for the message yet
     */
    void correlate(final PhaseTwoRequest<A> request) {
        final var rest = factory.rest();
        final var workflowModuleId = request.workflowModuleId();
        final var scopedName = scopedNameOf(workflowModuleId, request.messageName());
        final var correlationKey = request.correlationId() == null
                ? String.valueOf(request.workflowAggregateId())
                : request.correlationId();
        try {
            rest.publishMessage(scopedName, correlationKey, variables.apply(request));
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.nobodyWaits(e)) {
                throw e;
            }
            throw nobodyWaitsYet(request, scopedName, correlationKey, e);
        }
        log.debug("ZenBPM[{}]: correlated message '{}' (correlation key '{}') to workflow '{}' of workflow module '{}'",
                adapterId, scopedName, correlationKey, request.bpmnProcessId(), workflowModuleId);
    }

    // mostly the engine found nothing waiting and moved nothing, so the outbox may hand the
    // publication back without counting an attempt; where the 404 came from a failure behind the
    // element which took the message, a counted failure would not stop the repetitions either, see
    // decision 43 in the repository's DECISIONS.md
    private RuntimeException nobodyWaitsYet(
        final PhaseTwoRequest<A> request,
        final String scopedName,
        final String correlationKey,
        final RuntimeException refusal
    ) {
        final var window = factory
                .configuration()
                .getWorkflowVisibilityTimeout();
        final var name = ZenBpmKnownMessages.nameAsWritten(request.messageName(), scopedName);
        final var workflow = request.bpmnProcessId();
        final var workflowModuleId = request.workflowModuleId();
        final var aggregateId = request.workflowAggregateId();
        final var nobodyWaits = """
                ZenBPM adapter '%s': nothing waits for message %s under correlation key '%s' yet, in workflow '%s' \
                of workflow module '%s' for aggregate '%s', and ZenBPM keeps no message nothing waits for.""".formatted(
                adapterId, name, correlationKey, workflow, workflowModuleId, aggregateId);
        final var cannotHelp = """
                The workflow may not have reached the element which waits for the message yet; where it passed \
                that element, or waits under another key, the repetitions cannot help: the key the waiting element \
                evaluates has to be the correlation id passed to correlateMessage, or the aggregate id where none \
                was passed. ZenBPM answers the same where the message reached the element which waits for it and \
                the workflow failed right behind it on something it could not find, such as a called process \
                which is not deployed: where the workflow shows such an incident, the message was received, and \
                the repetitions cannot help either.""";
        if (!window.isPositive()) {
            // a zero window leaves the store with its own backoff, as the platform does for one
            return new IllegalStateException("""
                    %s %s is zero, so the publication is repeated with the backoff of the outbox, which blocks its \
                    entry after 'vanillabp.outbox.block-after-attempts' attempts. %s""".formatted(nobodyWaits,
                    ZenBpmAdapterConfiguration.propertyKey(adapterId, "workflow-visibility-timeout"),
                    cannotHelp), refusal);
        }
        final var retryLater = new PhaseTwoRetryLater("""
                %s The publication is repeated every %s until '%s' passed since the correlation was planned, and \
                then its entry is blocked. %s""".formatted(nobodyWaits, window, WAIT_FOR_VISIBILITY_AT_MOST,
                cannotHelp), window);
        retryLater.initCause(refusal);
        return retryLater;
    }

    // a start event of the newest version takes the message wherever no workflow waits for it under
    // its key yet, so a name it starts with never passes, even where an older version waits for it
    private boolean passes(
        final ModuleMessages known,
        final PhaseOneRequest<A> request
    ) {
        final var scopedName = scopedNameOf(request.workflowModuleId(), request.messageName());
        return known
                .awaited()
                .contains(scopedName) && !known
                        .starting()
                        .contains(scopedName);
    }

    private IllegalArgumentException refusal(
        final PhaseOneRequest<A> request,
        final ModuleMessages known
    ) {
        final var workflowModuleId = request.workflowModuleId();
        final var scopedName = scopedNameOf(workflowModuleId, request.messageName());
        final var name = ZenBpmKnownMessages.nameAsWritten(request.messageName(), scopedName);
        final var workflow = request.bpmnProcessId();
        if (known
                .starting()
                .contains(scopedName) && known
                        .awaited()
                        .contains(scopedName)) {
            return new IllegalArgumentException("""
                    ZenBPM adapter '%s' cannot correlate message %s to workflow '%s' of workflow module '%s': the \
                    newest version of a process of the workflow module starts with that message, and a model the \
                    engine holds waits for it as well. ZenBPM hands a message no workflow waits for under its correlation key \
                    yet to the start event of the newest version, so a correlation which comes before the workflow \
                    waits would start a new workflow instead. Give the message which starts the process another \
                    name than the message a workflow waits for."""
                    .formatted(adapterId, name, workflow,
                            workflowModuleId));
        }
        if (known
                .starting()
                .contains(scopedName)) {
            return new IllegalArgumentException("""
                    ZenBPM adapter '%s' cannot correlate message %s to workflow '%s' of workflow module '%s': a \
                    model of the workflow module starts its process with that message, and none waits for it. \
                    ZenBPM hands a message no workflow waits for to a start event of the same name, so the \
                    correlation would start a new workflow. To START a workflow by a message use \
                    startWorkflowByMessage instead.""".formatted(adapterId, name, workflow, workflowModuleId));
        }
        return new IllegalArgumentException("""
                ZenBPM adapter '%s' cannot correlate message %s to workflow '%s' of workflow module '%s': no model \
                of the workflow module waits for that message. ZenBPM keeps no message nothing waits for, so its \
                publication would be repeated until '%s' passed and then blocked. Read was every version the \
                engine holds of the processes %s, and the messages they wait for are: %s. Correct the name passed \
                to correlateMessage, or declare the message at the element which waits for it."""
                .formatted(adapterId, name, workflow, workflowModuleId, WAIT_FOR_VISIBILITY_AT_MOST, known
                        .scopedBpmnProcessIds(), awaitedOf(known)));
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

    private static Object awaitedOf(final ModuleMessages known) {
        return known
                .awaited()
                .isEmpty() ? "none" : new TreeSet<>(known.awaited());
    }

}
