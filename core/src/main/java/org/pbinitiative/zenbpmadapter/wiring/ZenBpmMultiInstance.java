/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache.InstanceFacts;
import org.pbinitiative.zenbpmadapter.client.ZenBpmCommandRetry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef.ProcessType;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.MultiInstanceElement;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelReader;

import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import lombok.extern.slf4j.Slf4j;

/**
 * The iterations of multi-instance elements a task runs in, which the core binds to the parameters
 * annotated with <code>&#64;MultiInstanceElement</code>, <code>&#64;MultiInstanceIndex</code> and
 * <code>&#64;MultiInstanceTotal</code>. ZenBPM hands a job neither an index nor a total, so all three
 * are read from what the engine keeps of every activation of a multi-instance element, see decision
 * 54 in the repository's DECISIONS.md:
 * <ul>
 * <li>the engine runs an activation in an instance of its own, the body (process type
 * <code>multiInstance</code>), a child of the instance the element stands in. An iteration of a
 * multi-instance sub-process or call activity is a child instance of the body, and an iteration of
 * a multi-instance task is a job of the body;</li>
 * <li>the index is the position of the task's iteration among the iterations of its body, in the
 * order they were created, which is the order in which the engine hands out the items;</li>
 * <li>the element is the item: the variable named by <code>inputElement</code> of the iteration's
 * instance, or of the job of a multi-instance task;</li>
 * <li>the total is the size of the collection the engine iterates over. The engine evaluates it once,
 * when the element is entered, and keeps it in the history of the instance the element stands in,
 * at the entry of the token which waits there for the iterations, under the name of the input
 * element. The body holds a copy of the variables as well, but that copy is written by every
 * completion and push of the aggregate's values, so it may no longer be what the engine iterates
 * over. Where the entry cannot be read, the total is {@value #UNKNOWN_TOTAL}, as Camunda 8 reports
 * a total it cannot tell.</li>
 * </ul>
 * Which multi-instance element a body belongs to is told by the model of the version an instance
 * runs on: walking up from the task, every body met is the next multi-instance element around the
 * task, from the inside out. A task of a called process runs in the iterations of its caller as
 * well, so where the walk meets the instance a call activity started, it goes on in the caller, from
 * the call activity of the caller's model which calls the process; where the model holds several,
 * the one the caller's token waits at. The map is keyed by the ids of the multi-instance elements,
 * the outermost first.
 * <p>
 * The iterations of a body are kept, bounded like the instance cache, and read again where the
 * iteration asked about is not among them yet, as the next iteration of a sequential element. An
 * iteration keeps its place in the list of its body while the body runs, so a kept list stays
 * right. Nothing is read before a method asks for an iteration.
 */
@Slf4j
public final class ZenBpmMultiInstance {

    /**
     * The total of an iteration whose collection cannot be read, as Camunda 8 reports it.
     */
    public static final int UNKNOWN_TOTAL = -1;

    private final String adapterId;

    private final String workflowModuleId;

    private final ZenBpmRestClient rest;

    private final ZenBpmInstanceCache instances;

    /**
     * The bodies by key, the one used longest ago first. Guarded by <code>this</code>.
     */
    private final Map<Long, Body> bodies;

    /**
     * The multi-instance elements and call activities of each version, by definition key.
     */
    private final Map<Long, Shape> shapes = new ConcurrentHashMap<>();

    /**
     * The versions and called processes whose caller could not be told, each warned about once.
     */
    private final Set<String> untoldCallers = ConcurrentHashMap.newKeySet();

    /**
     * Keeps nothing yet. Contacts nothing.
     *
     * @param adapterId The adapter id, for the messages
     * @param workflowModuleId The workflow module, for the messages
     * @param rest The REST client of the adapter id
     * @param instances The instance cache of the adapter id
     * @param maxEntries How many bodies it keeps at most, <code>0</code> for none
     */
    public ZenBpmMultiInstance(
        final String adapterId,
        final String workflowModuleId,
        final ZenBpmRestClient rest,
        final ZenBpmInstanceCache instances,
        final int maxEntries
    ) {
        this.adapterId = adapterId;
        this.workflowModuleId = workflowModuleId;
        this.rest = rest;
        this.instances = instances;
        this.bodies = new LinkedHashMap<>(16, 0.75f, true) {

            @Override
            protected boolean removeEldestEntry(final Map.Entry<Long, Body> eldest) {
                return size() > maxEntries;
            }

        };
    }

