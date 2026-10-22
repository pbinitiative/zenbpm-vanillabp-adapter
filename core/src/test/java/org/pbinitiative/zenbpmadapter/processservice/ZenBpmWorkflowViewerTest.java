/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.MessageStartLookup;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowElementHistory;
import io.vanillabp.spi.process.WorkflowElementType;

/**
 * The viewer/history API of an adapter id, against an engine standing in for ZenBPM, which answers
 * what each test tells it to and writes down what it was asked, so every test also sees how many
 * requests a read sent. The workflow module <code>shop</code> is scoped with a prefix, so the
 * process <code>Order</code> is <code>shop__Order</code> at the engine and calls
 * <code>shop__Invoice</code>. The workflow of the aggregate <code>order-1</code> is the instance
 * {@value #ROOT} of definition {@value #ORDER_DEFINITION}.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmWorkflowViewerTest {

    private static final long ROOT = 4096001;

    private static final long ORDER_DEFINITION = 11;

    private static final long REMINDED_DEFINITION = 12;

    private static final String SEARCH = "/v1/process-instances";

    private static final String MODEL = "/v1/process-definitions/%d".formatted(ORDER_DEFINITION);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The model the engine holds for <code>Order</code>, with the prefixes and the end marker the
     * deployment wrote: a sub-process, a multi-instance task, a call activity which iterates, and
     * call activities which name the latest version, a version, a tag, a tag the engine reads as a
     * version, a version, a tag and a number the engine does not hold, a process the engine does not
     * hold, a binding the engine refuses, and nothing.
     */
    private static final String ORDER_MODEL = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="order" targetNamespace="x">
              <bpmn:process id="shop__Order" isExecutable="true">
                <bpmn:startEvent id="Start" />
                <bpmn:subProcess id="Sub">
                  <bpmn:startEvent id="SubStart" />
                  <bpmn:serviceTask id="SubTask">
                    <bpmn:extensionElements><zenbpm:taskDefinition type="shop__Order__sub" /></bpmn:extensionElements>
                  </bpmn:serviceTask>
                </bpmn:subProcess>
                <bpmn:serviceTask id="Each">
                  <bpmn:extensionElements><zenbpm:taskDefinition type="shop__Order__each" /></bpmn:extensionElements>
                  <bpmn:multiInstanceLoopCharacteristics>
                    <bpmn:extensionElements><zenbpm:loopCharacteristics inputCollection="=[1,2]" inputElement="item" /></bpmn:extensionElements>
                  </bpmn:multiInstanceLoopCharacteristics>
                </bpmn:serviceTask>
                <bpmn:callActivity id="InvoiceEach">
                  <bpmn:extensionElements><zenbpm:calledElement processId="shop__Invoice" /></bpmn:extensionElements>
                  <bpmn:multiInstanceLoopCharacteristics>
                    <bpmn:extensionElements><zenbpm:loopCharacteristics inputCollection="=[1,2]" inputElement="item" /></bpmn:extensionElements>
                  </bpmn:multiInstanceLoopCharacteristics>
                </bpmn:callActivity>
                <bpmn:callActivity id="Invoice">
                  <bpmn:extensionElements><zenbpm:calledElement processId="shop__Invoice" /></bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="InvoiceTwo">
                  <bpmn:extensionElements><zenbpm:calledElement processId="shop__Invoice" version="2" /></bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="InvoiceTagged">
                  <bpmn:extensionElements>
                    <zenbpm:calledElement processId="shop__Invoice" bindingType="versionTag" versionTag="release-1" />
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="InvoiceVersionOne">
                  <bpmn:extensionElements>
                    <zenbpm:calledElement processId="shop__Invoice" bindingType="versionTag" versionTag="v1" />
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="InvoiceSeven">
                  <bpmn:extensionElements><zenbpm:calledElement processId="shop__Invoice" version="7" /></bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="InvoiceUnknownTag">
                  <bpmn:extensionElements>
                    <zenbpm:calledElement processId="shop__Invoice" bindingType="versionTag" versionTag="release-9" />
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="InvoiceTooLarge">
                  <bpmn:extensionElements>
                    <zenbpm:calledElement processId="shop__Invoice" bindingType="versionTag" versionTag="v99999999999" />
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="Refund">
                  <bpmn:extensionElements><zenbpm:calledElement processId="shop__Refund" /></bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="Refused">
                  <bpmn:extensionElements>
                    <zenbpm:calledElement processId="shop__Invoice" bindingType="latest" version="2" />
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="CallsNothing" />
                <bpmn:serviceTask id="vanillabp__ended__End">
                  <bpmn:extensionElements><zenbpm:taskDefinition type="vanillabp__ended__shop__Order" /></bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:endEvent id="End" />
              </bpmn:process>
            </bpmn:definitions>
            """;

    /**
     * A version of <code>Order</code> which waits at a user task and whose interrupting timer event
     * sub-process escalates the wait.
     */
    private static final String REMINDED_MODEL = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="reminded" targetNamespace="x">
              <bpmn:process id="shop__Order" isExecutable="true">
                <bpmn:startEvent id="Start" />
                <bpmn:userTask id="Wait" />
                <bpmn:endEvent id="End" />
                <bpmn:subProcess id="Escalation" triggeredByEvent="true">
                  <bpmn:startEvent id="EscalationStart">
                    <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                  </bpmn:startEvent>
                  <bpmn:userTask id="Escalate" />
                </bpmn:subProcess>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private final FakeEngine engine = FakeEngine.started();

    private final ZenBpmClientFactory factory = configured(Duration.ZERO);

    private final ZenBpmProcessService<Object> processService = processService(factory);

    @AfterEach
    void stopTheEngine() {
        factory.close();
        engine.close();
    }

    @Test
    @DisplayName("The definitions are the workflow's and, for its call activities, the one the engine would start now, call activities which start the same definition in one entry, with one search, one read of the model and one list per process called")
    void theDefinitionsAreTheWorkflowsAndThoseItsCallActivitiesWouldStart() {
        searchFinds(root("completed", 0));
        holdsTheOrderModel();
        engine.answers("GET", "/v1/process-definitions", exchange -> FakeEngine.respond(exchange, 200, exchange
                .getRequestURI()
                .getRawQuery()
                .contains("bpmnProcessId=shop__Invoice")
                        ? definitions(definition(21, "shop__Invoice", 1, "release-1"),
                                definition(23, "shop__Invoice", 3, null), definition(22, "shop__Invoice", 2, null))
                        : definitions()));

        final var definitions = processService.getProcessDefinitions("shop", "Order", null, "order-1", null);

        assertAll(
                () -> assertEquals(List.of(
                        new ProcessDefinition("11", "Order", "4", null),
                        new ProcessDefinition("23", "Invoice", "3", List.of("InvoiceEach", "Invoice")),
                        new ProcessDefinition("22", "Invoice", "2", List.of("InvoiceTwo")),
                        new ProcessDefinition("21", "Invoice", "1", List.of("InvoiceTagged", "InvoiceVersionOne"))),
                        definitions),
                () -> assertEquals(List.of(
                        "GET %s".formatted(SEARCH),
                        "GET %s".formatted(MODEL),
                        "GET /v1/process-definitions",
                        "GET /v1/process-definitions"), requests()),
                // a lower bound of the creation, so the engine searches the process by its index
                () -> assertTrue(engine
                        .requests()
                        .getFirst()
                        .query()
                        .contains(
                                "businessKey=order-1&bpmnProcessId=shop__Order&createdFrom=1970-01-01T00%3A00%3A00Z&page=1"),
                        () -> engine
                                .requests()
                                .getFirst()
                                .query()));
    }

    @Test
    @DisplayName("Of the workflows of an aggregate the one which runs is shown, else the one created last, and an instance of a sub-process or a multi-instance element carrying the business key is never taken for one")
    void aRunningWorkflowIsShownElseTheNewest() {
        holdsTheOrderModel();
        listsNoVersions();
        searchFinds(instance(7, ORDER_DEFINITION, "shop__Order", 2, "completed", null, "default",
                "2026-10-20T10:00:03Z", 0),
                instance(8, ORDER_DEFINITION, "shop__Order", 3, "active", null, "default",
                        "2026-10-20T10:00:01Z", 0),
                instance(9, ORDER_DEFINITION, "shop__Order", 9, "active", 8L,
                        "subprocess", "2026-10-20T10:00:04Z", 0));
        final var running = processService.getProcessDefinitions("shop", "Order", null, "order-1", null);
        searchFinds(instance(7, ORDER_DEFINITION, "shop__Order", 2, "completed", null, "default",
                "2026-10-20T10:00:03Z", 0),
                instance(8, ORDER_DEFINITION, "shop__Order", 3, "terminated", null,
                        "default", "2026-10-20T10:00:01Z", 0),
                instance(6, ORDER_DEFINITION, "shop__Order", 5,
                        "completed", null, "default", "2026-10-20T10:00:03Z", 0),
                instance(9,
                        ORDER_DEFINITION, "shop__Order", 9, "completed", 8L, "multiInstance",
                        "2026-10-20T10:00:04Z", 0));
        final var ended = processService.getProcessDefinitions("shop", "Order", null, "order-1", null);

        assertAll(
                () -> assertEquals("3", running
                        .getFirst()
                        .version()),
                // the same moment: the larger key is the younger instance
                () -> assertEquals("2", ended
                        .getFirst()
                        .version()));
    }

    @Test
    @DisplayName("The search pages through every workflow of the aggregate")
    void theSearchReadsEveryPage() {
        holdsTheOrderModel();
        listsNoVersions();
        engine.answers("GET", SEARCH, exchange -> FakeEngine.respond(exchange, 200, exchange
                .getRequestURI()
                .getRawQuery()
                .contains("page=2")
                        ? page(102, instance(8, ORDER_DEFINITION, "shop__Order", 3, "active", null,
                                "default", "2026-10-20T10:00:01Z", 0))
                        : page(102, Stream
                                .iterate(100L, key -> key + 1)
                                .limit(100)
                                .map(key -> instance(key, ORDER_DEFINITION, "shop__Order", 2, "completed", null,
                                        "default", "2026-10-20T10:00:00Z", 0))
                                .toArray(String[]::new))));

        final var definitions = processService.getProcessDefinitions("shop", "Order", null, "order-1", null);

        assertAll(
                () -> assertEquals("3", definitions
                        .getFirst()
                        .version()),
                () -> assertEquals(2, engine
                        .requests()
                        .stream()
                        .filter(request -> SEARCH.equals(request.path()))
                        .count()));
    }

    @Test
    @DisplayName("A workflow the engine does not find yet is searched for again within workflow-visibility-timeout, because a node which lags behind may not show what the probe found")
    void aWorkflowNotVisibleYetIsSearchedForAgain() {
        try (var waiting = configured(Duration.ofSeconds(5))) {
            final var searches = new AtomicInteger();
            engine.answers("GET", SEARCH, exchange -> FakeEngine.respond(exchange, 200, searches.incrementAndGet() < 3
                    ? page(0)
                    : page(1, root("active", 0))));
            holdsTheOrderModel();
            listsNoVersions();

            final var definitions = processService(waiting).getProcessDefinitions("shop", "Order", null, "order-1",
                    null);

            assertAll(
                    () -> assertEquals("11", definitions
                            .getFirst()
                            .id()),
                    () -> assertEquals(3, searches.get()));
        }
    }

    @Test
    @DisplayName("A workflow the engine does not hold is unknown: no definitions and no history, after one search where workflow-visibility-timeout is zero")
    void anUnknownWorkflowIsUnknown() {
        searchFinds();

        final var definitions = processService.getProcessDefinitions("shop", "Order", null, "order-1", null);
        final var history = processService.getWorkflowHistory("shop", "Order", null, "order-1", null);

        assertAll(
                () -> assertEquals(List.of(), definitions),
                () -> assertNull(history),
                () -> assertEquals(List.of("GET %s".formatted(SEARCH),
                        "GET %s".formatted(SEARCH)),
                        requests()));
    }

    @Test
    @DisplayName("A search the engine does not answer throws, naming the keys of the engine's address and of the time it may take, because an unknown workflow is another answer")
    void aSearchTheEngineDoesNotAnswerThrows() {
        engine.answers("GET", SEARCH, 503, null);

        final var definitions = assertThrows(IllegalStateException.class, () -> processService.getProcessDefinitions(
                "shop", "Order", null, "order-1", null));
        final var history = assertThrows(IllegalStateException.class, () -> processService.getWorkflowHistory("shop",
                "Order", null, "order-1", null));

        assertAll(Stream
                .of(definitions, history)
                .map(failure -> () -> assertTrue(failure
                        .getMessage()
                        .startsWith(
                                "ZenBPM adapter 'orders' could not ask the engine for the workflow of aggregate 'order-1' (process 'Order' of workflow module 'shop'): ") && failure
                                        .getMessage()
                                        .endsWith(
                                                "Check that the engine at vanillabp.adapters.orders.rest-address answers within vanillabp.adapters.orders.request-timeout."),
                        failure::getMessage)));
    }

    @Test
    @DisplayName("Where the model cannot be read, the definition of the workflow is reported without those of its call activities, and the failure is a WARN once until a read succeeds again")
    void aModelWhichCannotBeReadCostsTheCalledDefinitions(final CapturedOutput output) {
        searchFinds(root("active", 0));
        engine.answers("GET", MODEL, 503, null);

        final var first = processService.getProcessDefinitions("shop", "Order", null, "order-1", null);
        processService.getProcessDefinitions("shop", "Order", null, "order-1", null);
        holdsTheOrderModel();
        listsNoVersions();
        processService.getProcessDefinitions("shop", "Order", null, "order-1", null);
        // the model of definition 11 is kept now, so the next failure is that of another version
        searchFinds(instance(ROOT, 12, "shop__Order", 5, "active", null, "default", "2026-10-20T10:00:00Z", 0));
        engine.answers("GET", "/v1/process-definitions/12", 503, null);
        processService.getProcessDefinitions("shop", "Order", null, "order-1", null);

        assertAll(
                () -> assertEquals(List.of(new ProcessDefinition("11", "Order", "4", null)), first),
                () -> assertEquals(List.of(
                        "ZenBPM[orders]: the viewer could not read the model of process definition 11, so the definitions its call activities would start are not reported; check that the engine at vanillabp.adapters.orders.rest-address answers within vanillabp.adapters.orders.request-timeout; further failures of the viewer are logged at DEBUG until one of its reads succeeds again",
                        "ZenBPM[orders]: the viewer could not read the model of process definition 12, so the definitions its call activities would start are not reported; check that the engine at vanillabp.adapters.orders.rest-address answers within vanillabp.adapters.orders.request-timeout; further failures of the viewer are logged at DEBUG until one of its reads succeeds again"),
                        warnings(output)));
    }

    @Test
    @DisplayName("The BPMN is the model as the engine holds it, read once and kept; an id which is no key, and a definition the engine answers 404 for, are unknown, and an engine which answers otherwise or not at all throws")
    void theBpmnIsTheModelTheEngineHolds() throws IOException {
        holdsTheOrderModel();

        final String first;
        try (var xml = processService.getBpmnXml("shop", "Order", "11")) {
            first = new String(xml.readAllBytes(), StandardCharsets.UTF_8);
        }
        final String again;
        try (var xml = processService.getBpmnXml("shop", "Order", "11")) {
            again = new String(xml.readAllBytes(), StandardCharsets.UTF_8);
        }
        final var noKey = processService.getBpmnXml("shop", "Order", "definition-11");
        engine.answers("GET", "/v1/process-definitions/12", 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no\"}");
        final var unknown = processService.getBpmnXml("shop", "Order", "12");
        engine.answers("GET", "/v1/process-definitions/13", 503, null);
        final var away = assertThrows(IllegalStateException.class, () -> processService.getBpmnXml("shop", "Order",
                "13"));
        engine.answers("GET", "/v1/process-definitions/14", exchange -> exchange.close());
        final var cut = assertThrows(IllegalStateException.class, () -> processService.getBpmnXml("shop", "Order",
                "14"));

        assertAll(
                () -> assertEquals(ORDER_MODEL, first),
                () -> assertEquals(ORDER_MODEL, again),
                () -> assertNull(noKey),
                () -> assertNull(unknown),
                () -> assertTrue(away
                        .getMessage()
                        .startsWith(
                                "ZenBPM adapter 'orders' could not ask the engine for the BPMN of process definition 13: "),
                        away::getMessage),
                () -> assertTrue(cut
                        .getMessage()
                        .startsWith(
                                "ZenBPM adapter 'orders' could not ask the engine for the BPMN of process definition 14: "),
                        cut::getMessage),
                () -> assertEquals(List.of(
                        "GET %s".formatted(MODEL),
                        "GET /v1/process-definitions/12",
                        "GET /v1/process-definitions/13"),
                        requests()
                                .stream()
                                // the HTTP client may send a read again on a connection which broke
                                .filter(request -> !request.endsWith("/14"))
                                .toList()));
    }

    @Test
    @DisplayName("The history lists the workflow's elements with those of its sub-process and of its multi-instance elements in the order they were entered, the entry of a multi-instance element as such, a call activity naming the instance it started, and no marker, with one request per instance read")
    void theHistoryListsTheWorkflowWithItsParts() {
        searchFinds(root("completed", 0));
        holdsTheOrderModel();
        historyOf(ROOT, entry(1, "Start", "START_EVENT", "00.001", "00.001"), entry(2, "Sub", "SUB_PROCESS",
                "00.002", "00.010"), entry(20, "Each", "SERVICE_TASK", "00.020", "00.030"),
                entry(40, "InvoiceEach",
                        "CALL_ACTIVITY", "00.040", "00.050"),
                entry(60, "Invoice", "CALL_ACTIVITY", "00.060", "00.070"),
                entry(80, "vanillabp__ended__End", "SERVICE_TASK", "00.080", "00.081"), entry(81,
                        "vanillabp__ended__End__flow", "SEQUENCE_FLOW", "00.081", "00.081"),
                entry(82, "End",
                        "END_EVENT", "00.082", "00.090"));
        childrenOf(ROOT, instance(3, ORDER_DEFINITION, "shop__Order", 4, "completed", ROOT, "subprocess",
                "2026-10-20T10:00:00.002Z", 0),
                instance(21, ORDER_DEFINITION, "shop__Order", 4, "completed", ROOT,
                        "multiInstance", "2026-10-20T10:00:00.020Z", 0),
                instance(41, ORDER_DEFINITION, "shop__Order", 4,
                        "completed", ROOT, "multiInstance", "2026-10-20T10:00:00.040Z", 0),
                instance(61, 23,
                        "shop__Invoice", 3, "completed", ROOT, "callActivity",
                        "2026-10-20T10:00:00.061Z", 0));
        historyOf(3, entry(4, "SubStart", "START_EVENT", "00.003", "00.003"), entry(5, "SubTask", "SERVICE_TASK",
                "00.004", "00.009"));
        historyOf(21, entry(22, "Each", "SERVICE_TASK", "00.021", "00.025"), entry(23, "Each", "SERVICE_TASK",
                "00.021", "00.026"));
        historyOf(41, entry(42, "InvoiceEach", "CALL_ACTIVITY", "00.041", "00.045"), entry(43, "InvoiceEach",
                "CALL_ACTIVITY", "00.042", "00.046"));
        childrenOf(41, instance(44, 23, "shop__Invoice", 3, "completed", 41L, "callActivity",
                "2026-10-20T10:00:00.041Z", 0),
                instance(45, 23, "shop__Invoice", 3, "completed", 41L, "callActivity",
                        "2026-10-20T10:00:00.042Z", 0));

        final var history = processService.getWorkflowHistory("shop", "Order", null, "order-1", null);

        assertAll(
                () -> assertEquals("11", history.processDefinitionId()),
                () -> assertEquals(Instant
                        .parse("2026-10-20T10:00:00Z")
                        .atOffset(ZoneOffset.UTC), history.startTime()),
                // the end of the last element of the workflow itself
                () -> assertEquals(Instant
                        .parse("2026-10-20T10:00:00.090Z")
                        .atOffset(ZoneOffset.UTC), history.endTime()),
                () -> assertEquals(List.of(
                        "Start START_EVENT -",
                        "Sub SUB_PROCESS -",
                        "SubStart START_EVENT -",
                        "SubTask SERVICE_TASK -",
                        "Each MULTI_INSTANCE -",
                        "Each SERVICE_TASK -",
                        "Each SERVICE_TASK -",
                        "InvoiceEach MULTI_INSTANCE -",
                        "InvoiceEach CALL_ACTIVITY 44",
                        "InvoiceEach CALL_ACTIVITY 45",
                        "Invoice CALL_ACTIVITY 61",
                        "End END_EVENT -"), described(history.elementsHistory())),
                () -> assertEquals(List.of(
                        "GET %s".formatted(SEARCH),
                        "GET %s".formatted(MODEL),
                        "GET /v1/process-instances/%d/history".formatted(ROOT),
                        "GET /v1/process-instances/%d/child-processes".formatted(ROOT),
                        "GET /v1/process-instances/3/history",
                        "GET /v1/process-instances/21/history",
                        "GET /v1/process-instances/41/history",
                        "GET /v1/process-instances/41/child-processes"), requests()));
    }

    @Test
    @DisplayName("The engine names no call activity in the instance it started, so each entry takes the first instance of its called process with a larger key which no entry before it took")
    void theCalledInstancesAreTakenInTheOrderOfTheirKeys() {
        searchFinds(root("active", 0));
        holdsTheOrderModel();
        historyOf(ROOT, entry(10, "Invoice", "CALL_ACTIVITY", "00.010", "00.020"), entry(30, "Invoice",
                "CALL_ACTIVITY", "00.030", null), entry(50, "Refund", "CALL_ACTIVITY", "00.050", null),
                entry(70,
                        "InvoiceTwo", "CALL_ACTIVITY", "00.070", null));
        // the refund started before the second invoice, and the instance the third call activity
        // started is not visible yet
        childrenOf(ROOT,
                instance(40, 23, "shop__Invoice", 3, "active", ROOT, "callActivity", "2026-10-20T10:00:00.031Z",
                        0),
                instance(15, 23, "shop__Invoice", 3, "completed", ROOT, "callActivity", "2026-10-20T10:00:00.011Z",
                        0),
                instance(35, 31, "shop__Refund", 1, "active", ROOT, "callActivity",
                        "2026-10-20T10:00:00.051Z", 0));

        final var history = processService.getWorkflowHistory("shop", "Order", null, "order-1", null);

        assertAll(
                () -> assertEquals(List.of(
                        "Invoice CALL_ACTIVITY 15",
                        "Invoice CALL_ACTIVITY 40",
                        "Refund CALL_ACTIVITY -",
                        "InvoiceTwo CALL_ACTIVITY -"), described(history.elementsHistory())),
                () -> assertNull(history.endTime()));
    }

    @Test
    @DisplayName("The iterations of a multi-instance task are read from its body where nothing else of the workflow starts a child; a workflow which was cancelled ended with its last element, and no element is reported as cancelled, because the engine keeps no state of an element; the entry of a call activity which names no process takes no instance")
    void aMultiInstanceTaskAloneAndACancelledWorkflow() {
        searchFinds(root("terminated", 0));
        holdsTheOrderModel();
        historyOf(ROOT, entry(1, "Start", "START_EVENT", "00.001", "00.001"), entry(20, "Each", "SERVICE_TASK",
                "00.020", "00.040"), entry(30, "CallsNothing", "CALL_ACTIVITY", "00.030", "00.040"));
        childrenOf(ROOT, instance(21, ORDER_DEFINITION, "shop__Order", 4, "terminated", ROOT, "multiInstance",
                "2026-10-20T10:00:00.020Z", 0),
                instance(31, 23, "shop__Invoice", 3, "terminated", ROOT, "callActivity",
                        "2026-10-20T10:00:00.031Z", 0));
        historyOf(21, entry(22, "Each", "SERVICE_TASK", "00.021", "00.040"), entry(23, "Each", "SERVICE_TASK",
                "00.021", "00.040"));

        final var history = processService.getWorkflowHistory("shop", "Order", null, "order-1", null);

        assertAll(
                () -> assertEquals(List.of(
                        "Start START_EVENT -",
                        "Each MULTI_INSTANCE -",
                        "Each SERVICE_TASK -",
                        "Each SERVICE_TASK -",
                        "CallsNothing CALL_ACTIVITY -"), described(history.elementsHistory())),
                () -> assertEquals(Instant
                        .parse("2026-10-20T10:00:00.040Z")
                        .atOffset(ZoneOffset.UTC), history.endTime()),
                () -> assertTrue(history
                        .elementsHistory()
                        .stream()
                        .noneMatch(WorkflowElementHistory::isCanceled)));
    }

    @Test
    @DisplayName("An element with an incident carries the engine's message: the incidents are read where the list of instances counts some, and for an instance read by its key where it runs")
    void anIncidentNamesItsElement() {
        searchFinds(root("active", 1));
        holdsTheOrderModel();
        listsNoVersions();
        historyOf(ROOT, entry(1, "Start", "START_EVENT", "00.001", "00.001"), entry(2, "Invoice", "CALL_ACTIVITY",
                "00.002", null), entry(3, "Each", "SERVICE_TASK", "00.003", null));
        childrenOf(ROOT, instance(5, 23, "shop__Invoice", 3, "active", ROOT, "callActivity", "2026-10-20T10:00:00.004Z",
                null),
                instance(4, ORDER_DEFINITION, "shop__Order", 4, "active", ROOT, "multiInstance",
                        "2026-10-20T10:00:00.003Z", 0));
        historyOf(4, entry(6, "Each", "SERVICE_TASK", "00.005", null));
        engine.answers("GET", "/v1/process-instances/%d/incidents".formatted(ROOT), 200, incidents(incident(3, "Each",
                "the root failed"), incident(3, "Each", "and failed again")));
        engine.answers("GET", "/v1/process-instances/5", 200, instance(5, 23, "shop__Invoice", 3, "active", ROOT,
                "callActivity", "2026-10-20T10:00:00.004Z", null));
        engine.answers("GET", "/v1/process-instances/5/incidents", 200, incidents(incident(7, "Bill", "no bill")));
        engine.answers("GET", "/v1/process-definitions/23", 200, definition(23, "shop__Invoice", 3, null, """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="i" targetNamespace="x">
                  <bpmn:process id="shop__Invoice" isExecutable="true"><bpmn:serviceTask id="Bill" /></bpmn:process>
                </bpmn:definitions>"""));
        historyOf(5, entry(7, "Bill", "SERVICE_TASK", "00.006", null));
        engine.answers("GET", "/v1/process-instances/8", 200, instance(8, 23, "shop__Invoice", 3, "completed", ROOT,
                "callActivity", "2026-10-20T10:00:00.007Z", null));
        historyOf(8, entry(9, "Bill", "SERVICE_TASK", "00.008", "00.009"));

        final var history = processService.getWorkflowHistory("shop", "Order", null, "order-1", null);
        final var called = processService.getWorkflowHistory("shop", "Order", null, "order-1", "5");
        final var calledAndEnded = processService.getWorkflowHistory("shop", "Order", null, "order-1", "8");

        assertAll(
                () -> assertEquals(List.of("-", "-", "the root failed", "-"), history
                        .elementsHistory()
                        .stream()
                        .map(element -> element.error() == null ? "-" : element.error())
                        .toList()),
                () -> assertEquals("23", called.processDefinitionId()),
                () -> assertEquals("no bill", called
                        .elementsHistory()
                        .getFirst()
                        .error()),
                () -> assertNull(calledAndEnded
                        .elementsHistory()
                        .getFirst()
                        .error()),
                // the multi-instance body counts none, and the called instance which ended has
                // none unresolved, so their incidents are not asked for
                () -> assertTrue(engine
                        .requests()
                        .stream()
                        .noneMatch(request -> "/v1/process-instances/4/incidents"
                                .equals(request.path()) || "/v1/process-instances/8/incidents".equals(request.path()))),
                () -> assertTrue(engine
                        .requests()
                        .stream()
                        .filter(request -> request
                                .path()
                                .endsWith("/incidents"))
                        .allMatch(request -> "state=unresolved&page=1&size=100".equals(request.query()))));
    }

    @Test
    @DisplayName("A history context is served where it names an instance a call activity started below the workflow, also inside a multi-instance body, and nowhere else")
    void aContextIsServedOnlyBelowTheWorkflow(final CapturedOutput output) {
        searchFinds(root("active", 0));
        engine.answers("GET", "/v1/process-definitions/23", 200, definition(23, "shop__Invoice", 3, null, """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="i" targetNamespace="x">
                  <bpmn:process id="shop__Invoice" isExecutable="true"><bpmn:serviceTask id="Bill" /></bpmn:process>
                </bpmn:definitions>"""));
        listsNoVersions();
        // 5 was called by the workflow, 6 by an iteration of its body 4, 8 by another workflow's
        // root 9, and 10 is a sub-process of the workflow
        answersInstance(instance(5, 23, "shop__Invoice", 3, "completed", ROOT, "callActivity",
                "2026-10-20T10:00:00.004Z", null));
        answersInstance(instance(6, 23, "shop__Invoice", 3, "completed", 4L, "callActivity", "2026-10-20T10:00:00.005Z",
                null));
        answersInstance(instance(4, ORDER_DEFINITION, "shop__Order", 4, "completed", ROOT, "multiInstance",
                "2026-10-20T10:00:00.003Z", null));
        answersInstance(instance(8, 23, "shop__Invoice", 3, "completed", 9L, "callActivity", "2026-10-20T10:00:00.006Z",
                null));
        answersInstance(instance(9, ORDER_DEFINITION, "shop__Order", 4, "completed", null, "default",
                "2026-10-20T10:00:00.001Z", null));
        answersInstance(instance(10, ORDER_DEFINITION, "shop__Order", 4, "completed", ROOT, "subprocess",
                "2026-10-20T10:00:00.002Z", null));
        engine.answers("GET", "/v1/process-instances/11", 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no\"}");
        answersInstance(instance(12, 23, "shop__Invoice", 3, "completed", 11L, "callActivity",
                "2026-10-20T10:00:00.007Z", null));

        final var called = processService.getProcessDefinitions("shop", "Order", null, "order-1", "5");
        final var calledInABody = processService.getProcessDefinitions("shop", "Order", null, "order-1", "6");
        final var ofAnotherWorkflow = processService.getProcessDefinitions("shop", "Order", null, "order-1", "8");
        final var aSubProcess = processService.getWorkflowHistory("shop", "Order", null, "order-1", "10");
        final var unknown = processService.getWorkflowHistory("shop", "Order", null, "order-1", "11");
        final var noKey = processService.getWorkflowHistory("shop", "Order", null, "order-1", "invoice");
        final var parentUnknown = processService.getWorkflowHistory("shop", "Order", null, "order-1", "12");

        assertAll(
                () -> assertEquals(List.of(new ProcessDefinition("23", "Invoice", "3", null)), called),
                () -> assertEquals(List.of(new ProcessDefinition("23", "Invoice", "3", null)), calledInABody),
                () -> assertEquals(List.of(), ofAnotherWorkflow),
                () -> assertNull(aSubProcess),
                () -> assertNull(unknown),
                () -> assertNull(noKey),
                () -> assertNull(parentUnknown),
                () -> assertEquals(List.of(
                        "ZenBPM[orders]: the history context '8' names no instance a call activity of the workflow of aggregate 'order-1' (process 'Order' of workflow module 'shop') started, so it is not served",
                        "ZenBPM[orders]: the history context '10' names no instance a call activity of the workflow of aggregate 'order-1' (process 'Order' of workflow module 'shop') started, so it is not served",
                        "ZenBPM[orders]: the history context '12' names no instance a call activity of the workflow of aggregate 'order-1' (process 'Order' of workflow module 'shop') started, so it is not served"),
                        warnings(output)));
    }

    @Test
    @DisplayName("A history context of a partition the engine does not have is unknown, and a failure to read it throws")
    void aContextOfAnotherPartitionIsUnknown() {
        searchFinds(root("active", 0));
        final var otherPartition = 4096L * 7;
        engine.answers("GET", "/v1/process-instances/%d".formatted(otherPartition), 502, """
                {"code":"CLUSTER_ERROR","message":"partition not found"}""");
        engine.answers("GET", "/system/status", 200, """
                {"git": {"commitId": "%s"}, "build": {"version": "v1.8.0"}, "nodes": {"zen-1": {}},
                 "partitions": {"1": {"id": 1}}}""".formatted(FakeEngine.pinnedCommitAsReported()));
        engine.answers("GET", "/v1/process-instances/5", 503, null);

        final var ofAnotherPartition = processService.getWorkflowHistory("shop", "Order", null, "order-1", String
                .valueOf(otherPartition));
        final var away = assertThrows(IllegalStateException.class, () -> processService.getWorkflowHistory("shop",
                "Order", null, "order-1", "5"));

        assertAll(
                () -> assertNull(ofAnotherPartition),
                () -> assertTrue(away
                        .getMessage()
                        .startsWith("ZenBPM adapter 'orders' could not ask the engine for the workflow of aggregate"),
                        away::getMessage));
    }

    @Test
    @DisplayName("A history the engine does not answer is reported without its elements, and the failure is a WARN once until a read succeeds again")
    void aHistoryTheEngineDoesNotAnswerHasNoElements(final CapturedOutput output) {
        searchFinds(root("active", 0));
        holdsTheOrderModel();
        engine.answers("GET", "/v1/process-instances/%d/history".formatted(ROOT), 503, null);

        final var first = processService.getWorkflowHistory("shop", "Order", null, "order-1", null);
        processService.getWorkflowHistory("shop", "Order", null, "order-1", null);
        historyOf(ROOT, entry(1, "Start", "START_EVENT", "00.001", "00.001"));
        final var answered = processService.getWorkflowHistory("shop", "Order", null, "order-1", null);
        engine.answers("GET", "/v1/process-instances/%d/history".formatted(ROOT), 503, null);
        processService.getWorkflowHistory("shop", "Order", null, "order-1", null);

        final var warning = "ZenBPM[orders]: the viewer could not read the history of instance %d, so its elements are reported as unknown; check that the engine at vanillabp.adapters.orders.rest-address answers within vanillabp.adapters.orders.request-timeout; further failures of the viewer are logged at DEBUG until one of its reads succeeds again"
                .formatted(ROOT);
        assertAll(
                () -> assertEquals("11", first.processDefinitionId()),
                () -> assertEquals(Instant
                        .parse("2026-10-20T10:00:00Z")
                        .atOffset(ZoneOffset.UTC), first.startTime()),
                () -> assertNull(first.endTime()),
                () -> assertNull(first.elementsHistory()),
                () -> assertEquals(1, answered
                        .elementsHistory()
                        .size()),
                () -> assertEquals(List.of(warning, warning), warnings(output)));
    }

    @Test
    @DisplayName("Every page of a history is read, and an entry which comes on two pages is reported once")
    void everyPageOfAHistoryIsRead() {
        searchFinds(root("completed", 0));
        holdsTheOrderModel();
        engine.answers("GET", "/v1/process-instances/%d/history".formatted(ROOT), exchange -> FakeEngine.respond(
                exchange, 200, exchange
                        .getRequestURI()
                        .getRawQuery()
                        .contains("page=2")
                                ? history(101, entry(199, "Start", "START_EVENT", "00.001", "00.001"),
                                        entry(200, "End", "END_EVENT", "00.200", "00.200"))
                                : history(101, Stream
                                        .iterate(100, key -> key + 1)
                                        .limit(100)
                                        .map(key -> entry(key, "Start", "START_EVENT", "00.001", "00.001"))
                                        .toArray(String[]::new))));

        final var history = processService.getWorkflowHistory("shop", "Order", null, "order-1", null);

        assertAll(
                () -> assertEquals(101, history
                        .elementsHistory()
                        .size()),
                () -> assertEquals(Instant
                        .parse("2026-10-20T10:00:00.200Z")
                        .atOffset(ZoneOffset.UTC), history.endTime()));
    }

    @Test
    @DisplayName("An instance a call activity started whose entry the read of the history missed is taken by no later entry, and an entry the read met twice is reported once")
    void anInstanceWhoseEntryTheReadMissedIsNotTaken() {
        searchFinds(root("active", 0));
        holdsTheOrderModel();
        // the entry 10 of the instance 15 came on neither page, the entry 30 on both
        engine.answers("GET", "/v1/process-instances/%d/history".formatted(ROOT), exchange -> FakeEngine.respond(
                exchange, 200, exchange
                        .getRequestURI()
                        .getRawQuery()
                        .contains("page=2") ? history(101, entry(30, "Invoice", "CALL_ACTIVITY", "00.030", null))
                                : history(101, Stream
                                        .concat(Stream
                                                .iterate(100, key -> key + 1)
                                                .limit(99)
                                                .map(key -> entry(key, "Start", "START_EVENT", "00.001", "00.001")),
                                                Stream.of(entry(30, "Invoice", "CALL_ACTIVITY", "00.030", null)))
                                        .toArray(String[]::new))));
        childrenOf(ROOT, instance(15, 23, "shop__Invoice", 3, "completed", ROOT, "callActivity",
                "2026-10-20T10:00:00.011Z", 0),
                instance(40, 23, "shop__Invoice", 3, "active", ROOT, "callActivity",
                        "2026-10-20T10:00:00.031Z", 0));

        final var history = processService.getWorkflowHistory("shop", "Order", null, "order-1", null);

        assertEquals(List.of("Invoice CALL_ACTIVITY 40"), described(history.elementsHistory())
                .stream()
                .filter(element -> element.startsWith("Invoice"))
                .toList());
    }

    @Test
    @DisplayName("An event sub-process writes no entry into the history of its workflow, so where the model has one the children are listed anyway, and the event sub-process is reported with its elements; the workflow ended with the last of them")
    void anEventSubProcessIsReportedWithItsElements() {
        searchFinds(instance(ROOT, REMINDED_DEFINITION, "shop__Order", 5, "completed", null, "default",
                "2026-10-20T10:00:00Z", 0));
        engine.answers("GET", "/v1/process-definitions/%d".formatted(REMINDED_DEFINITION), 200, definition(
                REMINDED_DEFINITION, "shop__Order", 5, null, REMINDED_MODEL));
        // the interrupting event sub-process terminated the user task and ended the workflow
        historyOf(ROOT, entry(1, "Start", "START_EVENT", "00.001", "00.001"), entry(2, "Wait", "USER_TASK", "00.002",
                "00.010"));
        childrenOf(ROOT, instance(30, REMINDED_DEFINITION, "shop__Order", 5, "completed", ROOT, "subprocess",
                "2026-10-20T10:00:00.010Z", 0));
        historyOf(30, entry(31, "EscalationStart", "START_EVENT", "00.010", "00.010"), entry(32, "Escalate",
                "USER_TASK", "00.011", "00.050"));
        childrenOf(30);

        final var history = processService.getWorkflowHistory("shop", "Order", null, "order-1", null);

        final var eventSubProcess = history
                .elementsHistory()
                .stream()
                .filter(element -> "Escalation".equals(element.elementId()))
                .findFirst()
                .orElseThrow();
        assertAll(
                () -> assertEquals(List.of(
                        "Start START_EVENT -",
                        "Wait USER_TASK -",
                        "Escalation EVENT_SUB_PROCESS -",
                        "EscalationStart START_EVENT -",
                        "Escalate USER_TASK -"), described(history.elementsHistory())),
                () -> assertEquals(Instant
                        .parse("2026-10-20T10:00:00.010Z")
                        .atOffset(ZoneOffset.UTC), eventSubProcess.startTime()),
                () -> assertEquals(Instant
                        .parse("2026-10-20T10:00:00.050Z")
                        .atOffset(ZoneOffset.UTC), eventSubProcess.endTime()),
                () -> assertEquals(Instant
                        .parse("2026-10-20T10:00:00.050Z")
                        .atOffset(ZoneOffset.UTC), history.endTime()),
                () -> assertEquals(List.of(
                        "GET %s".formatted(SEARCH),
                        "GET /v1/process-definitions/%d".formatted(REMINDED_DEFINITION),
                        "GET /v1/process-instances/%d/history".formatted(ROOT),
                        "GET /v1/process-instances/%d/child-processes".formatted(ROOT),
                        "GET /v1/process-instances/30/history",
                        "GET /v1/process-instances/30/child-processes"), requests()));
    }

    @Test
    @DisplayName("An event sub-process which still runs has no end, and a sub-process instance which starts at no start event of an event sub-process is no event sub-process")
    void aRunningEventSubProcessHasNoEnd() {
        searchFinds(instance(ROOT, REMINDED_DEFINITION, "shop__Order", 5, "active", null, "default",
                "2026-10-20T10:00:00Z", 0));
        engine.answers("GET", "/v1/process-definitions/%d".formatted(REMINDED_DEFINITION), 200, definition(
                REMINDED_DEFINITION, "shop__Order", 5, null, REMINDED_MODEL));
        historyOf(ROOT, entry(1, "Start", "START_EVENT", "00.001", "00.001"), entry(2, "Wait", "USER_TASK", "00.002",
                null));
        childrenOf(ROOT, instance(30, REMINDED_DEFINITION, "shop__Order", 5, "active", ROOT, "subprocess",
                "2026-10-20T10:00:00.010Z", 0),
                instance(40, REMINDED_DEFINITION, "shop__Order", 5, "active", ROOT,
                        "subprocess", "2026-10-20T10:00:00.040Z", 0));
        historyOf(30, entry(31, "EscalationStart", "START_EVENT", "00.010", "00.010"), entry(32, "Escalate",
                "USER_TASK", "00.011", null));
        historyOf(40, entry(41, "Wait", "USER_TASK", "00.040", null));
        childrenOf(30);
        childrenOf(40);

        final var history = processService.getWorkflowHistory("shop", "Order", null, "order-1", null);

        assertAll(
                () -> assertEquals(List.of(
                        "Start START_EVENT -",
                        "Wait USER_TASK -",
                        "Escalation EVENT_SUB_PROCESS -",
                        "EscalationStart START_EVENT -",
                        "Escalate USER_TASK -",
                        "Wait USER_TASK -"), described(history.elementsHistory())),
                () -> assertNull(history
                        .elementsHistory()
                        .get(2)
                        .endTime()),
                () -> assertNull(history.endTime()));
    }

    @Test
    @DisplayName("A list of versions the engine does not answer costs the definitions the call activities would start, as a model which cannot be read does, and the failure is a WARN once until a read succeeds again")
    void aListOfVersionsTheEngineDoesNotAnswerCostsTheCalledDefinitions(final CapturedOutput output) {
        searchFinds(root("active", 0));
        holdsTheOrderModel();
        engine.answers("GET", "/v1/process-definitions", 503, null);

        final var first = processService.getProcessDefinitions("shop", "Order", null, "order-1", null);
        processService.getProcessDefinitions("shop", "Order", null, "order-1", null);
        listsNoVersions();
        processService.getProcessDefinitions("shop", "Order", null, "order-1", null);
        engine.answers("GET", "/v1/process-definitions", 503, null);
        processService.getProcessDefinitions("shop", "Order", null, "order-1", null);

        final var warning = "ZenBPM[orders]: the viewer could not read the versions of the processes the call activities of process definition 11 call, so the definitions they would start are not reported; check that the engine at vanillabp.adapters.orders.rest-address answers within vanillabp.adapters.orders.request-timeout; further failures of the viewer are logged at DEBUG until one of its reads succeeds again";
        assertAll(
                () -> assertEquals(List.of(new ProcessDefinition("11", "Order", "4", null)), first),
                () -> assertEquals(List.of(warning, warning), warnings(output)));
    }

    @Test
    @DisplayName("Under message-start-lookup scan, where the search finds no workflow which runs, the workflow a message started, which has no business key, is found by its id variable; an instance of a sub-process the scan finds is not taken for it, and a workflow the search finds running costs no scan")
    void underScanAWorkflowAMessageStartedIsFound() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        configuration.setMessageStartLookup(MessageStartLookup.SCAN);
        try (var scanning = new ZenBpmClientFactory("orders", configuration, () -> "shop")) {
            final var messages = new ModuleMessages(List.of("shop__Order"), Set.of(), Set.of("shop__ordered"), Set.of(
                    "shop__Order"), List.of());
            scanning
                    .deployedProcesses()
                    .recordMessages("shop", messages, () -> messages);
            // the aggregate's first workflow ended; its second was started by a message
            final var keyed = new AtomicReference<>(instance(7, ORDER_DEFINITION, "shop__Order", 2, "completed", null,
                    "default", "2026-10-20T10:00:00Z", 0));
            final var running = new AtomicReference<>(startedByAMessage(ROOT, "default"));
            engine.answers("GET", SEARCH, exchange -> FakeEngine.respond(exchange, 200, exchange
                    .getRequestURI()
                    .getRawQuery()
                    .contains("businessKey=") ? page(1, keyed.get()) : page(1, running.get())));
            holdsTheOrderModel();
            listsNoVersions();
            final var viewing = processService(scanning);

            final var definitions = viewing.getProcessDefinitions("shop", "Order", persistence(), "order-1", null);
            final var scansWithPersistence = scans();
            final var withoutPersistence = viewing.getProcessDefinitions("shop", "Order", null, "order-1", null);
            final var scansWithoutPersistence = scans();
            running.set(startedByAMessage(ROOT + 1, "subprocess"));
            final var aSubProcessScanned = viewing.getProcessDefinitions("shop", "Order", persistence(), "order-1",
                    null);
            keyed.set(instance(8, ORDER_DEFINITION, "shop__Order", 3, "active", null, "default",
                    "2026-10-20T10:00:02Z", 0));
            final var scansBefore = scans();
            final var keyedRunning = viewing.getProcessDefinitions("shop", "Order", persistence(), "order-1", null);

            assertAll(
                    () -> assertEquals("4", definitions
                            .getFirst()
                            .version()),
                    // nothing names the id variable, so nothing is scanned and the ended workflow is shown
                    () -> assertEquals("2", withoutPersistence
                            .getFirst()
                            .version()),
                    () -> assertEquals(scansWithPersistence, scansWithoutPersistence),
                    () -> assertEquals("2", aSubProcessScanned
                            .getFirst()
                            .version()),
                    () -> assertEquals("3", keyedRunning
                            .getFirst()
                            .version()),
                    () -> assertEquals(scansBefore, scans()));
        }
    }

    @Test
    @DisplayName("A history context, and a parent on the way up to the workflow, which a node lagging behind does not show yet, is read again within workflow-visibility-timeout, while a context of another workflow is refused at once")
    void aContextNotVisibleYetIsReadAgain() {
        try (var waiting = configured(Duration.ofSeconds(5))) {
            searchFinds(root("active", 0));
            engine.answers("GET", "/v1/process-definitions/23", 200, definition(23, "shop__Invoice", 3, null,
                    """
                            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="i" targetNamespace="x">
                              <bpmn:process id="shop__Invoice" isExecutable="true"><bpmn:serviceTask id="Bill" /></bpmn:process>
                            </bpmn:definitions>"""));
            listsNoVersions();
            answersAfterA404(instance(5, 23, "shop__Invoice", 3, "completed", ROOT, "callActivity",
                    "2026-10-20T10:00:00.004Z", null));
            answersInstance(instance(6, 23, "shop__Invoice", 3, "completed", 4L, "callActivity",
                    "2026-10-20T10:00:00.005Z", null));
            answersAfterA404(instance(4, ORDER_DEFINITION, "shop__Order", 4, "completed", ROOT, "multiInstance",
                    "2026-10-20T10:00:00.003Z", null));
            answersInstance(instance(8, 23, "shop__Invoice", 3, "completed", 9L, "callActivity",
                    "2026-10-20T10:00:00.006Z", null));
            answersInstance(instance(9, ORDER_DEFINITION, "shop__Order", 4, "completed", null, "default",
                    "2026-10-20T10:00:00.001Z", null));
            final var viewing = processService(waiting);

            final var called = viewing.getProcessDefinitions("shop", "Order", null, "order-1", "5");
            final var calledInABody = viewing.getProcessDefinitions("shop", "Order", null, "order-1", "6");
            final var ofAnotherWorkflow = viewing.getProcessDefinitions("shop", "Order", null, "order-1", "8");

            assertAll(
                    () -> assertEquals(List.of(new ProcessDefinition("23", "Invoice", "3", null)), called),
                    () -> assertEquals(List.of(new ProcessDefinition("23", "Invoice", "3", null)), calledInABody),
                    () -> assertEquals(List.of(), ofAnotherWorkflow),
                    () -> assertEquals(List.of(
                            "GET /v1/process-instances/5",
                            "GET /v1/process-instances/5",
                            "GET /v1/process-instances/6",
                            "GET /v1/process-instances/4",
                            "GET /v1/process-instances/4",
                            "GET /v1/process-instances/8",
                            "GET /v1/process-instances/9"),
                            requests()
                                    .stream()
                                    .filter(request -> request.matches("GET /v1/process-instances/[0-9]+"))
                                    .toList()));
        }
    }

    @ParameterizedTest(name = "{0} is {1}")
    @MethodSource("engineTypes")
    @DisplayName("Every type of element the engine writes into a history is the type of the SPI of the same meaning")
    void theTypesOfTheEngineAreTheTypesOfTheSpi(
        final String engineType,
        final WorkflowElementType type
    ) {
        assertEquals(type, ZenBpmWorkflowViewer.typeOf(engineType));
    }

    // the strings of pkg/bpmn/model/bpmn20 at the pinned commit; a send task is a service task there
    private static Stream<Arguments> engineTypes() {
        return Stream.of(
                Arguments.of("START_EVENT", WorkflowElementType.START_EVENT),
                Arguments.of("END_EVENT", WorkflowElementType.END_EVENT),
                Arguments.of("INTERMEDIATE_CATCH_EVENT", WorkflowElementType.INTERMEDIATE_CATCH_EVENT),
                Arguments.of("INTERMEDIATE_THROW_EVENT", WorkflowElementType.INTERMEDIATE_THROW_EVENT),
                Arguments.of("INTERMEDIATE_MESSAGE_THROW_EVENT", WorkflowElementType.INTERMEDIATE_THROW_EVENT),
                Arguments.of("BOUNDARY_EVENT", WorkflowElementType.BOUNDARY_EVENT),
                Arguments.of("SERVICE_TASK", WorkflowElementType.SERVICE_TASK),
                Arguments.of("USER_TASK", WorkflowElementType.USER_TASK),
                Arguments.of("BUSINESS_RULE_TASK", WorkflowElementType.BUSINESS_RULE_TASK),
                Arguments.of("RECEIVE_TASK", WorkflowElementType.RECEIVE_TASK),
                Arguments.of("CALL_ACTIVITY", WorkflowElementType.CALL_ACTIVITY),
                Arguments.of("SUB_PROCESS", WorkflowElementType.SUB_PROCESS),
                Arguments.of("SEQUENCE_FLOW", WorkflowElementType.SEQUENCE_FLOW),
                Arguments.of("EXCLUSIVE_GATEWAY", WorkflowElementType.EXCLUSIVE_GATEWAY),
                Arguments.of("PARALLEL_GATEWAY", WorkflowElementType.PARALLEL_GATEWAY),
                Arguments.of("INCLUSIVE_GATEWAY", WorkflowElementType.INCLUSIVE_GATEWAY),
                Arguments.of("EVENT_BASED_GATEWAY", WorkflowElementType.EVENT_BASED_GATEWAY),
                Arguments.of("SCRIPT_TASK", WorkflowElementType.UNKNOWN));
    }

    private void holdsTheOrderModel() {
        engine.answers("GET", MODEL, 200, definition(ORDER_DEFINITION, "shop__Order", 4, null, ORDER_MODEL));
    }

    private void listsNoVersions() {
        engine.answers("GET", "/v1/process-definitions", 200, definitions());
    }

    private void searchFinds(final String... instances) {
        engine.answers("GET", SEARCH, 200, page(instances.length, instances));
    }

    private void historyOf(
        final long instanceKey,
        final String... entries
    ) {
        engine.answers("GET", "/v1/process-instances/%d/history".formatted(instanceKey), 200, history(entries.length,
                entries));
    }

    private void childrenOf(
        final long instanceKey,
        final String... children
    ) {
        engine.answers("GET", "/v1/process-instances/%d/child-processes".formatted(instanceKey), 200, page(
                children.length, children));
    }

    private void answersInstance(final String instance) {
        try {
            engine.answers("GET", "/v1/process-instances/%d".formatted(JSON
                    .readTree(instance)
                    .path("key")
                    .asLong()), 200, instance);
        } catch (final JsonProcessingException notJson) {
            throw new IllegalArgumentException(notJson);
        }
    }

    // the searches which ask for no business key, as the scan does
    private long scans() {
        return engine
                .requests()
                .stream()
                .filter(request -> SEARCH.equals(request.path()))
                .filter(request -> !request
                        .query()
                        .contains("businessKey="))
                .count();
    }

    // a node lagging behind answers 404 for an instance another node shows
    private void answersAfterA404(final String instance) {
        try {
            final var reads = new AtomicInteger();
            engine.answers("GET", "/v1/process-instances/%d".formatted(JSON
                    .readTree(instance)
                    .path("key")
                    .asLong()), exchange -> {
                        if (reads.incrementAndGet() == 1) {
                            FakeEngine.respond(exchange, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no\"}");
                        } else {
                            FakeEngine.respond(exchange, 200, instance);
                        }
                    });
        } catch (final JsonProcessingException notJson) {
            throw new IllegalArgumentException(notJson);
        }
    }

    private List<String> requests() {
        return engine
                .requests()
                .stream()
                .map(request -> "%s %s".formatted(request.method(), request.path()))
                .toList();
    }

    private ZenBpmClientFactory configured(final Duration visibilityTimeout) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        configuration.setRequestTimeout(Duration.ofSeconds(5));
        configuration.setWorkflowVisibilityTimeout(visibilityTimeout);
        return new ZenBpmClientFactory("orders", configuration, () -> "shop");
    }

    private static ZenBpmProcessService<Object> processService(final ZenBpmClientFactory factory) {
        return new ZenBpmProcessService<>("orders", factory, TestCollaborators.of("orders", TestScoping.of(
                NameClashAvoidance.USE_PREFIX)), (
                    module,
                    process) -> null);
    }

    private static AggregatePersistenceAware<Object> persistence() {
        return new AggregatePersistenceAware<>() {

            @Override
            public String getAggregateIdName() {
                return "orderId";
            }

            @Override
            public Object loadById(final Object aggregateId) {
                throw new AssertionError("the viewer loads no aggregate");
            }

        };
    }

    // an instance a message started carries no business key, only the id variable
    private static String startedByAMessage(
        final long key,
        final String processType
    ) {
        return """
                {"key": %d, "processDefinitionKey": %d, "bpmnProcessId": "shop__Order", "version": 4,
                 "createdAt": "2026-10-20T10:00:01Z", "state": "active", "processType": "%s",
                 "variables": {"orderId": "order-1"}}"""
                .formatted(key, ORDER_DEFINITION, processType);
    }

    private static String root(
        final String state,
        final Integer incidentCount
    ) {
        return instance(ROOT, ORDER_DEFINITION, "shop__Order", 4, state, null, "default", "2026-10-20T10:00:00Z",
                incidentCount);
    }

    private static String instance(
        final long key,
        final long definitionKey,
        final String process,
        final int version,
        final String state,
        final Long parentKey,
        final String processType,
        final String createdAt,
        final Integer incidentCount
    ) {
        return """
                {"key": %d, "processDefinitionKey": %d, "bpmnProcessId": "%s", "version": %d, "businessKey": "order-1",
                 "createdAt": "%s", "state": "%s", "processType": "%s", "variables": {}%s%s}"""
                .formatted(key, definitionKey, process, version, createdAt, state, processType, parentKey == null ? ""
                        : ", \"parentProcessInstanceKey\": %d".formatted(parentKey),
                        incidentCount == null ? ""
                                : ", \"incidentCount\": %d".formatted(incidentCount));
    }

    private static String page(
        final long totalCount,
        final String... instances
    ) {
        return """
                {"partitions": [{"partition": 1, "totalCount": %d, "items": [%s]}],
                 "page": 1, "size": 100, "count": %d, "totalCount": %d}"""
                .formatted(totalCount, String.join(",", instances), instances.length, totalCount);
    }

    // the times are seconds and milliseconds after ten o'clock
    private static String entry(
        final long key,
        final String elementId,
        final String elementType,
        final String createdAt,
        final String completedAt
    ) {
        return """
                {"key": %d, "processInstanceKey": 1, "elementId": "%s", "elementType": "%s",
                 "createdAt": "2026-10-20T10:00:%sZ"%s, "inputVariables": {}}"""
                .formatted(key, elementId, elementType, createdAt, completedAt == null ? ""
                        : ", \"completedAt\": \"2026-10-20T10:00:%sZ\"".formatted(completedAt));
    }

    private static String history(
        final long totalCount,
        final String... entries
    ) {
        return """
                {"items": [%s], "page": 1, "size": 100, "count": %d, "totalCount": %d}"""
                .formatted(String.join(",", entries), entries.length, totalCount);
    }

    private static String definition(
        final long key,
        final String process,
        final int version,
        final String versionTag
    ) {
        return """
                {"key": %d, "bpmnProcessId": "%s", "version": %d%s}"""
                .formatted(key, process, version, versionTag == null ? ""
                        : ", \"versionTag\": \"%s\"".formatted(versionTag));
    }

    private static String definition(
        final long key,
        final String process,
        final int version,
        final String versionTag,
        final String bpmn
    ) {
        try {
            final var withoutBpmn = definition(key, process, version, versionTag);
            return "%s, \"bpmnData\": %s}".formatted(withoutBpmn.substring(0, withoutBpmn.length() - 1), JSON
                    .writeValueAsString(bpmn));
        } catch (final JsonProcessingException notJson) {
            throw new IllegalArgumentException(notJson);
        }
    }

    private static String definitions(final String... definitions) {
        return """
                {"items": [%s], "page": 1, "size": 100, "count": %d, "totalCount": %d}"""
                .formatted(String.join(",", definitions), definitions.length, definitions.length);
    }

    private static String incidents(final String... incidents) {
        return """
                {"items": [%s], "page": 1, "size": 100, "count": %d, "totalCount": %d}"""
                .formatted(String.join(",", incidents), incidents.length, incidents.length);
    }

    private static String incident(
        final long elementInstanceKey,
        final String elementId,
        final String message
    ) {
        return """
                {"key": 9%d, "elementInstanceKey": %d, "elementId": "%s", "processInstanceKey": 1,
                 "message": "%s", "createdAt": "2026-10-20T10:00:01Z", "executionToken": 1}"""
                .formatted(elementInstanceKey, elementInstanceKey, elementId, message);
    }

    private static List<String> described(final List<WorkflowElementHistory> elements) {
        return elements
                .stream()
                .map(element -> "%s %s %s".formatted(element.elementId(), element.elementType(), element
                        .secondaryWorkflowHistoryContext() == null ? "-" : element.secondaryWorkflowHistoryContext()))
                .toList();
    }

    private static List<String> warnings(final CapturedOutput output) {
        return output
                .getAllOfThisTest()
                .lines()
                .filter(line -> line.contains(" WARN "))
                .map(line -> line.substring(line.indexOf("ZenBPM[")))
                .map(line -> Arrays
                        .stream(line.split("\n"))
                        .findFirst()
                        .orElse(line))
                .collect(Collectors.toList());
    }

}
