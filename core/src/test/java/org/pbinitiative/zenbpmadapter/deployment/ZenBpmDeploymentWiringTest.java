/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.TestStarts;
import org.pbinitiative.zenbpmadapter.TestWiring;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;
import org.w3c.dom.Element;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the deployment service wires a process: the calls it makes to the platform for a claimed
 * process and none for a process nobody claims, what it refuses, and what it writes into the model
 * and the context while it wires.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDeploymentWiringTest {

    private static final String ADAPTER_ID = "orders";

    private static final String NOT_CONFIGURED = "ZenBPM adapter 'orders' is not configured: set the key";

    // elements which create a job, all of them but Charge naming no task definition
    private static final String JOBS_OF_NO_TYPE = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="order">
              <bpmn:message id="Message_Shipped" name="shipped" />
              <bpmn:process id="Order" isExecutable="true">
                <bpmn:serviceTask id="Ship" />
                <bpmn:sendTask id="Notify" />
                <bpmn:businessRuleTask id="Rate" />
                <bpmn:serviceTask id="Archive" />
                <bpmn:serviceTask id="Charge">
                  <bpmn:extensionElements>
                    <zenbpm:taskDefinition type="charge" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:endEvent id="Shipped">
                  <bpmn:messageEventDefinition messageRef="Message_Shipped" />
                </bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Test
    @DisplayName("A claimed process gets every wiring call once, with what the modeller wrote, and the context remembers its scoped id and the job types this application serves")
    void aClaimedProcessGetsEveryWiringCallOnce() {
        final var wiring = TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify");
        final var starts = TestStarts
                .everyProcessStartingAWorkflow()
                .listeningToTheEndOf("Orders");
        final var deploymentService = deploymentService(TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.USE_PREFIX), wiring.wiring(), starts));

        final var model = read(deploymentService, fixture("wiring.bpmn"));
        final var context = deploymentService.prepareBpmn("shop", null, "wiring.bpmn", "Orders", model);
        deploymentService.wireBpmn("shop", "wiring.bpmn", "Orders", model, context);

        assertAll(
                () -> assertEquals(List.of(
                        "reportModelExpressions[shop, Orders, %s]".formatted(model.expressions())),
                        wiring.callsOf("reportModelExpressions")),
                () -> assertEquals(List.of(
                        "validateTaskWiring[orders, shop, Orders, %s]".formatted(List.of(
                                new BpmnTaskSpec("Charge", "charge", false, "Charge the card"),
                                new BpmnTaskSpec("Notify", "notify", false, "Notify the customer"),
                                new BpmnTaskSpec("Ship", "ship", false, "Ship the parcel"),
                                new BpmnTaskSpec("Pack", "pack", false, "Pack the line"),
                                BpmnTaskSpec.userTask("Approve", "user-task-type", "Approve the order")))),
                        wiring.callsOf("validateTaskWiring")),
                () -> assertEquals(List.of("reportConcurrentTokenElements[shop, Orders, [Fork, EveryParcel]]"), wiring
                        .callsOf("reportConcurrentTokenElements")),
                () -> assertEquals(List.of(
                        "multiInstanceElementNames[shop, Orders, Ship]",
                        "multiInstanceElementNames[shop, Orders, ship]"),
                        wiring.callsOf("multiInstanceElementNames")),
                () -> assertEquals(List.of(
                        "reportStartMessages[orders, shop, Orders, [placed]]",
                        "validateBpmsInitiatedStarts[shop, Orders, [BpmsInitiatedStartSpec[elementId=Start, kind=NONE, signalName=null], BpmsInitiatedStartSpec[elementId=Placed, kind=MESSAGE, signalName=null], BpmsInitiatedStartSpec[elementId=EveryNight, kind=TIMER, signalName=null]]]",
                        "startsAWorkflowOfItsOwn[shop, Orders]",
                        "workflowEndedHandlerExists[shop, Orders]"),
                        starts.calls()),
                () -> assertEquals(Map.of("Orders", new WiredProcess("Orders", "shop__Orders", Set.of(
                        "shop__Orders__charge", "shop__Orders__ship", "shop__Orders__pack"), true, true)), context
                                .wiredProcesses()));
    }

    @Test
    @DisplayName("The expressions reported are the ones the modeller wrote, none of the keys and mappings preparing added")
    void theReportedExpressionsAreTheModellers() {
        final var wiring = TestWiring.claiming("Orders");
        final var deploymentService = deploymentService(TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.NONE), wiring.wiring(), TestStarts.everyProcessStartingAWorkflow()));
        final var model = read(deploymentService, fixture("wiring.bpmn"));

        final var context = deploymentService.prepareBpmn("shop", null, "wiring.bpmn", "Orders", model);
        deploymentService.wireBpmn("shop", "wiring.bpmn", "Orders", model, context);

        assertAll(
                () -> assertEquals(List.of("=orderId"), attributesOf(model, "subscription", "correlationKey")),
                () -> assertTrue(attributesOf(model, "input", "source").contains("=orderId")),
                () -> assertEquals(List.of(
                        "reportModelExpressions[shop, Orders, [ModelExpression[elementId=EveryParcel, place=MULTI_INSTANCE_COLLECTION, expression==parcels, body=parcels], ModelExpression[elementId=EveryLine, place=MULTI_INSTANCE_COLLECTION, expression==lines, body=lines]]]"),
                        wiring.callsOf("reportModelExpressions")));
    }

    @Test
    @DisplayName("A process nobody claims gets no wiring call at all and is not remembered")
    void aProcessNobodyClaimsGetsNoCall() {
        final var wiring = TestWiring.claiming();
        final var starts = TestStarts.everyProcessStartingAWorkflow();
        final var deploymentService = deploymentService(TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.USE_PREFIX), wiring.wiring(), starts));
        final var model = read(deploymentService, fixture("wiring.bpmn"));
        final var context = deploymentService.prepareBpmn("shop", null, "wiring.bpmn", "Orders", model);
        final var claimedAtPreparing = wiring.calls();

        deploymentService.wireBpmn("shop", "wiring.bpmn", "Orders", model, context);

        assertAll(
                () -> assertEquals(List.of("isClaimedByAWorkflowService[shop, Orders]"), wiring
                        .calls()
                        .subList(claimedAtPreparing.size(), wiring
                                .calls()
                                .size())),
                () -> assertEquals(List.of(), starts.calls()),
                () -> assertEquals(Map.of(), context.wiredProcesses()));
    }

    @Test
    @DisplayName("A task without a method ends wiring with the platform's message, before anything else is reported")
    void aTaskWithoutAMethodEndsWiring() {
        final var wiring = TestWiring
                .claiming("Orders")
                .refusingTheTasks("Task 'Charge' of BPMN process 'Orders' is served by no @WorkflowTask method");
        final var starts = TestStarts.everyProcessStartingAWorkflow();
        final var deploymentService = deploymentService(TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.NONE), wiring.wiring(), starts));
        final var model = read(deploymentService, fixture("wiring.bpmn"));
        final var context = deploymentService.prepareBpmn("shop", null, "wiring.bpmn", "Orders", model);

        final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.wireBpmn("shop",
                "wiring.bpmn", "Orders", model, context));

        assertAll(
                () -> assertEquals("Task 'Charge' of BPMN process 'Orders' is served by no @WorkflowTask method",
                        failure
                                .getMessage()),
                () -> assertEquals(List.of(), starts.calls()),
                () -> assertEquals(Map.of(), context.wiredProcesses()));
    }

    @Test
    @DisplayName("Start messages are reported as an empty collection where the process has no message start, and not at all where a name cannot be read")
    void startMessagesAreReportedOnlyWhereEveryNameCanBeRead() {
        final var starts = TestStarts.everyProcessStartingAWorkflow();
        final var deploymentService = deploymentService(TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.NONE),
                TestWiring
                        .claiming("Order", "Orders")
                        .wiring(),
                starts));

        wire(deploymentService, "order.bpmn", """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="order">
                  <bpmn:process id="Order" isExecutable="true">
                    <bpmn:startEvent id="Start" />
                  </bpmn:process>
                </bpmn:definitions>
                """, "Order");
        wire(deploymentService, "message-start-without-name.bpmn", fixture("message-start-without-name.bpmn"),
                "Orders");

        assertEquals(List.of(
                "reportStartMessages[orders, shop, Order, []]",
                "validateBpmsInitiatedStarts[shop, Order, [BpmsInitiatedStartSpec[elementId=Start, kind=NONE, signalName=null]]]",
                "startsAWorkflowOfItsOwn[shop, Order]",
                "workflowEndedHandlerExists[shop, Order]",
                "validateBpmsInitiatedStarts[shop, Orders, [BpmsInitiatedStartSpec[elementId=WithoutReference, kind=MESSAGE, signalName=null], BpmsInitiatedStartSpec[elementId=WithoutName, kind=MESSAGE, signalName=null], BpmsInitiatedStartSpec[elementId=ToNowhere, kind=MESSAGE, signalName=null]]]",
                "startsAWorkflowOfItsOwn[shop, Orders]",
                "workflowEndedHandlerExists[shop, Orders]"),
                starts.calls());
    }

    @Test
    @DisplayName("Every start event of a called process is reported as well, and the context remembers that its start is no workflow of its own")
    void theStartEventsOfACalledProcessAreReported() {
        final var starts = TestStarts
                .everyProcessStartingAWorkflow()
                .called("Orders");
        final var deploymentService = deploymentService(TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.NONE),
                TestWiring
                        .claiming("Orders")
                        .wiring(),
                starts));

        final var context = wire(deploymentService, "wiring.bpmn", fixture("wiring.bpmn"), "Orders");

        assertAll(
                () -> assertTrue(starts
                        .calls()
                        .contains(
                                "validateBpmsInitiatedStarts[shop, Orders, [BpmsInitiatedStartSpec[elementId=Start, kind=NONE, signalName=null], BpmsInitiatedStartSpec[elementId=Placed, kind=MESSAGE, signalName=null], BpmsInitiatedStartSpec[elementId=EveryNight, kind=TIMER, signalName=null]]]"),
                        starts.calls()::toString),
                () -> assertFalse(context
                        .wiredProcesses()
                        .get("Orders")
                        .reportsItsStart()),
                () -> assertFalse(context
                        .wiredProcesses()
                        .get("Orders")
                        .reportsItsEnd()));
    }

    @Test
    @DisplayName("Without the platform's invokers of starts and ends nothing about them is reported, and neither is remembered as reported")
    void withoutTheInvokersNothingIsReportedAboutStartsAndEnds() {
        final var deploymentService = deploymentService(TestCollaborators.withoutLifecycleInvokers(ADAPTER_ID,
                TestScoping.of(NameClashAvoidance.NONE), TestWiring
                        .claiming("Orders")
                        .wiring()));

        final var context = wire(deploymentService, "wiring.bpmn", fixture("wiring.bpmn"), "Orders");

        assertEquals(new WiredProcess("Orders", "Orders", Set.of("charge", "notify", "ship", "pack"), false, false),
                context
                        .wiredProcesses()
                        .get("Orders"));
    }

    @Test
    @DisplayName("Every element of a claimed process which creates a job and names no task definition ends wiring, named with the fix, before the check of the tasks")
    void anElementCreatingAJobOfNoTypeIsRefused() {
        final var wiring = TestWiring
                .claiming("Order")
                .servedElsewhere("Archive");
        final var deploymentService = deploymentService(TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.USE_PREFIX), wiring.wiring(), TestStarts.everyProcessStartingAWorkflow()));
        final var model = read(deploymentService, "order.bpmn", JOBS_OF_NO_TYPE);
        final var context = deploymentService.prepareBpmn("shop", null, "order.bpmn", "Order", model);

        final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.wireBpmn("shop",
                "order.bpmn", "Order", model, context));

        assertAll(
                () -> assertEquals("""
                        BPMN file 'order.bpmn' of workflow module 'shop' holds elements of process 'Order' which \
                        create a job and name no task definition:
                          - Ship
                          - Notify
                          - Rate
                          - Shipped
                        ZenBPM gives each of them a job of the type '', which no worker of this application \
                        subscribes, so a workflow would wait there for good. Give each of them a task definition, \
                        <zenbpm:taskDefinition type="..." />, and serve that type with a @WorkflowTask method.""",
                        failure.getMessage()),
                () -> assertEquals(List.of(), wiring.callsOf("validateTaskWiring")),
                () -> assertEquals(Map.of(), context.wiredProcesses()));
    }

    @Test
    @DisplayName("A process nobody claims may hold elements which create a job and name no task definition: it belongs to somebody else")
    void aProcessNobodyClaimsMayHoldJobsOfNoType() {
        final var deploymentService = deploymentService(TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.USE_PREFIX),
                TestWiring
                        .claiming()
                        .wiring(),
                TestStarts.everyProcessStartingAWorkflow()));
        final var model = read(deploymentService, "order.bpmn", JOBS_OF_NO_TYPE);
        final var context = deploymentService.prepareBpmn("shop", null, "order.bpmn", "Order", model);

        deploymentService.wireBpmn("shop", "order.bpmn", "Order", model, context);

        assertEquals(Map.of(), context.wiredProcesses());
    }

    @Test
    @DisplayName("A task served elsewhere which names no task definition is handed to the check of the tasks, gives no job type, and is asked about items by its element id only")
    void aTaskServedElsewhereWithoutATaskDefinitionGivesNoJobType() {
        final var wiring = TestWiring
                .claiming("Order")
                .servedElsewhere("Ship");
        final var deploymentService = deploymentService(TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.USE_PREFIX), wiring.wiring(), TestStarts.everyProcessStartingAWorkflow()));

        final var context = wire(deploymentService, "order.bpmn", """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="order">
                  <bpmn:process id="Order" isExecutable="true">
                    <bpmn:serviceTask id="Ship">
                      <bpmn:multiInstanceLoopCharacteristics>
                        <bpmn:extensionElements>
                          <zenbpm:loopCharacteristics xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0"
                              inputCollection="=parcels" />
                        </bpmn:extensionElements>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:serviceTask>
                  </bpmn:process>
                </bpmn:definitions>
                """, "Order");

        assertAll(
                () -> assertEquals(List.of("validateTaskWiring[orders, shop, Order, [%s]]".formatted(new BpmnTaskSpec(
                        "Ship", null, false, null))), wiring.callsOf("validateTaskWiring")),
                () -> assertEquals(List.of("multiInstanceElementNames[shop, Order, Ship]"), wiring.callsOf(
                        "multiInstanceElementNames")),
                () -> assertEquals(Set.of(), context
                        .wiredProcesses()
                        .get("Order")
                        .jobTypes()));
    }

    @Test
    @DisplayName("A method wanting the item of an iteration whose element names no inputElement ends wiring, naming task, element and the fix; by task definition and by element id alike")
    void aMethodWantingAnItemNoElementHandsOverIsRefused() {
        final var byTaskDefinition = TestWiring
                .claiming("Orders")
                .withMultiInstanceElementNames("ship", "EveryParcel");
        final var byElementId = TestWiring
                .claiming("Orders")
                .withMultiInstanceElementNames("Ship", "EveryParcel", "Elsewhere");

        final var refusedByTaskDefinition = assertThrows(IllegalStateException.class, () -> wire(deploymentService(
                TestCollaborators.of(ADAPTER_ID, TestScoping.of(NameClashAvoidance.NONE), byTaskDefinition.wiring(),
                        TestStarts.everyProcessStartingAWorkflow())),
                "wiring.bpmn", fixture("wiring.bpmn"),
                "Orders"));
        final var refusedByElementId = assertThrows(IllegalStateException.class, () -> wire(deploymentService(
                TestCollaborators.of(ADAPTER_ID, TestScoping.of(NameClashAvoidance.NONE), byElementId.wiring(),
                        TestStarts.everyProcessStartingAWorkflow())),
                "wiring.bpmn", fixture("wiring.bpmn"),
                "Orders"));

        final var expected = """
                A @WorkflowTask method of BPMN process 'Orders' (workflow module 'shop') declares @MultiInstanceElement \
                for an iteration which hands over no item:
                  - the method serving task 'Ship' (task definition 'ship') wants the item of 'EveryParcel'
                Each of these multi-instance elements names no 'inputElement', so ZenBPM gives its iterations no \
                item, and the parameter would receive null once a job arrives. Name the item on the element \
                (<zenbpm:loopCharacteristics inputCollection="=items" inputElement="item" />, or 'zeebe:' from the \
                Camunda Modeler), or drop the parameter.""";
        assertAll(
                () -> assertEquals(expected, refusedByTaskDefinition.getMessage()),
                () -> assertEquals(expected, refusedByElementId.getMessage()));
    }

    @Test
    @DisplayName("A method wanting the item of an element which names one, or of an element of another model, passes")
    void aMethodWantingAnItemAnElementHandsOverPasses() {
        final var wiring = TestWiring
                .claiming("Orders")
                .withMultiInstanceElementNames("pack", "EveryLine")
                .withMultiInstanceElementNames("charge", "EveryParcel")
                .withMultiInstanceElementNames("Approve", "CallerOfOrders");
        final var deploymentService = deploymentService(TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.NONE), wiring.wiring(), TestStarts.everyProcessStartingAWorkflow()));

        final var context = wire(deploymentService, "wiring.bpmn", fixture("wiring.bpmn"), "Orders");

        assertTrue(context
                .wiredProcesses()
                .containsKey("Orders"));
    }

    @Test
    @DisplayName("A process called on the same aggregate from inside multi-instance bodies gets the aggregate id carried into every body around the call; one on an aggregate of its own, or called outside a body, gets nothing")
    void theAggregateIdIsCarriedToAProcessCalledOnTheSameAggregate() {
        final var callingInsideBodies = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="calls">
                  <bpmn:process id="Orders" isExecutable="true">
                    <bpmn:subProcess id="EveryLine">
                      <bpmn:multiInstanceLoopCharacteristics>
                        <bpmn:extensionElements>
                          <zenbpm:loopCharacteristics inputCollection="=lines" inputElement="line" />
                        </bpmn:extensionElements>
                      </bpmn:multiInstanceLoopCharacteristics>
                      <bpmn:callActivity id="Bill">
                        <bpmn:extensionElements>
                          <zenbpm:calledElement processId="Billing" />
                        </bpmn:extensionElements>
                        <bpmn:multiInstanceLoopCharacteristics>
                          <bpmn:extensionElements>
                            <zenbpm:loopCharacteristics inputCollection="=line.invoices" inputElement="invoice" />
                          </bpmn:extensionElements>
                        </bpmn:multiInstanceLoopCharacteristics>
                      </bpmn:callActivity>
                      <bpmn:callActivity id="Insure">
                        <bpmn:extensionElements>
                          <zenbpm:calledElement processId="Insurance" />
                        </bpmn:extensionElements>
                      </bpmn:callActivity>
                      <bpmn:callActivity id="Rebill">
                        <bpmn:extensionElements>
                          <zenbpm:calledElement processId="Billing" />
                        </bpmn:extensionElements>
                      </bpmn:callActivity>
                    </bpmn:subProcess>
                    <bpmn:subProcess id="EveryParcel">
                      <bpmn:multiInstanceLoopCharacteristics>
                        <bpmn:extensionElements>
                          <zenbpm:loopCharacteristics inputCollection="=parcels" inputElement="parcel" />
                        </bpmn:extensionElements>
                      </bpmn:multiInstanceLoopCharacteristics>
                      <bpmn:callActivity id="Insure2">
                        <bpmn:extensionElements>
                          <zenbpm:calledElement processId="Insurance" />
                        </bpmn:extensionElements>
                      </bpmn:callActivity>
                      <bpmn:callActivity id="Unnamed" />
                    </bpmn:subProcess>
                    <bpmn:callActivity id="Audit">
                      <bpmn:extensionElements>
                        <zenbpm:calledElement processId="Billing" />
                      </bpmn:extensionElements>
                    </bpmn:callActivity>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        final var wiring = TestWiring
                .claiming("Orders")
                .sharingTheAggregateWith("Billing");
        final var deploymentService = deploymentService(TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.NONE), wiring.wiring(), TestStarts.everyProcessStartingAWorkflow()));
        final var model = read(deploymentService, "calls.bpmn", callingInsideBodies);
        final var context = deploymentService.prepareBpmn("shop", null, "calls.bpmn", "Orders", model);

        deploymentService.wireBpmn("shop", "calls.bpmn", "Orders", model, context);

        assertAll(
                () -> assertEquals(List.of("orderId"), inputTargetsOf(model, "EveryLine")),
                () -> assertEquals(List.of("orderId"), inputTargetsOf(model, "Bill")),
                () -> assertEquals(List.of(), inputTargetsOf(model, "EveryParcel")),
                () -> assertEquals(List.of(), inputTargetsOf(model, "Audit")),
                () -> assertEquals(List.of(
                        "workflowsShareTheWorkflowAggregate[shop, Orders, Billing]",
                        "workflowsShareTheWorkflowAggregate[shop, Orders, Insurance]",
                        "workflowsShareTheWorkflowAggregate[shop, Orders, Billing]",
                        "workflowsShareTheWorkflowAggregate[shop, Orders, Insurance]"),
                        wiring.callsOf("workflowsShareTheWorkflowAggregate")),
                () -> assertEquals(List.of("resolveWorkflowAggregateIdName[shop, Orders]"), wiring.callsOf(
                        "resolveWorkflowAggregateIdName")));
    }

    @Test
    @DisplayName("Wiring needs no engine: an adapter id without clients wires like a configured one")
    void wiringNeedsNoEngine() {
        final var wiring = TestWiring.claiming("Orders");
        final var deploymentService = new ZenBpmDeploymentService(ADAPTER_ID, new ZenBpmClientRegistry(List.of(
                ZenBpmClientFactory.unconfigured(ADAPTER_ID, new ZenBpmAdapterConfiguration(),
                        NOT_CONFIGURED))), TestCollaborators.of(ADAPTER_ID, TestScoping.of(NameClashAvoidance.NONE),
                                wiring.wiring(), TestStarts
                                        .everyProcessStartingAWorkflow()));

        final var context = wire(deploymentService, "wiring.bpmn", fixture("wiring.bpmn"), "Orders");

        assertTrue(context
                .wiredProcesses()
                .containsKey("Orders"));
    }

    private static ZenBpmDeploymentService deploymentService(final AdapterCollaborators collaborators) {
        return new ZenBpmDeploymentService(ADAPTER_ID, new ZenBpmClientRegistry(List.of(ZenBpmClientFactory
                .unconfigured(ADAPTER_ID, new ZenBpmAdapterConfiguration(), NOT_CONFIGURED))), collaborators);
    }

    private static ZenBpmProcessingContext wire(
        final ZenBpmDeploymentService deploymentService,
        final String filename,
        final String bpmn,
        final String bpmnProcessId
    ) {
        final var model = read(deploymentService, filename, bpmn);
        final var context = deploymentService.prepareBpmn("shop", null, filename, bpmnProcessId, model);
        deploymentService.wireBpmn("shop", filename, bpmnProcessId, model, context);
        return context;
    }

    private static ZenBpmBpmnModel read(
        final ZenBpmDeploymentService deploymentService,
        final String bpmn
    ) {
        return read(deploymentService, "wiring.bpmn", bpmn);
    }

    private static ZenBpmBpmnModel read(
        final ZenBpmDeploymentService deploymentService,
        final String filename,
        final String bpmn
    ) {
        return deploymentService
                .readBpmn("shop", filename, new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)), true)
                .getFirst()
                .getValue();
    }

    private static String fixture(final String name) {
        try (var file = ZenBpmDeploymentWiringTest.class.getResourceAsStream("/models/%s".formatted(name))) {
            return new String(file.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> attributesOf(
        final ZenBpmBpmnModel model,
        final String localName,
        final String attribute
    ) {
        final var found = model
                .document()
                .getElementsByTagNameNS("*", localName);
        final var values = new ArrayList<String>();
        for (var index = 0; index < found.getLength(); ++index) {
            values.add(((Element) found.item(index)).getAttribute(attribute));
        }
        return values;
    }

    // the targets of the input mappings an element itself declares
    private static List<String> inputTargetsOf(
        final ZenBpmBpmnModel model,
        final String elementId
    ) {
        final var targets = new ArrayList<String>();
        final var inputs = model
                .document()
                .getElementsByTagNameNS("*", "input");
        for (var index = 0; index < inputs.getLength(); ++index) {
            final var input = (Element) inputs.item(index);
            final var element = (Element) input
                    .getParentNode()
                    .getParentNode()
                    .getParentNode();
            if (elementId.equals(element.getAttribute("id"))) {
                targets.add(input.getAttribute("target"));
            }
        }
        return targets;
    }

}