    /**
     * The iterations a job runs in, read where they are asked for the first time and kept for every
     * later ask: the core asks once for every parameter which binds an iteration, see decision 54 in
     * the repository's DECISIONS.md.
     *
     * @param jobKey The key of the job
     * @param instanceKey The key of the instance the job waits in
     * @param elementId The id of the job's element
     * @param retry The repetition of the reads
     * @return What reads the iterations on the first call, as {@link #iterationsOf} does, and
     *         answers the same on every later one
     */
    public Supplier<Map<String, MultiInstanceValue>> iterationsOnFirstAsk(
        final long jobKey,
        final long instanceKey,
        final String elementId,
        final ZenBpmCommandRetry retry
    ) {
        return new Supplier<>() {

            private Map<String, MultiInstanceValue> iterations;

            @Override
            public Map<String, MultiInstanceValue> get() {
                if (iterations == null) {
                    iterations = iterationsOf(jobKey, instanceKey, elementId, retry);
                }
                return iterations;
            }

        };
    }

    /**
     * The iterations a job runs in.
     *
     * @param jobKey The key of the job
     * @param instanceKey The key of the instance the job waits in
     * @param elementId The id of the job's element
     * @param retry The repetition of the reads, bounded by the job's lock
     * @return The iterations by the id of their multi-instance element, the outermost first; empty
     *         where the job runs in none
     * @throws RuntimeException Where the engine could not be asked, or a node which lags behind does
     *             not know an instance or an iteration on the way
     */
    public Map<String, MultiInstanceValue> iterationsOf(
        final long jobKey,
        final long instanceKey,
        final String elementId,
        final ZenBpmCommandRetry retry
    ) {
        final var innermostFirst = new ArrayList<Map.Entry<String, MultiInstanceValue>>();
        var facts = factsOf(instanceKey, retry);
        var segment = new Segment(facts, elementId, false);
        Deque<MultiInstanceElement> chain = null;
        InstanceFacts below = null;
        while (true) {
            if (facts.processType() == ProcessType.MULTI_INSTANCE) {
                if (chain == null) {
                    chain = chainOf(segment, retry);
                }
                final var element = chain.pollLast();
                if (element == null) {
                    log.debug(
                            "ZenBPM[{}]: instance {} is the body of a multi-instance element which the model of version {} of process '{}' does not show around element '{}', so neither it nor what lies above it is reported",
                            adapterId, facts.key(), segment
                                    .firstInstance()
                                    .version(),
                            segment
                                    .firstInstance()
                                    .scopedProcessId(),
                            segment.elementId());
                    break;
                }
                final var iterationKey = below == null ? jobKey : below.key();
                innermostFirst.add(Map.entry(element.elementId(), iterationOf(facts, element, iterationKey,
                        below == null, retry)));
            }
            // only a root instance has no parent
            if (facts.parentKey() == null) {
                break;
            }
            final var parent = factsOf(facts.parentKey(), retry);
            if (facts.processType() == ProcessType.CALL_ACTIVITY) {
                // the caller's iterations come next, from the call activity which started it
                segment = new Segment(parent, facts.scopedProcessId(), true);
                chain = null;
            }
            below = facts;
            facts = parent;
        }
        final var iterations = new LinkedHashMap<String, MultiInstanceValue>();
        innermostFirst
                .reversed()
                .forEach(iteration -> iterations.put(iteration.getKey(), iteration.getValue()));
        return iterations;
    }

    private InstanceFacts factsOf(
        final long instanceKey,
        final ZenBpmCommandRetry retry
    ) {
        return retry
                .read("instance", () -> instances.facts(instanceKey), Optional::isEmpty)
                .orElseThrow(() -> new IllegalStateException("""
                        ZenBPM[%s]: instance %d, on the way up to the iterations a task runs in, is not known to \
                        the engine node asked, which may lag behind""".formatted(adapterId, instanceKey)));
    }

    // the multi-instance elements of a segment, the outermost first; empty where the model does not
    // tell them
    private Deque<MultiInstanceElement> chainOf(
        final Segment segment,
        final ZenBpmCommandRetry retry
    ) {
        final var shape = shapeOf(segment.firstInstance(), retry);
        if (!segment.calledProcess()) {
            return new ArrayDeque<>(shape.chainOf(segment.elementId()));
        }
        final var multiInstanceCall = segment
                .firstInstance()
                .processType() == ProcessType.MULTI_INSTANCE;
        var calls = shape.callsOf(segment.elementId(), multiInstanceCall);
        if (calls.size() > 1) {
            // the token which waits for the called instance stands at its call activity
            final var waitingAt = retry
                    .read("instance", () -> rest.activeElementsOf(segment
                            .firstInstance()
                            .key()), Optional::isEmpty)
                    .orElse(List.of());
            calls = calls
                    .stream()
                    .filter(waitingAt::contains)
                    .toList();
        }
        if (calls.size() != 1) {
            warnTheCallerIsNotTold(segment);
            return new ArrayDeque<>();
        }
        return new ArrayDeque<>(shape.chainOf(calls.getFirst()));
    }

