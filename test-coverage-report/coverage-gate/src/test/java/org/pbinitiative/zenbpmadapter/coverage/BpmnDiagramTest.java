/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.helpers.DefaultHandler;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Every BPMN file of this repository carries its diagram, see decision 29 in the repository's
 * DECISIONS.md: a shape with bounds for every flow node, boundary event and pool, an edge with
 * waypoints for every sequence flow, and nothing in the diagram which the model does not hold.
 */
@ExtendWith(SuppressOutputExtension.class)
class BpmnDiagramTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    private static final String BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI";

    private static final String DC = "http://www.omg.org/spec/DD/20100524/DC";

    private static final String DI = "http://www.omg.org/spec/DD/20100524/DI";

    // the files which are no model on purpose, with the reason
    private static final Map<String, String> NO_MODEL = Map.of("core/src/test/resources/models/broken.bpmn",
            "is not well-formed XML: the reader's refusal of such a file is what it tests");

    // what a process or a sub-process holds which is drawn as no shape of its own
    private static final Set<String> NOT_DRAWN = Set.of("extensionElements", "documentation",
            "laneSet", "dataObject", "property", "ioSpecification", "incoming", "outgoing",
            "multiInstanceLoopCharacteristics", "standardLoopCharacteristics");

    @Test
    @DisplayName("Every BPMN file carries a diagram with a shape for every flow node and an edge for every sequence flow")
    void everyBpmnFileCarriesItsDiagram() {
        final var offenders = new ArrayList<String>();
        for (final var file : bpmnFiles()) {
            final var name = ROOT
                    .relativize(file)
                    .toString()
                    .replace('\\', '/');
            if (!NO_MODEL.containsKey(name)) {
                offenders.addAll(missingIn(name, parse(file)));
            }
        }

        assertTrue(offenders.isEmpty(), () -> """
                A BPMN file is read by people in a modeler as well as by the engine, so it carries its \
                diagram (see decision 29 in the repository's DECISIONS.md). Add the shapes and edges, or \
                save the file from a modeler:
                %s""".formatted(String.join("\n", offenders)));
    }

    @Test
    @DisplayName("The check reads the BPMN files of this repository, so it cannot pass by reading none, and every file it leaves out exists")
    void theCheckReadsFiles() {
        final var files = bpmnFiles()
                .stream()
                .map(file -> ROOT
                        .relativize(file)
                        .toString()
                        .replace('\\', '/'))
                .toList();

        assertTrue(files.size() > 20, () -> "only %d BPMN files found below %s".formatted(files.size(), ROOT));
        assertTrue(files.containsAll(NO_MODEL.keySet()), () -> "a file left out is gone: %s".formatted(NO_MODEL
                .keySet()));
    }

    private static List<Path> bpmnFiles() {
        try (var paths = Files.walk(ROOT)) {
            return paths
                    .filter(path -> path
                            .toString()
                            .endsWith(".bpmn"))
                    .filter(path -> !ROOT
                            .relativize(path)
                            .toString()
                            .replace('\\', '/')
                            .contains("/target/"))
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // a DOCTYPE is read, as a fixture may declare one, but nothing it names is fetched
    private static Document parse(final Path file) {
        try {
            final var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            final var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(file.toFile());
        } catch (final Exception e) {
            throw new IllegalStateException("%s cannot be read: %s".formatted(file, e.getMessage()), e);
        }
    }

    private static List<String> missingIn(
        final String name,
        final Document document
    ) {
        final var missing = new ArrayList<String>();
        final var diagrams = document.getElementsByTagNameNS(BPMNDI, "BPMNDiagram");
        if (diagrams.getLength() == 0) {
            missing.add("  %s: no BPMNDiagram".formatted(name));
            return missing;
        }
        final var shapes = drawn(document, "BPMNShape", DC, "Bounds", 1);
        final var edges = drawn(document, "BPMNEdge", DI, "waypoint", 2);
        final var ids = new HashSet<String>();
        for (final var element : all(document.getDocumentElement())) {
            if (BPMN.equals(element.getNamespaceURI()) && element.hasAttribute("id")) {
                ids.add(element.getAttribute("id"));
            }
        }
        for (final var participant : bpmnElements(document, "participant")) {
            requireDrawn(name, participant, shapes, "shape", missing);
        }
        for (final var process : bpmnElements(document, "process")) {
            checkContainer(name, process, shapes, edges, missing);
        }
        final var planes = new HashSet<String>();
        final var planeElements = document.getElementsByTagNameNS(BPMNDI, "BPMNPlane");
        for (var index = 0; index < planeElements.getLength(); index++) {
            planes.add(((Element) planeElements.item(index)).getAttribute("bpmnElement"));
        }
        for (final var drawnId : union(union(shapes, edges), planes)) {
            if (!ids.contains(drawnId)) {
                missing.add("  %s: the diagram draws '%s', which the model does not hold".formatted(name, drawnId));
            }
        }
        return missing;
    }

    private static void checkContainer(
        final String name,
        final Element container,
        final Set<String> shapes,
        final Set<String> edges,
        final List<String> missing
    ) {
        for (var child = container.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element element) || !BPMN.equals(element.getNamespaceURI()) || NOT_DRAWN
                    .contains(element.getLocalName())) {
                continue;
            }
            if ("sequenceFlow".equals(element.getLocalName())) {
                requireDrawn(name, element, edges, "edge with two waypoints", missing);
                continue;
            }
            requireDrawn(name, element, shapes, "shape with bounds", missing);
            if ("subProcess".equals(element.getLocalName())) {
                checkContainer(name, element, shapes, edges, missing);
            }
        }
    }

    private static void requireDrawn(
        final String name,
        final Element element,
        final Set<String> drawn,
        final String what,
        final List<String> missing
    ) {
        if (!drawn.contains(element.getAttribute("id"))) {
            missing.add("  %s: %s '%s' has no %s".formatted(name, element.getLocalName(), element.getAttribute(
                    "id"), what));
        }
    }

    // the ids drawn by the diagram elements of the given kind which carry enough of the given
    // child: bounds for a shape, waypoints for an edge
    private static Set<String> drawn(
        final Document document,
        final String kind,
        final String childNamespace,
        final String child,
        final int atLeast
    ) {
        final var drawn = new HashSet<String>();
        final var elements = document.getElementsByTagNameNS(BPMNDI, kind);
        for (var index = 0; index < elements.getLength(); index++) {
            final var element = (Element) elements.item(index);
            if (element
                    .getElementsByTagNameNS(childNamespace, child)
                    .getLength() >= atLeast) {
                drawn.add(element.getAttribute("bpmnElement"));
            }
        }
        return drawn;
    }

    private static List<Element> bpmnElements(
        final Document document,
        final String localName
    ) {
        final var found = new ArrayList<Element>();
        final var elements = document.getElementsByTagNameNS(BPMN, localName);
        for (var index = 0; index < elements.getLength(); index++) {
            found.add((Element) elements.item(index));
        }
        return found;
    }

    private static List<Element> all(final Element root) {
        final var found = new ArrayList<Element>();
        found.add(root);
        for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                found.addAll(all((Element) child));
            }
        }
        return found;
    }

    private static Set<String> union(
        final Set<String> first,
        final Set<String> second
    ) {
        final var union = new HashSet<>(first);
        union.addAll(second);
        return union;
    }

}
