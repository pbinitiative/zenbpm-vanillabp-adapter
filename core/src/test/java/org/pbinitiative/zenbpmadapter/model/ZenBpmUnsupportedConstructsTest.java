/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What ZenBPM cannot run is refused before anything is sent to the engine, in one message per file
 * which names every element, its process and why it cannot run. One case per construct; the
 * fixture with one offence of each kind is in <code>src/test/resources/models/</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmUnsupportedConstructsTest {

    private static final String REFUSED = "ZenBPM refuses to deploy a file with these:";

    private static final String RUN_DIFFERENTLY = "ZenBPM deploys these, but runs something other than the model shows:";

    private static final String WITHOUT_AN_AGGREGATE = "These would start a workflow for which nobody built a workflow aggregate, because the engine starts it on its own:";

    @ParameterizedTest(name = "{0}")
    @MethodSource("constructs")
    @DisplayName("Every construct ZenBPM cannot run is refused under the reason which applies, naming process and element")
    void everyConstructIsRefused(
        final String construct,
        final String elements,
        final String heading,
        final String line
    ) {
        final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmUnsupportedConstructs.check(
                "shop", model(elements), true));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .startsWith("BPMN file 'order.bpmn' of workflow module 'shop' uses what ZenBPM cannot run:\n"),
                        failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .contains("    - %s\n".formatted(line)), failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .indexOf("  %s\n".formatted(heading)) < failure
                                .getMessage()
                                .indexOf("    - %s\n".formatted(line)),
                        failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .contains("  %s\n".formatted(heading)), failure.getMessage()));
    }

    @Test
    @DisplayName("What ZenBPM runs as modelled passes: an expression at a gateway, a default flow without one, a literal timer, a message throw event, a receive task waiting for a message, flags spelt as Go reads them, a blank condition, an unknown element without flows, an event definition on a task, which the engine never reads")
    void whatZenBpmRunsPasses() {
        assertDoesNotThrow(() -> ZenBpmUnsupportedConstructs.check("shop",
                model("""
                        <bpmn:exclusiveGateway id="Which" />
                        <bpmn:sequenceFlow id="Big" sourceRef="Which" targetRef="Wait">
                          <bpmn:conditionExpression>=amount &gt; 100</bpmn:conditionExpression>
                        </bpmn:sequenceFlow>
                        <bpmn:inclusiveGateway id="Any" />
                        <bpmn:sequenceFlow id="Small" sourceRef="Any" targetRef="Wait">
                          <bpmn:conditionExpression>=amount &lt; 10</bpmn:conditionExpression>
                        </bpmn:sequenceFlow>
                        <bpmn:intermediateCatchEvent id="Wait">
                          <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                        </bpmn:intermediateCatchEvent>
                        <bpmn:intermediateThrowEvent id="Announce"><bpmn:messageEventDefinition /></bpmn:intermediateThrowEvent>
                        <bpmn:intermediateThrowEvent id="Jump"><bpmn:linkEventDefinition name="there" /></bpmn:intermediateThrowEvent>
                        <bpmn:receiveTask id="AwaitPayment" messageRef="Message_Paid" />
                        <bpmn:receiveTask id="NotInstantiating" instantiate="false" messageRef="Message_Paid" />
                        <bpmn:endEvent id="Stop"><bpmn:terminateEventDefinition /></bpmn:endEvent>
                        <bpmn:endEvent id="Failed"><bpmn:errorEventDefinition id="Failed_Definition" /></bpmn:endEvent>
                        <bpmn:exclusiveGateway id="Choose" default="Otherwise" />
                        <bpmn:sequenceFlow id="Paid" sourceRef="Choose" targetRef="Stop">
                          <bpmn:conditionExpression>= paid</bpmn:conditionExpression>
                        </bpmn:sequenceFlow>
                        <bpmn:sequenceFlow id="Otherwise" sourceRef="Choose" targetRef="Failed" />
                        <bpmn:eventBasedGateway id="Race" />
                        <bpmn:sequenceFlow id="ToWait" sourceRef="Race" targetRef="Wait" />
                        <bpmn:receiveTask id="NeverInstantiating" instantiate="0" messageRef="Message_Paid" />
                        <bpmn:exclusiveGateway id="Single" />
                        <bpmn:sequenceFlow id="Only" sourceRef="Single" targetRef="Stop">
                          <bpmn:conditionExpression> </bpmn:conditionExpression>
                        </bpmn:sequenceFlow>
                        <bpmn:adHocSubProcess id="Unconnected"><bpmn:serviceTask id="Inside" /></bpmn:adHocSubProcess>
                        <bpmn:sendTask id="NotAnEvent"><bpmn:signalEventDefinition /></bpmn:sendTask>
                        <bpmn:boundaryEvent id="Reminder" attachedToRef="Pay" cancelActivity="false">
                          <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                        </bpmn:boundaryEvent>
                        <bpmn:callActivity id="Invoice">
                          <bpmn:extensionElements>
                            <zenbpm:calledElement processId="Invoicing" />
                            <zenbpm:in businessKey="=orderId" />
                          </bpmn:extensionElements>
                        </bpmn:callActivity>
                        <bpmn:serviceTask id="NotifyAll">
                          <bpmn:multiInstanceLoopCharacteristics isSequential="1">
                            <bpmn:extensionElements>
                              <zenbpm:loopCharacteristics inputCollection="=recipients" inputElement="recipient" />
                            </bpmn:extensionElements>
                          </bpmn:multiInstanceLoopCharacteristics>
                        </bpmn:serviceTask>
                        """),
                true));
    }

    @Test
    @DisplayName("Several offences of a file are named in one message, grouped by why they cannot run, in the order of the file")
    void severalOffencesAreNamedInOneMessage() {
        final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmUnsupportedConstructs.check(
                "shop", fixture("three-offences.bpmn"), true));

        assertEquals(
                """
                        BPMN file 'three-offences.bpmn' of workflow module 'shop' uses what ZenBPM cannot run:
                          ZenBPM refuses to deploy a file with these:
                            - Orders/Calculate (scriptTask)
                            - Orders/Recalled (signalEventDefinition on boundaryEvent)
                          ZenBPM deploys these, but runs something other than the model shows:
                            - Orders/Retry (standardLoopCharacteristics, which the engine ignores and runs the element once)
                          These would start a workflow for which nobody built a workflow aggregate, because the engine starts it on its own:
                            - Orders/OrderReceived (receiveTask with instantiate="true")
                        Model them with what ZenBPM runs, or take them out of the file.""",
                failure.getMessage());
    }

    @Test
    @DisplayName("A process nobody claims is refused for what the engine cannot run as modelled, but not for an instantiating receive task")
    void aProcessNobodyClaimsIsRefusedOnlyForWhatTheEngineCannotRun() {
        final var refused = assertThrows(IllegalStateException.class, () -> ZenBpmUnsupportedConstructs.check(
                "shop", fixture("three-offences.bpmn"), false));

        assertAll(
                () -> assertTrue(refused
                        .getMessage()
                        .contains("Orders/Calculate (scriptTask)"), refused.getMessage()),
                () -> assertTrue(refused
                        .getMessage()
                        .contains("Orders/Retry (standardLoopCharacteristics"), refused.getMessage()),
                () -> assertFalse(refused
                        .getMessage()
                        .contains(WITHOUT_AN_AGGREGATE), refused.getMessage()),
                () -> assertDoesNotThrow(() -> ZenBpmUnsupportedConstructs.check("shop", model("""
                        <bpmn:receiveTask id="OrderReceived" instantiate="true" messageRef="Message_Paid" />
                        """), false)));
    }

    @Test
    @DisplayName("A message name written as an expression, which ZenBPM takes literally, and an isClosed which is no boolean are refused")
    void messageNamesAndProcessFlagsAreChecked() {
        final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmUnsupportedConstructs.check(
                "shop", ZenBpmModelReader
                        .read("shop", "order.bpmn", new ByteArrayInputStream("""
                                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="order">
                                  <bpmn:message id="Message_Ordered" name="=orderType" />
                                  <bpmn:process id="Orders" isExecutable="true" isClosed="sometimes" />
                                </bpmn:definitions>
                                """.getBytes(StandardCharsets.UTF_8)))
                        .getFirst(),
                true));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .contains(
                                "    - Orders (isClosed=\"sometimes\" on the process, which is no boolean)\n"),
                        failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .contains(
                                "    - Orders/Message_Ordered (message name written as an expression, which the engine takes literally)\n"),
                        failure.getMessage()));
    }

    static Stream<Arguments> constructs() {
        return Stream.of(
                Arguments.of("signal catch event", """
                        <bpmn:intermediateCatchEvent id="E"><bpmn:signalEventDefinition /></bpmn:intermediateCatchEvent>
                        """, REFUSED, "Orders/E (signalEventDefinition on intermediateCatchEvent)"),
                Arguments.of("conditional catch event",
                        """
                                <bpmn:intermediateCatchEvent id="E"><bpmn:conditionalEventDefinition /></bpmn:intermediateCatchEvent>
                                """,
                        REFUSED, "Orders/E (conditionalEventDefinition on intermediateCatchEvent)"),
                Arguments.of("escalation throw event",
                        """
                                <bpmn:intermediateThrowEvent id="E"><bpmn:escalationEventDefinition /></bpmn:intermediateThrowEvent>
                                """,
                        REFUSED, "Orders/E (escalationEventDefinition on intermediateThrowEvent)"),
                Arguments.of("compensation throw event",
                        """
                                <bpmn:intermediateThrowEvent id="E"><bpmn:compensateEventDefinition /></bpmn:intermediateThrowEvent>
                                """,
                        REFUSED, "Orders/E (compensateEventDefinition on intermediateThrowEvent)"),
                Arguments.of("cancel boundary event",
                        """
                                <bpmn:boundaryEvent id="E" attachedToRef="Pay"><bpmn:cancelEventDefinition /></bpmn:boundaryEvent>
                                """,
                        REFUSED, "Orders/E (cancelEventDefinition on boundaryEvent)"),
                Arguments.of("script task", """
                        <bpmn:scriptTask id="E" />
                        """, REFUSED, "Orders/E (scriptTask)"),
                Arguments.of("manual task", """
                        <bpmn:manualTask id="E" />
                        """, REFUSED, "Orders/E (manualTask)"),
                Arguments.of("plain task", """
                        <bpmn:task id="E" />
                        """, REFUSED, "Orders/E (task)"),
                Arguments.of("complex gateway", """
                        <bpmn:complexGateway id="E" />
                        """, REFUSED, "Orders/E (complexGateway)"),
                Arguments.of("condition leaving an event-based gateway", """
                        <bpmn:eventBasedGateway id="Race" />
                        <bpmn:sequenceFlow id="E" sourceRef="Race" targetRef="Pay">
                          <bpmn:conditionExpression>=fast</bpmn:conditionExpression>
                        </bpmn:sequenceFlow>
                        """, REFUSED, "Orders/E (condition on a sequenceFlow leaving an eventBasedGateway)"),
                Arguments.of("element the engine does not know, with sequence flows", """
                        <bpmn:adHocSubProcess id="E"><bpmn:incoming>Flow</bpmn:incoming></bpmn:adHocSubProcess>
                        """, REFUSED, "Orders/E (adHocSubProcess)"),
                Arguments.of("transaction", """
                        <bpmn:transaction id="E"><bpmn:outgoing>Flow</bpmn:outgoing></bpmn:transaction>
                        """, REFUSED, "Orders/E (transaction)"),
                Arguments.of("sub-process with two start events", """
                        <bpmn:subProcess id="E"><bpmn:startEvent id="A" /><bpmn:startEvent id="B" /></bpmn:subProcess>
                        """, REFUSED, "Orders/E (subProcess with 2 start events)"),
                Arguments.of("non-interrupting error start event",
                        """
                                <bpmn:subProcess id="OnError" triggeredByEvent="true">
                                  <bpmn:startEvent id="E" isInterrupting="false"><bpmn:errorEventDefinition id="D" /></bpmn:startEvent>
                                </bpmn:subProcess>
                                """,
                        REFUSED, "Orders/E (non-interrupting error startEvent)"),
                Arguments.of("event-based gateway leading to a task", """
                        <bpmn:eventBasedGateway id="E" />
                        <bpmn:sequenceFlow id="ToPay" sourceRef="E" targetRef="Pay" />
                        """, REFUSED,
                        "Orders/E (eventBasedGateway leading to 'Pay', which is no intermediateCatchEvent)"),
                Arguments.of("instantiate which is no boolean", """
                        <bpmn:receiveTask id="E" instantiate="yes" messageRef="Message_Paid" />
                        """, REFUSED, "Orders/E (instantiate=\"yes\", which is no boolean)"),
                Arguments.of("blank cancelActivity",
                        """
                                <bpmn:boundaryEvent id="E" attachedToRef="Pay" cancelActivity=" ">
                                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                                </bpmn:boundaryEvent>
                                """,
                        REFUSED, "Orders/E (cancelActivity=\" \", which is no boolean)"),
                Arguments.of("isSequential which is no boolean",
                        """
                                <bpmn:serviceTask id="E">
                                  <bpmn:multiInstanceLoopCharacteristics isSequential="maybe">
                                    <bpmn:extensionElements><zenbpm:loopCharacteristics inputCollection="=items" /></bpmn:extensionElements>
                                  </bpmn:multiInstanceLoopCharacteristics>
                                </bpmn:serviceTask>
                                """,
                        REFUSED, "Orders/E (isSequential=\"maybe\", which is no boolean)"),
                Arguments.of("signal start event", """
                        <bpmn:startEvent id="E"><bpmn:signalEventDefinition /></bpmn:startEvent>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (signalEventDefinition on startEvent, which the engine drops and runs a plain startEvent)"),
                Arguments.of("conditional start event", """
                        <bpmn:startEvent id="E"><bpmn:conditionalEventDefinition /></bpmn:startEvent>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (conditionalEventDefinition on startEvent, which the engine drops and runs a plain startEvent)"),
                Arguments.of("escalation start event of an event sub-process", """
                        <bpmn:subProcess id="OnEscalation" triggeredByEvent="1">
                          <bpmn:startEvent id="E"><bpmn:escalationEventDefinition /></bpmn:startEvent>
                        </bpmn:subProcess>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (escalationEventDefinition on startEvent, which the engine drops, so the event sub-process never starts)"),
                Arguments.of("error end event whose definition has no id", """
                        <bpmn:endEvent id="E"><bpmn:errorEventDefinition /></bpmn:endEvent>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (errorEventDefinition without an id on endEvent, which the engine drops and runs a plain endEvent)"),
                Arguments.of("condition at an exclusive gateway without '='", """
                        <bpmn:exclusiveGateway id="Which" />
                        <bpmn:sequenceFlow id="E" sourceRef="Which" targetRef="Done">
                          <bpmn:conditionExpression>amount &gt; 100</bpmn:conditionExpression>
                        </bpmn:sequenceFlow>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (condition without a leading '=', which the engine never evaluates: an exclusive gateway fails on it, an inclusive one never takes the flow)"),
                Arguments.of("condition at an inclusive gateway in CDATA", """
                        <bpmn:inclusiveGateway id="Which" />
                        <bpmn:sequenceFlow id="E" sourceRef="Which" targetRef="Done">
                          <bpmn:conditionExpression><![CDATA[=amount > 100]]></bpmn:conditionExpression>
                        </bpmn:sequenceFlow>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (condition written with CDATA or a comment, which the engine reads as text and never evaluates)"),
                Arguments.of("flow without a condition at a gateway with more flows", """
                        <bpmn:exclusiveGateway id="Which" />
                        <bpmn:sequenceFlow id="Big" sourceRef="Which" targetRef="Done">
                          <bpmn:conditionExpression>=big</bpmn:conditionExpression>
                        </bpmn:sequenceFlow>
                        <bpmn:sequenceFlow id="E" sourceRef="Which" targetRef="Pay" />
                        """, RUN_DIFFERENTLY,
                        "Orders/E (sequenceFlow without a condition leaving an exclusiveGateway with more flows, which the engine never takes: give it a condition or make it the default flow)"),
                Arguments.of("multi-instance with a literal collection",
                        """
                                <bpmn:serviceTask id="E">
                                  <bpmn:multiInstanceLoopCharacteristics>
                                    <bpmn:extensionElements><zenbpm:loopCharacteristics inputCollection="items" /></bpmn:extensionElements>
                                  </bpmn:multiInstanceLoopCharacteristics>
                                </bpmn:serviceTask>
                                """,
                        RUN_DIFFERENTLY,
                        "Orders/E (multiInstanceLoopCharacteristics without an inputCollection written as an expression, which the engine fails on when a workflow reaches it)"),
                Arguments.of("multi-instance with a loop cardinality",
                        """
                                <bpmn:serviceTask id="E">
                                  <bpmn:multiInstanceLoopCharacteristics><bpmn:loopCardinality>3</bpmn:loopCardinality></bpmn:multiInstanceLoopCharacteristics>
                                </bpmn:serviceTask>
                                """,
                        RUN_DIFFERENTLY, "Orders/E (loopCardinality, which the engine never reads)"),
                Arguments.of("called process written as an expression",
                        """
                                <bpmn:callActivity id="E"><bpmn:extensionElements><zenbpm:calledElement processId="=target" /></bpmn:extensionElements></bpmn:callActivity>
                                """,
                        RUN_DIFFERENTLY,
                        "Orders/E (processId of calledElement written as an expression, which the engine takes literally)"),
                Arguments.of("job type written as an expression",
                        """
                                <bpmn:serviceTask id="E"><bpmn:extensionElements><zenbpm:taskDefinition type="=kind" /></bpmn:extensionElements></bpmn:serviceTask>
                                """,
                        RUN_DIFFERENTLY,
                        "Orders/E (type of taskDefinition written as an expression, which the engine takes literally)"),
                Arguments.of("decision id written as an expression",
                        """
                                <bpmn:businessRuleTask id="E"><bpmn:extensionElements><zenbpm:calledDecision decisionId="=which" /></bpmn:extensionElements></bpmn:businessRuleTask>
                                """,
                        RUN_DIFFERENTLY,
                        "Orders/E (decisionId of calledDecision written as an expression, which the engine takes literally)"),
                Arguments.of("candidate groups written as an expression",
                        """
                                <bpmn:userTask id="E"><bpmn:extensionElements><zenbpm:assignmentDefinition candidateGroups="=groups" /></bpmn:extensionElements></bpmn:userTask>
                                """,
                        RUN_DIFFERENTLY,
                        "Orders/E (candidateGroups of assignmentDefinition written as an expression, which the engine takes literally)"),
                Arguments.of("business key not written as an expression",
                        """
                                <bpmn:callActivity id="E"><bpmn:extensionElements><zenbpm:in businessKey="order-1" /></bpmn:extensionElements></bpmn:callActivity>
                                """,
                        RUN_DIFFERENTLY,
                        "Orders/E (businessKey not written as an expression, which the engine fails on when a workflow reaches it)"),
                Arguments.of("timer on a throw event",
                        """
                                <bpmn:intermediateThrowEvent id="E">
                                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                                </bpmn:intermediateThrowEvent>
                                """,
                        RUN_DIFFERENTLY,
                        "Orders/E (timerEventDefinition on intermediateThrowEvent, which fails when a workflow reaches it)"),
                Arguments.of("instantiating event-based gateway", """
                        <bpmn:eventBasedGateway id="E" instantiate="true" />
                        """, RUN_DIFFERENTLY,
                        "Orders/E (eventBasedGateway with instantiate=\"true\", which the engine never uses to start a workflow)"),
                Arguments.of("instantiating receive task inside a sub-process", """
                        <bpmn:subProcess id="Inner">
                          <bpmn:receiveTask id="E" instantiate="true" messageRef="Message_Paid" />
                        </bpmn:subProcess>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (receiveTask with instantiate=\"true\" inside a subProcess, which the engine never uses to start a workflow)"),
                Arguments.of("signal end event", """
                        <bpmn:endEvent id="E"><bpmn:signalEventDefinition /></bpmn:endEvent>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (signalEventDefinition on endEvent, which the engine drops and runs a plain endEvent)"),
                Arguments.of("escalation end event", """
                        <bpmn:endEvent id="E"><bpmn:escalationEventDefinition /></bpmn:endEvent>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (escalationEventDefinition on endEvent, which the engine drops and runs a plain endEvent)"),
                Arguments.of("compensation end event", """
                        <bpmn:endEvent id="E"><bpmn:compensateEventDefinition /></bpmn:endEvent>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (compensateEventDefinition on endEvent, which the engine drops and runs a plain endEvent)"),
                Arguments.of("cancel end event inside a sub-process", """
                        <bpmn:subProcess id="Booking">
                          <bpmn:endEvent id="E"><bpmn:cancelEventDefinition /></bpmn:endEvent>
                        </bpmn:subProcess>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (cancelEventDefinition on endEvent, which the engine drops and runs a plain endEvent)"),
                Arguments.of("condition leaving a task", """
                        <bpmn:sequenceFlow id="E" sourceRef="Pay" targetRef="Done">
                          <bpmn:conditionExpression>=paid</bpmn:conditionExpression>
                        </bpmn:sequenceFlow>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (condition on a sequenceFlow leaving a serviceTask, which the engine ignores: it reads conditions only on the flows leaving an exclusive or inclusive gateway)"),
                Arguments.of("condition leaving no element", """
                        <bpmn:sequenceFlow id="E" sourceRef="Nowhere" targetRef="Done">
                          <bpmn:conditionExpression>=paid</bpmn:conditionExpression>
                        </bpmn:sequenceFlow>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (condition on a sequenceFlow leaving no element of the process, which the engine never reads)"),
                Arguments.of("standard loop", """
                        <bpmn:serviceTask id="E"><bpmn:standardLoopCharacteristics /></bpmn:serviceTask>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (standardLoopCharacteristics, which the engine ignores and runs the element once)"),
                Arguments.of("completion condition", """
                        <bpmn:serviceTask id="E">
                          <bpmn:multiInstanceLoopCharacteristics>
                            <bpmn:completionCondition>=done</bpmn:completionCondition>
                          </bpmn:multiInstanceLoopCharacteristics>
                        </bpmn:serviceTask>
                        """, RUN_DIFFERENTLY, "Orders/E (completionCondition, which the engine never reads)"),
                Arguments.of("timer written as an expression",
                        """
                                <bpmn:intermediateCatchEvent id="E">
                                  <bpmn:timerEventDefinition><bpmn:timeDuration>=delay</bpmn:timeDuration></bpmn:timerEventDefinition>
                                </bpmn:intermediateCatchEvent>
                                """,
                        RUN_DIFFERENTLY,
                        "Orders/E (timeDuration written as an expression, which the engine cannot read: it reads ISO 8601 only and fails on it)"),
                Arguments.of("intermediate throw event without an event definition", """
                        <bpmn:intermediateThrowEvent id="E" />
                        """, RUN_DIFFERENTLY,
                        "Orders/E (intermediateThrowEvent without an event definition, which fails when a workflow reaches it)"),
                Arguments.of("instantiating receive task", """
                        <bpmn:receiveTask id="E" instantiate="true" messageRef="Message_Paid" />
                        """, WITHOUT_AN_AGGREGATE, "Orders/E (receiveTask with instantiate=\"true\")"),
                Arguments.of("instantiating receive task spelt as 1", """
                        <bpmn:receiveTask id="E" instantiate="1" messageRef="Message_Paid" />
                        """, WITHOUT_AN_AGGREGATE, "Orders/E (receiveTask with instantiate=\"true\")"));
    }

    // a process 'Orders' in 'order.bpmn' with a service task 'Pay' and an end event 'Done', and
    // the given elements
    private static ZenBpmBpmnModel model(final String elements) {
        final var bpmn = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="order">
                  <bpmn:message id="Message_Paid" name="paid" />
                  <bpmn:process id="Orders" isExecutable="true">
                    <bpmn:serviceTask id="Pay" />
                    <bpmn:endEvent id="Done" />
                %s  </bpmn:process>
                </bpmn:definitions>
                """.formatted(elements);
        return ZenBpmModelReader
                .read("shop", "order.bpmn", new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)))
                .getFirst();
    }

    private static ZenBpmBpmnModel fixture(final String fixture) {
        try (var stream = ZenBpmUnsupportedConstructsTest.class.getResourceAsStream("/models/%s".formatted(fixture))) {
            return ZenBpmModelReader
                    .read("shop", fixture, stream)
                    .getFirst();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
