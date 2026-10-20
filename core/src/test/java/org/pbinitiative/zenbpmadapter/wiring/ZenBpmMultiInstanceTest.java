/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmCommandRetry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The iterations a task runs in, read from what the engine keeps of a multi-instance element. The
 * engine is a fake which holds instances in the shape the pinned engine gives them: an activation
 * of a multi-instance element is a body of the process type <code>multiInstance</code> under the
 * instance the element stands in, holding a copy of that instance's variables; an iteration of a
 * sub-process or a call activity is a child instance of the body which holds the item, and an
 * iteration of a task is a job of the body which carries the item; every child takes the business
 * key and the definition of its parent, and the engine lists children and jobs by key, not in the
 * order they were created.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmMultiInstanceTest {

    private static final long ORDER = 4503599627370498L;

    private static final long LABELLING = 4503599627370499L;

    private static final long ROOT = 100L;

    private static final long PARCELS = 200L;

    private static final long WEIGHING = 300L;

    private static final long STAMPING = 400L;

    private static final long LABELS = 500L;

    private static final long SCANNING = 600L;

    private static final long PRINTING = 700L;

    private static final String COLLECTION = "\"orderId\": \"order-1\", \"parcels\": [\"a\", \"b\", \"c\"]";

    private final FakeEngine engine = FakeEngine.started();

    private final ZenBpmRestClient rest = restClient(engine);

    /**
     * The jobs of each instance, which <code>GET /v1/jobs</code> answers by its
     * <code>processInstanceKey</code>.
     */
    private final Map<Long, List<String>> jobs = new ConcurrentHashMap<>();

    private final ZenBpmMultiInstance multiInstance = new ZenBpmMultiInstance("orders", "shop", rest, new ZenBpmInstanceCache(rest, 100), 100);

    @AfterEach
    void stopTheEngine() {
        rest.close();
        engine.close();
    }

    @Test
    @DisplayName("A task inside a parallel multi-instance sub-process is told the item of its iteration, its index counted from 0 and the size of the collection, keyed by the sub-process")
    void aTaskInsideAParallelSubProcessIsToldItsIteration() {
        holdsTheParcelsBody();

        final var iterations = multiInstance.iterationsOf(42L, 202L, "Pick", retry());

        assertEquals(Map.of("Parcels", new MultiInstanceValue("b", 1, 3)), iterations);
    }

    @Test
    @DisplayName("The iterations are counted in the order the engine created them, and two of the same millisecond by their keys, whatever order the engine lists them in")
    void theIterationsAreCountedInTheOrderTheyWereCreated() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(PARCELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        holds(203L, "subprocess", PARCELS, ORDER, "shop__Order", "\"parcel\": \"c\"");
        hasChildren(PARCELS, child(203L, "2026-10-17T14:30:25.130Z", "subprocess", PARCELS, "\"parcel\": \"c\""),
                child(202L, "2026-10-17T14:30:25.123Z", "subprocess", PARCELS, "\"parcel\": \"b\""), child(201L,
                        "2026-10-17T14:30:25.123Z", "subprocess", PARCELS, "\"parcel\": \"a\""));

        assertEquals(new MultiInstanceValue("c", 2, 3), multiInstance
                .iterationsOf(42L, 203L, "Pick", retry())
                .get("Parcels"));
    }

    @Test
    @DisplayName("A second task of the same body asks the engine nothing but its own instance: the model, the body and the iterations are kept")
    void aSecondTaskOfTheSameBodyReadsNothingAgain() {
        holdsTheParcelsBody();
        holds(203L, "subprocess", PARCELS, ORDER, "shop__Order", "\"parcel\": \"c\"");

        final var first = multiInstance.iterationsOf(42L, 202L, "Pick", retry());
        final var second = multiInstance.iterationsOf(43L, 203L, "Pick", retry());

        assertAll(
                () -> assertEquals(Map.of("Parcels", new MultiInstanceValue("b", 1, 3)), first),
                () -> assertEquals(Map.of("Parcels", new MultiInstanceValue("c", 2, 3)), second),
                () -> assertEquals(1, requestsFor("/v1/process-definitions/%d".formatted(ORDER))),
                // once by the walk up, whose instance cache keeps no collection, and once for the
                // copy of the collection
                () -> assertEquals(2, requestsFor("/v1/process-instances/%d".formatted(PARCELS))),
                () -> assertEquals(1, requestsFor("/v1/process-instances/%d/child-processes".formatted(PARCELS))));
    }

    @Test
    @DisplayName("An iteration of a sequential multi-instance task is a job of its body, and one created after the iterations were kept is found by reading them again")
    void aSequentialTaskFindsAnIterationCreatedLater() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "completed", "\"parcel\": \"a\""));

        final var first = multiInstance.iterationsOf(301L, WEIGHING, "Weigh", retry());
        hasJobs(WEIGHING, job(302L, "2026-10-17T14:30:26.123Z", "active", "\"parcel\": \"b\""), job(301L,
                "2026-10-17T14:30:25.123Z", "completed", "\"parcel\": \"a\""));
        final var second = multiInstance.iterationsOf(302L, WEIGHING, "Weigh", retry());

        assertAll(
                () -> assertEquals(Map.of("Weigh", new MultiInstanceValue("a", 0, 3)), first),
                () -> assertEquals(Map.of("Weigh", new MultiInstanceValue("b", 1, 3)), second),
                () -> assertEquals(2, requestsFor("/v1/jobs")),
                () -> assertEquals(2, requestsFor("/v1/process-instances/%d".formatted(WEIGHING))));
    }

    @Test
    @DisplayName("A task inside two multi-instance elements is told both iterations, the outermost first")
    void nestedIterationsAreToldOutermostFirst() {
        holdsTheParcelsBody();
        // the item of the iteration and the collection the modeller mapped into it, which the inner
        // body copies
        final var mappedIn = "\"parcel\": \"b\", \"boxes\": [\"x\", \"y\"]";
        holds(202L, "subprocess", PARCELS, ORDER, "shop__Order", mappedIn);
        holds(SCANNING, "multiInstance", 202L, ORDER, "shop__Order", mappedIn);
        hasJobs(SCANNING, job(602L, "2026-10-17T14:30:27.123Z", "active", "\"box\": \"y\""), job(601L,
                "2026-10-17T14:30:26.123Z", "active", "\"box\": \"x\""));

        final var iterations = multiInstance.iterationsOf(602L, SCANNING, "Scan", retry());

        assertAll(
                () -> assertEquals(List.of("Parcels", "Scan"), List.copyOf(iterations.keySet())),
                () -> assertEquals(new MultiInstanceValue("b", 1, 3), iterations.get("Parcels")),
                () -> assertEquals(new MultiInstanceValue("y", 1, 2), iterations.get("Scan")));
    }

    @Test
    @DisplayName("The total of a collection written as an expression other than a bare variable is -1, as Camunda 8 reports a total it cannot tell, and the body is not read for it")
    void aCollectionWrittenAsAnExpressionHasNoTotal() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(STAMPING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(STAMPING, job(401L, "2026-10-17T14:30:25.123Z", "active", "\"parcel\": \"A\""));

        final var iterations = multiInstance.iterationsOf(401L, STAMPING, "Stamp", retry());

        assertAll(
                () -> assertEquals(Map.of("Stamp", new MultiInstanceValue("A", 0, ZenBpmMultiInstance.UNKNOWN_TOTAL)),
                        iterations),
                // by the walk up alone
                () -> assertEquals(1, requestsFor("/v1/process-instances/%d".formatted(STAMPING))));
    }

    @Test
    @DisplayName("The total of a body which holds no list under the name of the collection is -1")
    void aBodyWhichHoldsNoListHasNoTotal() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", "\"parcels\": \"a, b, c\"");
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "active", "\"parcel\": \"a\""));

        assertEquals(Map.of("Weigh", new MultiInstanceValue("a", 0, ZenBpmMultiInstance.UNKNOWN_TOTAL)),
                multiInstance.iterationsOf(301L, WEIGHING, "Weigh", retry()));
    }

    @Test
    @DisplayName("A task of a process an iteration of a multi-instance call activity started is told the caller's iteration first, then its own")
    void aTaskOfACalledProcessIsToldTheIterationOfItsCaller() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(LABELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        final var calledVariables = "\"parcel\": \"b\", \"copies\": [1, 2]";
        holds(502L, "callActivity", LABELS, LABELLING, "shop__Labelling", calledVariables);
        hasChildren(LABELS, child(503L, "2026-10-17T14:30:25.127Z", "callActivity", LABELS, "\"parcel\": \"c\""),
                child(502L, "2026-10-17T14:30:25.125Z", "callActivity", LABELS, calledVariables), child(501L,
                        "2026-10-17T14:30:25.123Z", "callActivity", LABELS, "\"parcel\": \"a\""));
        holds(PRINTING, "multiInstance", 502L, LABELLING, "shop__Labelling", calledVariables);
        hasJobs(PRINTING, job(701L, "2026-10-17T14:30:26.123Z", "active", "\"copy\": 1"));

        final var iterations = multiInstance.iterationsOf(701L, PRINTING, "Print", retry());

        assertAll(
                () -> assertEquals(List.of("Label", "Print"), List.copyOf(iterations.keySet())),
                () -> assertEquals(new MultiInstanceValue("b", 1, 3), iterations.get("Label")),
                () -> assertEquals(new MultiInstanceValue(1L, 0, 2), iterations.get("Print")));
    }

    @Test
    @DisplayName("A caller whose model calls the process from two call activities which may have started the instance is not asked about, and the called process's own iterations are told")
    void aCallerWithTwoCallsOfTheProcessIsNotAskedAbout() {
        holdsTheModel(ORDER, modelCalling(List.of("Label", "Relabel"), List.of()));
        holdsTheModel(LABELLING, modelOfLabelling());
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(LABELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        final var calledVariables = "\"parcel\": \"b\", \"copies\": [1, 2]";
        holds(502L, "callActivity", LABELS, LABELLING, "shop__Labelling", calledVariables);
        holds(PRINTING, "multiInstance", 502L, LABELLING, "shop__Labelling", calledVariables);
        hasJobs(PRINTING, job(701L, "2026-10-17T14:30:26.123Z", "active", "\"copy\": 1"));

        final var iterations = multiInstance.iterationsOf(701L, PRINTING, "Print", retry());

        assertAll(
                () -> assertEquals(Map.of("Print", new MultiInstanceValue(1L, 0, 2)), iterations),
                () -> assertEquals(0, requestsFor("/v1/process-instances/%d/child-processes".formatted(LABELS))));
    }

    @Test
    @DisplayName("A caller whose model calls the process from a call activity which iterates and from one which does not is told the iteration of the one which started the instance")
    void aCallerWhichAlsoCallsOnceIsToldTheIteratingCall() {
        holdsTheModel(ORDER, modelCalling(List.of("Label"), List.of("LabelOnce")));
        holdsTheModel(LABELLING, modelOfLabelling());
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(LABELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        final var calledVariables = "\"parcel\": \"a\", \"copies\": [1]";
        holds(501L, "callActivity", LABELS, LABELLING, "shop__Labelling", calledVariables);
        hasChildren(LABELS, child(501L, "2026-10-17T14:30:25.123Z", "callActivity", LABELS, calledVariables));
        holds(PRINTING, "multiInstance", 501L, LABELLING, "shop__Labelling", calledVariables);
        hasJobs(PRINTING, job(701L, "2026-10-17T14:30:26.123Z", "active", "\"copy\": 1"));

        assertEquals(new MultiInstanceValue("a", 0, 3), multiInstance
                .iterationsOf(701L, PRINTING, "Print", retry())
                .get("Label"));
    }

    @Test
    @DisplayName("A task of a process which a call activity started inside an iteration of the caller's multi-instance sub-process is told that iteration first, then its own")
    void aCallInsideAnIterationOfTheCallerIsToldThatIteration() {
        holdsTheParcelsBody();
        final var calledVariables = "\"parcel\": \"b\", \"copies\": [1, 2]";
        holds(520L, "callActivity", 202L, LABELLING, "shop__Labelling", calledVariables);
        holds(PRINTING, "multiInstance", 520L, LABELLING, "shop__Labelling", calledVariables);
        hasJobs(PRINTING, job(701L, "2026-10-17T14:30:26.123Z", "active", "\"copy\": 1"));

        final var iterations = multiInstance.iterationsOf(701L, PRINTING, "Print", retry());

        assertAll(
                () -> assertEquals(List.of("Parcels", "Print"), List.copyOf(iterations.keySet())),
                () -> assertEquals(new MultiInstanceValue("b", 1, 3), iterations.get("Parcels")),
                () -> assertEquals(new MultiInstanceValue(1L, 0, 2), iterations.get("Print")));
    }

    @Test
    @DisplayName("A called process which no call activity iterates over is told its own iterations, and its caller is walked up without a read of its iterations")
    void aCallerWhichDoesNotIterateAddsNoIteration() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        final var calledVariables = "\"copies\": [1, 2]";
        holds(510L, "callActivity", ROOT, LABELLING, "shop__Labelling", calledVariables);
        holds(PRINTING, "multiInstance", 510L, LABELLING, "shop__Labelling", calledVariables);
        hasJobs(PRINTING, job(701L, "2026-10-17T14:30:26.123Z", "active", "\"copy\": 1"));

        assertEquals(Map.of("Print", new MultiInstanceValue(1L, 0, 2)), multiInstance.iterationsOf(701L, PRINTING,
                "Print", retry()));
    }

    @Test
    @DisplayName("A task outside every multi-instance element is told no iteration, and neither the model nor a body is read for it")
    void aTaskOutsideEveryIterationIsToldNone() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);

        final var iterations = multiInstance.iterationsOf(42L, ROOT, "Ship", retry());

        assertAll(
                () -> assertEquals(Map.of(), iterations),
                () -> assertEquals(0, requestsFor("/v1/process-definitions/%d".formatted(ORDER))));
    }

    @Test
    @DisplayName("A body which the model does not show around the task ends the walk, and nothing above it is told")
    void aBodyTheModelDoesNotShowEndsTheWalk() {
        holdsTheParcelsBody();

        assertEquals(Map.of(), multiInstance.iterationsOf(42L, 202L, "NotInTheModel", retry()));
    }

    @Test
    @DisplayName("A version whose model cannot be read shows no multi-instance element, so its task is told no iteration")
    void aModelWhichCannotBeReadShowsNoIteration() {
        holdsTheParcelsBody();
        holdsTheModel(ORDER, "no model at all");

        assertEquals(Map.of(), multiInstance.iterationsOf(42L, 202L, "Pick", retry()));
    }

    @Test
    @DisplayName("An element which names no input element hands no item, so the element of its iterations is null")
    void anElementWithoutAnInputElementHasNoItem() {
        holdsTheModel(ORDER, modelWithoutAnItem());
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "active", ""));

        assertEquals(Map.of("Weigh", new MultiInstanceValue(null, 0, 3)), multiInstance.iterationsOf(301L, WEIGHING,
                "Weigh", retry()));
    }

    @Test
    @DisplayName("An iteration which the engine node asked does not list yet, as one which lags behind, is asked for again and then is a failure which names it")
    void anIterationTheEngineDoesNotListIsAFailure() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(PARCELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        holds(202L, "subprocess", PARCELS, ORDER, "shop__Order", "\"parcel\": \"b\"");
        hasChildren(PARCELS, child(201L, "2026-10-17T14:30:25.123Z", "subprocess", PARCELS, "\"parcel\": \"a\""));

        final var failure = assertThrows(IllegalStateException.class, () -> multiInstance.iterationsOf(42L, 202L,
                "Pick", retry()));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .contains("iteration 202 of multi-instance element 'Parcels'"), failure::getMessage),
                () -> assertTrue(requestsFor("/v1/process-instances/%d/child-processes".formatted(PARCELS)) > 1));
    }

    @Test
    @DisplayName("An instance on the way up which the engine node asked does not know is a failure which names it")
    void anInstanceTheEngineDoesNotKnowIsAFailure() {
        holdsTheModels();
        holds(202L, "subprocess", PARCELS, ORDER, "shop__Order", "\"parcel\": \"b\"");
        engine.answers("GET", "/v1/process-instances/%d".formatted(PARCELS), 404, """
                {"code":"NOT_FOUND","message":"process instance not found"}""");

        final var failure = assertThrows(IllegalStateException.class, () -> multiInstance.iterationsOf(42L, 202L,
                "Pick", retry()));

        assertTrue(failure
                .getMessage()
                .contains("instance %d".formatted(PARCELS)), failure::getMessage);
    }

    @Test
    @DisplayName("Where no body is to be kept, every task reads the iterations of its body anew")
    void aCacheOfNoEntriesReadsTheBodyEveryTime() {
        holdsTheParcelsBody();
        final var keepingNothing = new ZenBpmMultiInstance("orders", "shop", rest, new ZenBpmInstanceCache(rest, 100), 0);

        keepingNothing.iterationsOf(42L, 202L, "Pick", retry());
        keepingNothing.iterationsOf(42L, 202L, "Pick", retry());

        assertEquals(2, requestsFor("/v1/process-instances/%d/child-processes".formatted(PARCELS)));
    }

    private void holdsTheParcelsBody() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(PARCELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        holds(202L, "subprocess", PARCELS, ORDER, "shop__Order", "\"parcel\": \"b\"");
        hasChildren(PARCELS, child(203L, "2026-10-17T14:30:25.127Z", "subprocess", PARCELS, "\"parcel\": \"c\""),
                child(202L, "2026-10-17T14:30:25.125Z", "subprocess", PARCELS, "\"parcel\": \"b\""), child(201L,
                        "2026-10-17T14:30:25.123Z", "subprocess", PARCELS, "\"parcel\": \"a\""));
    }

    private void holdsTheModels() {
        holdsTheModel(ORDER, modelCalling(List.of("Label"), List.of()));
        holdsTheModel(LABELLING, modelOfLabelling());
    }

    private void holds(
        final long key,
        final String processType,
        final Long parentKey,
        final long definitionKey,
        final String scopedProcessId,
        final String variables
    ) {
        engine.answers("GET", "/v1/process-instances/%d".formatted(key), 200, """
                {"key": %d, "processDefinitionKey": %d, "bpmnProcessId": "%s", "version": 3, \
                "businessKey": "order-1", "createdAt": "2026-10-17T14:30:25.123Z", "state": "active", %s \
                "processType": "%s", "variables": {%s}, "activeElementInstances": []}"""
                .formatted(key, definitionKey, scopedProcessId, parentKey == null ? ""
                        : "\"parentProcessInstanceKey\": %d,".formatted(parentKey), processType, variables));
    }

    private void hasChildren(
        final long key,
        final String... children
    ) {
        engine.answers("GET", "/v1/process-instances/%d/child-processes".formatted(key), 200, """
                {"partitions": [{"partition": 1, "totalCount": %d, "items": [%s]}], "totalCount": %d}"""
                .formatted(children.length, String.join(",", children), children.length));
    }

    private void hasJobs(
        final long instanceKey,
        final String... jobsOfTheInstance
    ) {
        jobs.put(instanceKey, List.of(jobsOfTheInstance));
        engine.answers("GET", "/v1/jobs", exchange -> {
            final var query = exchange
                    .getRequestURI()
                    .getRawQuery();
            final var listed = jobs
                    .entrySet()
                    .stream()
                    .filter(entry -> query.contains("processInstanceKey=%d".formatted(entry.getKey())))
                    .flatMap(entry -> entry
                            .getValue()
                            .stream()
                            .map(job -> job.formatted(entry.getKey())))
                    .toList();
            FakeEngine.respond(exchange, 200, """
                    {"partitions": [{"partition": 1, "totalCount": %d, "items": [%s]}], "totalCount": %d}"""
                    .formatted(listed.size(), String.join(",", listed), listed.size()));
        });
    }

    private void holdsTheModel(
        final long definitionKey,
        final String bpmn
    ) {
        try {
            engine.answers("GET", "/v1/process-definitions/%d".formatted(definitionKey), 200, """
                    {"key": %d, "version": 3, "bpmnProcessId": "shop__Order", "bpmnData": %s}"""
                    .formatted(definitionKey, new ObjectMapper().writeValueAsString(bpmn)));
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private long requestsFor(final String path) {
        return engine
                .requests()
                .stream()
                .filter(request -> request
                        .path()
                        .equals(path))
                .count();
    }

    private static ZenBpmCommandRetry retry() {
        return new ZenBpmCommandRetry("orders", 42L, "ship", () -> Instant
                .now()
                .plus(Duration.ofMinutes(1)), () -> false);
    }

    private static String child(
        final long key,
        final String createdAt,
        final String processType,
        final long parentKey,
        final String variables
    ) {
        return """
                {"key": %d, "processDefinitionKey": %d, "bpmnProcessId": "shop__Order", "version": 3, \
                "businessKey": "order-1", "createdAt": "%s", "state": "active", "parentProcessInstanceKey": %d, \
                "processType": "%s", "variables": {%s}}""".formatted(key, ORDER, createdAt, parentKey, processType,
                variables);
    }

    // %d is left for the key of the instance the job waits in
    private static String job(
        final long key,
        final String createdAt,
        final String state,
        final String inputVariables
    ) {
        return """
                {"key": %d, "elementId": "MultiInstanceTask", "type": "shop__Order__task", "elementType": "SERVICE_TASK", \
                "processInstanceKey": %%d, "elementInstanceKey": %d, "state": "%s", "createdAt": "%s", \
                "inputVariables": {%s}}"""
                .formatted(key, key + 1000, state, createdAt, inputVariables);
    }

    // the call activities which iterate over the parcels, and those which call once
    private static String modelCalling(
        final List<String> iteratingCallActivityIds,
        final List<String> onceCallActivityIds
    ) {
        final var calls = new ArrayList<String>();
        for (final var callActivityId : onceCallActivityIds) {
            calls.add(
                    """
                            <bpmn:callActivity id="%s">
                              <bpmn:extensionElements><zenbpm:calledElement processId="shop__Labelling" /></bpmn:extensionElements>
                            </bpmn:callActivity>"""
                            .formatted(callActivityId));
        }
        for (final var callActivityId : iteratingCallActivityIds) {
            calls.add(
                    """
                            <bpmn:callActivity id="%s">
                              <bpmn:extensionElements><zenbpm:calledElement processId="shop__Labelling" /></bpmn:extensionElements>
                              <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                                <zenbpm:loopCharacteristics inputCollection="=parcels" inputElement="parcel" />
                              </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                            </bpmn:callActivity>"""
                            .formatted(callActivityId));
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="Definitions_Order" \
                targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="shop__Order" isExecutable="true">
                    <bpmn:serviceTask id="Ship" />
                    <bpmn:subProcess id="Parcels">
                      <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="= parcels " inputElement="parcel" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                      <bpmn:serviceTask id="Pick" />
                      <bpmn:callActivity id="LabelInside">
                        <bpmn:extensionElements><zenbpm:calledElement processId="shop__Labelling" /></bpmn:extensionElements>
                      </bpmn:callActivity>
                      <bpmn:serviceTask id="Scan">
                        <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                          <zenbpm:loopCharacteristics inputCollection="=boxes" inputElement="box" />
                        </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                      </bpmn:serviceTask>
                    </bpmn:subProcess>
                    <bpmn:serviceTask id="Weigh">
                      <bpmn:multiInstanceLoopCharacteristics isSequential="true"><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="=parcels" inputElement="parcel" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:serviceTask>
                    <bpmn:serviceTask id="Stamp">
                      <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="=for p in parcels return upper case(p)" \
                inputElement="parcel" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:serviceTask>
                    %s
                  </bpmn:process>
                </bpmn:definitions>
                """
                .formatted(String.join("\n", calls));
    }

    private static String modelOfLabelling() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="Definitions_Labelling" \
                targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="shop__Labelling" isExecutable="true">
                    <bpmn:serviceTask id="Print">
                      <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="=copies" inputElement="copy" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:serviceTask>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String modelWithoutAnItem() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="Definitions_Order" \
                targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="shop__Order" isExecutable="true">
                    <bpmn:serviceTask id="Weigh">
                      <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="=parcels" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:serviceTask>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static ZenBpmRestClient restClient(final FakeEngine engine) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        return new ZenBpmRestClient("orders", configuration);
    }

}
