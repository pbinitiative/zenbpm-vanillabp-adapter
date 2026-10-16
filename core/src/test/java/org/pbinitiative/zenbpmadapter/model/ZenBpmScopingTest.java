/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What <code>use-prefix</code> rewrites in a BPMN file: every identifier the engine resolves across
 * the files it holds, each through the platform's scoping, and nothing else. The fixture
 * <code>models/scoping.bpmn</code> carries every attribute which is rewritten, and the ones which
 * are left because they name nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmScopingTest {

    private static final String FIXTURE = "scoping.bpmn";

    // what use-prefix makes of the fixture, as a search and replace on its text
    private static final Map<String, String> PREFIXED = Map.of(
            "<bpmn:process id=\"Orders\"", "<bpmn:process id=\"shop__Orders\"",
            "bpmnElement=\"Orders\"", "bpmnElement=\"shop__Orders\"",
            "name=\"paid\"", "name=\"shop__paid\"",
            "errorCode=\"declined\"", "errorCode=\"shop__declined\"",
            "escalationCode=\"late\"", "escalationCode=\"shop__late\"",
            "type=\"charge\"", "type=\"shop__Orders__charge\"",
            "type=\"approve\"", "type=\"shop__Orders__approve\"",
            "decisionId=\"rating\"", "decisionId=\"shop__rating\"",
            "processId=\"Invoicing\"", "processId=\"shop__Invoicing\"");

    @Test
    @DisplayName("Under use-prefix the process id and the references to it, message names, error and escalation codes, called processes and decisions and the task definitions of tasks and user tasks are scoped, and nothing else changes")
    void usePrefixScopesWhatTheEngineResolvesAcrossFiles() {
        final var document = read(text());

        ZenBpmScoping.apply(document, "shop", "Orders", TestScoping.of(NameClashAvoidance.USE_PREFIX), "orders");

        final var expected = read(prefixed(text()));
        assertTrue(expected.isEqualNode(document), () -> "%s%nis not%n%s".formatted(serialized(document),
                serialized(expected)));
    }

    @ParameterizedTest
    @EnumSource(value = NameClashAvoidance.class, names = {
            "NONE", "BY_ADAPTER"
    })
    @DisplayName("Under none and by-adapter the file stays as it was written")
    void otherModesLeaveTheFileAsItWas(final NameClashAvoidance mode) {
        final var document = read(text());

        ZenBpmScoping.apply(document, "shop", "Orders", TestScoping.of(mode), "orders");

        assertTrue(read(text()).isEqualNode(document), () -> serialized(document));
    }

    @Test
    @DisplayName("A workflow's own mode decides its process id and task definitions, the module's mode the names the module shares")
    void theModeOfTheWorkflowDecidesItsOwnIdentifiers() {
        final var document = read(text());

        ZenBpmScoping.apply(document, "shop", "Orders", TestScoping
                .of(NameClashAvoidance.USE_PREFIX)
                .withWorkflow("shop", "Orders", NameClashAvoidance.NONE), "orders");

        assertAll(
                () -> assertEquals("Orders", attributeOf(document, "process", "id")),
                () -> assertEquals("charge", attributeOf(document, "taskDefinition", "type")),
                () -> assertEquals("shop__paid", attributeOf(document, "message", "name")),
                () -> assertEquals("shop__rating", attributeOf(document, "calledDecision", "decisionId")),
                () -> assertEquals("shop__Invoicing", attributeOf(document, "calledElement", "processId")));
    }

    @Test
    @DisplayName("The pool of a collaboration keeps pointing at its process, a pool of another process is left, and an extension element outside extensionElements is no extension")
    void aPoolFollowsItsProcess() {
        final var document = read("""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="pool">
                  <bpmn:collaboration id="Collaboration">
                    <bpmn:participant id="Shop" processRef="Orders" />
                    <bpmn:participant id="Customer" />
                    <bpmn:participant id="Bank" processRef="Banking" />
                  </bpmn:collaboration>
                  <bpmn:process id="Orders" isExecutable="true">
                    <bpmn:documentation><zenbpm:taskDefinition type="a note" /></bpmn:documentation>
                  </bpmn:process>
                </bpmn:definitions>
                """);

        ZenBpmScoping.apply(document, "shop", "Orders", TestScoping.of(NameClashAvoidance.USE_PREFIX), "orders");

        assertAll(
                () -> assertEquals("shop__Orders", attributeOf(document, "participant", "processRef")),
                () -> assertEquals("Banking", ((Element) document
                        .getElementsByTagNameNS("*", "participant")
                        .item(2)).getAttribute("processRef")),
                () -> assertEquals("a note", attributeOf(document, "taskDefinition", "type")));
    }

    @Test
    @DisplayName("Under use-prefix a decision named with the id of its DMN definitions, and any decision of a module whose id holds a '.', is refused before anything is rewritten, naming each task, unless it is bound by its version tag")
    void aDecisionIdTheEngineWouldSplitIsRefusedUnderUsePrefix() {
        final var named = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="decisions">
                  <bpmn:process id="Orders" isExecutable="true">
                    <bpmn:businessRuleTask id="ByDefinitions">
                      <bpmn:extensionElements><zenbpm:calledDecision decisionId="pricing.rating" /></bpmn:extensionElements>
                    </bpmn:businessRuleTask>
                    <bpmn:businessRuleTask id="ByLatest">
                      <bpmn:extensionElements>
                        <zenbpm:calledDecision decisionId="rating" bindingType="latest" />
                      </bpmn:extensionElements>
                    </bpmn:businessRuleTask>
                    <bpmn:businessRuleTask id="ByVersion">
                      <bpmn:extensionElements>
                        <zenbpm:calledDecision decisionId="pricing.rating" bindingType="versionTag" versionTag="1" />
                      </bpmn:extensionElements>
                    </bpmn:businessRuleTask>
                    <bpmn:businessRuleTask id="Unnamed">
                      <bpmn:extensionElements><zenbpm:calledDecision /></bpmn:extensionElements>
                    </bpmn:businessRuleTask>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        final var prefixing = TestScoping.of(NameClashAvoidance.USE_PREFIX);

        final var ofTheDefinitions = assertThrows(IllegalStateException.class, () -> ZenBpmScoping.apply(read(
                named), "shop", "Orders", prefixing, "orders"));
        final var document = read(named);
        final var ofTheModule = assertThrows(IllegalStateException.class, () -> ZenBpmScoping.apply(document,
                "com.shop", "Orders", prefixing, "orders"));
        final var unscoped = read(named);
        ZenBpmScoping.apply(unscoped, "com.shop", "Orders", TestScoping.of(NameClashAvoidance.NONE), "orders");

        assertAll(
                () -> assertEquals(
                        """
                                Under name-clash-avoidance 'use-prefix', business rule tasks of workflow module 'shop' would \
                                call decisions ZenBPM does not find:
                                  - Orders/ByDefinitions: decisionId 'pricing.rating' becomes 'shop__pricing.rating'
                                ZenBPM reads a decision id holding a '.' as '<DMN definitions id>.<decision id>', and VanillaBP \
                                prefixes the ids of the decisions with the workflow module id but not the ids of the DMN \
                                definitions. Name each decision by its own id alone, give the workflow module an id without \
                                a '.', or bind the decision by its version tag (bindingType="versionTag"), under which the \
                                engine does not split the id.""",
                        ofTheDefinitions.getMessage()),
                () -> assertTrue(ofTheModule
                        .getMessage()
                        .contains("""
                                  - Orders/ByDefinitions: decisionId 'pricing.rating' becomes 'com.shop__pricing.rating'
                                  - Orders/ByLatest: decisionId 'rating' becomes 'com.shop__rating'
                                ZenBPM reads"""), ofTheModule::getMessage),
                () -> assertEquals("Orders", attributeOf(document, "process", "id")),
                () -> assertTrue(read(named).isEqualNode(unscoped), () -> serialized(unscoped)));
    }

    private static String attributeOf(
        final Document document,
        final String localName,
        final String attribute
    ) {
        return ((Element) document
                .getElementsByTagNameNS("*", localName)
                .item(0)).getAttribute(attribute);
    }

    private static String serialized(final Document document) {
        try {
            final var text = new StringWriter();
            TransformerFactory
                    .newInstance()
                    .newTransformer()
                    .transform(new DOMSource(document), new StreamResult(text));
            return text.toString();
        } catch (final TransformerException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String prefixed(final String text) {
        var prefixed = text;
        for (final var replacement : PREFIXED.entrySet()) {
            assertTrue(prefixed.contains(replacement.getKey()), replacement.getKey());
            prefixed = prefixed.replace(replacement.getKey(), replacement.getValue());
        }
        return prefixed;
    }

    private static Document read(final String text) {
        return ZenBpmModelReader
                .read("shop", FIXTURE, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
                .getFirst()
                .document();
    }

    private static String text() {
        try (InputStream fixture = ZenBpmScopingTest.class.getResourceAsStream("/models/%s".formatted(FIXTURE))) {
            return new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
