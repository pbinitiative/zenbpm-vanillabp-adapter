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
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmApiException;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;
import io.vanillabp.integration.adapter.spi.workflowstart.WorkflowStartReport;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
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
            PhaseOperation.COMPLETE_TASK, PhaseOperation.CANCEL_TASK);

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
    @DisplayName("Every operation the core requires of an adapter is served, and only those, in a map nobody can change")
    void everyRequiredOperationIsServed() {
        final var operations = new ZenBpmProcessService<Object>("orders", unconfigured("orders"), TestCollaborators
                .of("orders"), (
                    module,
                    process) -> null)
                .phaseOperations();

        assertAll(
                () -> assertEquals(PhaseOperation.CORE_OPERATIONS
                        .stream()
                        .filter(PhaseOperation::requiredOfEveryAdapter)
                        .toList(), List.copyOf(operations.keySet())),
                () -> assertFalse(operations.containsKey(PhaseOperation.SEND_SIGNAL)),
                () -> assertFalse(operations.containsKey(PhaseOperation.AGGREGATE_CHANGED)),
                () -> assertThrows(UnsupportedOperationException.class, () -> operations.put(
                        PhaseOperation.SEND_SIGNAL, null)));
    }

    @Test
    @DisplayName("Every handler and viewer method refuses with the message of the validation where the adapter id has no clients, a start and the handlers of a task included")
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
        final var calls = Stream.concat(Stream.of(() -> start.phaseOne(null), () -> start.phaseTwo(startRequest(
                new ArrayList<>())), () -> completion.phaseOne(taskRequest()), () -> completion.phaseTwo(
                        startRequest(
                                new ArrayList<>())),
                () -> cancellation.phaseOne(taskRequest()), () -> cancellation.phaseTwo(
                        startRequest(new ArrayList<>()))),
                notBuiltYet(processService));

        assertAll(calls.map(call -> () -> assertEquals(NOT_CONFIGURED, assertThrows(IllegalStateException.class,
                call::run).getMessage())));
        assertEquals(List.of(), engine.requests());
    }

    @Test
    @DisplayName("Every handler but the start and those of a task, and the viewer methods, of a configured adapter id say that they are not built yet, and what they were asked to do")
    void aConfiguredAdapterIdSaysThatTheCallIsNotBuiltYet() {
        try (var factory = configured("http://localhost:8080")) {
            final var processService = startingProcessService(factory, (
                module,
                process) -> null);

            final var messages = notBuiltYet(processService)
                    .map(call -> assertThrows(UnsupportedOperationException.class, call::run).getMessage())
                    .toList();

            assertAll(
                    () -> assertEquals(
                            "ZenBPM adapter 'orders': phase one of COMPLETE_USER_TASK is implemented in a later story",
                            messages.getFirst()),
                    () -> assertEquals(
                            "ZenBPM adapter 'orders': phase two of COMPLETE_USER_TASK is implemented in a later story",
                            messages.get(1)),
                    () -> assertTrue(messages
                            .stream()
                            .noneMatch(message -> BUILT
                                    .stream()
                                    .anyMatch(built -> message.contains(" of %s ".formatted(built.name())))),
                            messages::toString),
                    () -> assertEquals(List.of(
                            "ZenBPM adapter 'orders': listing the process definitions of workflow 'Order' of workflow module 'shop' is implemented in a later story",
                            "ZenBPM adapter 'orders': reading the BPMN XML of process definition 'definition-1' of workflow module 'shop' is implemented in a later story",
                            "ZenBPM adapter 'orders': reading the history of workflow 'Order' of workflow module 'shop' is implemented in a later story"),
                            messages.subList(messages.size() - 3, messages.size())));
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
        final var handlers = processService
                .phaseOperations()
                .entrySet()
                .stream()
                .filter(handler -> !BUILT.contains(handler.getKey()))
                .map(Map.Entry::getValue)
                .flatMap(handler -> Stream.<Consumer<PhaseOperationHandler<Object>>>of(
                        phases -> phases.phaseOne(null),
                        phases -> phases.phaseTwo(null))
                        .map(phase -> (Runnable) () -> phase.accept(handler)));
        return Stream.concat(handlers, Stream.of(
                () -> processService.getProcessDefinitions("shop", "Order", null, "order-1", null),
                () -> processService.getBpmnXml("shop", "Order", "definition-1"),
                () -> processService.getWorkflowHistory("shop", "Order", null, "order-1", null)));
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

    // the module is scoped with a prefix, so the test sees the id the engine knows
    private static ZenBpmProcessService<Object> startingProcessService(
        final ZenBpmClientFactory factory,
        final ZenBpmProcessService.HistoryTimeToLive historyTimeToLive
    ) {
        return new ZenBpmProcessService<>("orders", factory, collaborators(), historyTimeToLive);
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
