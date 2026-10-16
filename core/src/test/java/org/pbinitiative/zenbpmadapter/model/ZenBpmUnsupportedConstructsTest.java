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
                        .contains("  %s\n    - %s\n".formatted(heading, line)), failure.getMessage()));
    }

    @Test
    @DisplayName("What ZenBPM runs as modelled passes: a condition at a gateway, a literal timer, a message throw event, a receive task waiting for a message")
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
                        <bpmn:endEvent id="Failed"><bpmn:errorEventDefinition /></bpmn:endEvent>
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
                Arguments.of("signal start event", """
                        <bpmn:startEvent id="E"><bpmn:signalEventDefinition /></bpmn:startEvent>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (signalEventDefinition on startEvent, which the engine drops and runs a plain startEvent)"),
                Arguments.of("conditional start event", """
                        <bpmn:startEvent id="E"><bpmn:conditionalEventDefinition /></bpmn:startEvent>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (conditionalEventDefinition on startEvent, which the engine drops and runs a plain startEvent)"),
                Arguments.of("escalation start event of an event sub-process", """
                        <bpmn:subProcess id="OnEscalation" triggeredByEvent="true">
                          <bpmn:startEvent id="E"><bpmn:escalationEventDefinition /></bpmn:startEvent>
                        </bpmn:subProcess>
                        """, RUN_DIFFERENTLY,
                        "Orders/E (escalationEventDefinition on startEvent, which the engine drops and runs a plain startEvent)"),
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
                        """, WITHOUT_AN_AGGREGATE, "Orders/E (receiveTask with instantiate=\"true\")"));
    }

    // a process 'Orders' in 'order.bpmn' with a service task 'Pay' and an end event 'Done', and
    // the given elements
    private static ZenBpmBpmnModel model(final String elements) {
        final var bpmn = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="order">
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
        try (var stream = ZenBpmUnsupportedConstructsTest.class.getResourceAsStream("/models/"
                + fixture)) {
            return ZenBpmModelReader
                    .read("shop", fixture, stream)
                    .getFirst();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
