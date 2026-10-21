/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmFindings;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessDefinitionRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;
import org.pbinitiative.zenbpmadapter.model.ZenBpmMarkerTasks;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelReader;
import org.pbinitiative.zenbpmadapter.model.ZenBpmScoping;

import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.version.CachingProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.spi.startup.StartupTopic;

/**
 * The versions of the processes the engine of ONE adapter id holds, against which the platform
 * matches <code>&#64;WorkflowTask(version = ...)</code> and its siblings, and through which it
 * checks the versions older than the one a start deployed.
 * <p>
 * A job carries the version of its definition, so a version specification made of numbers needs
 * nothing from here. A version TAG is different: which version carries which tag is read from the
 * engine's list of the definitions of a process, one request per process, which answers every
 * version with its key and its tag, see decision 14 in the repository's DECISIONS.md. The version a
 * start deployed is recorded from the deployment's answer without any request. The engine reads
 * the tag of a model from its process's <code>versionTag</code> extension element
 * (<code>&lt;zenbpm:versionTag value="..." /&gt;</code>) and gives a tag to one version of a
 * process only, so a model deployed again with the tag and the content of an older version gets
 * that older version back, which the deployment refuses. How the catalog is made, where it is
 * registered and why such a deployment is refused is decision 55 in the repository's DECISIONS.md.
 * <p>
 * The questions about one held version read its model, which the engine hands back as it holds
 * it, through the cache every model question of a start shares, so a model is read once per
 * start, see decision 56 in the repository's DECISIONS.md. The answers speak about the model the
 * way the wiring speaks about the model it deploys: the tasks without the markers the adapter
 * wrote, the task definitions and the identifiers without the prefixes the deployment added. Where
 * the engine no longer holds the version, or could not be asked, which a warning about the
 * infrastructure says, a question answers <code>null</code>, which the platform reads as "this BPMS
 * cannot say", never as "nothing is there". The platform asks while the application starts, so the
 * warning is a finding of the start; one asked later, by an extension, the startup report writes
 * into the log.
 */
public class ZenBpmProcessVersions extends CachingProcessVersionCatalog {

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final NameClashAvoidanceSupport scoping;

    private final ZenBpmFindings findings;

    /**
     * The engine's definition key per workflow module, plain process id and version, from the
     * list of definitions and the deployments of this start. The keys stay for the life of the
     * application: a definition the engine dropped meanwhile is answered from here, which serves
     * the questions of a start, and the next start reads the list again.
     */
    private final Map<String, Long> definitionKeys = new ConcurrentHashMap<>();

    /**
     * How many workflows run on a version, per workflow module, plain process id and version, as
     * the engine counted them last. The platform asks the counts of the older versions right before
     * it asks what those versions miss, so the answer to that compares only the versions somebody
     * still runs on without asking the engine again.
     */
    private final Map<String, Long> instanceCounts = new ConcurrentHashMap<>();

    /**
     * The catalog of one adapter id.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id, whose engine holds the versions
     * @param scoping The platform's name-clash scoping, which names a process as the engine knows
     *            it
     * @param findings Where a question the engine could not answer is reported
     */
    public ZenBpmProcessVersions(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final NameClashAvoidanceSupport scoping,
        final ZenBpmFindings findings
    ) {
        this.adapterId = adapterId;
        this.factory = factory;
        this.scoping = scoping;
        this.findings = findings;
    }

    /**
     * Records the version a deployment of this start ended up with, with its version tag, so neither
     * needs a request.
     *
     * @param deployed The definition the deployment ended up with
     */
    public void recordDeployed(final ZenBpmDeployedProcesses.DeployedProcess deployed) {
        final var version = String.valueOf(deployed.version());
        definitionKeys.put(keyOf(deployed.workflowModuleId(), deployed.bpmnProcessId(), version), deployed.key());
        record(deployed.workflowModuleId(), deployed.bpmnProcessId(), DeployedProcessVersion.of(version, deployed
                .versionTag()));
    }

