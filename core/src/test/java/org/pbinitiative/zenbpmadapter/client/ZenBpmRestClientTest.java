/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.DefinitionStatistics;
import org.pbinitiative.zenbpmadapter.client.api.DeployedResource;
import org.pbinitiative.zenbpmadapter.client.api.EngineStatus;
import org.pbinitiative.zenbpmadapter.client.api.HistoryEntry;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessDefinitionRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.client.api.Readiness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the REST client sends and how it reads the answers, against a fake engine which answers
 * what the test tells it. That the requests and the answers are the engine's is proven by
 * {@link ZenBpmRestClientContractIT} against the pinned engine.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmRestClientTest {

    private static final String ADAPTER_ID = "orders";

    /**
     * The shortest <code>request-timeout</code> the configuration accepts, so a test of the
     * timeout waits as little as possible.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);

    /**
     * How much longer than <code>request-timeout</code> a call may take until it fails.
     */
    private static final Duration TIMEOUT_TOLERANCE = Duration.ofSeconds(1);

    /**
     * Longer than the request may take, so an answer delayed by it arrives too late.
     */
    private static final long LONGER_THAN_THE_TIMEOUT_MILLIS = 5_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INSTANCE = """
            {
              "key": 4503599627370501,
              "processDefinitionKey": 4503599627370498,
              "bpmnProcessId": "orders_order",
              "version": 3,
              "versionTag": "2026.10",
              "businessKey": "order-1",
              "createdAt": "2026-10-13T14:30:25.123Z",
              "state": "active",
              "parentProcessInstanceKey": 4503599627370400,
              "processType": "callActivity",
              "variables": {"amount": 1000},
              "incidentCount": 0,
              "activeElementInstances": []
            }
            """;

    private static final String JOB = """
            {
              "key": 4503599627370540,
              "elementId": "Task_Ship",
              "type": "orders_order#ship",
              "elementType": "SERVICE_TASK",
              "processInstanceKey": 4503599627370501,
              "elementInstanceKey": 4503599627370539,
              "state": "active",
              "createdAt": "2026-10-13T15:10:26Z",
              "assignee": "john.doe",
              "retries": 3,
              "inputVariables": {"orderId": "order-1"}
            }
            """;

    private FakeEngine engine;

    private ZenBpmRestClient client;

    @BeforeEach
    void startTheEngine() {

        engine = FakeEngine.started();
        client = clientOf(engine.address());

    }

    // the engine first: it ends the answers still running, which the client's close waits for
    @AfterEach
    void stopTheEngine() {

        engine.close();
        client.close();

    }

    @Test
    @DisplayName("A ready engine answers 200, and the client says ready")
    void aReadyEngineIsReady() {

        engine.answers("GET", "/system/health/ready", 200, """
                {"status": "UP", "reasons": []}""");

        assertEquals(new Readiness(true, List.of()), client.ready());

    }

    @Test
    @DisplayName("An engine which is not ready answers 503, which is an answer and no failure")
    void anEngineWhichIsNotReadyAnswers() {

        engine.answers("GET", "/system/health/ready", 503, """
                {"status": "DOWN", "reasons": ["no leader", "partition 1 not initialized"]}""");

        assertEquals(new Readiness(false, List.of("no leader", "partition 1 not initialized")), client.ready());

    }

    @Test
    @DisplayName("Readiness without reasons reads as no reasons")
    void readinessWithoutReasons() {

        engine.answers("GET", "/system/health/ready", 200, """
                {"status": "UP"}""");

        assertEquals(new Readiness(true, List.of()), client.ready());

    }

    @Test
    @DisplayName("Readiness answered with another status than 200 or 503 is a failure")
    void readinessAnsweredOtherwiseFails() {

        engine.answers("GET", "/system/health/ready", 500, null);

        final var failure = assertThrows(ZenBpmApiException.class, client::ready);

        assertEquals(500, failure.getStatus());

    }

    @Test
    @DisplayName("The status names version, shortened commit and the node ids, sorted")
    void theStatusNamesVersionCommitAndNodes() {

        engine.answers("GET", "/system/status", 200, """
                {
                  "git": {"branch": "main", "commitId": "bdd795a399b6"},
                  "build": {"version": "v1.8.0", "time": "2026-10-13T08:00:00Z"},
                  "health": {"ok": true, "reasons": []},
                  "clusterConfig": {"desiredPartitions": 1},
                  "partitions": {"1": {"id": 1, "leaderId": "zen-2"}},
                  "nodes": {"zen-2": {"id": "zen-2"}, "zen-1": {"id": "zen-1"}}
                }""");

        assertEquals(new EngineStatus("v1.8.0", "bdd795a399b6", List.of("zen-1", "zen-2")), client.status());

    }

    @ParameterizedTest(name = "status {0} means created = {1}")
    @MethodSource("deploymentAnswers")
    @DisplayName("A deployed BPMN file is sent as it is, and 201 means a new version, 200 none")
    void aBpmnFileIsDeployed(
        final int status,
        final boolean created
    ) {

        engine.answers("POST", "/v1/process-definitions", status, """
                {"processDefinitionKey": 4503599627370498}""");

        final var deployed = client.deployBpmn("<definitions/>".getBytes(StandardCharsets.UTF_8));

        assertEquals(new DeployedResource(4503599627370498L, created), deployed);
        final var request = engine.onlyRequest();
        assertEquals("application/octet-stream", request.contentType());
        assertEquals("<definitions/>", request.body());

    }

    @ParameterizedTest(name = "status {0} means created = {1}")
    @MethodSource("deploymentAnswers")
    @DisplayName("A deployed DMN file is sent as it is, and 201 means a new version, 200 none")
    void aDmnFileIsDeployed(
        final int status,
        final boolean created
    ) {

        engine.answers("POST", "/v1/dmn-resource-definitions", status, """
                {"dmnResourceDefinitionKey": 4503599627370496}""");

        final var deployed = client.deployDmn("<definitions/>".getBytes(StandardCharsets.UTF_8));

        assertEquals(new DeployedResource(4503599627370496L, created), deployed);
        assertEquals("application/octet-stream", engine
                .onlyRequest()
                .contentType());

    }

    @Test
    @DisplayName("A process definition carries its BPMN, and no version tag where it has none")
    void aProcessDefinitionCarriesItsBpmn() {

        engine.answers("GET", "/v1/process-definitions/4503599627370498", 200, """
                {"key": 4503599627370498, "version": 1, "bpmnProcessId": "orders_order",
                 "bpmnProcessName": "Order", "bpmnData": "<definitions/>"}""");

        assertEquals(new ProcessDefinitionRef(4503599627370498L, "orders_order", 1, null, "<definitions/>"),
                client.processDefinition(4503599627370498L));

    }

    @Test
    @DisplayName("The versions of a process are read page by page until the total is reached")
    void theVersionsOfAProcessAreReadToTheEnd() {

        engine.answers("GET", "/v1/process-definitions", exchange -> {
            final var query = exchange
                    .getRequestURI()
                    .getRawQuery();
            final var page = query.contains("page=1&") ? 1 : 2;
            final var items = IntStream
                    .rangeClosed(1, page == 1 ? ZenBpmRestClient.PAGE_SIZE : 1)
                    .mapToObj(index -> """
                            {"key": %d, "version": %d, "bpmnProcessId": "orders_order", "versionTag": "v%d"}"""
                            .formatted(1000 + ((page - 1) * 100) + index, ((page - 1) * 100) + index,
                                    ((page - 1) * 100) + index))
                    .collect(Collectors.joining(","));
            FakeEngine.respond(exchange, 200, """
                    {"items": [%s], "page": %d, "size": 100, "count": 0, "totalCount": 101}""".formatted(items, page));
        });

        final var versions = client.processDefinitions("orders_order", false);

        assertEquals(101, versions.size());
        assertEquals(new ProcessDefinitionRef(1101L, "orders_order", 101, "v101", null), versions.getLast());
        assertEquals(List.of(
                "bpmnProcessId=orders_order&onlyLatest=false&page=1&size=100&sortBy=key&sortOrder=asc",
                "bpmnProcessId=orders_order&onlyLatest=false&page=2&size=100&sortBy=key&sortOrder=asc"), queries());

    }

    @Test
    @DisplayName("A short page ends the reading of versions, even where the total says more")
    void aShortPageEndsTheReading() {

        engine.answers("GET", "/v1/process-definitions", 200, """
                {"items": [{"key": 1, "version": 1, "bpmnProcessId": "orders_order"}],
                 "page": 1, "size": 100, "count": 1, "totalCount": 500}""");

        assertEquals(1, client
                .processDefinitions("orders_order", true)
                .size());
        assertEquals(1, engine
                .requests()
                .size());

    }

    @Test
    @DisplayName("A full page which reaches the total is the last one, without asking for an empty one")
    void aFullPageWhichReachesTheTotalIsTheLastOne() {

        final var items = IntStream
                .rangeClosed(1, ZenBpmRestClient.PAGE_SIZE)
                .mapToObj(key -> """
                        {"key": %d, "version": %d, "bpmnProcessId": "orders_order"}""".formatted(key, key))
                .collect(Collectors.joining(","));
        // a later page is empty, as the engine answers it
        engine.answers("GET", "/v1/process-definitions", exchange -> FakeEngine.respond(exchange, 200,
                isFirstPage(exchange)
                        ? """
                                {"items": [%s], "page": 1, "size": 100, "count": 100, "totalCount": 100}"""
                                .formatted(items)
                        : """
                                {"items": [], "page": 2, "size": 100, "count": 0, "totalCount": 100}"""));

        assertEquals(ZenBpmRestClient.PAGE_SIZE, client
                .processDefinitions("orders_order", false)
                .size());
        assertEquals(1, engine
                .requests()
                .size());

    }

    @Test
    @DisplayName("The statistics of a definition are summed per element over the partitions")
    void theStatisticsAreSummedOverThePartitions() {

        engine.answers("GET", "/v1/process-definitions/42/statistics", 200, """
                {"partitions": [
                  {"partition": 1, "items": {
                    "Task_Ship": {"activeCount": 5, "incidentCount": 2},
                    "Task_Bill": {"activeCount": 3, "incidentCount": 0}}},
                  {"partition": 2, "items": {
                    "Task_Ship": {"activeCount": 4, "incidentCount": 1}}}
                ]}""");

        assertEquals(new DefinitionStatistics(Map.of(
                "Task_Ship", new DefinitionStatistics.ElementCounts(9, 3),
                "Task_Bill", new DefinitionStatistics.ElementCounts(3, 0))), client.processDefinitionStatistics(42));

    }

    @Test
    @DisplayName("A start sends what it was given and nothing it was not")
    void aStartSendsWhatItWasGiven() throws Exception {

        engine.answers("POST", "/v1/process-instances", 201, INSTANCE);
        final var variables = new HashMap<String, Object>();
        variables.put("amount", 1000);
        variables.put("note", null);

        client.createInstance(CreateInstanceRequest
                .latestVersionOf("orders_order")
                .withBusinessKey("order-1")
                .withVariables(variables)
                .withHistoryTimeToLive("30d"));
        client.createInstance(CreateInstanceRequest.version(4503599627370498L));

        final var requests = engine.requests();
        assertEquals("application/json", requests
                .getFirst()
                .contentType());
        assertEquals(JSON.readTree("""
                {"bpmnProcessId": "orders_order", "businessKey": "order-1",
                 "variables": {"amount": 1000, "note": null}, "historyTimeToLive": "30d"}"""), JSON.readTree(requests
                .getFirst()
                .body()));
        assertEquals(JSON.readTree("""
                {"processDefinitionKey": 4503599627370498}"""), JSON.readTree(requests
                .getLast()
                .body()));

    }

    @Test
    @DisplayName("An instance is read with every field the adapter uses")
    void anInstanceIsRead() {

        engine.answers("GET", "/v1/process-instances/4503599627370501", 200, INSTANCE);

        assertEquals(Optional.of(new ProcessInstanceRef(
                4503599627370501L, 4503599627370498L, "orders_order", 3, "2026.10", "order-1", ProcessInstanceRef.State.ACTIVE, Map
                        .of("amount", 1000L), 4503599627370400L, ProcessInstanceRef.ProcessType.CALL_ACTIVITY, Instant
                                .parse("2026-10-13T14:30:25.123Z"))),
                client.instance(4503599627370501L));

    }

    @ParameterizedTest(name = "{0} is {1}")
    @MethodSource("instanceStates")
    @DisplayName("Every state of an instance has its constant")
    void everyInstanceStateHasItsConstant(
        final String state,
        final ProcessInstanceRef.State expected
    ) {

        engine.answers("GET", "/v1/process-instances/1", 200, instance(1, state, "default"));

        assertEquals(expected, client
                .instance(1)
                .orElseThrow()
                .state());

    }

    @ParameterizedTest(name = "{0} is {1}")
    @MethodSource("processTypes")
    @DisplayName("Every process type of an instance has its constant")
    void everyProcessTypeHasItsConstant(
        final String processType,
        final ProcessInstanceRef.ProcessType expected
    ) {

        engine.answers("GET", "/v1/process-instances/1", 200, instance(1, "active", processType));

        assertEquals(expected, client
                .instance(1)
                .orElseThrow()
                .processType());

    }

    @ParameterizedTest(name = "state {0}, process type {1}")
    @MethodSource("unknownInstanceValues")
    @DisplayName("A state or a process type the pinned API does not know makes an instance unreadable")
    void anUnknownStateOrProcessTypeIsUnreadable(
        final String state,
        final String processType,
        final String reason
    ) {

        engine.answers("GET", "/v1/process-instances/1", 200, instance(1, state, processType));

        final var failure = assertThrows(ZenBpmUnavailableException.class, () -> client.instance(1));

        assertEquals(reason, failure
                .getCause()
                .getMessage());

    }

    @Test
    @DisplayName("An instance the engine does not know is empty, not a failure")
    void anUnknownInstanceIsEmpty() {

        engine.answers("GET", "/v1/process-instances/7", 404, """
                {"code": "NOT_FOUND", "message": "process instance 7 not found"}""");

        assertEquals(Optional.empty(), client.instance(7));

    }

    @Test
    @DisplayName("A search for instances sends every criterion, ordered by key, and reads the total")
    void aSearchForInstancesSendsEveryCriterion() {

        engine.answers("GET", "/v1/process-instances", 200, """
                {"partitions": [
                  {"partition": 1, "totalCount": 24, "items": [%s]},
                  {"partition": 2, "totalCount": 18, "items": []}
                ], "page": 1, "size": 1, "count": 1, "totalCount": 42}""".formatted(INSTANCE));

        final var page = client.instances(InstanceFilter
                .anyInstance()
                .withBusinessKey("order 1&2")
                .withBpmnProcessId("orders_order")
                .withProcessDefinitionKey(4503599627370498L)
                .withState(ProcessInstanceRef.State.ACTIVE)
                .withCreatedFrom(Instant.parse("2026-10-13T00:00:00Z"))
                .withSize(1));

        assertEquals(42, page.totalCount());
        assertEquals(1, page
                .items()
                .size());
        assertFalse(page.last());
        assertEquals(List.of(
                "businessKey=order+1%262&bpmnProcessId=orders_order&processDefinitionKey=4503599627370498&state=active&createdFrom=2026-10-13T00%3A00%3A00Z&page=1&size=1&sortBy=key&sortOrder=asc"),
                queries());

    }

    @Test
    @DisplayName("A search for any instance sends no criterion")
    void aSearchForAnyInstanceSendsNoCriterion() {

        engine.answers("GET", "/v1/process-instances", 200, """
                {"partitions": [], "page": 1, "size": 100, "count": 0, "totalCount": 0}""");

        final var page = client.instances(InstanceFilter
                .anyInstance()
                .withPage(3));

        assertTrue(page.last());
        assertEquals(List.of("page=3&size=100&sortBy=key&sortOrder=asc"), queries());

    }

    @Test
    @DisplayName("The children of an instance are read until every partition is at its end")
    void theChildrenAreReadUntilEveryPartitionIsAtItsEnd() {

        // partition 1 holds 101 children, partition 2 holds 3: page 1 fills partition 1, page 2
        // brings its last child, and partition 2 was done after page 1
        engine.answers("GET", "/v1/process-instances/1/child-processes", exchange -> {
            final var first = exchange
                    .getRequestURI()
                    .getRawQuery()
                    .startsWith("page=1&");
            final var partitionOne = IntStream
                    .rangeClosed(first ? 1 : 101, first ? 100 : 101)
                    .mapToObj(key -> instance(key, "completed", "multiInstance"))
                    .collect(Collectors.joining(","));
            final var partitionTwo = first
                    ? IntStream
                            .rangeClosed(1001, 1003)
                            .mapToObj(key -> instance(key, "active", "multiInstance"))
                            .collect(Collectors.joining(","))
                    : "";
            FakeEngine.respond(exchange, 200, """
                    {"partitions": [
                      {"partition": 1, "totalCount": 101, "items": [%s]},
                      {"partition": 2, "totalCount": 3, "items": [%s]}
                    ], "page": 1, "size": 100, "count": 0, "totalCount": 104}""".formatted(partitionOne, partitionTwo));
        });

        final var children = client.children(1);

        assertEquals(104, children.size());
        assertEquals(List.of(
                "page=1&size=100&sortBy=key&sortOrder=asc",
                "page=2&size=100&sortBy=key&sortOrder=asc"), queries());

    }

    @Test
    @DisplayName("A partition whose full page reaches its total needs no further page")
    void aFullPartitionWhichReachesItsTotalIsAtItsEnd() {

        final var children = IntStream
                .rangeClosed(1, ZenBpmRestClient.PAGE_SIZE)
                .mapToObj(key -> instance(key, "active", "multiInstance"))
                .collect(Collectors.joining(","));
        // a later page is empty, as the engine answers it
        engine.answers("GET", "/v1/process-instances/1/child-processes", exchange -> FakeEngine.respond(exchange,
                200, """
                        {"partitions": [{"partition": 1, "totalCount": 100, "items": [%s]}],
                         "page": 1, "size": 100, "count": 0, "totalCount": 100}"""
                        .formatted(isFirstPage(exchange) ? children : "")));

        assertEquals(ZenBpmRestClient.PAGE_SIZE, client
                .children(1)
                .size());
        assertEquals(1, engine
                .requests()
                .size());

    }

    @Test
    @DisplayName("Variables are patched as given, a null value included")
    void variablesArePatched() throws Exception {

        engine.answers("PATCH", "/v1/process-instances/1/variables", 204, null);
        final var variables = new HashMap<String, Object>();
        variables.put("approved", true);
        variables.put("comment", null);

        client.patchVariables(1, variables);

        assertEquals(JSON.readTree("""
                {"variables": {"approved": true, "comment": null}}"""), JSON.readTree(engine
                .onlyRequest()
                .body()));

    }

    @Test
    @DisplayName("A page of the history is ordered by entry and says whether it is the last one")
    void aPageOfTheHistory() {

        engine.answers("GET", "/v1/process-instances/1/history", 200, """
                {"items": [
                  {"key": 11, "processInstanceKey": 1, "createdAt": "2026-10-13T14:30:26Z",
                   "completedAt": "2026-10-13T14:30:27Z", "elementId": "StartEvent_1",
                   "elementType": "START_EVENT", "inputVariables": {}},
                  {"key": 12, "processInstanceKey": 1, "createdAt": "2026-10-13T14:30:27Z",
                   "elementId": "Task_Ship", "elementType": "SERVICE_TASK", "inputVariables": {}}
                ], "page": 2, "size": 100, "count": 2, "totalCount": 102}""");

        final var page = client.history(1, 2);

        assertEquals(List.of(
                new HistoryEntry(11, "StartEvent_1", "START_EVENT", 1, Instant.parse("2026-10-13T14:30:26Z"), Instant
                        .parse("2026-10-13T14:30:27Z")),
                new HistoryEntry(12, "Task_Ship", "SERVICE_TASK", 1, Instant.parse("2026-10-13T14:30:27Z"), null)),
                page.items());
        assertEquals(102, page.totalCount());
        assertTrue(page.last());
        assertEquals(List.of("page=2&size=100&sortBy=createdAt&sortOrder=asc"), queries());

    }

    @Test
    @DisplayName("A history without items is an empty last page")
    void aHistoryWithoutItems() {

        engine.answers("GET", "/v1/process-instances/1/history", 200, """
                {"page": 1, "size": 100, "count": 0, "totalCount": 0}""");

        final var page = client.history(1, 1);

        assertEquals(List.of(), page.items());
        assertTrue(page.last());

    }

    @Test
    @DisplayName("A job is read with every field the adapter uses")
    void aJobIsRead() {

        engine.answers("GET", "/v1/jobs/4503599627370540", 200, JOB);

        assertEquals(Optional.of(new JobRef(
                4503599627370540L, "orders_order#ship", JobRef.State.ACTIVE, "Task_Ship", "SERVICE_TASK", 4503599627370539L, 4503599627370501L, "john.doe", Map
                        .of("orderId", "order-1"), Instant.parse("2026-10-13T15:10:26Z"))),
                client.job(4503599627370540L));

    }

    @ParameterizedTest(name = "{0} is {1}")
    @MethodSource("jobStates")
    @DisplayName("Every state of a job has its constant")
    void everyJobStateHasItsConstant(
        final String state,
        final JobRef.State expected
    ) {

        engine.answers("GET", "/v1/jobs/1", 200, JOB.replace("\"active\"", "\"%s\"".formatted(state)));

        assertEquals(expected, client
                .job(1)
                .orElseThrow()
                .state());

    }

    @Test
    @DisplayName("A job the engine does not know is empty, not a failure")
    void anUnknownJobIsEmpty() {

        engine.answers("GET", "/v1/jobs/7", 404, """
                {"code": "NOT_FOUND", "message": "job 7 not found"}""");

        assertEquals(Optional.empty(), client.job(7));

    }

    @Test
    @DisplayName("A search for jobs sends every criterion, ordered by key, and reads the total")
    void aSearchForJobsSendsEveryCriterion() {

        engine.answers("GET", "/v1/jobs", 200, """
                {"partitions": [{"partition": 1, "totalCount": 1, "items": [%s]}],
                 "page": 1, "size": 10, "count": 1, "totalCount": 1}""".formatted(JOB));

        final var page = client.jobs(JobFilter
                .anyJob()
                .withJobType("orders_order#ship")
                .withState(JobRef.State.TERMINATED)
                .withProcessInstanceKey(4503599627370501L)
                .withPage(1)
                .withSize(10));

        assertEquals(1, page.totalCount());
        assertTrue(page.last());
        assertEquals(List.of(
                "jobType=orders_order%23ship&state=terminated&processInstanceKey=4503599627370501&page=1&size=10&sortBy=key&sortOrder=asc"),
                queries());

    }

    @Test
    @DisplayName("A completion sends the variables, and no variables where it has none")
    void aCompletionSendsTheVariables() throws Exception {

        engine.answers("POST", "/v1/jobs/1/complete", 201, null);

        client.completeJob(1, Map.of("shipped", true));
        client.completeJob(1, null);

        assertEquals(List.of(JSON.readTree("""
                {"variables": {"shipped": true}}"""), JSON.readTree("{}")), bodies());

    }

    @Test
    @DisplayName("A failure sends the error code where there is one")
    void aFailureSendsTheErrorCode() throws Exception {

        engine.answers("POST", "/v1/jobs/1/fail", 204, null);

        client.failJob(1, "orders_out-of-stock", Map.of("item", "A-1"));
        client.failJob(1, null, null);

        assertEquals(List.of(JSON.readTree("""
                {"errorCode": "orders_out-of-stock", "variables": {"item": "A-1"}}"""), JSON.readTree("{}")),
                bodies());

    }

    @Test
    @DisplayName("A message is published with its name, its correlation key and its variables")
    void aMessageIsPublished() throws Exception {

        engine.answers("POST", "/v1/messages", 201, null);

        client.publishMessage("orders_paid", "order-1", Map.of("amount", 1000));
        client.publishMessage("orders_placed", null, null);

        assertEquals(List.of(JSON.readTree("""
                {"messageName": "orders_paid", "correlationKey": "order-1", "variables": {"amount": 1000}}"""),
                JSON.readTree("""
                        {"messageName": "orders_placed"}""")), bodies());

    }

    @ParameterizedTest(name = "{0} is sent as {1}")
    @MethodSource("lockDurations")
    @DisplayName("A lock is extended by whole seconds, rounded up, and the new deadline is read")
    void aLockIsExtended(
        final Duration duration,
        final String sent
    ) throws Exception {

        engine.answers("POST", "/v1/jobs/1/extend-lock", 200, """
                {"lockUntil": "2026-10-13T15:20:30Z"}""");

        final var lockUntil = client.extendJobLock(1, "app-orders-1a2b3c4d", duration);

        assertEquals(Instant.parse("2026-10-13T15:20:30Z"), lockUntil);
        assertEquals(JSON.readTree("""
                {"clientId": "app-orders-1a2b3c4d", "lockDuration": "%s"}""".formatted(sent)), JSON.readTree(engine
                .onlyRequest()
                .body()));

    }

    @Test
    @DisplayName("A lock extended without a duration sends none, so the subscription's duration applies")
    void aLockExtendedWithoutADuration() throws Exception {

        engine.answers("POST", "/v1/jobs/1/extend-lock", 200, """
                {"lockUntil": "2026-10-13T15:20:30Z"}""");

        client.extendJobLock(1, "app-orders-1a2b3c4d", null);

        assertEquals(JSON.readTree("""
                {"clientId": "app-orders-1a2b3c4d"}"""), JSON.readTree(engine
                .onlyRequest()
                .body()));

    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "PT0S", "PT-1S"
    })
    @DisplayName("A lock duration which is not positive is refused before anything is sent")
    void aLockDurationWhichIsNotPositiveIsRefused(
        final String duration
    ) {

        final var failure = assertThrows(IllegalArgumentException.class,
                () -> client.extendJobLock(1, "app-orders-1a2b3c4d", Duration.parse(duration)));

        assertEquals("A lock is extended by a positive duration, but %s was asked for".formatted(duration),
                failure.getMessage());
        assertEquals(List.of(), engine.requests());

    }

    @Test
    @DisplayName("The OpenAPI document is read where the engine serves it, and is empty on 404")
    void theOpenApiDocument() {

        engine.answers("GET", "/v1/openapi", 200, "{\"openapi\": \"3.0.0\"}");

        assertEquals(Optional.of("{\"openapi\": \"3.0.0\"}"), client.openApi());

        engine.answers("GET", "/v1/openapi", 404, null);

        assertEquals(Optional.empty(), client.openApi());

    }

    @Test
    @DisplayName("Every call asks for JSON")
    void everyCallAsksForJson() {

        engine.answers("GET", "/v1/jobs/1", 200, JOB);
        engine.answers("POST", "/v1/jobs/1/complete", 201, null);

        client.job(1);
        client.completeJob(1, null);

        assertEquals(List.of("application/json", "application/json"), engine
                .requests()
                .stream()
                .map(FakeEngine.Request::accept)
                .toList());

    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "http://127.0.0.1:%d", "http://127.0.0.1:%d/v1", "http://127.0.0.1:%d/v1/"
    })
    @DisplayName("'/v1' is appended to the address once, and '/system' stays beside it")
    void theApiPathIsAppendedOnce(
        final String restAddress
    ) {

        try (var addressed = clientOf(restAddress.formatted(port()))) {
            engine.answers("GET", "/v1/jobs/1", 404, null);
            engine.answers("GET", "/system/health/ready", 200, "{\"status\": \"UP\"}");

            addressed.job(1);
            addressed.ready();
        }

        assertEquals(List.of("/v1/jobs/1", "/system/health/ready"), paths());

    }

    @Test
    @DisplayName("A path in front of the engine, as a proxy has it, is kept for both '/v1' and '/system'")
    void aPathInFrontOfTheEngineIsKept() {

        try (var addressed = clientOf("http://127.0.0.1:%d/zenbpm".formatted(port()))) {
            engine.answers("GET", "/zenbpm/v1/jobs/1", 404, null);
            engine.answers("GET", "/zenbpm/system/status", 200, """
                    {"git": {"commitId": "bdd795a399b6"}, "build": {"version": "v1.8.0"}, "nodes": {}}""");

            addressed.job(1);
            addressed.status();
        }

        assertEquals(List.of("/zenbpm/v1/jobs/1", "/zenbpm/system/status"), paths());

    }

    @Test
    @DisplayName("An unexpected status names adapter, method, path, status and the engine's error, never the rest of the body")
    void anUnexpectedStatusIsAnApiException() {

        engine.answers("POST", "/v1/jobs/42/complete", 409, """
                {"code": "CONFLICT", "message": "job 42 is terminated", "details": "secret-detail"}""");

        final var failure = assertThrows(ZenBpmApiException.class, () -> client.completeJob(42, null));

        assertAll(
                () -> assertEquals(ADAPTER_ID, failure.getAdapterId()),
                () -> assertEquals(409, failure.getStatus()),
                () -> assertEquals("CONFLICT", failure.getEngineCode()),
                () -> assertEquals("job 42 is terminated", failure.getEngineMessage()),
                () -> assertEquals("POST", failure.getMethod()),
                () -> assertEquals("/v1/jobs/42/complete", failure.getPath()),
                () -> assertEquals(
                        "ZenBPM adapter 'orders': POST /v1/jobs/42/complete was answered with status 409, CONFLICT: job 42 is terminated",
                        failure.getMessage()));

    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "<html><body>Bad Gateway, secret-detail</body></html>", "", "null", "[\"secret-detail\"]"
    })
    @DisplayName("An unexpected status without the engine's error, as a proxy answers, names the status only")
    void anAnswerOfAProxyNamesTheStatusOnly(
        final String body
    ) {

        engine.answers("GET", "/v1/process-definitions/1", 502, body.isEmpty() ? null : body);

        final var failure = assertThrows(ZenBpmApiException.class, () -> client.processDefinition(1));

        assertAll(
                () -> assertEquals(502, failure.getStatus()),
                () -> assertNull(failure.getEngineCode()),
                () -> assertNull(failure.getEngineMessage()),
                () -> assertEquals(
                        "ZenBPM adapter 'orders': GET /v1/process-definitions/1 was answered with status 502",
                        failure.getMessage()));

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("partialEngineErrors")
    @DisplayName("An engine error with only a code or only a message names what it has")
    void anEngineErrorWithOnlyACodeOrAMessage(
        final String body,
        final String message
    ) {

        engine.answers("GET", "/v1/process-definitions/1", 400, body);

        assertEquals(message, assertThrows(ZenBpmApiException.class, () -> client.processDefinition(1)).getMessage());

    }

    @Test
    @DisplayName("A query is no part of the path an exception names")
    void aQueryIsNoPartOfThePath() {

        engine.answers("GET", "/v1/process-instances", 400, """
                {"code": "BAD_REQUEST", "message": "invalid"}""");

        final var failure = assertThrows(ZenBpmApiException.class, () -> client.instances(InstanceFilter
                .anyInstance()
                .withBusinessKey("order-1")));

        assertEquals("/v1/process-instances", failure.getPath());
        assertFalse(failure
                .getMessage()
                .contains("order-1"), failure::getMessage);

    }

    @Test
    @DisplayName("Fields the client does not know are ignored")
    void unknownFieldsAreIgnored() {

        engine.answers("GET", "/v1/jobs/1", 200, JOB.replace("\"retries\": 3", """
                "retries": 3, "aFieldOfANewerEngine": {"nested": [1, 2]}"""));

        assertTrue(client
                .job(1)
                .isPresent());

    }

    @Test
    @DisplayName("Variables are decoded as Long, BigInteger, Double, String, Boolean, null, Map and List")
    void variablesAreDecodedAsDocumented() {

        engine.answers("GET", "/v1/jobs/1", 200, JOB.replace("{\"orderId\": \"order-1\"}", """
                {"whole": 1000, "large": 92233720368547758070, "fraction": 0.5, "exponent": 1e3,
                 "text": "x", "flag": true, "nothing": null, "object": {"inner": 1}, "array": [1, "a"]}"""));

        final var variables = client
                .job(1)
                .orElseThrow()
                .inputVariables();

        assertAll(
                () -> assertEquals(1000L, variables.get("whole")),
                () -> assertEquals(new BigInteger("92233720368547758070"), variables.get("large")),
                () -> assertEquals(0.5, variables.get("fraction")),
                () -> assertEquals(1000.0, variables.get("exponent")),
                () -> assertEquals("x", variables.get("text")),
                () -> assertEquals(true, variables.get("flag")),
                () -> assertTrue(variables.containsKey("nothing")),
                () -> assertNull(variables.get("nothing")),
                () -> assertEquals(Map.of("inner", 1L), variables.get("object")),
                () -> assertEquals(List.of(1L, "a"), variables.get("array")));

    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Variables read cannot be changed afterwards, nested ones neither")
    void variablesReadCannotBeChanged() {

        engine.answers("GET", "/v1/jobs/1", 200, JOB.replace("{\"orderId\": \"order-1\"}", """
                {"orderId": "order-1", "object": {"inner": 1}, "array": [1, 2]}"""));

        final var variables = client
                .job(1)
                .orElseThrow()
                .inputVariables();

        assertThrows(UnsupportedOperationException.class, () -> variables.put("orderId", "another"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) variables.get("object")).put("inner", 2));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) variables.get("array")).add(3));

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableAnswers")
    @DisplayName("A successful answer the client cannot read means the engine gave no usable answer")
    void anUnreadableAnswerIsNoAnswer(
        final String description,
        final String body
    ) {

        engine.answers("GET", "/v1/jobs/1", 200, body);

        final var failure = assertThrows(ZenBpmUnavailableException.class, () -> client.job(1));

        assertAll(
                () -> assertEquals(ADAPTER_ID, failure.getAdapterId()),
                () -> assertEquals("GET", failure.getMethod()),
                () -> assertEquals("/v1/jobs/1", failure.getPath()),
                () -> assertEquals(OptionalInt.of(200), failure.getStatus()),
                () -> assertEquals(
                        "ZenBPM adapter 'orders': GET /v1/jobs/1 was answered with status 200, but the answer cannot be read",
                        failure.getMessage()));

    }

    @Test
    @DisplayName("A start whose answer cannot be read names the status, which says the instance was created")
    void aStartWhoseAnswerCannotBeReadNamesTheStatus() {

        engine.answers("POST", "/v1/process-instances", 201, "<html>maintenance</html>");

        final var failure = assertThrows(ZenBpmUnavailableException.class,
                () -> client.createInstance(CreateInstanceRequest.latestVersionOf("orders_order")));

        assertEquals(OptionalInt.of(201), failure.getStatus());

    }

    @Test
    @DisplayName("An answer which does not arrive within the request timeout ends the call in time")
    void anAnswerWhichDoesNotArriveEndsTheCallInTime() {

        engine.answers("GET", "/v1/jobs/1", exchange -> sleepLongerThanTheTimeout());

        final var failure = assertUnavailableInTime(() -> client.job(1));

        assertInstanceOf(HttpTimeoutException.class, failure.getCause());
        assertEquals(OptionalInt.empty(), failure.getStatus());
        assertEquals(
                "ZenBPM adapter 'orders': GET /v1/jobs/1 got no answer within PT1S, which 'vanillabp.adapters.orders.request-timeout' allows",
                failure.getMessage());

    }

    @Test
    @DisplayName("A body which stops arriving ends the call in time too")
    void aBodyWhichStopsArrivingEndsTheCallInTime() {

        engine.answers("GET", "/v1/jobs/1", exchange -> stallTheBody(exchange, 200));

        final var failure = assertUnavailableInTime(() -> client.job(1));

        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertEquals(OptionalInt.of(200), failure.getStatus());
        assertEquals(
                "ZenBPM adapter 'orders': GET /v1/jobs/1 was answered with status 200, but the body did not arrive within PT1S, which 'vanillabp.adapters.orders.request-timeout' allows",
                failure.getMessage());

    }

    @Test
    @DisplayName("An unexpected status whose body stops arriving is the engine's answer all the same")
    void anUnexpectedStatusWhoseBodyStopsArriving() {

        engine.answers("POST", "/v1/jobs/1/complete", exchange -> stallTheBody(exchange, 400));

        final var failure = assertTimeoutPreemptively(REQUEST_TIMEOUT.plus(TIMEOUT_TOLERANCE),
                () -> assertThrows(ZenBpmApiException.class, () -> client.completeJob(1, null)));

        assertAll(
                () -> assertEquals(400, failure.getStatus()),
                () -> assertNull(failure.getEngineCode()),
                () -> assertNull(failure.getEngineMessage()),
                () -> assertInstanceOf(TimeoutException.class, failure.getCause()),
                () -> assertEquals("ZenBPM adapter 'orders': POST /v1/jobs/1/complete was answered with status 400",
                        failure.getMessage()));

    }

    @Test
    @DisplayName("An unexpected status whose body breaks off is the engine's answer all the same")
    void anUnexpectedStatusWhoseBodyBreaksOff() {

        engine.answers("POST", "/v1/jobs/1/complete", exchange -> breakOffTheBody(exchange, 409));

        final var failure = assertThrows(ZenBpmApiException.class, () -> client.completeJob(1, null));

        assertEquals(409, failure.getStatus());
        assertInstanceOf(IOException.class, failure.getCause());

    }

    @Test
    @DisplayName("An expected status whose body breaks off is no usable answer, and names the status")
    void anExpectedStatusWhoseBodyBreaksOff() {

        engine.answers("GET", "/v1/jobs/1", exchange -> breakOffTheBody(exchange, 200));

        final var failure = assertThrows(ZenBpmUnavailableException.class, () -> client.job(1));

        assertEquals(OptionalInt.of(200), failure.getStatus());
        assertTrue(failure
                .getMessage()
                .startsWith(
                        "ZenBPM adapter 'orders': GET /v1/jobs/1 was answered with status 200, but the body did not arrive ("),
                failure::getMessage);

    }

    @Test
    @DisplayName("An engine nobody listens for is unavailable")
    void anEngineNobodyListensForIsUnavailable() throws IOException {

        final int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        try (var unreachable = clientOf("http://127.0.0.1:%d".formatted(closedPort))) {
            final var failure = assertThrows(ZenBpmUnavailableException.class, unreachable::status);

            assertEquals("/system/status", failure.getPath());
            assertTrue(failure
                    .getMessage()
                    .startsWith("ZenBPM adapter 'orders': GET /system/status got no answer (java.net.ConnectException"),
                    failure::getMessage);
        }

    }

    @Test
    @DisplayName("An interrupted call ends at once and leaves the thread interrupted")
    void anInterruptedCallEndsAtOnce() {

        engine.answers("GET", "/v1/jobs/1", exchange -> sleepLongerThanTheTimeout());
        Thread
                .currentThread()
                .interrupt();

        final var failure = assertThrows(ZenBpmUnavailableException.class, () -> client.job(1));

        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
        assertEquals("ZenBPM adapter 'orders': GET /v1/jobs/1 got no answer before the calling thread was interrupted",
                failure.getMessage());

    }

    @Test
    @DisplayName("Variables which cannot be written as JSON are refused before anything is sent")
    void variablesWhichCannotBeWrittenAreRefused() {

        final var failure = assertThrows(IllegalArgumentException.class,
                () -> client.completeJob(1, Map.of("thing", new Object())));

        assertEquals("ZenBPM adapter 'orders': the request of POST /v1/jobs/1/complete cannot be written as JSON",
                failure.getMessage());
        assertEquals(List.of(), engine.requests());

    }

    @Test
    @DisplayName("Closing the client does not wait for a call which ran out of time")
    void closingDoesNotWaitForACallWhichRanOutOfTime() {

        // the headers arrive and the body stops, so only the client's own wait ends the call
        engine.answers("GET", "/v1/jobs/1", exchange -> stallTheBody(exchange, 200));
        final var closing = clientOf(engine.address());
        assertUnavailableInTime(() -> closing.job(1));

        assertTimeoutPreemptively(TIMEOUT_TOLERANCE, closing::close,
                "Closing waited for the call which had run out of time");

    }

    @Test
    @DisplayName("A call after the client was closed is refused, and is no sign of an unavailable engine")
    void aCallAfterCloseIsRefused() {

        client.close();

        final var failure = assertThrows(IllegalStateException.class, () -> client.job(1));

        assertEquals("ZenBPM adapter 'orders': GET /v1/jobs/1 was called after the client was closed",
                failure.getMessage());
        assertEquals(List.of(), engine.requests());

    }

    @Test
    @DisplayName("Building a client contacts nothing")
    void buildingAClientContactsNothing() {

        try (var built = clientOf(engine.address())) {
            assertEquals(List.of(), engine.requests());
        }

    }

    @ParameterizedTest(name = "page {0}, size {1}")
    @MethodSource("pagesOutOfRange")
    @DisplayName("A page or a size the engine would refuse is refused by the filter")
    void aPageOutOfRangeIsRefused(
        final int page,
        final int size,
        final String message
    ) {

        assertEquals(message, assertThrows(IllegalArgumentException.class, () -> InstanceFilter
                .anyInstance()
                .withPage(page)
                .withSize(size)).getMessage());
        assertEquals(message, assertThrows(IllegalArgumentException.class, () -> JobFilter
                .anyJob()
                .withPage(page)
                .withSize(size)).getMessage());

    }

    @Test
    @DisplayName("A start which names no process is refused by the request")
    void aStartWhichNamesNoProcessIsRefused() {

        final var failure = assertThrows(IllegalArgumentException.class,
                () -> new CreateInstanceRequest(null, null, "order-1", null, null));

        assertEquals("A start names the process or the version of a process to start", failure.getMessage());

    }

    private static Stream<Arguments> deploymentAnswers() {

        return Stream.of(Arguments.of(201, true), Arguments.of(200, false));

    }

    private static Stream<Arguments> instanceStates() {

        return Stream.of(
                Arguments.of("active", ProcessInstanceRef.State.ACTIVE),
                Arguments.of("completed", ProcessInstanceRef.State.COMPLETED),
                Arguments.of("terminated", ProcessInstanceRef.State.TERMINATED),
                Arguments.of("failed", ProcessInstanceRef.State.FAILED));

    }

    private static Stream<Arguments> processTypes() {

        return Stream.of(
                Arguments.of("default", ProcessInstanceRef.ProcessType.DEFAULT),
                Arguments.of("multiInstance", ProcessInstanceRef.ProcessType.MULTI_INSTANCE),
                Arguments.of("subprocess", ProcessInstanceRef.ProcessType.SUBPROCESS),
                Arguments.of("callActivity", ProcessInstanceRef.ProcessType.CALL_ACTIVITY));

    }

    private static Stream<Arguments> unknownInstanceValues() {

        return Stream.of(
                Arguments.of("paused", "default", "the process instance state 'paused' is unknown"),
                Arguments.of("active", "eventSubprocess", "the process type 'eventSubprocess' is unknown"));

    }

    private static Stream<Arguments> jobStates() {

        return Stream.of(
                Arguments.of("active", JobRef.State.ACTIVE),
                Arguments.of("completed", JobRef.State.COMPLETED),
                Arguments.of("terminated", JobRef.State.TERMINATED),
                Arguments.of("failed", JobRef.State.FAILED));

    }

    private static Stream<Arguments> partialEngineErrors() {

        return Stream.of(
                Arguments.of("{\"code\": \"BAD_REQUEST\"}",
                        "ZenBPM adapter 'orders': GET /v1/process-definitions/1 was answered with status 400, BAD_REQUEST"),
                Arguments.of("{\"message\": \"processDefinitionKey must be > 0\"}",
                        "ZenBPM adapter 'orders': GET /v1/process-definitions/1 was answered with status 400: processDefinitionKey must be > 0"));

    }

    private static Stream<Arguments> unreadableAnswers() {

        return Stream.of(
                Arguments.of("not JSON", "<html>maintenance</html>"),
                Arguments.of("no body", ""),
                Arguments.of("null", "null"),
                Arguments.of("a required field missing", JOB.replace("\"key\": 4503599627370540,", "")),
                Arguments.of("a required number null", JOB.replace("4503599627370540", "null")),
                Arguments.of("a required text null", JOB.replace("\"Task_Ship\"", "null")),
                Arguments.of("a whole number with a fraction", JOB.replace("4503599627370540", "7.9")),
                Arguments.of("a state the pinned API does not know", JOB.replace("\"active\"", "\"paused\"")),
                Arguments.of("a timestamp which is none", JOB.replace("2026-10-13T15:10:26Z", "yesterday")),
                Arguments.of("variables null", JOB.replace("{\"orderId\": \"order-1\"}", "null")));

    }

    private static Stream<Arguments> lockDurations() {

        return Stream.of(
                Arguments.of(Duration.ofMinutes(5), "PT300S"),
                Arguments.of(Duration.ofSeconds(30), "PT30S"),
                Arguments.of(Duration.ofMillis(1_500), "PT2S"),
                Arguments.of(Duration.ofNanos(1), "PT1S"));

    }

    private static Stream<Arguments> pagesOutOfRange() {

        return Stream.of(
                Arguments.of(0, 10, "A page is counted from 1, but 0 was asked for"),
                Arguments.of(1, 0, "A page holds 1 to 100 items, but 0 were asked for"),
                Arguments.of(1, 101, "A page holds 1 to 100 items, but 101 were asked for"));

    }

    private static ZenBpmRestClient clientOf(
        final String restAddress
    ) {

        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        configuration.setRequestTimeout(REQUEST_TIMEOUT);
        return new ZenBpmRestClient(ADAPTER_ID, configuration);

    }

    private static String instance(
        final long key,
        final String state,
        final String processType
    ) {

        return """
                {"key": %d, "processDefinitionKey": 2, "version": 1, "createdAt": "2026-10-13T14:30:25Z",
                 "state": "%s", "processType": "%s", "variables": {}}""".formatted(key, state, processType);

    }

    private static void sleepLongerThanTheTimeout() {

        try {
            Thread.sleep(LONGER_THAN_THE_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            // the fake engine was stopped
            Thread
                    .currentThread()
                    .interrupt();
        }

    }

    private static boolean isFirstPage(
        final HttpExchange exchange
    ) {

        return exchange
                .getRequestURI()
                .getRawQuery()
                .contains("page=1&");

    }

    private static void stallTheBody(
        final HttpExchange exchange,
        final int status
    ) throws IOException {

        exchange.sendResponseHeaders(status, 1_000);
        exchange
                .getResponseBody()
                .write("{\"key\":".getBytes(StandardCharsets.UTF_8));
        exchange
                .getResponseBody()
                .flush();
        sleepLongerThanTheTimeout();

    }

    // fewer bytes than announced, then the connection ends; flushed, or the headers end with it
    private static void breakOffTheBody(
        final HttpExchange exchange,
        final int status
    ) throws IOException {

        exchange.sendResponseHeaders(status, 1_000);
        exchange
                .getResponseBody()
                .write("{\"key\":".getBytes(StandardCharsets.UTF_8));
        exchange
                .getResponseBody()
                .flush();
        exchange.close();

    }

    private static ZenBpmUnavailableException assertUnavailableInTime(
        final Runnable call
    ) {

        // preemptively, so a call which never ends fails the test instead of hanging the build
        return assertTimeoutPreemptively(REQUEST_TIMEOUT.plus(TIMEOUT_TOLERANCE),
                () -> assertThrows(ZenBpmUnavailableException.class, call::run),
                () -> "The call took longer than the request timeout %s plus %s".formatted(REQUEST_TIMEOUT,
                        TIMEOUT_TOLERANCE));

    }

    private int port() {

        return Integer.parseInt(engine
                .address()
                .substring(engine
                        .address()
                        .lastIndexOf(':') + 1));

    }

    private List<String> queries() {

        return engine
                .requests()
                .stream()
                .map(FakeEngine.Request::query)
                .toList();

    }

    private List<String> paths() {

        return engine
                .requests()
                .stream()
                .map(FakeEngine.Request::path)
                .toList();

    }

    private List<Object> bodies() {

        return engine
                .requests()
                .stream()
                .map(request -> {
                    try {
                        return (Object) JSON.readTree(request.body());
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();

    }

}
