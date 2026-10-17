/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.CallActivity;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.MessageReference;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.MultiInstanceElement;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.StartEvent;

import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.expressions.ExpressionPlace;
import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * What the reader takes from a BPMN file: one model per file, with the elements the wiring, the
 * rewriting and the markers of the pipeline need, read as the modeller wrote them. One fixture per
 * kind of element is in <code>src/test/resources/models/</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmModelReaderTest {

    // an input and an output mapping, each with an expression, for the elements which may carry them
    private static final String MAPPINGS = """
            <bpmn:extensionElements>
              <zenbpm:ioMapping>
                <zenbpm:input source="=in" target="a" />
                <zenbpm:output source="=out" target="b" />
              </zenbpm:ioMapping>
            </bpmn:extensionElements>""";

    @Test
    @DisplayName("The elements which create jobs are the tasks, each with its task definition and its name; a business rule task naming a decision is none, an empty calledDecision names none; user tasks are listed apart")
    void theElementsWhichCreateJobsAreTheTasks() {
        final var model = readOne("job-tasks-zeebe.bpmn");

        assertAll(
                () -> assertEquals("Orders", model.bpmnProcessId()),
                () -> assertEquals("job-tasks-zeebe.bpmn", model.filename()),
                () -> assertEquals(List.of(
                        new BpmnTaskSpec("Charge", "charge", false, "Charge the card"),
                        new BpmnTaskSpec("Unwired", null, false, "Nobody serves me"),
                        new BpmnTaskSpec("Notify", "notify", false, null),
                        new BpmnTaskSpec("Rate", "rate", false, "Rate the customer"),
                        new BpmnTaskSpec("RateWithoutDecision", "rate-again", false, "No decision named"),
                        new BpmnTaskSpec("Announce", "announce", false, "Announce"),
                        new BpmnTaskSpec("Pack", "pack", false, "Pack"),
                        new BpmnTaskSpec("Shipped", "report-shipping", false, "Report the shipping")),
                        model.tasks()),
                () -> assertEquals(List.of(
                        BpmnTaskSpec.userTask("Approve", "approve", "Approve the order"),
                        BpmnTaskSpec.userTask("Review", "user-task-type", null)),
                        model.userTasks()));
    }

    @Test
    @DisplayName("A model in the zeebe namespace reads like the same model in the zenbpm namespace, every collection of it")
    void bothExtensionNamespacesReadAlike() {
        final var zeebe = readOne("job-tasks-zeebe.bpmn");
        final var zenbpm = readOne("job-tasks-zenbpm.bpmn");

        assertAll(
                () -> assertEquals(zeebe.bpmnProcessId(), zenbpm.bpmnProcessId()),
                () -> assertEquals(zeebe.tasks(), zenbpm.tasks()),
                () -> assertEquals(zeebe.userTasks(), zenbpm.userTasks()),
                () -> assertEquals(zeebe.catchMessages(), zenbpm.catchMessages()),
                () -> assertEquals(zeebe.startMessages(), zenbpm.startMessages()),
                () -> assertEquals(zeebe.startMessagesAreKnown(), zenbpm.startMessagesAreKnown()),
                () -> assertEquals(zeebe.startEvents(), zenbpm.startEvents()),
                () -> assertEquals(zeebe.endEventIds(), zenbpm.endEventIds()),
                () -> assertEquals(zeebe.multiInstanceElements(), zenbpm.multiInstanceElements()),
                () -> assertEquals(zeebe.callActivities(), zenbpm.callActivities()),
                () -> assertEquals(zeebe.concurrentTokenElements(), zenbpm.concurrentTokenElements()),
                () -> assertEquals(zeebe.expressions(), zenbpm.expressions()),
                () -> assertEquals(zeebe.messageStarts(), zenbpm.messageStarts()),
                () -> assertEquals(zeebe.messageCatches(), zenbpm.messageCatches()),
                () -> assertEquals(zeebe.enclosingMultiInstances(), zenbpm.enclosingMultiInstances()),
                () -> assertEquals(List.of("Charge", "Invoice", "EveryParcel"), zeebe
                        .expressions()
                        .stream()
                        .map(ModelExpression::elementId)
                        .toList()));
    }

    @Test
    @DisplayName("A model in the shape the Camunda Modeler exports for Camunda 8 reads without changes")
    void aModelOfTheCamundaModelerReadsWithoutChanges() {
        final var model = readOne("camunda-modeler.bpmn");

        assertAll(
                () -> assertEquals(List.of(new BpmnTaskSpec("Activity_Charge", "charge", false, "Charge")), model
                        .tasks()),
                () -> assertEquals(List.of(BpmnTaskSpec.userTask("Activity_Approve", "user-task-type", "Approve")),
                        model.userTasks()),
                () -> assertEquals(Set.of("paid"), model.catchMessages()),
                () -> assertEquals(List.of(new StartEvent("StartEvent_1", BpmsStartTrigger.Kind.NONE)), model
                        .startEvents()),
                () -> assertEquals(List.of("Event_Done"), model.endEventIds()),
                () -> assertEquals(
                        List.of(new MultiInstanceElement("Activity_EveryParcel", "=parcels", "parcel", List.of())),
                        model.multiInstanceElements()),
                () -> assertEquals(List.of(new CallActivity("Activity_Invoice", "Invoicing")), model.callActivities()),
                () -> assertEquals(List.of("Activity_EveryParcel"), List.copyOf(model.concurrentTokenElements())),
                () -> assertEquals(List.of(
                        new ModelExpression("Activity_Charge", ExpressionPlace.INPUT_OR_OUTPUT_MAPPING, "=order.total", "order.total"),
                        new ModelExpression("Activity_Approve", ExpressionPlace.SOMEWHERE_ELSE, "=reviewer", "reviewer"),
                        new ModelExpression("Event_Paid", ExpressionPlace.MESSAGE_CORRELATION_KEY, "=orderId", "orderId"),
                        new ModelExpression("Activity_EveryParcel", ExpressionPlace.MULTI_INSTANCE_COLLECTION, "=parcels", "parcels")),
                        model.expressions()));
    }

    @Test
    @DisplayName("Job types, user task types and message names are kept as written, whitespace included, as the engine keeps them")
    void namesAreKeptAsWritten() {
        final var model = readInline("""
                <bpmn:message id="Message_Ordered" name=" ordered " />
                <bpmn:process id="Orders" isExecutable="true">
                  <bpmn:startEvent id="Ordered">
                    <bpmn:messageEventDefinition messageRef="Message_Ordered" />
                  </bpmn:startEvent>
                  <bpmn:serviceTask id="Charge">
                    <bpmn:extensionElements><zenbpm:taskDefinition type=" charge " /></bpmn:extensionElements>
                  </bpmn:serviceTask>
                  <bpmn:userTask id="Approve">
                    <bpmn:extensionElements><zenbpm:taskDefinition type=" " /></bpmn:extensionElements>
                  </bpmn:userTask>
                  <bpmn:userTask id="Review">
                    <bpmn:extensionElements><zenbpm:taskDefinition type="" /></bpmn:extensionElements>
                  </bpmn:userTask>
                </bpmn:process>
                """);

        assertAll(
                () -> assertEquals(Set.of(" ordered "), model.startMessages()),
                () -> assertEquals(" charge ", model
                        .tasks()
                        .getFirst()
                        .taskDefinition()),
                () -> assertEquals(List.of(" ", "user-task-type"), model
                        .userTasks()
                        .stream()
                        .map(BpmnTaskSpec::taskDefinition)
                        .toList()));
    }

    @Test
    @DisplayName("Mappings are expressions only where the engine keeps them for the kind of element; a plain end event keeps none, a start or boundary event only outputs")
    void mappingsCountWhereTheEngineKeepsThem() {
        final var model = readInline(
                """
                        <bpmn:message id="Message_Shipped" name="shipped" />
                        <bpmn:process id="Orders" isExecutable="true">
                          <bpmn:startEvent id="Start">%s</bpmn:startEvent>
                          <bpmn:serviceTask id="Charge" />
                          <bpmn:boundaryEvent id="Late" attachedToRef="Charge">%s
                            <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                          </bpmn:boundaryEvent>
                          <bpmn:exclusiveGateway id="Which">%s</bpmn:exclusiveGateway>
                          <bpmn:endEvent id="Plain">%s</bpmn:endEvent>
                          <bpmn:endEvent id="Terminate">%s<bpmn:terminateEventDefinition /></bpmn:endEvent>
                          <bpmn:endEvent id="Failed">%s<bpmn:errorEventDefinition id="Failed_Definition" /></bpmn:endEvent>
                          <bpmn:endEvent id="FailedWithoutId">%s<bpmn:errorEventDefinition /></bpmn:endEvent>
                          <bpmn:endEvent id="Shipped">%s<bpmn:messageEventDefinition messageRef="Message_Shipped" /></bpmn:endEvent>
                        </bpmn:process>
                        """
                        .formatted(MAPPINGS, MAPPINGS, MAPPINGS, MAPPINGS, MAPPINGS, MAPPINGS, MAPPINGS, MAPPINGS));

        assertEquals(List.of("Start:=out", "Late:=out", "Failed:=in", "Failed:=out", "Shipped:=in", "Shipped:=out"),
                model
                        .expressions()
                        .stream()
                        .map(expression -> "%s:%s".formatted(expression.elementId(), expression.expression()))
                        .toList());
    }

    @Test
    @DisplayName("A correlation key or a condition the engine takes as text is no expression: a key starting with a blank, a condition in CDATA")
    void whatTheEngineTakesAsTextIsNoExpression() {
        final var model = readInline("""
                <bpmn:message id="Message_Paid" name="paid">
                  <bpmn:extensionElements><zenbpm:subscription correlationKey=" =orderId" /></bpmn:extensionElements>
                </bpmn:message>
                <bpmn:process id="Orders" isExecutable="true">
                  <bpmn:intermediateCatchEvent id="Paid">
                    <bpmn:messageEventDefinition messageRef="Message_Paid" />
                  </bpmn:intermediateCatchEvent>
                  <bpmn:exclusiveGateway id="Which" />
                  <bpmn:sequenceFlow id="Big" sourceRef="Which" targetRef="Paid">
                    <bpmn:conditionExpression><![CDATA[=amount > 100]]></bpmn:conditionExpression>
                  </bpmn:sequenceFlow>
                </bpmn:process>
                """);

        assertEquals(List.of(), model.expressions());
    }

    @Test
    @DisplayName("Messages are split into those which start the process and those a catch element waits for; a thrown message is neither, and a receive task naming none adds nothing")
    void messagesAreSplitIntoStartAndCatch() {
        final var model = readOne("messages.bpmn");

        assertAll(
                () -> assertEquals(List.of("ordered", "=orderType"), List.copyOf(model.startMessages())),
                () -> assertTrue(model.startMessagesAreKnown()),
                () -> assertEquals(List.of("paid", "shipped", "cancelled", "complaint"), List.copyOf(model
                        .catchMessages())),
                () -> assertEquals(List.of(
                        new MessageReference("Ordered", "Message_Ordered", "ordered"),
                        new MessageReference("OrderedByType", "Message_Expression", "=orderType")),
                        model.messageStarts()),
                () -> assertEquals(List.of(
                        new MessageReference("Paid", "Message_Paid", "paid"),
                        new MessageReference("AwaitShipping", "Message_Shipped", "shipped"),
                        new MessageReference("Cancelled", "Message_Cancelled", "cancelled"),
                        new MessageReference("Complaint", "Message_Complaint", "complaint")),
                        model.messageCatches()));
    }

    @Test
    @DisplayName("A message start event whose name cannot be read makes the start messages unknown")
    void aMessageStartWithoutANameMakesTheStartMessagesUnknown() {
        final var model = readOne("message-start-without-name.bpmn");

        assertAll(
                () -> assertFalse(model.startMessagesAreKnown()),
                () -> assertEquals(Set.of(), model.startMessages()),
                () -> assertEquals(List.of(
                        new MessageReference("WithoutName", "Message_Unnamed", null),
                        new MessageReference("ToNowhere", "Message_Missing", null)),
                        model.messageStarts()));
    }

    @Test
    @DisplayName("Every start event of the process itself is collected with its trigger; those of sub-processes are not, and neither are their end events")
    void startAndEndEventsOfTheProcessItself() {
        final var model = readOne("start-and-end-events.bpmn");

        assertAll(
                () -> assertEquals(List.of(
                        new StartEvent("Plain", BpmsStartTrigger.Kind.NONE),
                        new StartEvent("ByMessage", BpmsStartTrigger.Kind.MESSAGE),
                        new StartEvent("ByTimer", BpmsStartTrigger.Kind.TIMER),
                        new StartEvent("BySignal", BpmsStartTrigger.Kind.SIGNAL),
                        new StartEvent("ByCondition", BpmsStartTrigger.Kind.CONDITIONAL)),
                        model.startEvents()),
                () -> assertEquals(List.of("Shipped", "Rejected"), model.endEventIds()));
    }

    @Test
    @DisplayName("Multi-instance elements carry their collection, their item and the multi-instance elements around them; call activities their called process")
    void multiInstanceElementsAndCallActivities() {
        final var model = readOne("multi-instance.bpmn");

        assertAll(
                () -> assertEquals(List.of(
                        new MultiInstanceElement("EveryParcel", "=parcels", "parcel", List.of()),
                        new MultiInstanceElement("LabelEveryItem", "=parcel.items", null, List.of("EveryParcel"))),
                        model.multiInstanceElements()),
                () -> assertEquals(List.of(
                        new CallActivity("Invoice", "Invoicing"),
                        new CallActivity("Unnamed", null)),
                        model.callActivities()),
                () -> assertEquals(Map.of(
                        "EveryParcel_Start", List.of("EveryParcel"),
                        "LabelEveryItem", List.of("EveryParcel"),
                        "EveryParcel_End", List.of("EveryParcel")),
                        model.enclosingMultiInstances()));
    }

    @Test
    @DisplayName("The elements which give a workflow a second token are collected, an activity leaving on two flows and flags spelt as Go reads them included (an empty one is false); their sequential or interrupting siblings are not")
    void theElementsWhichGiveASecondToken() {
        final var model = readOne("concurrent-tokens.bpmn");

        assertEquals(List.of("Fork", "Choose", "Reminder", "Complaints", "NotifyAll", "Split", "ReminderSpelledAsZero",
                "ReminderWithEmptyFlag", "ComplaintsSpelledAsDigits"), List.copyOf(model.concurrentTokenElements()));
    }

    @Test
    @DisplayName("Expressions are the values the engine evaluates as FEEL, one per place; literals, timers, completion conditions and names are not")
    void expressionsAreWhatTheEngineEvaluates() {
        final var model = readOne("expressions.bpmn");

        assertEquals(List.of(
                new ModelExpression("Flow_Big", ExpressionPlace.SEQUENCE_FLOW_CONDITION, "= amount > 100", "amount > 100"),
                new ModelExpression("Paid", ExpressionPlace.MESSAGE_CORRELATION_KEY, "=orderId", "orderId"),
                new ModelExpression("EveryParcel", ExpressionPlace.MULTI_INSTANCE_COLLECTION, "=parcels", "parcels"),
                new ModelExpression("EveryParcel", ExpressionPlace.SOMEWHERE_ELSE, "=parcel.weight", "parcel.weight"),
                new ModelExpression("Charge", ExpressionPlace.SOMEWHERE_ELSE, "=maxAttempts", "maxAttempts"),
                new ModelExpression("Charge", ExpressionPlace.SOMEWHERE_ELSE, "=backoff", "backoff"),
                new ModelExpression("Charge", ExpressionPlace.INPUT_OR_OUTPUT_MAPPING, "=customer.name", "customer.name"),
                new ModelExpression("Charge", ExpressionPlace.INPUT_OR_OUTPUT_MAPPING, "=receipt", "receipt"),
                new ModelExpression("Review", ExpressionPlace.SOMEWHERE_ELSE, "=reviewer", "reviewer"),
                new ModelExpression("Invoice", ExpressionPlace.SOMEWHERE_ELSE, "=orderId", "orderId")),
                model.expressions());
    }

    @Test
    @DisplayName("A file with a second process is refused naming every process, because the engine would read them as one")
    void aSecondProcessIsRefused() {
        final var failure = assertThrows(BpmnParseException.class, () -> read("two-processes.bpmn"));

        assertEquals(
                """
                        BPMN file 'two-processes.bpmn' of workflow module 'shop' holds the processes 'Orders' and 'Warehouse_Documentation', \
                        but ZenBPM reads every process of a file as one process with the elements of all of them, \
                        under the id of the last one, whether a process is executable or not. Give every process \
                        a file of its own; a pool which only shows a partner needs no process inside.""",
                failure.getMessage());
    }

    @Test
    @DisplayName("A file with two executable processes is refused naming both")
    void twoExecutableProcessesAreRefused() {
        final var failure = assertThrows(BpmnParseException.class, () -> read("two-executable-processes.bpmn"));

        assertTrue(failure
                .getMessage()
                .startsWith(
                        "BPMN file 'two-executable-processes.bpmn' of workflow module 'shop' holds the processes 'Orders' and 'Invoicing',"),
                failure.getMessage());
    }

    @Test
    @DisplayName("An executable process without an id, or an isExecutable which is no boolean, is refused naming the file; 1 is executable")
    void aProcessTheEngineCannotTakeIsRefused() {
        final var withoutId = assertThrows(BpmnParseException.class, () -> readInline("""
                <bpmn:process isExecutable="true" />
                """));
        final var noBoolean = assertThrows(BpmnParseException.class, () -> readInline("""
                <bpmn:process id="Orders" isExecutable="yes" />
                """));

        assertAll(
                () -> assertEquals(
                        "BPMN file 'order.bpmn' of workflow module 'shop' cannot be deployed: its executable process has no id.",
                        withoutId.getMessage()),
                () -> assertEquals("""
                        BPMN file 'order.bpmn' of workflow module 'shop' cannot be deployed: process 'Orders' has \
                        isExecutable="yes", which is no boolean, and ZenBPM refuses the file over it. Write true or \
                        false.""", noBoolean.getMessage()),
                () -> assertEquals("Orders", readInline("""
                        <bpmn:process id="Orders" isExecutable="1" />
                        """).bpmnProcessId()));
    }

    @Test
    @DisplayName("A file without an executable process yields no model")
    void aFileWithoutAnExecutableProcessYieldsNothing() {
        assertEquals(List.of(), read("not-executable.bpmn"));
    }

    @Test
    @DisplayName("A file with a DOCTYPE is refused, so no entity it declares is ever resolved")
    void aDoctypeIsRefused() {
        final var failure = assertThrows(BpmnParseException.class, () -> read("doctype.bpmn"));

        assertTrue(failure
                .getMessage()
                .startsWith("BPMN file 'doctype.bpmn' of workflow module 'shop' cannot be read: "),
                failure.getMessage());
        assertTrue(failure
                .getCause()
                .getMessage()
                .contains("DOCTYPE"),
                failure
                        .getCause()
                        .getMessage());
    }

    @Test
    @DisplayName("A file which is no XML, or no BPMN, is refused naming the file")
    void aFileWhichIsNoBpmnIsRefused() {
        final var broken = assertThrows(BpmnParseException.class, () -> read("broken.bpmn"));
        final var noBpmn = assertThrows(BpmnParseException.class, () -> ZenBpmModelReader.read("shop", "order.bpmn",
                new ByteArrayInputStream("<definitions/>".getBytes(StandardCharsets.UTF_8))));
        final var otherNamespace = assertThrows(BpmnParseException.class, () -> ZenBpmModelReader.read("shop",
                "order.bpmn", new ByteArrayInputStream("""
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL/" />
                        """.getBytes(StandardCharsets.UTF_8))));

        assertAll(
                () -> assertTrue(broken
                        .getMessage()
                        .startsWith("BPMN file 'broken.bpmn' of workflow module 'shop' cannot be read: "),
                        broken.getMessage()),
                () -> assertEquals(
                        "BPMN file 'order.bpmn' of workflow module 'shop' cannot be read: its root element is 'definitions' of no namespace, not 'definitions' of the namespace http://www.omg.org/spec/BPMN/20100524/MODEL.",
                        noBpmn.getMessage()),
                () -> assertEquals(
                        "BPMN file 'order.bpmn' of workflow module 'shop' cannot be read: its root element is 'definitions' of the namespace http://www.omg.org/spec/BPMN/20100524/MODEL/, not 'definitions' of the namespace http://www.omg.org/spec/BPMN/20100524/MODEL.",
                        otherNamespace.getMessage()));
    }

    @Test
    @DisplayName("The stream belongs to the platform and is read to its end, but not closed")
    void theStreamIsLeftOpen() throws IOException {
        try (var file = fixture("messages.bpmn"); var stream = new CloseRecordingStream(file)) {
            final var models = ZenBpmModelReader.read("shop", "messages.bpmn", stream);

            assertAll(
                    () -> assertEquals(1, models.size()),
                    () -> assertFalse(stream.closed),
                    () -> assertEquals(-1, stream.read()));
        }
    }

    @Test
    @DisplayName("A stream which fails while it is read is refused naming file and workflow module")
    void aStreamWhichFailsIsRefused() {
        final var failure = assertThrows(BpmnParseException.class, () -> ZenBpmModelReader.read("shop",
                "order.bpmn", new InputStream() {

                    @Override
                    public int read() throws IOException {
                        throw new IOException("disk gone");
                    }

                }));

        assertEquals("BPMN file 'order.bpmn' of workflow module 'shop' cannot be read: disk gone", failure
                .getMessage());
    }

    @Test
    @DisplayName("The model keeps the bytes it was read from and hands out copies of them")
    void theModelKeepsTheBytesItWasReadFrom() throws IOException {
        final byte[] file;
        try (var stream = fixture("messages.bpmn")) {
            file = stream.readAllBytes();
        }
        final var model = ZenBpmModelReader
                .read("shop", "messages.bpmn", new ByteArrayInputStream(file))
                .getFirst();

        model.content()[0] = 0;

        assertArrayEquals(file, model.content());
    }

    @Test
    @DisplayName("The model holds the document it was read from, and its collections cannot be changed")
    void theModelHoldsItsDocumentAndCannotBeChanged() {
        final var model = readOne("job-tasks-zeebe.bpmn");

        assertAll(
                () -> assertSame(model.document(), model
                        .document()
                        .getDocumentElement()
                        .getOwnerDocument()),
                () -> assertEquals("jobTasks", model
                        .document()
                        .getDocumentElement()
                        .getAttribute("id")),
                () -> assertThrows(UnsupportedOperationException.class, () -> model
                        .tasks()
                        .clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> model
                        .catchMessages()
                        .clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> model
                        .enclosingMultiInstances()
                        .clear()));
    }

    private static ZenBpmBpmnModel readOne(final String fixture) {
        final var models = read(fixture);
        assertEquals(1, models.size(), fixture);
        return models.getFirst();
    }

    private static List<ZenBpmBpmnModel> read(final String fixture) {
        try (var stream = fixture(fixture)) {
            return ZenBpmModelReader.read("shop", fixture, stream);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // the process and messages given, in a file 'order.bpmn' of workflow module 'shop'
    private static ZenBpmBpmnModel readInline(final String rootElements) {
        final var models = ZenBpmModelReader.read("shop", "order.bpmn", new ByteArrayInputStream("""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="order">
                %s</bpmn:definitions>
                """.formatted(rootElements).getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, models.size());
        return models.getFirst();
    }

    private static InputStream fixture(final String fixture) {
        final var stream = ZenBpmModelReaderTest.class.getResourceAsStream("/models/%s".formatted(fixture));
        if (stream == null) {
            throw new IllegalStateException("No fixture '%s' in src/test/resources/models".formatted(fixture));
        }
        return stream;
    }

    /**
     * A stream which remembers whether somebody closed it, and stays readable afterwards so the
     * test can look at it.
     */
    private static final class CloseRecordingStream extends FilterInputStream {

        private boolean closed;

        private CloseRecordingStream(final InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            closed = true;
        }

    }

}
