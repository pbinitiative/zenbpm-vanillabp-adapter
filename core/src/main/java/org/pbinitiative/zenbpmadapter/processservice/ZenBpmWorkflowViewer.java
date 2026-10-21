/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmApiException;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.HistoryEntry;
import org.pbinitiative.zenbpmadapter.client.api.IncidentRef;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessDefinitionRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.CalledVersion;
import org.pbinitiative.zenbpmadapter.model.ZenBpmMarkerTasks;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelReader;

import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowElementHistory;
import io.vanillabp.spi.process.WorkflowElementType;
import io.vanillabp.spi.process.WorkflowHistory;
import lombok.extern.slf4j.Slf4j;

/**
 * The viewer/history API of one ZenBPM adapter id: the process definitions a workflow runs on, the
 * BPMN of a definition, and what a workflow did, all read from the engine. The definition ids it
 * hands out are the engine's definition keys; the core puts them into the namespace of the adapter
 * id. See decision 57 in the repository's DECISIONS.md.
 * <p>
 * <b>Which workflow.</b> The aggregate id is the workflow's business key, see decision 25 in the
 * repository's DECISIONS.md, so the workflow is found by searching the instances of the process
 * under that key: one which runs, else the one created last, so an aggregate whose first workflow
 * ended and whose second runs shows the second. A workflow the engine started by a timer or a message has no business key; under
 * <code>message-start-lookup: scan</code> one a message started is found by its id variable among
 * the instances which run, as the probes find it, and otherwise it is reported as unknown. A node of
 * the engine which lags behind may not find a workflow the core's probe found a moment before, so
 * where the search finds nothing it is repeated until <code>workflow-visibility-timeout</code>
 * passed, the window the probes report. A search the engine does not answer throws, because an
 * unknown workflow is a different answer.
 * <p>
 * <b>The history context</b> of a call activity is the key of the instance it started, as a decimal
 * number. A context is served only where that instance was started by a call activity of the
 * workflow found, or of a process the workflow called, so the history of another workflow cannot be
 * read through a context made up. The instance and its parents are read again within the same
 * window where a node does not show them yet.
 * <p>
 * <b>The definitions</b> are the one the workflow runs on, and for each call activity of its model
 * the definition the engine would start now: the latest version of the called process, the version
 * the call activity names, or the one which carries the tag it names.
 * <p>
 * <b>The history</b> lists the flow elements the workflow passed, its sequence flows included, with
 * those of its embedded sub-processes, event sub-processes and multi-instance elements, which the
 * engine keeps in child instances of their own: the entry of a multi-instance element in the
 * instance it stands in is reported as {@link WorkflowElementType#MULTI_INSTANCE}, and each
 * iteration with the type of the element. An event sub-process writes no entry where it stands, so
 * it is reported as {@link WorkflowElementType#EVENT_SUB_PROCESS} from its instance. A call activity
 * names the instance it started as its secondary history context. The markers the adapter writes
 * into a model are left out. An element with an incident nobody resolved carries the engine's
 * message. The engine does not tell an element which was terminated from one which completed, and
 * keeps no end of an instance, so no element is reported as cancelled, and the end of a workflow is
 * the end of its last element. A read of the history which the engine does not answer costs the
 * elements, which are reported as <code>null</code>, and a read of a model or of the versions of the
 * processes called which fails costs the definitions its call activities would start; the first
 * such failure is logged at WARN, the next ones at DEBUG until a read succeeds again.
 */
@Slf4j
final class ZenBpmWorkflowViewer {

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final NameClashAvoidanceSupport scoping;

    private final ZenBpmMessageStartScan messageStartScan;

    /**
     * How often the search is repeated while it waits for a workflow to become visible.
     */
    private final Duration searchInterval;

    /**
     * Whether the last read of a history or of a model for the call activities failed.
     */
    private final AtomicBoolean readsFail = new AtomicBoolean();

