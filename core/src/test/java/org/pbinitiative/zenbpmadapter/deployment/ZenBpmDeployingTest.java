/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.TestStarts;
import org.pbinitiative.zenbpmadapter.TestWiring;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.MessageStartLookup;
import org.pbinitiative.zenbpmadapter.client.ZenBpmApiException;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the deployment service deploys a workflow module to an engine standing in for ZenBPM: one
 * request per file after the engine is ready, the version of each process registered with the
 * platform also where nothing changed, what the engine refuses named with its reason, and the
 * identifiers the files declare reported.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDeployingTest {

    private static final String ADAPTER_ID = "orders";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ORDER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="order">
              <bpmn:message id="Message_Paid" name="paid">
                <bpmn:extensionElements>
                  <zenbpm:subscription correlationKey="=orderId" />
                </bpmn:extensionElements>
              </bpmn:message>
              <bpmn:error id="Error_Declined" errorCode="declined" />
              <bpmn:process id="Order" isExecutable="true">
                <bpmn:serviceTask id="Charge">
                  <bpmn:extensionElements>
                    <zenbpm:taskDefinition type="charge" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:receiveTask id="AwaitPayment" messageRef="Message_Paid" />
              </bpmn:process>
            </bpmn:definitions>
            """;

    private static final String DMN = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions" name="Rating">
              <decision id="rating" name="Rating" />
            </definitions>
            """;

    private FakeEngine engine;

    @BeforeEach
    void startTheEngine() {
        engine = FakeEngine.started();
        engine.answers("GET", "/system/health/ready", 200, "{\"status\":\"UP\",\"reasons\":[]}");
        engine.answers("GET", "/system/status", 200, """
                {"git":{"commitId":"bdd795a399b6"},"build":{"version":"1.2.0"},"nodes":{"node-1":{}}}""");
    }

    @AfterEach
    void stopTheEngine() {
        engine.close();
    }

    @Test
    @DisplayName("Every file is deployed after the engine answered ready, the DMN files before the BPMN files, the version of each process is registered, and what the engine ended up with is remembered and logged")
    void everyFileIsDeployedAndItsVersionRegistered(final CapturedOutput output) {
        engine.answers("POST", "/v1/process-definitions", 201, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 200, """
                {"key":11,"version":3,"bpmnProcessId":"shop__Order","versionTag":"v3","bpmnData":"<xml/>"}""");
        engine.answers("POST", "/v1/dmn-resource-definitions", 200, "{\"dmnResourceDefinitionKey\":21}");
        final var wiring = TestWiring.claiming("Order");
        final var scoping = TestScoping.of(NameClashAvoidance.USE_PREFIX);

        try (var registry = registry()) {
            final var deploymentService = deploymentService(registry, scoping, wiring);
            final var context = prepared(deploymentService, ORDER);
            deploymentService.readDmn("shop", context, "rating.dmn", new ByteArrayInputStream(DMN.getBytes(
                    StandardCharsets.UTF_8)));

            deploymentService.deployResources("shop", context);

            final var log = output.getAllOfThisTest();
            assertAll(
                    () -> assertEquals(List.of(
                            "GET /system/health/ready",
                            "GET /system/status",
                            "POST /v1/dmn-resource-definitions",
                            "POST /v1/process-definitions",
                            "GET /v1/process-definitions/11"),
                            engine
                                    .requests()
                                    .stream()
                                    .map(request -> "%s %s".formatted(request.method(), request.path()))
                                    .toList()),
                    () -> assertEquals(List.of("registerDeployedVersion[orders, shop, Order, 3]"), wiring.callsOf(
                            "registerDeployedVersion")),
                    () -> assertEquals(Optional.of(11L), deployedProcesses(registry).keyOf("shop", "Order")),
                    () -> assertEquals(Optional.of(3), deployedProcesses(registry).versionOf("shop", "Order")),
                    () -> assertEquals(Optional.of("<xml/>"), deployedProcesses(registry).xmlOf(11)),
                    () -> assertEquals(List.of("shop__Order"), deployedProcesses(registry).scopedIdsOf("shop")),
                    () -> assertTrue(
                            log.contains("""
                                    ZenBPM[orders]: workflow module 'shop' is deployed to the engine at %s:
                                      rating.dmn as DMN resource definition key 21 (unchanged)
                                      order.bpmn as process 'shop__Order', version 3, definition key 11"""
                                    .formatted(engine.address())),
                            log),
                    () -> assertEquals(List.of(
                            "reportIdentifiersTheModelsDeclare(orders, shop, [ModelIdentifier[kind=MESSAGE_NAME, plainIdentifier=paid, bpmnProcessId=null], ModelIdentifier[kind=ERROR_CODE, plainIdentifier=declined, bpmnProcessId=null], ModelIdentifier[kind=TASK_DEFINITION, plainIdentifier=charge, bpmnProcessId=Order], ModelIdentifier[kind=DMN_DECISION_ID, plainIdentifier=rating, bpmnProcessId=null]])"),
                            scoping
                                    .calls()
                                    .stream()
                                    .filter(call -> call.startsWith("reportIdentifiers"))
                                    .toList()));
        }
    }

    @Test
    @DisplayName("A file the pipeline left as it was written is sent as its bytes, a rewritten one written out")
    void aFileIsSentAsItsBytesWhereNothingWasChanged() {
        engine.answers("POST", "/v1/process-definitions", 200, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 200, """
                {"key":11,"version":1,"bpmnProcessId":"Order","bpmnData":"<xml/>"}""");

        try (var registry = registry()) {
            deploymentService(registry, TestScoping.of(NameClashAvoidance.NONE), TestWiring.claiming("Order"))
                    .deployResources("shop",
                            prepared(deploymentService(registry, TestScoping.of(NameClashAvoidance.NONE),
                                    TestWiring.claiming("Order")), ORDER));
            deploymentService(registry, TestScoping.of(NameClashAvoidance.USE_PREFIX), TestWiring.claiming("Order"))
                    .deployResources("shop", prepared(deploymentService(registry, TestScoping.of(
                            NameClashAvoidance.USE_PREFIX), TestWiring.claiming("Order")), ORDER));

            final var sent = engine
                    .requests()
                    .stream()
                    .filter(request -> request
                            .path()
                            .equals("/v1/process-definitions"))
                    .map(FakeEngine.Request::body)
                    .toList();
            assertAll(
                    () -> assertEquals(ORDER, sent.getFirst()),
                    () -> assertTrue(sent
                            .getLast()
                            .contains("<bpmn:process id=\"shop__Order\""), sent::getLast),
                    () -> assertEquals("application/octet-stream", engine
                            .requests()
                            .get(2)
                            .contentType()));
        }
    }

    @Test
    @DisplayName("A BPMN file the engine refuses ends the deployment naming file, workflow module, process and the engine's reason; no version of the files it took before is registered or remembered")
    void aRejectedModelNamesTheFile() {
        final var deployments = new AtomicInteger();
        engine.answers("POST", "/v1/process-definitions", exchange -> {
            if (deployments.getAndIncrement() == 0) {
                FakeEngine.respond(exchange, 201, "{\"processDefinitionKey\":11}");
                return;
            }
            FakeEngine.respond(exchange, 400,
                    "{\"code\":\"BAD_REQUEST\",\"message\":\"no registered BaseElement with ID [nowhere]\"}");
        });
        engine.answers("GET", "/v1/process-definitions/11", 200, """
                {"key":11,"version":1,"bpmnProcessId":"Order","bpmnData":"<xml/>"}""");
        final var wiring = TestWiring.claiming("Order", "Refund");

        try (var registry = registry()) {
            final var deploymentService = deploymentService(registry, TestScoping.of(NameClashAvoidance.NONE), wiring);
            final var context = prepared(deploymentService, ORDER);
            prepared(deploymentService, context, "refund.bpmn", ORDER.replace("\"Order\"", "\"Refund\""));

            final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.deployResources(
                    "shop", context));

            assertAll(
                    () -> assertEquals(
                            """
                                    ZenBPM refuses BPMN file 'refund.bpmn' of workflow module 'shop' (process 'Refund'): POST \
                                    /v1/process-definitions was answered with status 400, BAD_REQUEST: no registered BaseElement \
                                    with ID [nowhere]. Correct the model where the engine's reason points, or take the file out \
                                    of the workflow module.""",
                            failure.getMessage()),
                    () -> assertInstanceOf(ZenBpmApiException.class, failure.getCause()),
                    () -> assertEquals(2, deployments.get()),
                    () -> assertEquals(List.of(), wiring.callsOf("registerDeployedVersion")),
                    () -> assertEquals(Optional.empty(), deployedProcesses(registry).keyOf("shop", "Order")));
        }
    }

    @Test
    @DisplayName("A DMN file the engine refuses ends the deployment naming file and workflow module, before any process calling its decisions is sent")
    void aRejectedDecisionNamesTheFile() {
        engine.answers("POST", "/v1/process-definitions", 201, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 200, """
                {"key":11,"version":1,"bpmnProcessId":"Order","bpmnData":"<xml/>"}""");
        engine.answers("POST", "/v1/dmn-resource-definitions", 400,
                "{\"code\":\"BAD_REQUEST\",\"message\":\"no DMN\"}");

        try (var registry = registry()) {
            final var deploymentService = deploymentService(registry, TestScoping.of(NameClashAvoidance.NONE),
                    TestWiring.claiming("Order"));
            final var context = prepared(deploymentService, ORDER);
            deploymentService.readDmn("shop", context, "rating.dmn", new ByteArrayInputStream(DMN.getBytes(
                    StandardCharsets.UTF_8)));

            final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.deployResources(
                    "shop", context));

            assertAll(
                    () -> assertTrue(failure
                            .getMessage()
                            .startsWith(
                                    "ZenBPM refuses DMN file 'rating.dmn' of workflow module 'shop': POST /v1/dmn-resource-definitions was answered with status 400, BAD_REQUEST: no DMN."),
                            failure.getMessage()),
                    () -> assertTrue(engine
                            .requests()
                            .stream()
                            .noneMatch(request -> request
                                    .path()
                                    .startsWith("/v1/process-definitions")),
                            engine.requests()::toString));
        }
    }

    @Test
    @DisplayName("Any other failure of a file ends the deployment naming file, workflow module, process and the address of the engine, with the failure as its cause")
    void anotherFailureNamesTheFileAndTheAddress() {
        engine.answers("POST", "/v1/process-definitions", 201, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 500, "{\"code\":\"TECHNICAL_ERROR\",\"message\":\"boom\"}");

        try (var registry = registry()) {
            final var deploymentService = deploymentService(registry, TestScoping.of(NameClashAvoidance.NONE),
                    TestWiring.claiming("Order"));
            final var context = prepared(deploymentService, ORDER);

            final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.deployResources(
                    "shop", context));

            assertAll(
                    () -> assertEquals(
                            "ZenBPM adapter 'orders' could not deploy BPMN file 'order.bpmn' of workflow module 'shop' (process 'Order') to the engine at %s: GET /v1/process-definitions/11 was answered with status 500, TECHNICAL_ERROR: boom"
                                    .formatted(engine.address()),
                            failure.getMessage()),
                    () -> assertInstanceOf(ZenBpmApiException.class, failure.getCause()));
        }
    }

    @Test
    @DisplayName("The messages every version the engine holds of a deployed process waits for are remembered for the workflow module, as the engine holds them, and those the deployed version starts with, not those of an older one; the deployed version is read from the answer of the deployment")
    void theMessagesOfEveryHeldVersionAreRemembered() throws Exception {
        engine.answers("POST", "/v1/process-definitions", 201, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 200, definition(11, 2, heldOrder("shop__paid")));
        engine.answers("GET", "/v1/process-definitions", 200, """
                {"items": [{"key": 9, "version": 1, "bpmnProcessId": "shop__Order"},
                           {"key": 11, "version": 2, "bpmnProcessId": "shop__Order"}], "totalCount": 2}""");
        engine.answers("GET", "/v1/process-definitions/9", 200, definition(9, 1, heldOrder("shop__settled")
                .replace("<bpmn:process id=\"shop__Order\" isExecutable=\"true\">", """
                        <bpmn:message id="Message_Ordered" name="shop__ordered" />
                        <bpmn:process id="shop__Order" isExecutable="true">
                          <bpmn:startEvent id="Ordered">
                            <bpmn:messageEventDefinition messageRef="Message_Ordered" />
                          </bpmn:startEvent>""")));

        try (var registry = registry()) {
            final var deploymentService = deploymentService(registry, TestScoping.of(NameClashAvoidance.USE_PREFIX),
                    TestWiring.claiming("Order"));
            deploymentService.deployResources("shop", prepared(deploymentService, ORDER));

            assertAll(
                    () -> assertEquals(Optional.of(new ModuleMessages(List.of("shop__Order"), Set.of("shop__paid",
                            "shop__settled"), Set.of(), Set.of(), List.of())), deployedProcesses(registry)
                                    .messagesOf("shop")),
                    () -> assertEquals(List.of(
                            "GET /v1/process-definitions/11",
                            "GET /v1/process-definitions",
                            "GET /v1/process-definitions/9"),
                            engine
                                    .requests()
                                    .stream()
                                    .map(request -> "%s %s".formatted(request.method(), request.path()))
                                    .filter(request -> request.startsWith("GET /v1/process-definitions"))
                                    .toList()));
        }
    }

    @Test
    @DisplayName("Read again, every version the engine holds now is read, a version another node deployed since included, and the newest one's start messages count, not those of the version this start deployed")
    void readAgainEveryVersionTheEngineHoldsNowIsRead() throws Exception {
        engine.answers("POST", "/v1/process-definitions", 201, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 200, definition(11, 1, heldOrder("shop__paid")
                .replace("<bpmn:process id=\"shop__Order\" isExecutable=\"true\">", """
                        <bpmn:message id="Message_Ordered" name="shop__ordered" />
                        <bpmn:process id="shop__Order" isExecutable="true">
                          <bpmn:startEvent id="Ordered">
                            <bpmn:messageEventDefinition messageRef="Message_Ordered" />
                          </bpmn:startEvent>""")));

        try (var registry = registry()) {
            final var deploymentService = deploymentService(registry, TestScoping.of(NameClashAvoidance.USE_PREFIX),
                    TestWiring.claiming("Order"));
            deploymentService.deployResources("shop", prepared(deploymentService, ORDER));
            final var keptWhileDeploying = deployedProcesses(registry).messagesOf("shop");
            // another node deploys the next version
            engine.answers("GET", "/v1/process-definitions", 200, """
                    {"items": [{"key": 11, "version": 1, "bpmnProcessId": "shop__Order"},
                               {"key": 12, "version": 2, "bpmnProcessId": "shop__Order"}], "totalCount": 2}""");
            engine.answers("GET", "/v1/process-definitions/12", 200, definition(12, 2, heldOrder("shop__settled")
                    .replace("<bpmn:process id=\"shop__Order\" isExecutable=\"true\">", """
                            <bpmn:message id="Message_Returned" name="shop__returned" />
                            <bpmn:process id="shop__Order" isExecutable="true">
                              <bpmn:startEvent id="Returned">
                                <bpmn:messageEventDefinition messageRef="Message_Returned" />
                              </bpmn:startEvent>""")));

            final var readAgain = deployedProcesses(registry).messagesReadAgain("shop");

            assertAll(
                    () -> assertEquals(Optional.of(new ModuleMessages(List.of("shop__Order"), Set.of("shop__paid"), Set
                            .of("shop__ordered"), Set.of("shop__Order"), List.of())), keptWhileDeploying),
                    () -> assertEquals(Optional.of(new ModuleMessages(List.of("shop__Order"), Set.of("shop__paid",
                            "shop__settled"), Set.of("shop__returned"), Set.of("shop__Order"), List.of())), readAgain),
                    () -> assertEquals(readAgain, deployedProcesses(registry).messagesOf("shop")));
        }
    }

    @Test
    @DisplayName("Read again, a process counts as started by a message only where its newest version has a message start event, not where only the version this start deployed has one")
    void readAgainAProcessIsStartedByAMessageWhereItsNewestVersionIs() throws Exception {
        engine.answers("POST", "/v1/process-definitions", 201, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 200, definition(11, 1, heldOrder("shop__paid")
                .replace("<bpmn:process id=\"shop__Order\" isExecutable=\"true\">", """
                        <bpmn:message id="Message_Ordered" name="shop__ordered" />
                        <bpmn:process id="shop__Order" isExecutable="true">
                          <bpmn:startEvent id="Ordered">
                            <bpmn:messageEventDefinition messageRef="Message_Ordered" />
                          </bpmn:startEvent>""")));

        try (var registry = registry()) {
            final var deploymentService = deploymentService(registry, TestScoping.of(NameClashAvoidance.USE_PREFIX),
                    TestWiring.claiming("Order"));
            deploymentService.deployResources("shop", prepared(deploymentService, ORDER));
            // another node deploys a next version which no message starts
            engine.answers("GET", "/v1/process-definitions", 200, """
                    {"items": [{"key": 11, "version": 1, "bpmnProcessId": "shop__Order"},
                               {"key": 12, "version": 2, "bpmnProcessId": "shop__Order"}], "totalCount": 2}""");
            engine.answers("GET", "/v1/process-definitions/12", 200, definition(12, 2, heldOrder("shop__paid")));

            assertEquals(Set.of(), deployedProcesses(registry)
                    .messagesReadAgain("shop")
                    .orElseThrow()
                    .startedByMessage());
        }
    }

    @Test
    @DisplayName("Read again, versions the engine does not hand out are named with the reason")
    void versionsWhichCannotBeReadAgainAreNamed() throws Exception {
        engine.answers("POST", "/v1/process-definitions", 201, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 200, definition(11, 1, heldOrder("shop__paid")));

        try (var registry = registry()) {
            final var deploymentService = deploymentService(registry, TestScoping.of(NameClashAvoidance.USE_PREFIX),
                    TestWiring.claiming("Order"));
            deploymentService.deployResources("shop", prepared(deploymentService, ORDER));
            engine.answers("GET", "/v1/process-definitions", 500, """
                    {"code":"TECHNICAL_ERROR","message":"database is locked"}""");

            assertEquals(Optional.of(new ModuleMessages(List.of("shop__Order"), Set.of(), Set.of(), Set.of(), List.of(
                    "the versions of process 'shop__Order' (GET /v1/process-definitions was answered with status 500, TECHNICAL_ERROR: database is locked)"))),
                    deployedProcesses(registry).messagesReadAgain("shop"));
        }
    }

    @Test
    @DisplayName("The first version of a process is the only one, so nothing more is asked; a module which deployed no process remembers no messages")
    void aFirstVersionIsTheOnlyOne() throws Exception {
        engine.answers("POST", "/v1/process-definitions", 201, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 200, definition(11, 1, heldOrder("shop__paid")));
        engine.answers("POST", "/v1/dmn-resource-definitions", 200, "{\"dmnResourceDefinitionKey\":21}");

        try (var registry = registry()) {
            final var deploymentService = deploymentService(registry, TestScoping.of(NameClashAvoidance.USE_PREFIX),
                    TestWiring.claiming("Order"));
            deploymentService.deployResources("shop", prepared(deploymentService, ORDER));
            deploymentService.deployResources("rates", deploymentService.readDmn("rates", new ZenBpmProcessingContext(
                    "rates"), "rating.dmn",
                    new ByteArrayInputStream(DMN.getBytes(StandardCharsets.UTF_8))));

            assertAll(
                    () -> assertEquals(Optional.of(new ModuleMessages(List.of("shop__Order"), Set.of("shop__paid"), Set
                            .of(), Set.of(), List.of())), deployedProcesses(registry).messagesOf("shop")),
                    () -> assertEquals(Optional.empty(), deployedProcesses(registry).messagesOf("rates")),
                    () -> assertTrue(engine
                            .requests()
                            .stream()
                            .noneMatch(request -> "/v1/process-definitions".equals(request.path()) && "GET".equals(
                                    request.method())),
                            engine
                                    .requests()::toString));
        }
    }

    @Test
    @DisplayName("Versions the engine does not hand out are named with the reason, in the result and in a WARN which says that correlations are not refused then; what was read counts all the same")
    void versionsWhichCannotBeReadAreNamed(final CapturedOutput output) throws Exception {
        engine.answers("POST", "/v1/process-definitions", 201, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 200, definition(11, 2, heldOrder("shop__paid")));
        engine.answers("GET", "/v1/process-definitions", 500, """
                {"code":"TECHNICAL_ERROR","message":"database is locked"}""");

        try (var registry = registry()) {
            final var deploymentService = deploymentService(registry, TestScoping.of(NameClashAvoidance.USE_PREFIX),
                    TestWiring.claiming("Order"));
            deploymentService.deployResources("shop", prepared(deploymentService, ORDER));

            final var unread = "the versions of process 'shop__Order' (GET /v1/process-definitions was answered with status 500, TECHNICAL_ERROR: database is locked)";
            assertAll(
                    () -> assertEquals(Optional.of(new ModuleMessages(List.of("shop__Order"), Set.of("shop__paid"), Set
                            .of(), Set.of(), List.of(unread))), deployedProcesses(registry).messagesOf("shop")),
                    () -> assertTrue(output
                            .getAllOfThisTest()
                            .contains(
                                    """
                                            ZenBPM[orders]: %s of workflow module 'shop' could not be read, so a correlation of a \
                                            message which the models read do not wait for is not refused when the application \
                                            calls: it is published, and the outbox repeats it for as long as nothing waits for \
                                            it. The next start of the application reads them again."""
                                            .formatted(unread)),
                            output::getAllOfThisTest));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MessageStartLookup.class)
    @DisplayName("Once a workflow module is deployed, one INFO names its processes a message starts and what message-start-lookup does with them, and under scan a WARN names the bound and what the lookup cannot do")
    void theProcessesAMessageStartsAreNamedOnce(
        final MessageStartLookup lookup,
        final CapturedOutput output
    ) throws Exception {
        engine.answers("POST", "/v1/process-definitions", 201, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 200, definition(11, 1, heldOrder("shop__paid")
                .replace("<bpmn:process id=\"shop__Order\" isExecutable=\"true\">", """
                        <bpmn:message id="Message_Ordered" name="shop__ordered" />
                        <bpmn:process id="shop__Order" isExecutable="true">
                          <bpmn:startEvent id="Ordered">
                            <bpmn:messageEventDefinition messageRef="Message_Ordered" />
                          </bpmn:startEvent>""")));

        try (var registry = registry()) {
            registry
                    .getFactory(ADAPTER_ID)
                    .configuration()
                    .setMessageStartLookup(lookup);
            final var deploymentService = deploymentService(registry, TestScoping.of(NameClashAvoidance.USE_PREFIX),
                    TestWiring.claiming("Order"));
            deploymentService.deployResources("shop", prepared(deploymentService, ORDER));
        }

        final var named = "INFO  o.p.z.d.ZenBpmDeploymentService - ZenBPM[orders]: workflow module 'shop' has processes a message starts: 'Order'. vanillabp.adapters.orders.message-start-lookup is '%s': "
                .formatted(lookup.configured());
        final var lines = output
                .getAllOfThisTest()
                .lines()
                .filter(line -> line.contains("message-start-lookup"))
                .map(line -> line.substring(line.indexOf(" INFO ") >= 0 ? line.indexOf(" INFO ") + 1 : line.indexOf(
                        " WARN ") + 1))
                .toList();
        final var what = switch (lookup) {
            case START_ROW -> """
                    startWorkflowByMessage starts them, and the workflow it starts is found through the instance key \
                    its start marker writes into the start row of the aggregate. Before the marker ran, in an \
                    application without a delivery log, and once the row is removed, it is not found, and a start \
                    sent again after its answer was lost may start a second workflow; 'scan' finds a running one in \
                    those cases as well.""";
            case SCAN -> """
                    startWorkflowByMessage starts them, and the workflow it starts is found through the instance key \
                    its start marker writes into the start row of the aggregate, and where the row does not know it, \
                    by reading their instances.""";
            case REFUSE -> """
                    startWorkflowByMessage is refused for them. Set the key to 'start-row', the default, or to 'scan' \
                    to start them.""";
        };
        final var expected = lookup == MessageStartLookup.SCAN
                ? List.of(named + what,
                        """
                                WARN  o.p.z.d.ZenBpmDeploymentService - ZenBPM[orders]: \
                                vanillabp.adapters.orders.message-start-lookup is 'scan': where the start row does not know a \
                                workflow of the processes 'Order' of workflow module 'shop', a probe reads their instances to \
                                find it by its id variable, at most 1000 \
                                (vanillabp.adapters.orders.message-start-lookup-max-instances), and answers BPMS_UNAVAILABLE \
                                where it would have to read more. A workflow of them which ended is not read where the platform \
                                asks which adapter holds a workflow, so it is answered as unknown to the engine, which the \
                                platform's contract for adapters does not allow: an operation on it is then treated as one on \
                                a workflow the engine never held. The lookup is an opt-in until ZenBPM gives a workflow it \
                                starts by a message a business key.""")
                : List.of(named + what);
        assertEquals(expected, lines);
    }

    @Test
    @DisplayName("A workflow module whose processes no message starts says nothing about message-start-lookup")
    void aModuleNoMessageStartsSaysNothingAboutTheLookup(final CapturedOutput output) throws Exception {
        engine.answers("POST", "/v1/process-definitions", 201, "{\"processDefinitionKey\":11}");
        engine.answers("GET", "/v1/process-definitions/11", 200, definition(11, 1, heldOrder("shop__paid")));

        try (var registry = registry()) {
            final var deploymentService = deploymentService(registry, TestScoping.of(NameClashAvoidance.USE_PREFIX),
                    TestWiring.claiming("Order"));
            deploymentService.deployResources("shop", prepared(deploymentService, ORDER));
        }

        assertTrue(output
                .getAllOfThisTest()
                .contains("workflow module 'shop' is deployed"), output::getAllOfThisTest);
        assertFalse(output
                .getAllOfThisTest()
                .contains("message-start-lookup"), output::getAllOfThisTest);
    }

    private static ZenBpmDeployedProcesses deployedProcesses(final ZenBpmClientRegistry registry) {
        return registry
                .getFactory(ADAPTER_ID)
                .deployedProcesses();
    }

    private ZenBpmClientRegistry registry() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        return new ZenBpmClientRegistry(List.of(new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")));
    }

    private static ZenBpmDeploymentService deploymentService(
        final ZenBpmClientRegistry registry,
        final TestScoping scoping,
        final TestWiring wiring
    ) {
        return new ZenBpmDeploymentService(ADAPTER_ID, registry, TestCollaborators.of(ADAPTER_ID, scoping, wiring
                .wiring(), TestStarts.everyProcessStartingAWorkflow()));
    }

    private static String definition(
        final long key,
        final int version,
        final String bpmn
    ) throws JsonProcessingException {
        return JSON.writeValueAsString(Map.of("key", key, "version", version, "bpmnProcessId", "shop__Order",
                "bpmnData", bpmn));
    }

    // the process as the engine holds it after a deployment under use-prefix, waiting for one message
    private static String heldOrder(final String messageName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="order">
                  <bpmn:message id="Message_Paid" name="%s" />
                  <bpmn:process id="shop__Order" isExecutable="true">
                    <bpmn:receiveTask id="AwaitPayment" messageRef="Message_Paid" />
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(messageName);
    }

    private static ZenBpmProcessingContext prepared(
        final ZenBpmDeploymentService deploymentService,
        final String bpmn
    ) {
        return prepared(deploymentService, null, "order.bpmn", bpmn);
    }

    private static ZenBpmProcessingContext prepared(
        final ZenBpmDeploymentService deploymentService,
        final ZenBpmProcessingContext existingContext,
        final String filename,
        final String bpmn
    ) {
        final var model = deploymentService
                .readBpmn("shop", filename, new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)), true)
                .getFirst()
                .getValue();
        final var context = deploymentService.prepareBpmn("shop", existingContext, filename, model.bpmnProcessId(),
                model);
        deploymentService.wireBpmn("shop", filename, model.bpmnProcessId(), model, context);
        return context;
    }

}