    /**
     * The tasks of one version as the platform's wiring check reads them: every task and user task
     * of the held model but the markers, with its task definition as the workflow module names it,
     * and the multi-instance elements around it which hand no item to an iteration.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The plain id of the process
     * @param version The version the engine gave the definition
     * @return The tasks, <code>null</code> where the engine could not be asked or holds no such
     *         version
     */
    @Override
    public Collection<BpmnTaskSpec> tasksOfVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version
    ) {
        return answerOf(workflowModuleId, bpmnProcessId, version, "whether this application still serves its tasks",
                model -> Stream
                        .concat(model
                                .tasks()
                                .stream(),
                                model
                                        .userTasks()
                                        .stream())
                        .filter(task -> !task
                                .activityId()
                                .startsWith(ZenBpmMarkerTasks.RESERVED_PREFIX))
                        .map(task -> new BpmnTaskSpec(task.activityId(), plainTaskDefinition(workflowModuleId,
                                bpmnProcessId, task.taskDefinition()), task.optional(), task
                                        .name(), multiInstanceElementsWithoutAnItem(model, task.activityId())))
                        .toList());
    }

    /**
     * The start events of the process itself in one version, as the wiring reports those of the
     * model it deploys.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The plain id of the process
     * @param version The version the engine gave the definition
     * @return The start events, <code>null</code> where the engine could not be asked or holds no
     *         such version
     */
    @Override
    public Collection<BpmsInitiatedStartSpec> startEventsOfVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version
    ) {
        return answerOf(workflowModuleId, bpmnProcessId, version, "which start events it holds", model -> model
                .startEvents()
                .stream()
                .map(startEvent -> BpmsInitiatedStartSpec.of(startEvent.elementId(), startEvent.kind()))
                .toList());
    }

    /**
     * The elements of one version which put a second token into a workflow, as the wiring reports
     * those of the model it deploys.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The plain id of the process
     * @param version The version the engine gave the definition
     * @return The ids of the elements, <code>null</code> where the engine could not be asked or holds
     *         no such version
     */
    @Override
    public Collection<String> concurrentTokenElementsOfVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version
    ) {
        return answerOf(workflowModuleId, bpmnProcessId, version, "which of its elements put a second token "
                + "into a workflow", model -> List.copyOf(model.concurrentTokenElements()));
    }

    /**
     * The identifiers one version declares, plain, as the deployment reports those of the model it
     * deploys.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The plain id of the process
     * @param version The version the engine gave the definition
     * @return The identifiers, <code>null</code> where the engine could not be asked or holds no
     *         such version
     */
    @Override
    public Collection<ModelIdentifier> identifiersOfVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version
    ) {
        return answerOf(workflowModuleId, bpmnProcessId, version, "which names it still declares",
                model -> ZenBpmScoping.declaredIn(model.document(), workflowModuleId, bpmnProcessId, scoping,
                        adapterId));
    }

    /**
     * How many workflows run on one version: those the engine holds as active and those it holds as
     * failed, which an operator resumes, counted by the engine, so the answer costs two requests
     * whatever their number, see decision 14 in the repository's DECISIONS.md. Each search names a
     * lower bound of the creation time, the earliest there is, because only with one does the engine
     * search the index of the definition's instances instead of every instance it holds, see
     * decision 56 in the repository's DECISIONS.md.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The plain id of the process
     * @param version The version the engine gave the definition
     * @return The number, <code>null</code> where the engine could not be asked or holds no such
     *         version
     */
    @Override
    public Long activeInstanceCountOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version
    ) {
        try {
            final var definitionKey = definitionKeyOf(workflowModuleId, bpmnProcessId, version);
            if (definitionKey.isEmpty()) {
                return null;
            }
            final var running = InstanceFilter
                    .anyInstance()
                    .withProcessDefinitionKey(definitionKey.get())
                    .withCreatedFrom(Instant.EPOCH)
                    .withSize(1);
            final var rest = factory.rest();
            final var count = rest
                    .instances(running.withState(ProcessInstanceRef.State.ACTIVE))
                    .totalCount() + rest
                            .instances(running.withState(ProcessInstanceRef.State.FAILED))
                            .totalCount();
            instanceCounts.put(keyOf(workflowModuleId, bpmnProcessId, version), count);
            return count;
        } catch (final RuntimeException e) {
            findings.warn(StartupTopic.INFRASTRUCTURE, ZenBpmFindings.ofProcess(adapterId, workflowModuleId,
                    bpmnProcessId),
                    "The engine did not say how many workflows run on version %s of this BPMN process (%s), so VanillaBP cannot tell whether any does. Check that the engine answers; the next start asks again."
                            .formatted(version, ZenBpmErrors.withoutAdapterId(adapterId, e)));
            return null;
        }
    }

    /**
     * What a workflow running on a version older than the one this start deployed does not get,
     * read from the models: the end markers the deployed model has and the older ones lack, the
     * start markers alike, and the names the deployed model maps into a multi-instance body which
     * an older one does not map in. Only the older versions on which workflows run are compared,
     * because the platform tells this to the workflows that run, and a version whose count the
     * engine did not give is compared as well, because nobody can say that none runs on it. Their
     * models are read one after the other and let go before the next.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The plain id of the process
     * @return What they miss, in words an operator acts on, <code>null</code> where they miss
     *         nothing, where this start deployed no model under the id, or where a model could not
     *         be read
     */
    @Override
    public String whatOlderVersionsMiss(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        final var deployedProcesses = factory.deployedProcesses();
        final var deployedKey = deployedProcesses.keyOf(workflowModuleId, bpmnProcessId);
        if (deployedKey.isEmpty()) {
            return null;
        }
        final var deployedVersion = deployedProcesses
                .versionOf(workflowModuleId, bpmnProcessId)
                .orElseThrow();
        try {
            final var deployed = ZenBpmOlderVersions.Carried.by(modelOf(workflowModuleId, String.valueOf(
                    deployedVersion), factory.heldBpmnOf(deployedKey.get())));
            final var older = new ArrayList<ZenBpmOlderVersions.Carried>();
            for (final var version : deployedVersionsOf(workflowModuleId, bpmnProcessId)) {
                if ((Integer.parseInt(version.version()) < deployedVersion) && workflowsMayRunOn(workflowModuleId,
                        bpmnProcessId, version.version())) {
                    heldModelOf(workflowModuleId, bpmnProcessId, version.version())
                            .map(ZenBpmOlderVersions.Carried::by)
                            .ifPresent(older::add);
                }
            }
            return ZenBpmOlderVersions.whatTheyMiss(deployed, older);
        } catch (final RuntimeException e) {
            findings.warn(StartupTopic.INFRASTRUCTURE, ZenBpmFindings.ofProcess(adapterId, workflowModuleId,
                    bpmnProcessId),
                    "The models of the versions of this BPMN process could not be read (%s), so VanillaBP does not say what the workflows on older versions miss. Where the engine did not answer, check that it does; the next start reads them again."
                            .formatted(ZenBpmErrors.withoutAdapterId(adapterId, e)));
            return null;
        }
    }

    /**
     * Every version the engine holds under the process's id, oldest first, with its tag, read with
     * one request for the list. Each version's key is kept, so the questions about it need no other
     * request for the key.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The plain id of the process
     * @return The versions, empty where the engine holds none
     * @throws RuntimeException Where the engine could not be asked; the platform logs it
     */
    @Override
    protected List<DeployedProcessVersion> fetchDeployedVersions(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        final var definitions = factory
                .rest()
                .processDefinitions(scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId), false)
                .stream()
                .sorted(Comparator.comparingInt(ProcessDefinitionRef::version))
                .toList();
        definitions.forEach(definition -> definitionKeys.put(keyOf(workflowModuleId, bpmnProcessId, String.valueOf(
                definition.version())), definition.key()));
        return definitions
                .stream()
                .map(definition -> DeployedProcessVersion.of(String.valueOf(definition.version()), definition
                        .versionTag()))
                .toList();
    }

    // see decision 56 in the repository's DECISIONS.md: null tells the platform that this adapter
    // cannot say, which it never mistakes for an empty model
    private <T> Collection<T> answerOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version,
        final String question,
        final Function<ZenBpmBpmnModel, Collection<T>> answer
    ) {
        try {
            return heldModelOf(workflowModuleId, bpmnProcessId, version)
                    .map(answer)
                    .orElse(null);
        } catch (final RuntimeException e) {
            findings.warn(StartupTopic.INFRASTRUCTURE, ZenBpmFindings.ofProcess(adapterId, workflowModuleId,
                    bpmnProcessId),
                    "The model of version %s of this BPMN process could not be read (%s), so VanillaBP cannot tell %s. Where the engine did not answer, check that it does; the next start reads it again."
                            .formatted(version, ZenBpmErrors.withoutAdapterId(adapterId, e), question));
            return null;
        }
    }

    private boolean workflowsMayRunOn(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version
    ) {
        final var counted = instanceCounts.get(keyOf(workflowModuleId, bpmnProcessId, version));
        final var count = counted != null ? counted : activeInstanceCountOf(workflowModuleId, bpmnProcessId, version);
        return (count == null) || (count > 0);
    }

    private Optional<ZenBpmBpmnModel> heldModelOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version
    ) {
        return definitionKeyOf(workflowModuleId, bpmnProcessId, version).map(definitionKey -> modelOf(
                workflowModuleId, version, factory.heldBpmnOf(definitionKey)));
    }

    // the key of a version this start neither deployed nor listed yet, such as one another node
    // deployed meanwhile, costs one more request for the list
    private Optional<Long> definitionKeyOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version
    ) {
        final var key = keyOf(workflowModuleId, bpmnProcessId, version);
        if (!definitionKeys.containsKey(key)) {
            fetchDeployedVersions(workflowModuleId, bpmnProcessId);
        }
        return Optional.ofNullable(definitionKeys.get(key));
    }

    private ZenBpmBpmnModel modelOf(
        final String workflowModuleId,
        final String version,
        final String bpmn
    ) {
        return ZenBpmModelReader
                .read(workflowModuleId, "version %s of a process the engine holds".formatted(version),
                        new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)))
                .getFirst();
    }

    private String plainTaskDefinition(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinition
    ) {
        return taskDefinition == null ? null
                : scoping.plainTaskDefinition(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);
    }

    // the rule refuseItemsNoElementHandsOver applies to the model it deploys
    private static List<String> multiInstanceElementsWithoutAnItem(
        final ZenBpmBpmnModel model,
        final String activityId
    ) {
        return model
                .multiInstanceChainOf(activityId)
                .stream()
                .filter(multiInstance -> multiInstance.inputElement() == null)
                .map(ZenBpmBpmnModel.MultiInstanceElement::elementId)
                .toList();
    }

    private static String keyOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version
    ) {
        return "%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, version);
    }

}
