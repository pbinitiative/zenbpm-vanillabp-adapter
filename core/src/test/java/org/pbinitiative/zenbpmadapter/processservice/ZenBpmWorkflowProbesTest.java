/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The probes which ask for the workflow of an aggregate: the election's, with and without the key
 * of the workflow, and the one asked before a start is sent again. The engine is a fake which
 * answers what each test tells it to and writes down what it was asked, so every test also sees
 * how many requests a probe sent. The module is scoped with a prefix, so the scope's processes are
 * <code>shop__Order</code> and <code>shop__Invoice</code> at the engine.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmWorkflowProbesTest {

    private static final WorkflowScope SCOPE = new WorkflowScope("shop", List.of("Order", "Invoice"));

    private static final String WORKFLOW_ID = "4503599627370501";

    private static final String INSTANCE_PATH = "/v1/process-instances/%s".formatted(WORKFLOW_ID);

    private static final String SEARCH_PATH = "/v1/process-instances";

    private final FakeEngine engine = FakeEngine.started();

    private final ZenBpmClientFactory factory = configured(engine.address(), Duration.ofSeconds(5));

    private final ZenBpmProcessService<Object> processService = processService(factory);

    @AfterEach
    void stopTheEngine() {
        factory.close();
        engine.close();
    }

    @Test
    @DisplayName("A running workflow is found by its business key, and the search asks for no further process once one runs")
    void aRunningWorkflowIsFoundByItsBusinessKey() {
        searchFinds(Map.of("shop__Order", page(instance("shop__Order", "active", "order-1"))));

        final var awareness = processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1");

        assertAll(
                () -> assertEquals(WorkflowAwareness.ACTIVE, awareness),
                () -> assertEquals(List.of(
                        "GET /v1/process-instances businessKey=order-1&bpmnProcessId=shop__Order&page=1&size=100&sortBy=key&sortOrder=asc"),
                        requests()));
    }

    @Test
    @DisplayName("A workflow with an incident runs, and one whose every instance ended is completed")
    void anIncidentRunsAndEndedInstancesAreCompleted() {
        searchFinds(Map.of("shop__Invoice", page(instance("shop__Invoice", "failed", "order-1"))));
        final var withAnIncident = processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1");
        searchFinds(Map.of("shop__Order", page(instance("shop__Order", "completed", "order-1"), instance(
                "shop__Order", "terminated", "order-1"))));
        final var ended = processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1");

        assertAll(
                () -> assertEquals(WorkflowAwareness.ACTIVE, withAnIncident),
                () -> assertEquals(WorkflowAwareness.COMPLETED, ended),
                () -> assertEquals(4, engine
                        .requests()
                        .size()));
    }

    @Test
    @DisplayName("A second workflow of the aggregate which runs wins over its first which ended, in whichever process")
    void aRunningWorkflowWinsOverAnEndedOne() {
        searchFinds(Map.of("shop__Order", page(instance("shop__Order", "completed", "order-1")), "shop__Invoice",
                page(instance("shop__Invoice", "active", "order-1"))));

        assertEquals(WorkflowAwareness.ACTIVE, processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1"));
    }

    @Test
    @DisplayName("A workflow of another module of the same engine with the same aggregate id is unknown")
    void aWorkflowOfAnotherModuleIsUnknown() {
        searchFinds(Map.of("crm__Order", page(instance("crm__Order", "active", "order-1"))));
        engine.answers("GET", "/v1/process-instances/4503599627370777", 200, instanceAt(4503599627370777L,
                "crm__Order", "active", "order-1", "{}"));

        final var awareness = processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1", "4503599627370777");

        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, awareness),
                () -> assertTrue(requests()
                        .stream()
                        .filter(request -> request.startsWith("GET /v1/process-instances "))
                        .allMatch(request -> request.contains("bpmnProcessId=shop__")), () -> requests().toString()),
                () -> assertTrue(requests().size() <= SCOPE
                        .bpmnProcessIds()
                        .size() + 1, () -> requests().toString()));
    }

    @Test
    @DisplayName("No workflow of the aggregate is unknown, after one search per process of the scope")
    void noWorkflowIsUnknown() {
        searchFinds(Map.of());

        final var awareness = processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1");

        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, awareness),
                () -> assertEquals(2, requests().size()));
    }

    @Test
    @DisplayName("The key of a running workflow of the scope and the aggregate is active at once, without a search, also where only its id variable names the aggregate")
    void theKeyOfARunningWorkflowIsActiveAtOnce() {
        engine.answers("GET", INSTANCE_PATH, 200, instanceAt(4503599627370501L, "shop__Invoice", "active", null,
                "{\"orderId\": \"order-1\"}"));
        final var byTheIdVariable = processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1",
                WORKFLOW_ID);
        engine.answers("GET", INSTANCE_PATH, 200, instanceAt(4503599627370501L, "shop__Order", "failed", "order-1",
                "{}"));
        final var byTheBusinessKey = processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1",
                WORKFLOW_ID);

        assertAll(
                () -> assertEquals(WorkflowAwareness.ACTIVE, byTheIdVariable),
                () -> assertEquals(WorkflowAwareness.ACTIVE, byTheBusinessKey),
                () -> assertEquals(List.of("GET %s null".formatted(INSTANCE_PATH), "GET %s null".formatted(
                        INSTANCE_PATH)), requests()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keysWhichAreNoAnswer")
    @DisplayName("Whatever else the key leads to falls through to the search, which answers")
    void whateverElseTheKeyLeadsToFallsThroughToTheSearch(
        final String what,
        final String workflowId,
        final FakeEngine.Handler answer
    ) {
        if (answer != null) {
            engine.answers("GET", "/v1/process-instances/%s".formatted(workflowId), answer);
        }
        searchFinds(Map.of("shop__Order", page(instance("shop__Order", "completed", "order-1"))));
        final var whereItEnded = processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1", workflowId);
        searchFinds(Map.of());
        final var whereNothingIsFound = processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1",
                workflowId);

        assertAll(
                () -> assertEquals(WorkflowAwareness.COMPLETED, whereItEnded),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, whereNothingIsFound),
                () -> assertEquals(answer == null ? 4 : 6, requests().size(), () -> requests().toString()));
    }

    @Test
    @DisplayName("A key which names an ended workflow is never the answer, so a running second workflow of the aggregate is found")
    void aKeyOfAnEndedWorkflowFindsTheSecondOne() {
        engine.answers("GET", INSTANCE_PATH, 200, instanceAt(4503599627370501L, "shop__Order", "completed",
                "order-1", "{}"));
        searchFinds(Map.of("shop__Order", page(instance("shop__Order", "active", "order-1"))));

        assertEquals(WorkflowAwareness.ACTIVE, processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1",
                WORKFLOW_ID));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failedSearches")
    @DisplayName("A search which fails is unavailable, never unknown, also after a key which did not answer")
    void aFailedSearchIsUnavailable(
        final String what,
        final FakeEngine.Handler answer
    ) {
        final var factory = configured(engine.address(), Duration.ofMillis(300));
        final var processService = processService(factory);
        engine.answers("GET", SEARCH_PATH, answer);
        engine.answers("GET", INSTANCE_PATH, answer);

        try (factory) {
            assertAll(
                    () -> assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, processService.awarenessOfWorkflow(SCOPE,
                            persistence(), "order-1")),
                    () -> assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, processService.awarenessOfWorkflow(SCOPE,
                            persistence(), "order-1", WORKFLOW_ID)),
                    () -> assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, processService
                            .awarenessOfWorkflowForRedispatch(SCOPE, persistence(), "order-1", Instant.now())));
        }
    }

    @Test
    @DisplayName("The first failure after an answer is a WARN, the next ones are not, until the engine answered a probe again")
    void theFirstFailureAfterAnAnswerIsAWarn(final CapturedOutput output) {
        engine.answers("GET", SEARCH_PATH, 503, null);
        processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1");
        processService.awarenessOfWorkflowForRedispatch(SCOPE, persistence(), "order-2", null);
        final var warnedWhileAway = warnings(output);
        searchFinds(Map.of());
        processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1");
        engine.answers("GET", SEARCH_PATH, 503, null);
        processService.awarenessOfWorkflow(SCOPE, persistence(), "order-3");

        assertAll(
                () -> assertEquals(List.of(
                        "ZenBPM[orders]: the engine could not be asked for the workflow of aggregate 'order-1', so the probe reports BPMS_UNAVAILABLE; check that the engine at vanillabp.adapters.orders.rest-address answers within vanillabp.adapters.orders.request-timeout; further failures are logged at DEBUG until the engine answers a probe again"),
                        warnedWhileAway),
                () -> assertEquals(2, warnings(output).size()),
                () -> assertTrue(warnings(output)
                        .getLast()
                        .contains("aggregate 'order-3'"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("An answer from the key alone counts as an answer, so the next failure is a WARN again")
    void anAnswerFromTheKeyAloneEndsTheFailures(final CapturedOutput output) {
        engine.answers("GET", SEARCH_PATH, 503, null);
        processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1");
        engine.answers("GET", INSTANCE_PATH, 200, instanceAt(4503599627370501L, "shop__Order", "active", "order-1",
                "{}"));
        final var fromTheKey = processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1", WORKFLOW_ID);
        processService.awarenessOfWorkflow(SCOPE, persistence(), "order-3");

        assertAll(
                () -> assertEquals(WorkflowAwareness.ACTIVE, fromTheKey),
                () -> assertEquals(2, warnings(output).size(), output::getAllOfThisTest),
                () -> assertTrue(warnings(output)
                        .getLast()
                        .contains("aggregate 'order-3'"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("More instances of one process than a page holds, none of those read running: the engine counts the running ones")
    void moreInstancesThanAPageAreCountedByTheEngine() {
        final var ended = IntStream
                .range(0, 100)
                .mapToObj(index -> instance("shop__Order", "completed", "order-1"))
                .toArray(String[]::new);
        final var counted = new ArrayList<String>();
        engine.answers("GET", SEARCH_PATH, exchange -> {
            final var query = exchange
                    .getRequestURI()
                    .getRawQuery();
            if (query.contains("state=")) {
                counted.add(query);
                FakeEngine.respond(exchange, 200, query.contains("state=failed") ? pageOf(1) : pageOf(0));
                return;
            }
            FakeEngine.respond(exchange, 200, query.contains("bpmnProcessId=shop__Order")
                    ? pageOf(150, ended)
                    : pageOf(0));
        });

        final var withAnIncidentBeyondThePage = processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1");

        assertAll(
                () -> assertEquals(WorkflowAwareness.ACTIVE, withAnIncidentBeyondThePage),
                () -> assertEquals(List.of(
                        "businessKey=order-1&bpmnProcessId=shop__Order&state=active&page=1&size=1&sortBy=key&sortOrder=asc",
                        "businessKey=order-1&bpmnProcessId=shop__Order&state=failed&page=1&size=1&sortBy=key&sortOrder=asc"),
                        counted));

        engine.answers("GET", SEARCH_PATH, exchange -> FakeEngine.respond(exchange, 200, exchange
                .getRequestURI()
                .getRawQuery()
                .contains("state=") ? pageOf(0) : pageOf(150, ended)));
        assertEquals(WorkflowAwareness.COMPLETED, processService.awarenessOfWorkflow(SCOPE, persistence(), "order-1"));
    }

    @Test
    @DisplayName("Before a start is sent again, only the search counts, ended workflows included, from the millisecond the start was planned in")
    void theRedispatchProbeCountsFromThePlannedMoment() {
        searchFinds(Map.of("shop__Order", page(instance("shop__Order", "completed", "order-1"))));
        engine.answers("GET", INSTANCE_PATH, 200, instanceAt(4503599627370501L, "shop__Order", "active", "order-1",
                "{}"));

        final var onTheMillisecond = processService.awarenessOfWorkflowForRedispatch(SCOPE, persistence(),
                "order-1", Instant.parse("2026-10-17T10:00:00.123Z"));
        final var insideAMillisecond = processService.awarenessOfWorkflowForRedispatch(SCOPE, persistence(),
                "order-1", Instant.parse("2026-10-17T10:00:00.123000001Z"));
        searchFinds(Map.of("shop__Invoice", page(instance("shop__Invoice", "active", "order-1"))));
        final var withoutAMoment = processService.awarenessOfWorkflowForRedispatch(SCOPE, persistence(), "order-1");
        searchFinds(Map.of());
        final var nothing = processService.awarenessOfWorkflowForRedispatch(SCOPE, persistence(), "order-1", Instant
                .now());

        final var requests = requests();
        assertAll(
                () -> assertEquals(WorkflowAwareness.COMPLETED, onTheMillisecond),
                () -> assertEquals(WorkflowAwareness.COMPLETED, insideAMillisecond),
                () -> assertEquals(WorkflowAwareness.ACTIVE, withoutAMoment),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, nothing),
                () -> assertEquals(
                        "GET /v1/process-instances businessKey=order-1&bpmnProcessId=shop__Order&createdFrom=2026-10-17T10%3A00%3A00.123Z&page=1&size=100&sortBy=key&sortOrder=asc",
                        requests.getFirst()),
                () -> assertTrue(requests
                        .get(2)
                        .contains("createdFrom=2026-10-17T10%3A00%3A00.124Z"), requests::toString),
                () -> assertTrue(requests
                        .subList(4, 6)
                        .stream()
                        .noneMatch(request -> request.contains("createdFrom")), requests::toString),
                () -> assertTrue(requests
                        .stream()
                        .noneMatch(request -> request.startsWith("GET %s".formatted(INSTANCE_PATH))),
                        requests::toString),
                () -> assertTrue(requests
                        .stream()
                        .noneMatch(request -> request.contains("state=")), requests::toString));
    }

    @Test
    @DisplayName("The probe asks, a workflow may stay invisible for workflow-visibility-timeout, asked every quarter of a second, and the window of one workflow is the same")
    void theWindowIsTheVisibilityTimeout() {
        final var longer = new ZenBpmAdapterConfiguration();
        longer.setWorkflowVisibilityTimeout(Duration.ofSeconds(5));
        final var configured = processService(ZenBpmClientFactory.unconfigured("orders", longer, "unset"));
        final var none = new ZenBpmAdapterConfiguration();
        none.setWorkflowVisibilityTimeout(Duration.ZERO);
        final var switchedOff = processService(ZenBpmClientFactory.unconfigured("orders", none, "unset"));

        assertAll(
                () -> assertTrue(processService.canLocateWorkflows()),
                () -> assertEquals(new WorkflowVisibilityDelay(Duration.ofSeconds(2), Duration.ofMillis(250)),
                        processService.workflowVisibilityDelay()),
                () -> assertEquals(processService.workflowVisibilityDelay(), processService.workflowVisibilityDelay(
                        WORKFLOW_ID)),
                () -> assertEquals(Duration.ZERO, switchedOff
                        .workflowVisibilityDelay()
                        .window()),
                () -> assertFalse(switchedOff
                        .workflowVisibilityDelay()
                        .isWaiting()),
                () -> assertEquals(new WorkflowVisibilityDelay(Duration.ofSeconds(5), Duration.ofMillis(250)),
                        configured.workflowVisibilityDelay()),
                () -> assertEquals(List.of(), engine.requests()));
    }

    @Test
    @DisplayName("An adapter id without clients refuses the probes with the message of its validation and asks nothing")
    void anAdapterIdWithoutClientsRefusesTheProbes() {
        final var unconfigured = processService(ZenBpmClientFactory.unconfigured("orders",
                new ZenBpmAdapterConfiguration(), "ZenBPM adapter 'orders' is not configured: set the key"));

        assertAll(Stream.<Runnable>of(
                () -> unconfigured.awarenessOfWorkflow(SCOPE, persistence(), "order-1"),
                () -> unconfigured.awarenessOfWorkflow(SCOPE, persistence(), "order-1", WORKFLOW_ID),
                () -> unconfigured.awarenessOfWorkflowForRedispatch(SCOPE, persistence(), "order-1"),
                () -> unconfigured.awarenessOfWorkflowForRedispatch(SCOPE, persistence(), "order-1", Instant.now()))
                .map(probe -> () -> assertEquals("ZenBPM adapter 'orders' is not configured: set the key",
                        assertThrows(IllegalStateException.class, probe::run).getMessage())));
        assertEquals(List.of(), engine.requests());
    }

    static Stream<Arguments> keysWhichAreNoAnswer() {
        return Stream.of(
                Arguments.of("a completed instance", WORKFLOW_ID, answer(200, instanceAt(4503599627370501L,
                        "shop__Order", "completed", "order-1", "{}"))),
                Arguments.of("a terminated instance", WORKFLOW_ID, answer(200, instanceAt(4503599627370501L,
                        "shop__Order", "terminated", "order-1", "{}"))),
                Arguments.of("an instance of a process outside the scope", WORKFLOW_ID, answer(200, instanceAt(
                        4503599627370501L, "shop__Shipment", "active", "order-1", "{}"))),
                Arguments.of("an instance of another aggregate", WORKFLOW_ID, answer(200, instanceAt(
                        4503599627370501L, "shop__Order", "active", "order-2", "{\"orderId\": \"order-2\"}"))),
                Arguments.of("an instance without business key and id variable", WORKFLOW_ID, answer(200, instanceAt(
                        4503599627370501L, "shop__Order", "active", null, "{\"amount\": 1000}"))),
                Arguments.of("a 404", WORKFLOW_ID, answer(404, """
                        {"code":"NOT_FOUND","message":"process instance not found"}""")),
                Arguments.of("a 502 for a key of a partition the engine does not have", WORKFLOW_ID, answer(502, """
                        {"code":"CLUSTER_ERROR","message":"partition not found"}""")),
                Arguments.of("a 500", WORKFLOW_ID, answer(500, """
                        {"code":"TECHNICAL_ERROR","message":"database is locked"}""")),
                Arguments.of("an answer which cannot be read", WORKFLOW_ID, answer(200, "{\"key\": 1")),
                Arguments.of("no key", null, null),
                Arguments.of("an id of another BPMS", "2251799813685249-a", null),
                Arguments.of("no positive number", "0", null));
    }

    static Stream<Arguments> failedSearches() {
        return Stream.of(
                Arguments.of("a 503", answer(503, null)),
                Arguments.of("a 502", answer(502, """
                        {"code":"CLUSTER_ERROR","message":"no leader"}""")),
                Arguments.of("a 400", answer(400, """
                        {"code":"BAD_REQUEST","message":"bad business key"}""")),
                Arguments.of("an answer which takes longer than request-timeout", (FakeEngine.Handler) exchange -> {
                    try {
                        Thread.sleep(2_000);
                    } catch (final InterruptedException e) {
                        Thread
                                .currentThread()
                                .interrupt();
                    }
                    FakeEngine.respond(exchange, 200, pageOf(0));
                }));
    }

    private void searchFinds(final Map<String, String> pagesByProcess) {
        engine.answers("GET", SEARCH_PATH, exchange -> {
            final var query = exchange
                    .getRequestURI()
                    .getRawQuery();
            final var page = pagesByProcess
                    .entrySet()
                    .stream()
                    .filter(byProcess -> query.contains("bpmnProcessId=%s&".formatted(byProcess.getKey())))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(pageOf(0));
            FakeEngine.respond(exchange, 200, page);
        });
    }

    private List<String> requests() {
        return engine
                .requests()
                .stream()
                .map(request -> "%s %s %s".formatted(request.method(), request.path(), request.query()))
                .toList();
    }

    private static List<String> warnings(final CapturedOutput output) {
        return output
                .getAllOfThisTest()
                .lines()
                .filter(line -> line.contains(" WARN "))
                .map(line -> line.substring(line.indexOf("ZenBPM[")))
                .toList();
    }

    private static FakeEngine.Handler answer(
        final int status,
        final String body
    ) {
        return exchange -> FakeEngine.respond(exchange, status, body);
    }

    private static String page(final String... items) {
        return pageOf(items.length, items);
    }

    // one partition holding the items, which reports the given number of matches
    private static String pageOf(
        final long totalCount,
        final String... items
    ) {
        return """
                {"partitions": [{"partition": 1, "totalCount": %d, "items": [%s]}],
                 "page": 1, "size": 100, "count": %d, "totalCount": %d}"""
                .formatted(totalCount, String.join(",", items), items.length, totalCount);
    }

    private static String instance(
        final String scopedProcessId,
        final String state,
        final String businessKey
    ) {
        return instanceAt(4503599627370501L, scopedProcessId, state, businessKey, "{}");
    }

    private static String instanceAt(
        final long key,
        final String scopedProcessId,
        final String state,
        final String businessKey,
        final String variables
    ) {
        return """
                {"key": %d, "processDefinitionKey": 4503599627370498, "bpmnProcessId": "%s", "version": 1,
                 %s"createdAt": "2026-10-17T10:00:00.123Z", "state": "%s", "processType": "default",
                 "variables": %s}"""
                .formatted(key, scopedProcessId, businessKey == null
                        ? ""
                        : "\"businessKey\": \"%s\", ".formatted(businessKey), state, variables);
    }

    private static AggregatePersistenceAware<Object> persistence() {
        return new AggregatePersistenceAware<>() {

            @Override
            public String getAggregateIdName() {
                return "orderId";
            }

            @Override
            public Object loadById(final Object aggregateId) {
                throw new AssertionError("a probe loads no aggregate");
            }

        };
    }

    private static ZenBpmProcessService<Object> processService(final ZenBpmClientFactory factory) {
        return new ZenBpmProcessService<>("orders", factory, TestCollaborators.of("orders", TestScoping.of(
                NameClashAvoidance.USE_PREFIX)), (
                    module,
                    process) -> null);
    }

    private static ZenBpmClientFactory configured(
        final String restAddress,
        final Duration requestTimeout
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        configuration.setRequestTimeout(requestTimeout);
        return new ZenBpmClientFactory("orders", configuration, () -> "shop");
    }

}