    /**
     * Builds the viewer of one adapter id, which its process service owns.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id
     * @param scoping The core's name-clash avoidance, which takes the prefix off a process id
     * @param messageStartScan The lookup of the workflows a message started, which the probes use
     * @param searchInterval How often the search is repeated while it waits for a workflow
     */
    ZenBpmWorkflowViewer(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final NameClashAvoidanceSupport scoping,
        final ZenBpmMessageStartScan messageStartScan,
        final Duration searchInterval
    ) {
        this.adapterId = adapterId;
        this.factory = factory;
        this.scoping = scoping;
        this.messageStartScan = messageStartScan;
        this.searchInterval = searchInterval;
    }

    /**
     * The definitions the workflow of an aggregate runs on: the one of the workflow, or of the
     * instance the history context names, and the one each of its call activities would start now.
     *
     * @param rest The REST client of the adapter id
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The process, as the workflow module names it
     * @param aggregateId The id of the aggregate
     * @param idVariableName The name of the variable which carries the aggregate's id,
     *            <code>null</code> where nobody named it
     * @param historyContext <code>null</code> for the workflow, else the key of an instance a call
     *            activity of the workflow started
     * @return The definitions, empty where the workflow or the context is unknown
     * @throws IllegalStateException Where the engine could not be asked for the workflow
     */
    List<ProcessDefinition> processDefinitionsOf(
        final ZenBpmRestClient rest,
        final String workflowModuleId,
        final String bpmnProcessId,
        final String aggregateId,
        final String idVariableName,
        final String historyContext
    ) {
        final var subject = subjectOf(rest, workflowModuleId, bpmnProcessId, aggregateId, idVariableName,
                historyContext);
        if (subject.isEmpty()) {
            return List.of();
        }
        final var instance = subject.get();
        final var definitions = new ArrayList<ProcessDefinition>();
        definitions.add(new ProcessDefinition(String.valueOf(instance.definitionKey()), plainIdOf(workflowModuleId,
                instance.bpmnProcessId()), String.valueOf(instance.version()), null));
        final ZenBpmBpmnModel model;
        try {
            model = modelOf(workflowModuleId, instance.definitionKey());
        } catch (final RuntimeException failure) {
            readFailed(
                    "the model of process definition %d, so the definitions its call activities would start are not reported"
                            .formatted(instance.definitionKey()),
                    failure);
            return List.copyOf(definitions);
        }
        final List<ProcessDefinition> called;
        try {
            called = calledDefinitionsOf(rest, workflowModuleId, model);
        } catch (final RuntimeException failure) {
            readFailed(
                    "the versions of the processes the call activities of process definition %d call, so the definitions they would start are not reported"
                            .formatted(instance.definitionKey()),
                    failure);
            return List.copyOf(definitions);
        }
        readsFail.set(false);
        definitions.addAll(called);
        return List.copyOf(definitions);
    }

    /**
     * The BPMN of a definition as the engine holds it, with the markers the adapter wrote: the one a
     * deployment of this start got back, else the one read for another question, else read from the
     * engine and kept, see decision 56 in the repository's DECISIONS.md.
     *
     * @param processDefinitionId The definition key
     * @return The BPMN, <code>null</code> where the id is no key or the engine holds no such
     *         definition
     * @throws IllegalStateException Where the engine could not be asked
     */
    InputStream bpmnXmlOf(final String processDefinitionId) {
        final var key = ZenBpmKeys.keyOf(processDefinitionId);
        if (key.isEmpty()) {
            return null;
        }
        try {
            return new ByteArrayInputStream(factory
                    .heldBpmnOf(key.getAsLong())
                    .getBytes(StandardCharsets.UTF_8));
        } catch (final RuntimeException failure) {
            if ((failure instanceof ZenBpmApiException refusal) && (refusal.getStatus() == 404)) {
                return null;
            }
            throw notAsked("the BPMN of process definition %d".formatted(key.getAsLong()), failure);
        }
    }