    private void warnTheCallerIsNotTold(final Segment segment) {
        final var caller = segment.firstInstance();
        if (!untoldCallers.add("%d %s".formatted(caller.definitionKey(), segment.elementId()))) {
            return;
        }
        log.warn("""
                ZenBPM[{}]: an instance of process '{}' was started by version {} of process '{}', whose model \
                holds no single call activity which started it, so the methods serving tasks of '{}' are told \
                none of the caller's multi-instance iterations; call '{}' from one call activity of '{}' only, \
                or hand the item of the caller's iteration over as a @TaskParam""", adapterId, segment.elementId(),
                caller.version(), caller.scopedProcessId(), segment.elementId(), segment.elementId(), caller
                        .scopedProcessId());
    }

    private Shape shapeOf(
        final InstanceFacts facts,
        final ZenBpmCommandRetry retry
    ) {
        final var definitionKey = facts.definitionKey();
        final var known = shapes.get(definitionKey);
        if (known != null) {
            return known;
        }
        final var bpmn = retry.read("read of process definition %d".formatted(definitionKey), () -> rest
                .processDefinition(definitionKey)
                .bpmnData(), read -> false);
        Shape shape;
        try {
            shape = ZenBpmModelReader
                    .read(workflowModuleId, "version %d of process '%s'".formatted(facts.version(), facts
                            .scopedProcessId()), new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)))
                    .stream()
                    .findFirst()
                    .map(Shape::of)
                    .orElse(Shape.NONE);
        } catch (final BpmnParseException e) {
            log.debug(
                    "ZenBPM[{}]: version {} of process '{}' cannot be read, so it shows no multi-instance element: {}",
                    adapterId, facts.version(), facts.scopedProcessId(), e.getMessage());
            shape = Shape.NONE;
        }
        shapes.put(definitionKey, shape);
        return shape;
    }

    private MultiInstanceValue iterationOf(
        final InstanceFacts body,
        final MultiInstanceElement element,
        final long iterationKey,
        final boolean iterationIsAJob,
        final ZenBpmCommandRetry retry
    ) {
        var known = keptBody(body.key());
        if ((known == null) || (known.positionOf(iterationKey) < 0)) {
            final var total = known == null ? totalOf(body, element, retry) : known.total();
            final var iterations = retry.read("iterations of multi-instance element '%s'".formatted(element
                    .elementId()), () -> iterationsOf(body.key(), element, iterationIsAJob),
                    read -> Body
                            .positionOf(read, iterationKey) < 0);
            known = new Body(total, iterations);
            keep(body.key(), known);
        }
        final var position = known.positionOf(iterationKey);
        if (position < 0) {
            throw new IllegalStateException("""
                    ZenBPM[%s]: the iteration %d of multi-instance element '%s' is not among the iterations of \
                    its body, instance %d, which the engine node asked lists; it may lag behind"""
                    .formatted(adapterId, iterationKey, element.elementId(), body.key()));
        }
        return new MultiInstanceValue(known
                .iterations()
                .get(position)
                .item(), position, known.total());
    }

    // the order in which the engine created the iterations, which is the order of their items: a
    // body creates one iteration after the other, each with the item at the number of iterations
    // created before it
    private List<Iteration> iterationsOf(
        final long bodyKey,
        final MultiInstanceElement element,
        final boolean iterationIsAJob
    ) {
        final var iterations = iterationIsAJob
                ? rest
                        .jobsOf(bodyKey)
                        .stream()
                        .map(job -> new Iteration(job.key(), job.createdAt(), itemOf(job.inputVariables(), element)))
                : rest
                        .children(bodyKey)
                        .stream()
                        .map(child -> new Iteration(child.key(), child.createdAt(), itemOf(child.variables(),
                                element)));
        return iterations
                .sorted(Comparator
                        .comparing(Iteration::createdAt)
                        .thenComparing(Iteration::key))
                .toList();
    }

    // the collection as the engine evaluated it when the element was entered, which no write of a
    // variable changes; the body's copy of the variables does not stay so, see decision 54 in the
    // repository's DECISIONS.md
    private int totalOf(
        final InstanceFacts body,
        final MultiInstanceElement element,
        final ZenBpmCommandRetry retry
    ) {
        final long standsIn = body.parentKey();
        final var entries = retry
                .read("instance", () -> rest.tokensAt(standsIn, element.elementId()), read -> read
                        .map(List::isEmpty)
                        .orElse(true))
                .orElse(List.of());
        final var collections = retry.read("history of instance %d".formatted(standsIn), () -> collectionsOf(
                standsIn, entries, element), read -> read.size() < entries.size());
        // two activations of the element at once can only be told apart where they iterate alike
        final var totals = collections
                .values()
                .stream()
                .map(collection -> collection instanceof List<?> items ? items.size() : UNKNOWN_TOTAL)
                .distinct()
                .toList();
        if ((totals.size() == 1) && (collections.size() == entries.size())) {
            return totals.getFirst();
        }
        log.debug(
                "ZenBPM[{}]: instance {} shows no single collection of multi-instance element '{}' in its history, so the total of its iterations is reported as {}",
                adapterId, standsIn, element.elementId(), UNKNOWN_TOTAL);
        return UNKNOWN_TOTAL;
    }

    // the collections of the entries named, by entry: the engine keeps an element's collection
    // under the name of its input element, and under "" where it names none
    private Map<Long, Object> collectionsOf(
        final long instanceKey,
        final List<Long> entries,
        final MultiInstanceElement element
    ) {
        final var name = Objects.requireNonNullElse(element.inputElement(), "");
        final var collections = new HashMap<Long, Object>();
        for (var page = 1; collections.size() < entries.size(); ++page) {
            final var read = rest.history(instanceKey, page);
            read
                    .items()
                    .stream()
                    .filter(entry -> entries.contains(entry.key()))
                    .forEach(entry -> collections.put(entry.key(), entry
                            .inputVariables()
                            .get(name)));
            if (read.last()) {
                break;
            }
        }
        return collections;
    }

    private synchronized Body keptBody(final long bodyKey) {
        return bodies.get(bodyKey);
    }

    private synchronized void keep(
        final long bodyKey,
        final Body body
    ) {
        bodies.put(bodyKey, body);
    }

    private static Object itemOf(
        final Map<String, Object> variables,
        final MultiInstanceElement element
    ) {
        // an element without an input element names no variable, so it hands no item
        return variables.get(element.inputElement());
    }

    /**
     * A part of the walk which runs in one process: from the instance of the job, or from the
     * instance a call activity stands in, up to the root or to the next called instance.
     *
     * @param firstInstance The first instance of the part, whose version tells the model
     * @param elementId The element the job waits at; for a caller, the process it called, as the
     *            engine knows it
     * @param calledProcess Whether the part is a caller's
     */
    private record Segment(
        InstanceFacts firstInstance,
        String elementId,
        boolean calledProcess
    ) {
    }

    /**
     * What a version tells about its multi-instance elements and its call activities.
     *
     * @param chains The multi-instance elements each element runs in, the outermost first
     * @param calls The call activities, each with the process it calls
     */
    private record Shape(
        Map<String, List<MultiInstanceElement>> chains,
        List<ZenBpmBpmnModel.CallActivity> calls
    ) {

        private static final Shape NONE = new Shape(Map.of(), List.of());

        private static Shape of(final ZenBpmBpmnModel model) {
            final var chains = new HashMap<String, List<MultiInstanceElement>>();
            model
                    .enclosingMultiInstances()
                    .keySet()
                    .forEach(elementId -> chains.put(elementId, model.multiInstanceChainOf(elementId)));
            model
                    .multiInstanceElements()
                    .forEach(element -> chains.put(element.elementId(), model.multiInstanceChainOf(element
                            .elementId())));
            return new Shape(Map.copyOf(chains), model.callActivities());
        }

        private List<MultiInstanceElement> chainOf(final String elementId) {
            return chains.getOrDefault(elementId, List.of());
        }

        // a call activity which iterates leaves its called instances in a body, one which does not
        // directly in the instance it stands in
        private List<String> callsOf(
            final String calledProcessId,
            final boolean multiInstance
        ) {
            return calls
                    .stream()
                    .filter(call -> calledProcessId.equals(call.calledProcessId()))
                    .map(ZenBpmBpmnModel.CallActivity::elementId)
                    .filter(elementId -> chainOf(elementId)
                            .stream()
                            .anyMatch(element -> element
                                    .elementId()
                                    .equals(elementId)) == multiInstance)
                    .toList();
        }

    }

    /**
     * What is kept of a body.
     *
     * @param total The size of its collection, {@value ZenBpmMultiInstance#UNKNOWN_TOTAL} where it
     *            cannot be read
     * @param iterations Its iterations in the order they were created
     */
    private record Body(
        int total,
        List<Iteration> iterations
    ) {

        private int positionOf(final long iterationKey) {
            return positionOf(iterations, iterationKey);
        }

        private static int positionOf(
            final List<Iteration> iterations,
            final long iterationKey
        ) {
            for (var position = 0; position < iterations.size(); ++position) {
                if (iterations
                        .get(position)
                        .key() == iterationKey) {
                    return position;
                }
            }
            return -1;
        }

    }

    /**
     * One iteration of a body.
     *
     * @param key The key of its instance, or of its job for a multi-instance task
     * @param createdAt When the engine created it
     * @param item Its item, <code>null</code> where the element names no input element
     */
    private record Iteration(
        long key,
        Instant createdAt,
        Object item
    ) {
    }

}
