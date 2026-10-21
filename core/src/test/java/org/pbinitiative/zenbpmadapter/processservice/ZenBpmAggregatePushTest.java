/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.TestWiring;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.MessageStartLookup;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The push of a changed aggregate, both phases. A push of the workflow writes its root, found by
 * the key of the start row, by the business key or, under <code>message-start-lookup: scan</code>,
 * by the id variable, and then every instance below it which runs, each before the instances below
 * it are read, and it does not enter an instance a call activity started for another aggregate. A
 * push into the scope of a task writes the instance the task's job waits in and nothing else. The
 * engine is a fake which answers what each test tells it to and writes down what it was asked. The
 * module <code>shop</code> is scoped with a prefix, so its process is <code>shop__Order</code> at
 * the engine.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmAggregatePushTest {

    private static final long ROOT = 4503599627370501L;

    private static final long PACKING = 4503599627370510L;

    private static final long PACKED = 4503599627370511L;

    private static final long CALLED_ON_THE_SAME_AGGREGATE = 4503599627370512L;

    private static final long CALLED_ON_ANOTHER_AGGREGATE = 4503599627370513L;

    private static final long ITERATION = 4503599627370520L;

    private static final long BELOW_THE_OTHER_AGGREGATE = 4503599627370530L;

    private static final long JOB = 4503599627370600L;

    private static final String SEARCH_PATH = "/v1/process-instances";

    private static final String VALUES = "{\"variables\": {\"amount\": 1000, \"orderId\": \"order-1\"}}";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Object VALUES_AS_JSON = readTree(VALUES);

    private final FakeEngine engine = FakeEngine.started();

    private final List<ZenBpmClientFactory> factories = new ArrayList<>();

    @AfterEach
    void stopTheEngine() {
        factories.forEach(ZenBpmClientFactory::close);
        engine.close();
    }

    @Test
    @DisplayName("A push of the workflow writes the instance of the start row's key, then every instance below it which runs, at any depth, each before the instances below it are read; it passes over one which ended and does not enter one a call activity started for another aggregate")
    void aGlobalPushWritesTheRootAndEveryRunningInstanceBelowIt(final CapturedOutput output) throws Exception {
        engine.answers("GET", instancePath(ROOT), 200,
                instance(ROOT, "shop__Order", "active", "default", null, "order-1",
                        "order-1"));
        aTreeOfInstancesRunsBelowTheRoot();
        everyWriteIsTaken();

        handler().phaseTwo(push(String.valueOf(ROOT), null));

        assertAll(
                () -> assertEquals(List.of(ROOT, PACKING, CALLED_ON_THE_SAME_AGGREGATE, ITERATION), written()),
                () -> assertEquals(List.of("GET "
                        + instancePath(ROOT),
                        "PATCH "
                                + variablesPath(ROOT),
                        "GET "
                                + childrenPath(ROOT),
                        "PATCH "
                                + variablesPath(PACKING),
                        "PATCH "
                                + variablesPath(
                                        CALLED_ON_THE_SAME_AGGREGATE),
                        "GET "
                                + childrenPath(PACKING),
                        "PATCH "
                                + variablesPath(
                                        ITERATION),
                        "GET "
                                + childrenPath(CALLED_ON_THE_SAME_AGGREGATE),
                        "GET "
                                + childrenPath(ITERATION)),
                        requests()),
                () -> assertTrue(bodiesOfTheWrites()
                        .stream()
                        .allMatch(VALUES_AS_JSON::equals), bodiesOfTheWrites()::toString),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "ZenBPM[orders]: pushed the changed aggregate 'order-1' into workflow %d and the instances %s below it"
                                        .formatted(ROOT, List.of(PACKING, CALLED_ON_THE_SAME_AGGREGATE, ITERATION))),
                        output::getAllOfThisTest));
    }

    @ParameterizedTest(name = "the key of the start row leads to {0}")
    @ValueSource(strings = {
            "no instance", "an instance which ended", "an instance of another process", "an instance of another aggregate", "nothing, as there is no key"
    })
    @DisplayName("Where the start row's key does not lead to the running workflow of the aggregate, the business-key search finds the root, an embedded sub-process or iteration with the same business key passed over")
    void aKeyWhichLeadsElsewhereLeavesTheRootToTheSearch(final String where) {
        final var workflowId = switch (where) {
            case "no instance" -> {
                engine.answers("GET", instancePath(ROOT), 404, "{\"code\":\"NOT_FOUND\",\"message\":\"gone\"}");
                yield String.valueOf(ROOT);
            }
            case "an instance which ended" -> answeredByKey(instance(ROOT, "shop__Order", "completed", "default",
                    null, "order-1", "order-1"));
            case "an instance of another process" -> answeredByKey(instance(ROOT, "crm__Order", "active", "default",
                    null, "order-1", "order-1"));
            case "an instance of another aggregate" -> answeredByKey(instance(ROOT, "shop__Order", "active",
                    "default", null, "order-2", "order-2"));
            default -> null;
        };
        final var found = 4503599627370700L;
        engine.answers("GET", SEARCH_PATH, 200, page(instance(found - 2, "shop__Order", "active", "subprocess", found,
                "order-1", null), instance(found - 1, "shop__Order", "active", "multiInstance", found, "order-1", null),
                instance(found, "shop__Order", "active", "default", null, "order-1", "order-1")));
        engine.answers("GET", childrenPath(found), 200, page());
        everyWriteIsTaken();

        handler().phaseTwo(push(workflowId, null));

        assertAll(
                () -> assertEquals(List.of(found), written()),
                () -> assertEquals(List.of(
                        "businessKey=order-1&bpmnProcessId=shop__Order&state=active&page=1&size=100&sortBy=key&sortOrder=asc"),
                        searches()));
    }

    @Test
    @DisplayName("Where the engine fails to answer for the start row's key and nothing else finds the workflow, the failure is thrown, so the outbox repeats the push: a workflow the engine started is found by that key alone")
    void aFailedReadOfTheKeyIsThrownWhereNothingElseFindsTheWorkflow(final CapturedOutput output) {
        engine.answers("GET", instancePath(ROOT), 500, "{\"code\":\"TECHNICAL_ERROR\",\"message\":\"down\"}");
        engine.answers("GET", SEARCH_PATH, 200, page());

        final var failure = assertThrows(RuntimeException.class, () -> handler().phaseTwo(push(String.valueOf(ROOT),
                null)));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .contains(instancePath(ROOT)), failure::toString),
                () -> assertTrue(ZenBpmErrors.isRepeatable(failure), failure::toString),
                () -> assertEquals(List.of(), written()),
                () -> assertTrue(!output
                        .getAllOfThisTest()
                        .contains("no running workflow"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("Where the engine fails to answer for the start row's key, the business-key search still finds the root, which is written")
    void aFailedReadOfTheKeyLeavesTheRootToTheSearch() {
        engine.answers("GET", instancePath(ROOT), 500, "{\"code\":\"TECHNICAL_ERROR\",\"message\":\"down\"}");
        final var found = 4503599627370700L;
        engine.answers("GET", SEARCH_PATH, 200, page(instance(found, "shop__Order", "active", "default", null,
                "order-1", "order-1")));
        engine.answers("GET", childrenPath(found), 200, page());
        everyWriteIsTaken();

        handler().phaseTwo(push(String.valueOf(ROOT), null));

        assertEquals(List.of(found), written());
    }

    @Test
    @DisplayName("Where no root instance waits under the business key, one with an incident is the root")
    void aRootWithAnIncidentIsFoundWhereNoneWaits() {
        engine.answers("GET", SEARCH_PATH, exchange -> FakeEngine.respond(exchange, 200, exchange
                .getRequestURI()
                .getRawQuery()
                .contains("state=failed&page=1&size=100&sortBy=key&sortOrder=asc")
                        ? page(instance(ROOT, "shop__Order", "failed", "default", null, "order-1",
                                "order-1"))
                        : page()));
        engine.answers("GET", childrenPath(ROOT), 200, page());
        everyWriteIsTaken();

        handler().phaseTwo(push(null, null));

        assertAll(
                () -> assertEquals(List.of(ROOT), written()),
                () -> assertEquals(List.of(
                        "businessKey=order-1&bpmnProcessId=shop__Order&state=active&page=1&size=100&sortBy=key&sortOrder=asc",
                        "businessKey=order-1&bpmnProcessId=shop__Order&state=failed&page=1&size=100&sortBy=key&sortOrder=asc"),
                        searches()));
    }

    @Test
    @DisplayName("Where no workflow of the aggregate runs any more, the push writes nothing and says why, and the outbox entry is consumed")
    void aWorkflowWhichNoLongerRunsIsAWarnedNoOp(final CapturedOutput output) {
        engine.answers("GET", SEARCH_PATH, 200, page());

        assertDoesNotThrow(() -> handler().phaseTwo(push(null, null)));

        assertAll(
                () -> assertEquals(List.of(), written()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "ZenBPM[orders]: no running workflow 'Order' of workflow module 'shop' was found for aggregate 'order-1', so the changed aggregate is not pushed; the workflow ended after the push was planned, and the outbox entry is consumed"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A root which ended between its reading and its writing is a warned no-op, and nothing below it is read")
    void aRootWhichEndedMeanwhileIsAWarnedNoOp(final CapturedOutput output) {
        engine.answers("GET", SEARCH_PATH, 200, page(instance(ROOT, "shop__Order", "active", "default", null,
                "order-1", "order-1")));
        engine.answers("PATCH", variablesPath(ROOT), 409, ended());

        assertDoesNotThrow(() -> handler().phaseTwo(push(null, null)));

        assertAll(
                () -> assertEquals(List.of("GET "
                        + SEARCH_PATH,
                        "PATCH "
                                + variablesPath(ROOT)),
                        requests()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("so the changed aggregate is not pushed"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("An instance below the root which ended between its reading and its writing is passed over with the instances below it, and the others are written")
    void anInstanceBelowWhichEndedMeanwhileIsPassedOver() {
        engine.answers("GET", SEARCH_PATH, 200, page(instance(ROOT, "shop__Order", "active", "default", null,
                "order-1", "order-1")));
        aTreeOfInstancesRunsBelowTheRoot();
        everyWriteIsTaken();
        engine.answers("PATCH", variablesPath(PACKING), 409, ended());

        handler().phaseTwo(push(null, null));

        assertAll(
                () -> assertEquals(List.of(ROOT, PACKING, CALLED_ON_THE_SAME_AGGREGATE), written()),
                () -> assertTrue(requests()
                        .stream()
                        .noneMatch(("GET "
                                + childrenPath(PACKING))::equals),
                        requests()::toString));
    }

    @Test
    @DisplayName("A write the engine answers 404, as a node which lags behind does for an instance it does not know yet, is thrown, so the outbox repeats the push")
    void aWriteOfAnInstanceTheNodeDoesNotKnowYetIsRepeated() {
        engine.answers("GET", SEARCH_PATH, 200, page(instance(ROOT, "shop__Order", "active", "default", null,
                "order-1", "order-1")));
        engine.answers("PATCH", variablesPath(ROOT), 404, "{\"code\":\"NOT_FOUND\",\"message\":\"not found\"}");

        final var failure = assertThrows(RuntimeException.class, () -> handler().phaseTwo(push(null, null)));

        assertAll(
                () -> assertTrue(ZenBpmErrors.variablesFoundNoInstance(failure), failure::toString),
                () -> assertTrue(ZenBpmErrors.isRepeatable(failure), failure::toString));
    }

    @Test
    @DisplayName("Under message-start-lookup scan a workflow a message started, which has no business key, is found by its id variable and pushed")
    void underScanAWorkflowAMessageStartedIsPushed() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        configuration.setMessageStartLookup(MessageStartLookup.SCAN);
        final var scanning = new ZenBpmClientFactory("orders", configuration, () -> "shop");
        factories.add(scanning);
        final var messages = new ModuleMessages(List.of("shop__Order"), Set.of(), Set.of("shop__ordered"), Set.of(
                "shop__Order"), List.of());
        scanning
                .deployedProcesses()
                .recordMessages("shop", messages, () -> messages);
        engine.answers("GET", SEARCH_PATH, exchange -> FakeEngine.respond(exchange, 200, exchange
                .getRequestURI()
                .getRawQuery()
                .contains("businessKey=") ? page()
                        : page(instance(ROOT, "shop__Order", "active", "default", null,
                                null, "order-1"))));
        engine.answers("GET", childrenPath(ROOT), 200, page());
        everyWriteIsTaken();

        handler(scanning).phaseTwo(push(null, null));

        assertEquals(List.of(ROOT), written());
    }

    @Test
    @DisplayName("Without message-start-lookup scan, a workflow without a business key is not looked for among the instances")
    void withoutScanNoInstancesAreRead() {
        engine.answers("GET", SEARCH_PATH, 200, page());

        handler().phaseTwo(push(null, null));

        assertEquals(List.of(
                "businessKey=order-1&bpmnProcessId=shop__Order&state=active&page=1&size=100&sortBy=key&sortOrder=asc",
                "businessKey=order-1&bpmnProcessId=shop__Order&state=failed&page=1&size=100&sortBy=key&sortOrder=asc"),
                searches());
    }

    @Test
    @DisplayName("A push into the scope of a task writes the values into the instance the task's job waits in, and into no other")
    void aTaskScopedPushWritesTheInstanceTheTaskWaitsInAndNothingElse(final CapturedOutput output) throws Exception {
        engine.answers("GET", "/v1/jobs/%d".formatted(JOB), 200, job());
        everyWriteIsTaken();

        handler().phaseTwo(push(null, String.valueOf(JOB)));

        assertAll(
                () -> assertEquals(List.of("GET /v1/jobs/%d".formatted(JOB), "PATCH "
                        + variablesPath(PACKING)),
                        requests()),
                () -> assertEquals(List.of(VALUES_AS_JSON), bodiesOfTheWrites()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "ZenBPM[orders]: pushed the changed aggregate 'order-1' into instance %d, which task '%d' waits in"
                                        .formatted(PACKING, JOB)),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A push into the scope of a task whose instance ended, or whose id is no key of the engine, writes nothing else and says why")
    void aTaskScopeWhichIsGoneIsAWarnedNoOp(final CapturedOutput output) {
        engine.answers("GET", "/v1/jobs/%d".formatted(JOB), 200, job());
        engine.answers("PATCH", variablesPath(PACKING), 409, ended());

        handler().phaseTwo(push(null, String.valueOf(JOB)));
        handler().phaseTwo(push(null, "a-task-of-another-engine"));

        final var log = output.getAllOfThisTest();
        final var notPushed = "ZenBPM[orders]: the changed aggregate 'order-1' is not pushed into the scope of task '%s' of workflow 'Order' of workflow module 'shop', because %s; the workflow's own scope is not written instead, and the outbox entry is consumed";
        assertAll(
                () -> assertEquals(List.of(PACKING), written()),
                () -> assertTrue(log.contains(notPushed.formatted(JOB, "the instance %d it waits in ended"
                        .formatted(PACKING))), log),
                () -> assertTrue(log.contains(notPushed.formatted("a-task-of-another-engine",
                        "it is no key of this engine")), log));
    }

    @Test
    @DisplayName("A push into the scope of a task the engine answers 404 for, as a node which lags behind does for a job it does not know yet, is thrown, so the outbox repeats the push; nothing is written")
    void aTaskTheNodeDoesNotKnowYetIsRepeated() {
        engine.answers("GET", "/v1/jobs/%d".formatted(JOB), 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no job\"}");

        final var failure = assertThrows(IllegalStateException.class, () -> handler().phaseTwo(push(null, String
                .valueOf(JOB))));

        assertAll(
                () -> assertEquals(List.of(), written()),
                () -> assertEquals(
                        "ZenBPM[orders]: the engine does not know task '%d' of workflow 'Order' of workflow module 'shop' yet, so the changed aggregate 'order-1' is not pushed into its scope now; a node which lags behind answers so for a task handed out a moment ago, and the outbox repeats the push"
                                .formatted(JOB),
                        failure.getMessage()));
    }

    @Test
    @DisplayName("A called instance of a process the platform does not know is entered where its business key or the id variable names the aggregate, and passed over where neither does")
    void aCalledProcessThePlatformDoesNotKnowIsJudgedByWhatItCarries() {
        final var audit = 4503599627370540L;
        final var foreign = 4503599627370541L;
        engine.answers("GET", SEARCH_PATH, 200, page(instance(ROOT, "shop__Order", "active", "default", null,
                "order-1", "order-1")));
        engine.answers("GET", childrenPath(ROOT), 200, page(instance(audit, "shop__Audit", "active", "callActivity",
                ROOT, "order-1", "order-1"),
                instance(foreign, "shop__Foreign", "active", "callActivity", ROOT,
                        "", null)));
        engine.answers("GET", childrenPath(audit), 200, page());
        for (final var key : List.of(ROOT, audit, foreign)) {
            engine.answers("PATCH", variablesPath(key), 204, null);
        }

        handler().phaseTwo(push(null, null));

        assertEquals(List.of(ROOT, audit), written());
    }

    @Test
    @DisplayName("The business-key search takes an instance a call activity started as the root, because a called process runs a workflow of its own, as the probes find it")
    void theBusinessKeySearchFindsACalledWorkflow() {
        final var caller = 4503599627370800L;
        engine.answers("GET", SEARCH_PATH, 200, page(instance(ROOT, "shop__Order", "active", "callActivity", caller,
                "order-1", "order-1")));
        engine.answers("GET", childrenPath(ROOT), 200, page());
        everyWriteIsTaken();

        handler().phaseTwo(push(null, null));

        assertEquals(List.of(ROOT), written());
    }

    @Test
    @DisplayName("Phase one asks the engine nothing")
    void phaseOneAsksNothing() {
        handler().phaseOne(new PhaseOneRequest<>("shop", "Order", persistence(), new Object(), Map.of()));

        assertEquals(List.of(), engine.requests());
    }

    // below the root: the sub-process Packing, which runs, Packed, which ended, and two called
    // instances; an iteration runs below Packing, and an instance below the called instance of
    // another aggregate. As the engine does, the called instance of another aggregate carries a copy
    // of the caller's id variable and the empty business key decision 45 gives it
    private void aTreeOfInstancesRunsBelowTheRoot() {
        engine.answers("GET", childrenPath(ROOT), 200, page(instance(PACKING, "shop__Order", "active", "subprocess",
                ROOT, "order-1", "order-1"),
                instance(PACKED, "shop__Order", "completed", "subprocess", ROOT,
                        "order-1", "order-1"),
                instance(CALLED_ON_THE_SAME_AGGREGATE, "shop__Billing", "active",
                        "callActivity", ROOT, "order-1", "order-1"),
                instance(CALLED_ON_ANOTHER_AGGREGATE,
                        "shop__Rating", "active", "callActivity", ROOT, "", "order-1")));
        engine.answers("GET", childrenPath(PACKING), 200, page(instance(ITERATION, "shop__Order", "failed",
                "multiInstance", PACKING, "order-1", null)));
        engine.answers("GET", childrenPath(CALLED_ON_THE_SAME_AGGREGATE), 200, page());
        engine.answers("GET", childrenPath(CALLED_ON_ANOTHER_AGGREGATE), 200, page(instance(BELOW_THE_OTHER_AGGREGATE,
                "shop__Rating", "active", "subprocess", CALLED_ON_ANOTHER_AGGREGATE, "", "order-1")));
        engine.answers("GET", childrenPath(ITERATION), 200, page());
    }

    private void everyWriteIsTaken() {
        for (final var key : List.of(ROOT, PACKING, PACKED, CALLED_ON_THE_SAME_AGGREGATE, CALLED_ON_ANOTHER_AGGREGATE,
                ITERATION, BELOW_THE_OTHER_AGGREGATE, 4503599627370700L)) {
            engine.answers("PATCH", variablesPath(key), 204, null);
        }
    }

    private String answeredByKey(final String instance) {
        engine.answers("GET", instancePath(ROOT), 200, instance);
        return String.valueOf(ROOT);
    }

    private PhaseOperationHandler<Object> handler() {
        final var factory = new ZenBpmClientFactory("orders", configuration(), () -> "shop");
        factories.add(factory);
        return handler(factory);
    }

    private PhaseOperationHandler<Object> handler(final ZenBpmClientFactory factory) {
        return new ZenBpmProcessService<>("orders", factory, TestCollaborators.of("orders", TestScoping.of(
                NameClashAvoidance.USE_PREFIX),
                TestWiring
                        .claiming("Order", "Billing", "Rating")
                        .sharingTheAggregateWith("Billing")
                        .wiring(),
                new SharingTheAmount()), (
                    module,
                    process) -> null)
                .phaseOperations()
                .get(PhaseOperation.AGGREGATE_CHANGED);
    }

    private ZenBpmAdapterConfiguration configuration() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        return configuration;
    }

    // the keys of the instances the push wrote or tried to write, in that order
    private List<Long> written() {
        return engine
                .requests()
                .stream()
                .filter(request -> "PATCH".equals(request.method()))
                .map(request -> Long.valueOf(request
                        .path()
                        .split("/")[3]))
                .toList();
    }

    private List<Object> bodiesOfTheWrites() {
        return engine
                .requests()
                .stream()
                .filter(request -> "PATCH".equals(request.method()))
                .map(request -> readTree(request.body()))
                .toList();
    }

    private List<String> requests() {
        return engine
                .requests()
                .stream()
                .map(request -> "%s %s".formatted(request.method(), request.path()))
                .toList();
    }

    private List<String> searches() {
        return engine
                .requests()
                .stream()
                .filter(request -> SEARCH_PATH.equals(request.path()))
                .map(FakeEngine.Request::query)
                .toList();
    }

    private static Object readTree(final String json) {
        try {
            return JSON.readTree(json);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PhaseTwoRequest<Object> push(
        final String workflowId,
        final String taskId
    ) {
        final var args = new LinkedHashMap<String, String>();
        if (taskId != null) {
            args.put(PhaseTwoCall.ARG_TASK_ID, taskId);
        }
        return new PhaseTwoRequest<>("shop", "Order", persistence(), "order-1", args, null, workflowId, null);
    }

    private static String instancePath(final long key) {
        return "/v1/process-instances/%d".formatted(key);
    }

    private static String variablesPath(final long key) {
        return "%s/variables".formatted(instancePath(key));
    }

    private static String childrenPath(final long key) {
        return "%s/child-processes".formatted(instancePath(key));
    }

    private static String ended() {
        return "{\"code\":\"CONFLICT\",\"message\":\"Can update variables only for process instances in active or failed state\"}";
    }

    private static String job() {
        return """
                {"key": %d, "elementId": "Task_Pack", "type": "shop__Order__pack", "elementType": "SERVICE_TASK",
                 "processInstanceKey": %d, "elementInstanceKey": 4503599627370599, "state": "active",
                 "createdAt": "2026-10-17T10:00:00Z", "inputVariables": {}}""".formatted(JOB, PACKING);
    }

    private static String page(final String... instances) {
        return """
                {"partitions": [{"partition": 1, "totalCount": %d, "items": [%s]}],
                 "page": 1, "size": 100, "count": %d, "totalCount": %d}""".formatted(instances.length, String.join(
                ",", instances), instances.length, instances.length);
    }

    private static String instance(
        final long key,
        final String scopedProcessId,
        final String state,
        final String processType,
        final Long parent,
        final String businessKey,
        final String orderId
    ) {
        return """
                {"key": %d, "processDefinitionKey": 4503599627370401, "bpmnProcessId": "%s", "version": 1,
                 "createdAt": "2026-10-17T10:00:00Z", "state": "%s", %s "processType": "%s",
                 %s "variables": %s}""".formatted(key, scopedProcessId, state, parent == null ? ""
                : "\"parentProcessInstanceKey\": %d,".formatted(parent), processType,
                businessKey == null ? ""
                        : "\"businessKey\": \"%s\",".formatted(businessKey),
                orderId == null ? "{}"
                        : "{\"orderId\": \"%s\"}".formatted(orderId));
    }

    private static AggregatePersistenceAware<Object> persistence() {
        return new AggregatePersistenceAware<>() {

            @Override
            public Class<Object> getAggregateClass() {
                return Object.class;
            }

            @Override
            public String getAggregateIdName() {
                return "orderId";
            }

            @Override
            public Object loadById(final Object aggregateId) {
                assertEquals("order-1", aggregateId);
                return new Object();
            }

        };
    }

    /**
     * A sync model which shares the amount of every aggregate.
     */
    private static final class SharingTheAmount implements WorkflowAggregateSync {

        @Override
        public Map<String, Object> syncedValues(
            final Object workflowAggregate,
            final AggregateSyncMode adapterDefault
        ) {
            return Map.of("amount", 1000);
        }

        @Override
        public void validateSyncModel(final Class<?> workflowAggregateClass) {
        }

    }

}
