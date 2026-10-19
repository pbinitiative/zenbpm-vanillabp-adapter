/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.MessageStartLookup;
import org.pbinitiative.zenbpmadapter.client.ZenBpmApiException;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;
import io.vanillabp.integration.adapter.spi.workflowstart.WorkflowStartReport;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The process service of this version of the adapter: it says who it is, starts a workflow, serves
 * every other operation the core requires of an adapter, and refuses every other call with words
 * which say why, instead of pretending to have done the work. The engine is a fake which answers
 * what each test tells it to and writes down what it was asked.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmProcessServiceTest {

    private static final String NOT_CONFIGURED = "ZenBPM adapter 'orders' is not configured: set the key";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<PhaseOperation> BUILT = Set.of(PhaseOperation.START_WORKFLOW,
            PhaseOperation.START_WORKFLOW_BY_MESSAGE, PhaseOperation.COMPLETE_TASK, PhaseOperation.CANCEL_TASK,
            PhaseOperation.COMPLETE_USER_TASK, PhaseOperation.CANCEL_USER_TASK, PhaseOperation.CORRELATE_MESSAGE,
            PhaseOperation.AGGREGATE_CHANGED);

    private static final String NO_START_EVENT = """
            {"code":"NOT_FOUND","message":"no active definition-level message subscription found for name \\"shop__ordered\\""}""";

    /**
     * What the deployment remembered: the process <code>Order</code>, whose newest version starts
     * with the message <code>ordered</code>.
     */
    private static final ModuleMessages ORDER_STARTED_BY_A_MESSAGE = new ModuleMessages(List.of("shop__Order"), Set
            .of("shop__paid"), Set.of("shop__ordered"), Set.of("shop__Order"), List.of());

    private static final WorkflowScope ORDER = new WorkflowScope("shop", List.of("Order"));

    private static final String NOBODY_WAITS = """
            {"code":"NOT_FOUND","message":"no active message subscription found for name \\"shop__paid\\""}""";

    private static final String STARTED = """
            {
              "key": 4503599627370501,
              "processDefinitionKey": 4503599627370498,
              "bpmnProcessId": "shop__Order",
              "version": 3,
              "businessKey": "order-1",
              "createdAt": "2026-10-17T14:30:25.123Z",
              "state": "active",
              "processType": "default",
              "variables": {},
              "activeElementInstances": []
            }
            """;

    private final FakeEngine engine = FakeEngine.started();

    @AfterEach
    void stopTheEngine() {
        engine.close();
    }

    @Test
    @DisplayName("The process service names its adapter id and its collaborators")
    void theProcessServiceSaysWhoItIs() {
        final var collaborators = TestCollaborators.of("orders");
        final var processService = new ZenBpmProcessService<Object>("orders", unconfigured("orders"), collaborators, (
            module,
            process) -> null);

        assertAll(
                () -> assertEquals("orders", processService.getAdapterId()),
                () -> assertSame(collaborators, processService.collaborators()));
    }

    @Test
    @DisplayName("A factory or collaborators of another adapter id are refused, naming all three ids")
    void theThreeOfOneAdapterIdAreWiredTogether() {
        final var otherFactory = assertThrows(IllegalArgumentException.class, () -> new ZenBpmProcessService<>(
                "orders", unconfigured("billing"), TestCollaborators.of("orders"), (
                    module,
                    process) -> null));
        final var otherCollaborators = assertThrows(IllegalArgumentException.class, () -> new ZenBpmProcessService<>(
                "orders", unconfigured("orders"), TestCollaborators.of("billing"), (
                    module,
                    process) -> null));

        assertAll(
                () -> assertEquals(
                        "The process service of ZenBPM adapter id 'orders' was given the factory of adapter id 'billing' and the collaborators of adapter id 'orders'; the platform module wires the three of one id.",
                        otherFactory.getMessage()),
                () -> assertTrue(otherCollaborators
                        .getMessage()
                        .contains("the collaborators of adapter id 'billing'"), otherCollaborators.getMessage()));
    }

    @Test
    @DisplayName("Every operation the core requires of an adapter is served, and of the others only the push of a changed aggregate, in a map nobody can change")
    void everyRequiredOperationIsServed() {
        final var operations = new ZenBpmProcessService<Object>("orders", unconfigured("orders"), TestCollaborators
                .of("orders"), (
                    module,
                    process) -> null)
                .phaseOperations();

        assertAll(
                () -> assertEquals(Stream
                        .concat(PhaseOperation.CORE_OPERATIONS
                                .stream()
                                .filter(PhaseOperation::requiredOfEveryAdapter),
                                Stream.of(
                                        PhaseOperation.AGGREGATE_CHANGED))
                        .toList(), List.copyOf(operations.keySet())),
                () -> assertFalse(operations.containsKey(PhaseOperation.SEND_SIGNAL)),
                () -> assertThrows(UnsupportedOperationException.class, () -> operations.put(
                        PhaseOperation.SEND_SIGNAL, null)));
    }

    @Test
    @DisplayName("Every handler and viewer method refuses with the message of the validation where the adapter id has no clients, a start, a start by a message, the handlers of a task and of a user task, a correlation and a push included")
    void anAdapterIdWithoutClientsRefusesWithItsValidationMessage() {
        final var processService = startingProcessService(unconfigured("orders"), (
            module,
            process) -> null);
        final var start = processService
                .phaseOperations()
                .get(PhaseOperation.START_WORKFLOW);
        final var completion = processService
                .phaseOperations()
                .get(PhaseOperation.COMPLETE_TASK);
        final var cancellation = processService
                .phaseOperations()
                .get(PhaseOperation.CANCEL_TASK);
        final var userTaskCompletion = processService
                .phaseOperations()
                .get(PhaseOperation.COMPLETE_USER_TASK);
        final var userTaskCancellation = processService
                .phaseOperations()
                .get(PhaseOperation.CANCEL_USER_TASK);
        final var correlation = processService
                .phaseOperations()
                .get(PhaseOperation.CORRELATE_MESSAGE);
        final var startByAMessage = processService
                .phaseOperations()
                .get(PhaseOperation.START_WORKFLOW_BY_MESSAGE);
        final var push = processService
                .phaseOperations()
                .get(PhaseOperation.AGGREGATE_CHANGED);
        final var calls = Stream.concat(Stream.of(() -> push.phaseOne(taskRequest()), () -> push.phaseTwo(
                correlationRequest(null)), () -> startByAMessage.phaseOne(startByMessageCheck()),
                () -> startByAMessage.phaseTwo(startByMessageRequest()), () -> start.phaseOne(null), () -> start
                        .phaseTwo(startRequest(
                                new ArrayList<>())),
                () -> completion.phaseOne(taskRequest()), () -> completion.phaseTwo(
                        startRequest(
                                new ArrayList<>())),
                () -> cancellation.phaseOne(taskRequest()), () -> cancellation.phaseTwo(
                        startRequest(new ArrayList<>())),
                () -> userTaskCompletion.phaseOne(taskRequest()), () -> userTaskCompletion.phaseTwo(startRequest(
                        new ArrayList<>())),
                () -> userTaskCancellation.phaseOne(taskRequest()), () -> userTaskCancellation.phaseTwo(startRequest(
                        new ArrayList<>())),
                () -> correlation.phaseOne(taskRequest()), () -> correlation.phaseTwo(correlationRequest(null))),
                notBuiltYet(processService));

        assertAll(calls.map(call -> () -> assertEquals(NOT_CONFIGURED, assertThrows(IllegalStateException.class,
                call::run).getMessage())));
        assertEquals(List.of(), engine.requests());
    }

    @Test
    @DisplayName("Every handler is built, and the viewer methods of a configured adapter id say that they are not built yet, and what they were asked to do")
    void aConfiguredAdapterIdSaysThatTheCallIsNotBuiltYet() {
        try (var factory = configured("http://localhost:8080")) {
            final var processService = startingProcessService(factory, (
                module,
                process) -> null);

            final var messages = notBuiltYet(processService)
                    .map(call -> assertThrows(UnsupportedOperationException.class, call::run).getMessage())
                    .toList();

            assertAll(
                    () -> assertEquals(BUILT, processService
                            .phaseOperations()
                            .keySet()),
                    () -> assertEquals(List.of(
                            "ZenBPM adapter 'orders': listing the process definitions of workflow 'Order' of workflow module 'shop' is implemented in a later story",
                            "ZenBPM adapter 'orders': reading the BPMN XML of process definition 'definition-1' of workflow module 'shop' is implemented in a later story",
                            "ZenBPM adapter 'orders': reading the history of workflow 'Order' of workflow module 'shop' is implemented in a later story"),
                            messages));
        }
    }

    @Test
    @DisplayName("Phase one of a start asks the engine nothing")
    void phaseOneOfAStartAsksNothing() {
        try (var factory = configured(engine.address())) {
            startingProcessService(factory, (
                module,
                process) -> null)
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW)
                    .phaseOne(null);

            assertEquals(List.of(), engine.requests());
        }
    }

    @Test
    @DisplayName("Phase two starts the scoped process with the id as business key and as text variable after the shared values, sends no time to live where none is set, and reports key and version once")
    void phaseTwoStartsTheWorkflowAndReportsIt() throws Exception {
        engine.answers("POST", "/v1/process-instances", 201, STARTED);
        final var reported = new ArrayList<String>();

        try (var factory = configured(engine.address())) {
            startingProcessService(factory, (
                module,
                process) -> null)
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW)
                    .phaseTwo(startRequest(reported));
        }

        final var request = onlyStartOf(engine);
        assertAll(
                () -> assertEquals(JSON.readTree("""
                        {"bpmnProcessId": "shop__Order", "businessKey": "order-1",
                         "variables": {"amount": 1000, "orderId": "order-1"}}"""), JSON.readTree(request.body())),
                () -> assertEquals(List.of("4503599627370501 3"), reported));
    }

    @Test
    @DisplayName("Phase two sends the time to live resolved for the workflow in whole seconds, PT0S as 0s")
    void phaseTwoSendsTheTimeToLiveOfTheWorkflow() throws Exception {
        engine.answers("POST", "/v1/process-instances", 201, STARTED);
        final var timesToLive = Map.of("Order", Duration.ofDays(30), "Invoice", Duration.ZERO);

        try (var factory = configured(engine.address())) {
            final var processService = startingProcessService(factory, (
                module,
                process) -> "shop".equals(module)
                        ? timesToLive.get(process)
                        : null);
            final var start = processService
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW);
            start.phaseTwo(startRequest("Order", new ArrayList<>()));
            start.phaseTwo(startRequest("Invoice", new ArrayList<>()));

            assertAll(
                    () -> assertEquals(Optional.of(Duration.ofDays(30)), processService.historyTimeToLiveOf("shop",
                            "Order")),
                    () -> assertEquals(Optional.empty(), processService.historyTimeToLiveOf("crm", "Order")));
        }

        final var requests = engine.requests();
        assertAll(
                () -> assertEquals("2592000s", JSON
                        .readTree(requests
                                .getFirst()
                                .body())
                        .get("historyTimeToLive")
                        .asText()),
                () -> assertEquals("0s", JSON
                        .readTree(requests
                                .getLast()
                                .body())
                        .get("historyTimeToLive")
                        .asText()));
    }

    @Test
    @DisplayName("An aggregate which is gone by phase two starts its workflow with the id alone, and says so")
    void anAggregateGoneByPhaseTwoStartsWithItsIdAlone(final CapturedOutput output) throws Exception {
        engine.answers("POST", "/v1/process-instances", 201, STARTED);

        try (var factory = configured(engine.address())) {
            startingProcessService(factory, (
                module,
                process) -> null)
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW)
                    .phaseTwo(new PhaseTwoRequest<>("shop", "Order", persistence(null), "order-1", Map
                            .of(), WorkflowStartReport.NOBODY_LISTENS));
        }

        assertAll(
                () -> assertEquals(JSON.readTree("""
                        {"orderId": "order-1"}"""), JSON
                        .readTree(onlyStartOf(engine).body())
                        .get("variables")),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "ZenBPM[orders]: the workflow aggregate 'order-1' could not be loaded, so what is sent about workflow 'Order' of workflow module 'shop' carries its id alone and no shared value"),
                        output.getAllOfThisTest()));
    }

    @Test
    @DisplayName("A start of a process the engine does not hold is permanent, and its message names module, process, scoped id and deployment-failure")
    void aStartOfAProcessTheEngineDoesNotHoldIsPermanent() {
        engine.answers("POST", "/v1/process-instances", 404, """
                {"code":"NOT_FOUND","message":"process definition 0 not found"}""");
        final var reported = new ArrayList<String>();

        try (var factory = configured(engine.address())) {
            final var processService = startingProcessService(factory, (
                module,
                process) -> null);
            final var failure = assertThrows(IllegalStateException.class, () -> processService
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW)
                    .phaseTwo(startRequest(reported)));

            assertAll(
                    () -> assertEquals(
                            """
                                    ZenBPM adapter 'orders' cannot start workflow 'Order' of workflow module 'shop' for aggregate \
                                    'order-1': the engine holds no process 'shop__Order'. Every further attempt is answered the \
                                    same way, so the start is not repeated and its outbox entry is blocked. Deploy workflow \
                                    module 'shop' to the engine of this adapter id. The application deploys it while it boots, \
                                    and a boot whose deployment failed goes on only where \
                                    'vanillabp.adapters.orders.deployment-failure' is 'warn'; the log of that boot says why.""",
                            failure.getMessage()),
                    () -> assertEquals(404, assertInstanceOf(ZenBpmApiException.class, failure.getCause())
                            .getStatus()),
                    () -> assertFalse(processService.isPhaseTwoFailureRepeatable(failure)),
                    () -> assertEquals(List.of(), reported));
        }
    }

    @Test
    @DisplayName("Every other refusal of a start is thrown as the engine answered it, and classified as such")
    void everyOtherRefusalIsThrownAsItIs() {
        try (var factory = configured(engine.address())) {
            final var processService = startingProcessService(factory, (
                module,
                process) -> null);
            final var start = processService
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW);
            engine.answers("POST", "/v1/process-instances", 500, """
                    {"code":"TECHNICAL_ERROR","message":"grpc: received message larger than max"}""");
            final var technical = assertThrows(ZenBpmApiException.class, () -> start.phaseTwo(startRequest(
                    new ArrayList<>())));
            engine.answers("POST", "/v1/process-instances", 400, """
                    {"code":"BAD_REQUEST","message":"failed to parse historyTimeToLive"}""");
            final var refused = assertThrows(ZenBpmApiException.class, () -> start.phaseTwo(startRequest(
                    new ArrayList<>())));

            assertAll(
                    () -> assertEquals(500, technical.getStatus()),
                    () -> assertTrue(processService.isPhaseTwoFailureRepeatable(technical)),
                    () -> assertEquals(400, refused.getStatus()),
                    () -> assertFalse(processService.isPhaseTwoFailureRepeatable(refused)),
                    () -> assertTrue(ZenBpmErrors.permanentFailure(refused)));
        }
    }

    @Test
    @DisplayName("Phase two of a correlation publishes the scoped message under the aggregate id as its key, with the shared values and the id, and asks the engine nothing else")
    void phaseTwoPublishesTheMessageUnderTheAggregateId() throws Exception {
        engine.answers("POST", "/v1/messages", 201, null);

        try (var factory = configured(engine.address())) {
            startingProcessService(factory, (
                module,
                process) -> null)
                    .phaseOperations()
                    .get(PhaseOperation.CORRELATE_MESSAGE)
                    .phaseTwo(correlationRequest(null));
        }

        assertAll(
                () -> assertEquals(1, engine
                        .requests()
                        .size(),
                        engine
                                .requests()::toString),
                () -> assertEquals(JSON.readTree("""
                        {"messageName": "shop__paid", "correlationKey": "order-1",
                         "variables": {"amount": 1000, "orderId": "order-1"}}"""), JSON.readTree(onlyPublicationOf(
                        engine).body())));
    }

    @Test
    @DisplayName("A correlation id is the correlation key of the publication, in place of the aggregate id")
    void aCorrelationIdIsTheCorrelationKey() throws Exception {
        engine.answers("POST", "/v1/messages", 201, null);

        try (var factory = configured(engine.address())) {
            startingProcessService(factory, (
                module,
                process) -> null)
                    .phaseOperations()
                    .get(PhaseOperation.CORRELATE_MESSAGE)
                    .phaseTwo(correlationRequest("second-review"));
        }

        assertEquals("second-review", JSON
                .readTree(onlyPublicationOf(engine).body())
                .get("correlationKey")
                .asText());
    }

    @Test
    @DisplayName("A publication nothing waits for is handed back to the outbox for workflow-visibility-timeout, naming module, process, aggregate, message, key, the outbox's bound and the incident a 404 may stand for, and stays repeatable with the engine's answer as its cause")
    void nobodyWaitsIsRetryLater() {
        engine.answers("POST", "/v1/messages", 404, NOBODY_WAITS);

        try (var factory = configured(engine.address())) {
            factory
                    .configuration()
                    .setWorkflowVisibilityTimeout(Duration.ofSeconds(3));
            final var processService = startingProcessService(factory, (
                module,
                process) -> null);
            final var retryLater = assertThrows(PhaseTwoRetryLater.class, () -> processService
                    .phaseOperations()
                    .get(PhaseOperation.CORRELATE_MESSAGE)
                    .phaseTwo(correlationRequest(null)));

            assertAll(
                    () -> assertEquals(Duration.ofSeconds(3), retryLater.getRetryAfter()),
                    () -> assertEquals(Duration.ofSeconds(3), PhaseTwoRetryLater.retryAfter(new RuntimeException(
                            retryLater))),
                    () -> assertEquals(
                            """
                                    ZenBPM adapter 'orders': nothing waits for message 'paid' (scoped: 'shop__paid') under \
                                    correlation key 'order-1' yet, in workflow 'Order' of workflow module 'shop' for aggregate \
                                    'order-1', and ZenBPM keeps no message nothing waits for. The publication is repeated every \
                                    PT3S until 'vanillabp.outbox.wait-for-visibility-at-most' passed since the correlation was \
                                    planned, and then its entry is blocked. The workflow may not have reached the element which \
                                    waits for the message yet; where it passed that element, or waits under another key, the \
                                    repetitions cannot help: the key the waiting element evaluates has to be the correlation id \
                                    passed to correlateMessage, or the aggregate id where none was passed. ZenBPM answers the \
                                    same where the message reached the element which waits for it and the workflow failed right \
                                    behind it on something it could not find, such as a called process which is not deployed: \
                                    where the workflow shows such an incident, the message was received, and the repetitions \
                                    cannot help either.""",
                            retryLater
                                    .getMessage()),
                    () -> assertEquals(404, assertInstanceOf(ZenBpmApiException.class, retryLater.getCause())
                            .getStatus()),
                    () -> assertTrue(processService.isPhaseTwoFailureRepeatable(retryLater)),
                    () -> assertFalse(ZenBpmErrors.permanentFailure(retryLater)));
        }
    }

    @Test
    @DisplayName("Where workflow-visibility-timeout is zero, a publication nothing waits for is an ordinary repeatable failure, which names the key and leaves the outbox its own backoff")
    void nobodyWaitsWithAZeroWindowIsAnOrdinaryFailure() {
        engine.answers("POST", "/v1/messages", 404, NOBODY_WAITS);

        try (var factory = configured(engine.address())) {
            factory
                    .configuration()
                    .setWorkflowVisibilityTimeout(Duration.ZERO);
            final var processService = startingProcessService(factory, (
                module,
                process) -> null);
            final var failure = assertThrows(IllegalStateException.class, () -> processService
                    .phaseOperations()
                    .get(PhaseOperation.CORRELATE_MESSAGE)
                    .phaseTwo(correlationRequest(null)));

            assertAll(
                    () -> assertEquals(null, PhaseTwoRetryLater.retryAfter(failure)),
                    () -> assertTrue(failure
                            .getMessage()
                            .contains(
                                    "vanillabp.adapters.orders.workflow-visibility-timeout is zero, so the publication is repeated with the backoff of the outbox, which blocks its entry after 'vanillabp.outbox.block-after-attempts' attempts. The workflow may not have reached"),
                            failure.getMessage()),
                    () -> assertEquals(404, assertInstanceOf(ZenBpmApiException.class, failure.getCause())
                            .getStatus()),
                    () -> assertTrue(processService.isPhaseTwoFailureRepeatable(failure)));
        }
    }

    @Test
    @DisplayName("Every other refusal of a publication is thrown as the engine answered it, a permanent one as permanent and a technical one as repeatable, and never as a retry-later")
    void everyOtherRefusalOfAPublicationIsThrownAsItIs() {
        try (var factory = configured(engine.address())) {
            final var processService = startingProcessService(factory, (
                module,
                process) -> null);
            final var correlation = processService
                    .phaseOperations()
                    .get(PhaseOperation.CORRELATE_MESSAGE);
            engine.answers("POST", "/v1/messages", 400, """
                    {"code":"BAD_REQUEST","message":"messageName is required"}""");
            final var refused = assertThrows(ZenBpmApiException.class, () -> correlation.phaseTwo(
                    correlationRequest(null)));
            engine.answers("POST", "/v1/messages", 500, """
                    {"code":"TECHNICAL_ERROR","message":"failed to publish message event"}""");
            final var technical = assertThrows(ZenBpmApiException.class, () -> correlation.phaseTwo(
                    correlationRequest(null)));

            assertAll(
                    () -> assertEquals(400, refused.getStatus()),
                    () -> assertFalse(processService.isPhaseTwoFailureRepeatable(refused)),
                    () -> assertEquals(500, technical.getStatus()),
                    () -> assertTrue(processService.isPhaseTwoFailureRepeatable(technical)),
                    () -> assertEquals(null, PhaseTwoRetryLater.retryAfter(technical)));
        }
    }

    @Test
    @DisplayName("Under message-start-lookup start-row, the default, a message starts the workflow: phase one asks the engine nothing, phase two publishes the scoped message without a correlation key, with the shared values and the id, and reports nothing; a probe never reads the instances")
    void underStartRowAMessageStartsTheWorkflowAndNoProbeReadsTheInstances() throws Exception {
        engine.answers("POST", "/v1/messages", 201, null);
        searchesFindOnlyTheInstanceTheMessageStarted();
        final var reported = new ArrayList<String>();

        try (var factory = configured(engine.address())) {
            final var processService = startedByAMessage(factory, null);
            final var startByAMessage = processService
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW_BY_MESSAGE);
            startByAMessage.phaseOne(startByMessageCheck());
            final var askedInPhaseOne = engine.requests();
            startByAMessage.phaseTwo(startByMessageRequest(reported));
            final var found = processService.awarenessOfWorkflow(ORDER, persistence(null), "order-1");

            assertAll(
                    () -> assertEquals(List.of(), askedInPhaseOne),
                    () -> assertEquals(JSON.readTree("""
                            {"messageName": "shop__ordered", "variables": {"amount": 1000, "orderId": "order-1"}}"""),
                            JSON.readTree(onlyPublicationOf(engine).body())),
                    () -> assertEquals(List.of(), reported),
                    () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, found),
                    () -> assertEquals(List.of("businessKey=order-1"), searchesOf(engine)));
        }
    }

    @Test
    @DisplayName("Under message-start-lookup scan, a message starts the workflow as under start-row, and where the business key finds nothing, a probe reads the instances of the process and finds the one whose id variable names the aggregate")
    void underScanAMessageStartsTheWorkflowAndAProbeReadsTheInstances() {
        engine.answers("POST", "/v1/messages", 201, null);
        searchesFindOnlyTheInstanceTheMessageStarted();

        try (var factory = configured(engine.address())) {
            final var processService = startedByAMessage(factory, MessageStartLookup.SCAN);
            final var startByAMessage = processService
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW_BY_MESSAGE);
            startByAMessage.phaseOne(startByMessageCheck());
            startByAMessage.phaseTwo(startByMessageRequest());
            final var found = processService.awarenessOfWorkflow(ORDER, persistence(null), "order-1");

            assertAll(
                    () -> assertEquals(1, publicationsOf(engine)),
                    () -> assertEquals(WorkflowAwareness.ACTIVE, found),
                    () -> assertEquals(List.of("businessKey=order-1", "state=active"), searchesOf(engine)));
        }
    }

    @Test
    @DisplayName("Under message-start-lookup refuse, phase one refuses every start by a message, naming the key, the gap and the other two values, and asks the engine nothing")
    void underRefuseNoWorkflowIsStartedByAMessage() {
        try (var factory = configured(engine.address())) {
            final var processService = startedByAMessage(factory, MessageStartLookup.REFUSE);

            final var refusal = assertThrows(IllegalStateException.class, () -> processService
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW_BY_MESSAGE)
                    .phaseOne(startByMessageCheck()));

            assertEquals("""
                    ZenBPM adapter 'orders' does not start workflow 'Order' of workflow module 'shop' by message \
                    'ordered': vanillabp.adapters.orders.message-start-lookup is 'refuse'. ZenBPM gives a workflow it \
                    starts by a message no business key, and none can be set afterwards (a gap of the engine which \
                    GAPS.md of zenbpm-vanillabp-adapter lists), so the adapter finds such a workflow only through \
                    the instance key its start marker writes into the start row of the aggregate. Set the key to \
                    'start-row', the default, to start it and find it that way, or to 'scan' to find a running one \
                    also where the start row does not know it yet.""", refusal.getMessage());
        }

        assertEquals(List.of(), engine.requests());
    }

    @Test
    @DisplayName("A start message no start event takes is an ordinary failure, repeated with the outbox's backoff and counted, never a retry-later: it names the deployment, a start on another node at the same moment and the outbox's bound, with the engine's answer as its cause")
    void aStartMessageNoStartEventTakesIsAnOrdinaryFailure() {
        engine.answers("POST", "/v1/messages", 404, NO_START_EVENT);

        try (var factory = configured(engine.address())) {
            final var processService = startedByAMessage(factory, null);
            final var failure = assertThrows(IllegalStateException.class, () -> processService
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW_BY_MESSAGE)
                    .phaseTwo(startByMessageRequest()));

            assertAll(
                    () -> assertEquals(
                            """
                                    ZenBPM adapter 'orders' could not start workflow 'Order' of workflow module 'shop' for \
                                    aggregate 'order-1' by message 'ordered' (scoped: 'shop__ordered'): the engine answered that \
                                    no start event takes it. Either no version the engine holds starts the process with that \
                                    message, which only a deployment changes: deploy workflow module 'shop' to the engine of \
                                    this adapter id; the application deploys it while it boots, and a boot whose deployment \
                                    failed goes on only where 'vanillabp.adapters.orders.deployment-failure' is 'warn'. Or \
                                    another node of the application published a message of the same name at the same moment, \
                                    which ZenBPM answers the same way. So the start is repeated with the backoff of the outbox, \
                                    which blocks its entry after 'vanillabp.outbox.block-after-attempts' attempts.""",
                            failure
                                    .getMessage()),
                    () -> assertEquals(404, assertInstanceOf(ZenBpmApiException.class, failure.getCause())
                            .getStatus()),
                    () -> assertEquals(null, PhaseTwoRetryLater.retryAfter(failure)),
                    () -> assertTrue(processService.isPhaseTwoFailureRepeatable(failure)));
        }
    }

    @Test
    @DisplayName("Every other refusal of a start message is thrown as the engine answered it, a permanent one as permanent and a technical one as repeatable")
    void everyOtherRefusalOfAStartMessageIsThrownAsItIs() {
        try (var factory = configured(engine.address())) {
            final var processService = startedByAMessage(factory, null);
            final var startByAMessage = processService
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW_BY_MESSAGE);
            engine.answers("POST", "/v1/messages", 400, """
                    {"code":"BAD_REQUEST","message":"messageName is required"}""");
            final var refused = assertThrows(ZenBpmApiException.class, () -> startByAMessage.phaseTwo(
                    startByMessageRequest()));
            engine.answers("POST", "/v1/messages", 500, """
                    {"code":"TECHNICAL_ERROR","message":"failed to publish message event"}""");
            final var technical = assertThrows(ZenBpmApiException.class, () -> startByAMessage.phaseTwo(
                    startByMessageRequest()));

            assertAll(
                    () -> assertFalse(processService.isPhaseTwoFailureRepeatable(refused)),
                    () -> assertTrue(processService.isPhaseTwoFailureRepeatable(technical)));
        }
    }

    @Test
    @DisplayName("The start messages of one name are published one after the other, because the engine starts nothing for a second one which arrives while it takes the first; each is published once")
    void startMessagesOfOneNameArePublishedOneAfterTheOther() throws Exception {
        final var inFlight = new AtomicInteger();
        final var mostAtOnce = new AtomicInteger();
        engine.answers("POST", "/v1/messages", exchange -> {
            mostAtOnce.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            LockSupport.parkNanos(Duration
                    .ofMillis(50)
                    .toNanos());
            inFlight.decrementAndGet();
            FakeEngine.respond(exchange, 201, null);
        });

        try (var factory = configured(engine.address()); var starts = Executors.newFixedThreadPool(6)) {
            final var startByAMessage = startedByAMessage(factory, null)
                    .phaseOperations()
                    .get(PhaseOperation.START_WORKFLOW_BY_MESSAGE);
            final var done = new ArrayList<Future<?>>();
            for (var start = 0; start < 6; ++start) {
                done.add(starts.submit(() -> startByAMessage.phaseTwo(startByMessageRequest())));
            }
            for (final var start : done) {
                start.get(10, TimeUnit.SECONDS);
            }
        }

        assertAll(
                () -> assertEquals(1, mostAtOnce.get()),
                () -> assertEquals(6, publicationsOf(engine)));
    }

    @Test
    @DisplayName("Tasks may be delivered more than once, and what the engine does with a type is ZenBpmValueTypes' answer")
    void theSwitchesSayWhatTheEngineDoes() {
        final var processService = startingProcessService(unconfigured("orders"), (
            module,
            process) -> null);

        assertAll(
                () -> assertTrue(processService.deliversTasksAtLeastOnce()),
                () -> assertEquals(ZenBpmValueTypes.verdictFor(Long.class, ValueDirection.TO_BPMS), processService
                        .whatThisBpmsDoesWith(Long.class, ValueDirection.TO_BPMS)),
                () -> assertEquals(ValueTypeVerdict.survives(), processService.whatThisBpmsDoesWith(String.class,
                        ValueDirection.FROM_BPMS)));
    }

    // both phases of every handler which is not built yet, then the three viewer methods
    private static Stream<Runnable> notBuiltYet(final ZenBpmProcessService<Object> processService) {
        return Stream.of(
                () -> processService.getProcessDefinitions("shop", "Order", null, "order-1", null),
                () -> processService.getBpmnXml("shop", "Order", "definition-1"),
                () -> processService.getWorkflowHistory("shop", "Order", null, "order-1", null));
    }

    // the business-key search finds nothing, as for a workflow a message started; the instances of
    // the process hold the one the message started, which carries the aggregate's id as a variable
    private void searchesFindOnlyTheInstanceTheMessageStarted() {
        engine.answers("GET", "/v1/process-instances", exchange -> FakeEngine.respond(exchange, 200, exchange
                .getRequestURI()
                .getRawQuery()
                .contains("businessKey=")
                        ? """
                                {"partitions": [{"partition": 1, "totalCount": 0, "items": []}],
                                 "page": 1, "size": 100, "count": 0, "totalCount": 0}"""
                        : """
                                {"partitions": [{"partition": 1, "totalCount": 1, "items": [
                                  {"key": 4503599627370501, "processDefinitionKey": 4503599627370498,
                                   "bpmnProcessId": "shop__Order", "version": 1,
                                   "createdAt": "2026-10-19T10:00:00.123Z", "state": "active",
                                   "processType": "default", "variables": {"orderId": "order-1"}}]}],
                                 "page": 1, "size": 100, "count": 1, "totalCount": 1}"""));
    }

    // each search of the instances by what it filters: the business key, or the state of a scan
    private static List<String> searchesOf(final FakeEngine engine) {
        return engine
                .requests()
                .stream()
                .filter(request -> "GET /v1/process-instances".equals("%s %s".formatted(request.method(), request
                        .path())))
                .map(request -> Stream
                        .of(request
                                .query()
                                .split("&"))
                        .filter(parameter -> parameter.startsWith("businessKey=") || parameter.startsWith("state="))
                        .findFirst()
                        .orElse(request.query()))
                .toList();
    }

    private static long publicationsOf(final FakeEngine engine) {
        return engine
                .requests()
                .stream()
                .filter(request -> "POST /v1/messages".equals("%s %s".formatted(request.method(), request.path())))
                .count();
    }

    private static FakeEngine.Request onlyStartOf(final FakeEngine engine) {
        final var starts = engine
                .requests()
                .stream()
                .filter(request -> "POST /v1/process-instances".equals("%s %s".formatted(request.method(), request
                        .path())))
                .toList();
        assertEquals(1, starts.size(), engine
                .requests()
                .toString());
        return starts.getFirst();
    }

    private static FakeEngine.Request onlyPublicationOf(final FakeEngine engine) {
        final var publications = engine
                .requests()
                .stream()
                .filter(request -> "POST /v1/messages".equals("%s %s".formatted(request.method(), request.path())))
                .toList();
        assertEquals(1, publications.size(), engine
                .requests()
                .toString());
        return publications.getFirst();
    }

    // the module is scoped with a prefix, so the test sees the id the engine knows
    private static ZenBpmProcessService<Object> startingProcessService(
        final ZenBpmClientFactory factory,
        final ZenBpmProcessService.HistoryTimeToLive historyTimeToLive
    ) {
        return new ZenBpmProcessService<>("orders", factory, collaborators(), historyTimeToLive);
    }

    // a process service of a module whose deployment remembered the process Order, which a message
    // starts, under the given message-start-lookup, null for the default
    private static ZenBpmProcessService<Object> startedByAMessage(
        final ZenBpmClientFactory factory,
        final MessageStartLookup lookup
    ) {
        factory
                .configuration()
                .setMessageStartLookup(lookup);
        factory
                .deployedProcesses()
                .recordMessages("shop", ORDER_STARTED_BY_A_MESSAGE, () -> ORDER_STARTED_BY_A_MESSAGE);
        return startingProcessService(factory, (
            module,
            process) -> null);
    }

    private static AdapterCollaborators collaborators() {
        return TestCollaborators.of("orders", TestScoping.of(NameClashAvoidance.USE_PREFIX), new SharingTheAmount());
    }

    private static PhaseOneRequest<Object> taskRequest() {
        return new PhaseOneRequest<>("shop", "Order", persistence(new Object()), new Object(), Map
                .of(PhaseTwoCall.ARG_TASK_ID, "42"));
    }

    private static PhaseTwoRequest<Object> startRequest(final List<String> reported) {
        return startRequest("Order", reported);
    }

    private static PhaseTwoRequest<Object> startRequest(
        final String bpmnProcessId,
        final List<String> reported
    ) {
        return new PhaseTwoRequest<>("shop", bpmnProcessId, persistence(new Object()), "order-1", Map
                .of(), new WorkflowStartReport() {

                    @Override
                    public void startedWorkflow(final String workflowId) {
                        reported.add(workflowId);
                    }

                    @Override
                    public void startedWorkflow(
                        final String workflowId,
                        final String processVersion
                    ) {
                        reported.add("%s %s".formatted(workflowId, processVersion));
                    }

                });
    }

    private static PhaseOneRequest<Object> startByMessageCheck() {
        return new PhaseOneRequest<>("shop", "Order", persistence(new Object()), new Object(), Map
                .of(PhaseTwoCall.ARG_MESSAGE_NAME, "ordered"));
    }

    private static PhaseTwoRequest<Object> startByMessageRequest() {
        return startByMessageRequest(new ArrayList<>());
    }

    // a start reports to the list, which a start by a message never does
    private static PhaseTwoRequest<Object> startByMessageRequest(final List<String> reported) {
        return new PhaseTwoRequest<>("shop", "Order", persistence(new Object()), "order-1", Map
                .of(PhaseTwoCall.ARG_MESSAGE_NAME, "ordered"), new WorkflowStartReport() {

                    @Override
                    public void startedWorkflow(final String workflowId) {
                        reported.add(workflowId);
                    }

                    @Override
                    public void startedWorkflow(
                        final String workflowId,
                        final String processVersion
                    ) {
                        reported.add("%s %s".formatted(workflowId, processVersion));
                    }

                });
    }

    private static PhaseTwoRequest<Object> correlationRequest(final String correlationId) {
        final var args = new LinkedHashMap<String, String>();
        args.put(PhaseTwoCall.ARG_MESSAGE_NAME, "paid");
        if (correlationId != null) {
            args.put(PhaseTwoCall.ARG_CORRELATION_ID, correlationId);
        }
        return new PhaseTwoRequest<>("shop", "Order", persistence(new Object()), "order-1", args);
    }

    // a persistence which names the id attribute and holds one aggregate, or none
    private static AggregatePersistenceAware<Object> persistence(final Object aggregate) {
        return new AggregatePersistenceAware<>() {

            @Override
            public String getAggregateIdName() {
                return "orderId";
            }

            @Override
            public Object loadById(final Object aggregateId) {
                assertEquals("order-1", aggregateId);
                return aggregate;
            }

        };
    }

    private static ZenBpmClientFactory unconfigured(final String adapterId) {
        return ZenBpmClientFactory.unconfigured(adapterId, new ZenBpmAdapterConfiguration(), NOT_CONFIGURED);
    }

    private static ZenBpmClientFactory configured(final String restAddress) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        return new ZenBpmClientFactory("orders", configuration, () -> "shop");
    }

    /**
     * A sync model which shares the amount of every aggregate, and also an attribute named like the
     * id, which the id has to win over.
     */
    private static final class SharingTheAmount implements WorkflowAggregateSync {

        @Override
        public Map<String, Object> syncedValues(
            final Object workflowAggregate,
            final AggregateSyncMode adapterDefault
        ) {
            assertEquals(AggregateSyncMode.FULL, adapterDefault);
            final var values = new LinkedHashMap<String, Object>();
            values.put("orderId", "an attribute");
            values.put("amount", 1000);
            return values;
        }

        @Override
        public void validateSyncModel(final Class<?> workflowAggregateClass) {
        }

    }

}