    /**
     * What the workflow of an aggregate did, or the instance the history context names.
     *
     * @param rest The REST client of the adapter id
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The process, as the workflow module names it
     * @param aggregateId The id of the aggregate
     * @param idVariableName The name of the variable which carries the aggregate's id,
     *            <code>null</code> where nobody named it
     * @param historyContext <code>null</code> for the workflow, else the key of an instance a call
     *            activity of the workflow started
     * @return The history, <code>null</code> where the workflow or the context is unknown
     * @throws IllegalStateException Where the engine could not be asked for the workflow
     */
    WorkflowHistory historyOf(
        final ZenBpmRestClient rest,
        final String workflowModuleId,
        final String bpmnProcessId,
        final String aggregateId,
        final String idVariableName,
        final String historyContext
    ) {
        final var subject = subjectOf(rest, workflowModuleId, bpmnProcessId, aggregateId, idVariableName,
                historyContext);
        if (subject.isEmpty()) {
            return null;
        }
        final var instance = subject.get();
        final var definitionId = String.valueOf(instance.definitionKey());
        final var startTime = offsetOf(instance.createdAt());
        final Read read;
        try {
            read = historyOf(rest, workflowModuleId, instance);
        } catch (final RuntimeException failure) {
            readFailed("the history of instance %d, so its elements are reported as unknown".formatted(instance
                    .key()), failure);
            return new WorkflowHistory(definitionId, startTime, null, null);
        }
        readsFail.set(false);
        return new WorkflowHistory(definitionId, startTime, read.endTime(), read.elements());
    }

    /**
     * The type of the SPI an element of the engine's history has.
     *
     * @param elementType The type as the engine names it
     * @return The type, {@link WorkflowElementType#UNKNOWN} for one the engine did not name before
     */
    static WorkflowElementType typeOf(final String elementType) {
        return switch (elementType) {
            case "START_EVENT" -> WorkflowElementType.START_EVENT;
            case "END_EVENT" -> WorkflowElementType.END_EVENT;
            case "INTERMEDIATE_CATCH_EVENT" -> WorkflowElementType.INTERMEDIATE_CATCH_EVENT;
            case "INTERMEDIATE_THROW_EVENT", "INTERMEDIATE_MESSAGE_THROW_EVENT" ->
                WorkflowElementType.INTERMEDIATE_THROW_EVENT;
            case "BOUNDARY_EVENT" -> WorkflowElementType.BOUNDARY_EVENT;
            case "SERVICE_TASK" -> WorkflowElementType.SERVICE_TASK;
            case "USER_TASK" -> WorkflowElementType.USER_TASK;
            case "BUSINESS_RULE_TASK" -> WorkflowElementType.BUSINESS_RULE_TASK;
            case "RECEIVE_TASK" -> WorkflowElementType.RECEIVE_TASK;
            case "CALL_ACTIVITY" -> WorkflowElementType.CALL_ACTIVITY;
            case "SUB_PROCESS" -> WorkflowElementType.SUB_PROCESS;
            case "SEQUENCE_FLOW" -> WorkflowElementType.SEQUENCE_FLOW;
            case "EXCLUSIVE_GATEWAY" -> WorkflowElementType.EXCLUSIVE_GATEWAY;
            case "PARALLEL_GATEWAY" -> WorkflowElementType.PARALLEL_GATEWAY;
            case "INCLUSIVE_GATEWAY" -> WorkflowElementType.INCLUSIVE_GATEWAY;
            case "EVENT_BASED_GATEWAY" -> WorkflowElementType.EVENT_BASED_GATEWAY;
            default -> WorkflowElementType.UNKNOWN;
        };
    }

