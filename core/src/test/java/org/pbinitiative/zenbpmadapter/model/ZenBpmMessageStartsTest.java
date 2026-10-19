/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.w3c.dom.Element;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which message start events of a process get an output of the aggregate's id variable: those whose
 * modeller maps outputs, but none to the id variable. The process <code>Postcards</code> has a start
 * event of each kind.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmMessageStartsTest {

    private static final String POSTCARDS = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="d">
              <bpmn:message id="Message_Posted" name="posted" />
              <bpmn:message id="Message_Sent" name="sent" />
              <bpmn:message id="Message_Mapped" name="mapped" />
              <bpmn:message id="Message_InputOnly" name="inputOnly" />
              <bpmn:process id="Postcards" isExecutable="true">
                <bpmn:startEvent id="Posted">
                  <bpmn:extensionElements>
                    <zenbpm:ioMapping>
                      <zenbpm:output source="=status" target="status" />
                    </zenbpm:ioMapping>
                  </bpmn:extensionElements>
                  <bpmn:messageEventDefinition messageRef="Message_Posted" />
                </bpmn:startEvent>
                <bpmn:startEvent id="Sent">
                  <bpmn:messageEventDefinition messageRef="Message_Sent" />
                </bpmn:startEvent>
                <bpmn:startEvent id="Mapped">
                  <bpmn:extensionElements>
                    <zenbpm:ioMapping>
                      <zenbpm:output source="=postcardNumber" target="id" />
                    </zenbpm:ioMapping>
                  </bpmn:extensionElements>
                  <bpmn:messageEventDefinition messageRef="Message_Mapped" />
                </bpmn:startEvent>
                <bpmn:startEvent id="InputOnly">
                  <bpmn:extensionElements>
                    <zenbpm:ioMapping>
                      <zenbpm:input source="=status" target="status" />
                    </zenbpm:ioMapping>
                  </bpmn:extensionElements>
                  <bpmn:messageEventDefinition messageRef="Message_InputOnly" />
                </bpmn:startEvent>
                <bpmn:startEvent id="Plain">
                  <bpmn:extensionElements>
                    <zenbpm:ioMapping>
                      <zenbpm:output source="=status" target="status" />
                    </zenbpm:ioMapping>
                  </bpmn:extensionElements>
                </bpmn:startEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Test
    @DisplayName("A message start event whose outputs do not map the id variable gets an output of it behind the modeller's")
    void aMappedMessageStartKeepsTheId() {
        final var model = read();

        ZenBpmMessageStarts.keepTheAggregateId(model, "id");

        assertEquals(List.of("=status -> status", "=id -> id"), outputsOf(model, "Posted"));
    }

    @Test
    @DisplayName("A message start event without outputs, one whose modeller maps the id variable, one with inputs only and a start event of no message are left as they are")
    void everyOtherStartEventIsLeftAlone() {
        final var model = read();

        ZenBpmMessageStarts.keepTheAggregateId(model, "id");

        assertAll(
                () -> assertEquals(List.of(), outputsOf(model, "Sent")),
                () -> assertEquals(List.of("=postcardNumber -> id"), outputsOf(model, "Mapped")),
                () -> assertEquals(List.of(), outputsOf(model, "InputOnly")),
                () -> assertEquals(List.of("=status -> status"), outputsOf(model, "Plain")));
    }

    @Test
    @DisplayName("Kept twice, the id variable is mapped once")
    void keepingTheIdAgainChangesNothing() {
        final var model = read();

        ZenBpmMessageStarts.keepTheAggregateId(model, "id");
        ZenBpmMessageStarts.keepTheAggregateId(model, "id");

        assertEquals(List.of("=status -> status", "=id -> id"), outputsOf(model, "Posted"));
    }

    private static List<String> outputsOf(
        final ZenBpmBpmnModel model,
        final String elementId
    ) {
        final var outputs = new ArrayList<String>();
        final var element = ZenBpmInputMappings
                .elementsById(model.document())
                .get(elementId);
        for (final var ioMapping : ZenBpmModelReader.extensions(element, "ioMapping")) {
            for (var child = ioMapping.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element output && "output".equals(output.getLocalName())) {
                    outputs.add("%s -> %s".formatted(output.getAttribute("source"), output.getAttribute("target")));
                }
            }
        }
        return outputs;
    }

    private static ZenBpmBpmnModel read() {
        return ZenBpmModelReader
                .read("post", "postcards.bpmn", new ByteArrayInputStream(POSTCARDS.getBytes(StandardCharsets.UTF_8)))
                .getFirst();
    }

}