    // the instance a read is about: the workflow, or the instance its history context names. A node
    // which lags behind may not show what the probe found a moment ago on another one, so every read
    // which finds nothing is repeated within one window, the one the probes report
    private Optional<ProcessInstanceRef> subjectOf(
        final ZenBpmRestClient rest,
        final String workflowModuleId,
        final String bpmnProcessId,
        final String aggregateId,
        final String idVariableName,
        final String historyContext
    ) {
        final var scopedId = scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);
        final var subject = "the workflow of aggregate '%s' (process '%s' of workflow module '%s')".formatted(
                aggregateId, bpmnProcessId, workflowModuleId);
        final var deadline = System.nanoTime() + factory
                .configuration()
                .getWorkflowVisibilityTimeout()
                .toNanos();
        try {
            final var workflow = untilVisible(() -> workflowOf(rest, workflowModuleId, scopedId, aggregateId,
                    idVariableName), deadline);
            if (workflow.isEmpty() || (historyContext == null)) {
                return workflow;
            }
            return calledInstanceOf(rest, workflow.get(), historyContext, subject, deadline);
        } catch (final RuntimeException failure) {
            throw notAsked(subject, failure);
        }
    }

    private Optional<ProcessInstanceRef> workflowOf(
        final ZenBpmRestClient rest,
        final String workflowModuleId,
        final String scopedId,
        final String aggregateId,
        final String idVariableName
    ) {
        final var searched = searched(rest, scopedId, aggregateId);
        if (searched
                .filter(instance -> ZenBpmRunningWorkflows.isRunning(instance.state()))
                .isPresent()) {
            return searched;
        }
        return scanned(rest, workflowModuleId, scopedId, aggregateId, idVariableName).or(() -> searched);
    }

    // a workflow the engine started by a message has no business key, so under the scan it is found
    // by its id variable among the instances which run, as the probes find it
    private Optional<ProcessInstanceRef> scanned(
        final ZenBpmRestClient rest,
        final String workflowModuleId,
        final String scopedId,
        final String aggregateId,
        final String idVariableName
    ) {
        if (idVariableName == null) {
            return Optional.empty();
        }
        final var toScan = messageStartScan.processesToScan(workflowModuleId, Set.of(scopedId));
        if (toScan.isEmpty()) {
            return Optional.empty();
        }
        return messageStartScan
                .running(rest, toScan, idVariableName, aggregateId)
                .filter(ZenBpmWorkflowViewer::isARoot);
    }

    private <T> Optional<T> untilVisible(
        final Supplier<Optional<T>> read,
        final long deadline
    ) {
        while (true) {
            final var found = read.get();
            if (found.isPresent() || ((System.nanoTime() - deadline) >= 0)) {
                return found;
            }
            // an interrupt ends the wait as well: the next request refuses to wait for an answer
            LockSupport.parkNanos(searchInterval.toNanos());
        }
    }

    // every page, because an aggregate's workflows are few: one which runs wins, else the one
    // created last. The search leaves out the instances of embedded sub-processes and
    // multi-instance elements, which carry the business key as well, and sends a lower bound of the
    // creation, so the engine searches the instances of the process by its index, see decision 56
    // in the repository's DECISIONS.md
    private static Optional<ProcessInstanceRef> searched(
        final ZenBpmRestClient rest,
        final String scopedId,
        final String aggregateId
    ) {
        final var filter = InstanceFilter
                .anyInstance()
                .withBusinessKey(aggregateId)
                .withBpmnProcessId(scopedId)
                .withCreatedFrom(Instant.EPOCH);
        final var found = new ArrayList<ProcessInstanceRef>();
        for (var page = 1;; ++page) {
            final var read = rest.instances(filter.withPage(page));
            found.addAll(read.items());
            if (read.last()) {
                break;
            }
        }
        final Comparator<ProcessInstanceRef> newest = Comparator
                .comparing(ProcessInstanceRef::createdAt)
                .thenComparingLong(ProcessInstanceRef::key);
        return found
                .stream()
                .filter(ZenBpmWorkflowViewer::isARoot)
                .max(Comparator
                        .<ProcessInstanceRef, Boolean>comparing(instance -> ZenBpmRunningWorkflows.isRunning(instance
                                .state()))
                        .thenComparing(newest));
    }

    private static boolean isARoot(final ProcessInstanceRef instance) {
        return (instance.processType() != ProcessInstanceRef.ProcessType.SUBPROCESS) && (instance
                .processType() != ProcessInstanceRef.ProcessType.MULTI_INSTANCE);
    }

    // a context names an instance a call activity started, below the workflow: up its parents, the
    // walk has to meet the workflow before it meets a root
    private Optional<ProcessInstanceRef> calledInstanceOf(
        final ZenBpmRestClient rest,
        final ProcessInstanceRef workflow,
        final String historyContext,
        final String subject,
        final long deadline
    ) {
        final var key = ZenBpmKeys.keyOf(historyContext);
        if (key.isEmpty()) {
            log.debug("ZenBPM[{}]: the history context '{}' of {} is no key of this engine", adapterId, historyContext,
                    subject);
            return Optional.empty();
        }
        final Optional<ProcessInstanceRef> called;
        try {
            called = untilVisible(() -> rest.instance(key.getAsLong()), deadline);
        } catch (final RuntimeException failure) {
            if (ZenBpmKeys.isAKeyOfAPartitionTheEngineDoesNotHave(rest, key.getAsLong(), failure)) {
                log.debug("ZenBPM[{}]: the history context '{}' of {} names a partition the engine does not have",
                        adapterId, historyContext, subject);
                return Optional.empty();
            }
            throw failure;
        }
        if (called.isEmpty()) {
            log.debug("ZenBPM[{}]: the engine holds no instance of the history context '{}' of {}", adapterId,
                    historyContext, subject);
            return Optional.empty();
        }
        if ((called
                .get()
                .processType() == ProcessInstanceRef.ProcessType.CALL_ACTIVITY) && isBelow(called
                        .get()
                        .parentKey(), workflow.key(), deadline)) {
            return called;
        }
        log.warn(
                "ZenBPM[{}]: the history context '{}' names no instance a call activity of {} started, so it is not served",
                adapterId, historyContext, subject);
        return Optional.empty();
    }

    // the parents are read through the instance cache, which keeps what does not change
    private boolean isBelow(
        final Long parentKey,
        final long workflowKey,
        final long deadline
    ) {
        var parent = parentKey;
        while (parent != null) {
            if (parent == workflowKey) {
                return true;
            }
            final long instanceKey = parent;
            final var facts = untilVisible(() -> factory
                    .instances()
                    .facts(instanceKey), deadline);
            if (facts.isEmpty()) {
                return false;
            }
            parent = facts
                    .get()
                    .parentKey();
        }
        return false;
    }

    private ZenBpmBpmnModel modelOf(
        final String workflowModuleId,
        final long definitionKey
    ) {
        final var bpmn = factory.heldBpmnOf(definitionKey);
        return ZenBpmModelReader
                .read(workflowModuleId, "process definition %d".formatted(definitionKey), new ByteArrayInputStream(bpmn
                        .getBytes(StandardCharsets.UTF_8)))
                .getFirst();
    }

    // one list of versions per process called, which answers every version a call activity names;
    // call activities which start the same definition share its entry
    private List<ProcessDefinition> calledDefinitionsOf(
        final ZenBpmRestClient rest,
        final String workflowModuleId,
        final ZenBpmBpmnModel model
    ) {
        final var versionsOfProcesses = new HashMap<String, List<ProcessDefinitionRef>>();
        final var started = new LinkedHashMap<Long, ProcessDefinitionRef>();
        final var elementsByDefinition = new LinkedHashMap<Long, List<String>>();
        for (final var callActivity : model.callActivities()) {
            final var calledId = callActivity.calledProcessId();
            final var calledVersion = callActivity.calledVersion();
            if ((calledId == null) || (calledVersion == null)) {
                log.debug("ZenBPM[{}]: call activity '{}' of process '{}' starts nothing the engine accepts", adapterId,
                        callActivity.elementId(), model.bpmnProcessId());
                continue;
            }
            final var versions = versionsOfProcesses.computeIfAbsent(calledId, id -> rest.processDefinitions(id,
                    false));
            final var definition = startedBy(calledVersion, versions);
            if (definition.isEmpty()) {
                log.debug("ZenBPM[{}]: the engine holds no version of process '{}' call activity '{}' would start",
                        adapterId, calledId, callActivity.elementId());
                continue;
            }
            started.putIfAbsent(definition
                    .get()
                    .key(), definition.get());
            elementsByDefinition
                    .computeIfAbsent(definition
                            .get()
                            .key(), key -> new ArrayList<>())
                    .add(callActivity.elementId());
        }
        return started
                .values()
                .stream()
                .map(definition -> new ProcessDefinition(String.valueOf(definition.key()), plainIdOf(workflowModuleId,
                        definition.bpmnProcessId()), String.valueOf(definition.version()), List.copyOf(
                                elementsByDefinition.get(definition.key()))))
                .toList();
    }

    // as the engine resolves a call activity: the latest version, the version of a number, or the
    // one which carries a tag, and where none does, the version a tag 'v<number>' names
    private static Optional<ProcessDefinitionRef> startedBy(
        final CalledVersion calledVersion,
        final List<ProcessDefinitionRef> versions
    ) {
        if (calledVersion.isLatest()) {
            return versions
                    .stream()
                    .max(Comparator.comparingInt(ProcessDefinitionRef::version));
        }
        if (calledVersion.version() != null) {
            return ofVersion(calledVersion.version(), versions);
        }
        final var tagged = versions
                .stream()
                .filter(version -> calledVersion
                        .versionTag()
                        .equals(version.versionTag()))
                .findFirst();
        if (tagged.isPresent()) {
            return tagged;
        }
        final var tag = calledVersion.versionTag();
        if (!tag.matches("v[0-9]+")) {
            return Optional.empty();
        }
        try {
            return ofVersion(Integer.parseInt(tag.substring(1)), versions);
        } catch (final NumberFormatException tooLarge) {
            return Optional.empty();
        }
    }

    private static Optional<ProcessDefinitionRef> ofVersion(
        final int number,
        final List<ProcessDefinitionRef> versions
    ) {
        return versions
                .stream()
                .filter(version -> version.version() == number)
                .findFirst();
    }

    private String plainIdOf(
        final String workflowModuleId,
        final String scopedId
    ) {
        return scoping.plainProcessId(workflowModuleId, scopedId, adapterId);
    }

    // the instance's own elements, and those of its embedded sub-processes and multi-instance
    // elements, which the engine keeps in child instances of the same definition
    private Read historyOf(
        final ZenBpmRestClient rest,
        final String workflowModuleId,
        final ProcessInstanceRef instance
    ) {
        final var model = modelOf(workflowModuleId, instance.definitionKey());
        final var shape = new Shape(model
                .multiInstanceElements()
                .stream()
                .map(ZenBpmBpmnModel.MultiInstanceElement::elementId)
                .collect(Collectors.toSet()), model
                        .callActivities()
                        .stream()
                        .filter(callActivity -> callActivity.calledProcessId() != null)
                        .collect(Collectors.toMap(ZenBpmBpmnModel.CallActivity::elementId,
                                ZenBpmBpmnModel.CallActivity::calledProcessId)), model.eventSubProcessStarts());
        final var elements = new ArrayList<Element>();
        collect(rest, instance, false, shape, elements);
        // an interrupting event sub-process ends the workflow after the last element of its own
        final var endTime = isEnded(instance.state())
                ? elements
                        .stream()
                        .map(element -> element
                                .history()
                                .endTime())
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null)
                : null;
        return new Read(elements
                .stream()
                .sorted(Comparator
                        .comparing(Element::createdAt)
                        .thenComparingLong(Element::key))
                .map(Element::history)
                .toList(), endTime);
    }

    // the entries of one instance and of its parts, which are added to the list; answers the
    // instance's own entries. An event sub-process writes no entry into the instance it stands in,
    // so the children of an instance whose model has one are read whatever its entries say
    private List<HistoryEntry> collect(
        final ZenBpmRestClient rest,
        final ProcessInstanceRef instance,
        final boolean isABody,
        final Shape shape,
        final List<Element> elements
    ) {
        final var entries = entriesOf(rest, instance.key());
        final var errors = errorsOf(rest, instance);
        final var children = !shape
                .eventSubProcessStarts()
                .isEmpty() || entries
                        .stream()
                        .anyMatch(entry -> startsAChild(entry, isABody, shape)) ? rest.children(instance.key())
                                : List
                                        .<ProcessInstanceRef>of();
        final var calledInstances = calledInstancesOf(entries, isABody, shape, children);
        for (final var entry : entries) {
            if (entry
                    .elementId()
                    .startsWith(ZenBpmMarkerTasks.RESERVED_PREFIX)) {
                continue;
            }
            final var type = !isABody && shape
                    .multiInstanceElements()
                    .contains(entry.elementId()) ? WorkflowElementType.MULTI_INSTANCE : typeOf(entry.elementType());
            final var calledInstance = calledInstances.get(entry.key());
            elements.add(new Element(entry.createdAt(), entry.key(), new WorkflowElementHistory(offsetOf(entry
                    .createdAt()), entry.completedAt() == null ? null : offsetOf(entry.completedAt()), entry
                            .elementId(), type, errors.get(entry.key()), false, calledInstance == null ? null
                                    : String.valueOf(calledInstance))));
        }
        for (final var child : children) {
            if (child.processType() == ProcessInstanceRef.ProcessType.SUBPROCESS) {
                final var childEntries = collect(rest, child, false, shape, elements);
                eventSubProcessOf(childEntries, shape).ifPresent(eventSubProcess -> elements.add(new Element(child
                        .createdAt(), child.key(), new WorkflowElementHistory(offsetOf(child.createdAt()), isEnded(
                                child.state()) ? lastEndOf(childEntries)
                                        : null, eventSubProcess, WorkflowElementType.EVENT_SUB_PROCESS, null, false, null))));
            }
            if (child.processType() == ProcessInstanceRef.ProcessType.MULTI_INSTANCE) {
                collect(rest, child, true, shape, elements);
            }
        }
        return entries;
    }

    // the engine orders a history by the millisecond an element was entered alone, so an entry of
    // the millisecond at the end of a page may come on the next page again, and is taken once
    private static List<HistoryEntry> entriesOf(
        final ZenBpmRestClient rest,
        final long instanceKey
    ) {
        final var entries = new LinkedHashMap<Long, HistoryEntry>();
        for (var page = 1;; ++page) {
            final var read = rest.history(instanceKey, page);
            read
                    .items()
                    .forEach(entry -> entries.putIfAbsent(entry.key(), entry));
            if (read.last()) {
                return List.copyOf(entries.values());
            }
        }
    }

    // a list of instances counts the incidents of each, the answer for one instance does not, so
    // that one is asked where it runs
    private static Map<Long, String> errorsOf(
        final ZenBpmRestClient rest,
        final ProcessInstanceRef instance
    ) {
        final var counted = instance.incidentCount();
        final var mayHaveIncidents = counted == null ? ZenBpmRunningWorkflows.isRunning(instance.state()) : counted > 0;
        if (!mayHaveIncidents) {
            return Map.of();
        }
        final var errors = new HashMap<Long, String>();
        for (final IncidentRef incident : rest.incidentsOf(instance.key())) {
            errors.putIfAbsent(incident.elementInstanceKey(), incident.message());
        }
        return errors;
    }

    // an embedded sub-process and a call activity start an instance, and so does the activation of a
    // multi-instance element, outside its body, whose iterations then start an instance each where
    // the element is a sub-process or a call activity
    private static boolean startsAChild(
        final HistoryEntry entry,
        final boolean isABody,
        final Shape shape
    ) {
        if (!isABody && shape
                .multiInstanceElements()
                .contains(entry.elementId())) {
            return true;
        }
        return "SUB_PROCESS".equals(entry.elementType()) || "CALL_ACTIVITY".equals(entry.elementType());
    }

    // the engine names no call activity in the instance it started, but creates that instance right
    // after the entry of the call activity, so its key is the next one above the entry's among the
    // instances of the called process. Each entry takes the first such instance no entry before it
    // took; one of a call activity which names no process started none
    private static Map<Long, Long> calledInstancesOf(
        final List<HistoryEntry> entries,
        final boolean isABody,
        final Shape shape,
        final List<ProcessInstanceRef> children
    ) {
        final var called = children
                .stream()
                .filter(child -> child.processType() == ProcessInstanceRef.ProcessType.CALL_ACTIVITY)
                .sorted(Comparator.comparingLong(ProcessInstanceRef::key))
                .toList();
        final var taken = new HashSet<Long>();
        final var calledInstances = new HashMap<Long, Long>();
        entries
                .stream()
                .filter(entry -> "CALL_ACTIVITY".equals(entry.elementType()))
                .filter(entry -> isABody || !shape
                        .multiInstanceElements()
                        .contains(entry.elementId()))
                .sorted(Comparator.comparingLong(HistoryEntry::key))
                .forEach(entry -> {
                    final var calledId = shape
                            .calledProcesses()
                            .get(entry.elementId());
                    called
                            .stream()
                            .filter(child -> (child.key() > entry.key()) && !taken.contains(child.key()))
                            .filter(child -> Objects.equals(calledId, child.bpmnProcessId()))
                            .findFirst()
                            .ifPresent(child -> {
                                taken.add(child.key());
                                calledInstances.put(entry.key(), child.key());
                            });
                });
        return calledInstances;
    }

    // the instance of an event sub-process starts at one of its start events, that of an embedded
    // sub-process at a start event no event sub-process has
    private static Optional<String> eventSubProcessOf(
        final List<HistoryEntry> entries,
        final Shape shape
    ) {
        return entries
                .stream()
                .filter(entry -> "START_EVENT".equals(entry.elementType()))
                .map(entry -> shape
                        .eventSubProcessStarts()
                        .get(entry.elementId()))
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static OffsetDateTime lastEndOf(final List<HistoryEntry> entries) {
        return entries
                .stream()
                .map(HistoryEntry::completedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(ZenBpmWorkflowViewer::offsetOf)
                .orElse(null);
    }

    private static boolean isEnded(final ProcessInstanceRef.State state) {
        return (state == ProcessInstanceRef.State.COMPLETED) || (state == ProcessInstanceRef.State.TERMINATED);
    }

    private static OffsetDateTime offsetOf(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    // an engine which is away fails every read, so its first failure is worth a WARN and the others
    // are not, until a read succeeded again
    private void readFailed(
        final String what,
        final RuntimeException failure
    ) {
        if (readsFail.compareAndSet(false, true)) {
            log.warn(
                    "ZenBPM[{}]: the viewer could not read {}; check that the engine at {} answers within {}; further failures of the viewer are logged at DEBUG until one of its reads succeeds again",
                    adapterId, what, ZenBpmAdapterConfiguration.propertyKey(adapterId, "rest-address"),
                    ZenBpmAdapterConfiguration.propertyKey(adapterId, "request-timeout"), failure);
        } else {
            log.debug("ZenBPM[{}]: the viewer could not read {}", adapterId, what, failure);
        }
    }

    private IllegalStateException notAsked(
        final String subject,
        final RuntimeException failure
    ) {
        return new IllegalStateException(
                "ZenBPM adapter '%s' could not ask the engine for %s: %s. Check that the engine at %s answers within %s."
                        .formatted(adapterId, subject, failure.getMessage(), ZenBpmAdapterConfiguration.propertyKey(
                                adapterId, "rest-address"),
                                ZenBpmAdapterConfiguration.propertyKey(adapterId,
                                        "request-timeout")), failure);
    }

    /**
     * What a model says about the history of its instances.
     *
     * @param multiInstanceElements The ids of its multi-instance elements
     * @param calledProcesses The process each call activity calls, by the id of the call activity,
     *            as the engine knows the process
     * @param eventSubProcessStarts The event sub-process each start event of one starts, by the id
     *            of the start event
     */
    private record Shape(
        Set<String> multiInstanceElements,
        Map<String, String> calledProcesses,
        Map<String, String> eventSubProcessStarts
    ) {
    }

    /**
     * One element of the history, with what orders it.
     *
     * @param createdAt When the engine entered it
     * @param key The key of its entry
     * @param history What is reported of it
     */
    private record Element(
        Instant createdAt,
        long key,
        WorkflowElementHistory history
    ) {
    }

    /**
     * What a read of a history found.
     *
     * @param elements The elements, in the order they were entered
     * @param endTime When the instance ended, <code>null</code> where it runs or nothing says
     */
    private record Read(
        List<WorkflowElementHistory> elements,
        OffsetDateTime endTime
    ) {
    }

}
