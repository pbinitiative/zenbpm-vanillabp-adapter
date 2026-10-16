/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import javax.xml.XMLConstants;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Writes what the pipeline adds to a model in the <code>zenbpm</code> namespace: an extension
 * element below an element's <code>extensionElements</code>, and the input mappings which carry a
 * variable into a multi-instance body.
 * <p>
 * The engine runs the body of a multi-instance element in a scope of its own, which starts with the
 * item and nothing else, and a scope inside it copies the variables of the scope around it only.
 * So a variable of the workflow reaches an element inside the body only where every
 * multi-instance element around it maps it in with an input mapping, which the engine evaluates
 * in the scope around that element.
 */
final class ZenBpmInputMappings {

    /**
     * The namespace of the engine's own extension elements.
     */
    static final String ZENBPM = "http://zenbpm.pbinitiative.org/1.0";

    private static final String EXTENSION_ELEMENTS = "extensionElements";

    private ZenBpmInputMappings() {
    }

    /**
     * Carries variables into the body of a multi-instance element: one input mapping
     * <code>source="=&lt;name&gt;" target="&lt;name&gt;"</code> per name the element does not map
     * to already, in its first <code>ioMapping</code>, or in a new one.
     *
     * @param element The multi-instance element
     * @param names The names of the variables
     */
    static void carry(
        final Element element,
        final Collection<String> names
    ) {
        final var mapped = new HashSet<String>();
        for (final var ioMapping : ZenBpmModelReader.extensions(element, "ioMapping")) {
            for (var child = ioMapping.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element input && "input".equals(input.getLocalName())) {
                    mapped.add(input.getAttribute("target"));
                }
            }
        }
        final var missing = names
                .stream()
                .filter(name -> !mapped.contains(name))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        final var ioMapping = ZenBpmModelReader
                .extension(element, "ioMapping")
                .orElseGet(() -> addExtension(element, "ioMapping"));
        final var firstOutput = firstChild(ioMapping, "output");
        for (final var name : missing) {
            final var input = element
                    .getOwnerDocument()
                    .createElementNS(ioMapping.getNamespaceURI(), qualified(ioMapping.getPrefix(), "input"));
            input.setAttribute("source", "=%s".formatted(name));
            input.setAttribute("target", name);
            ioMapping.insertBefore(input, firstOutput);
        }
    }

    /**
     * Adds an extension element of the engine's namespace to an element, into its
     * <code>extensionElements</code>, which is added where the element has none.
     *
     * @param element The BPMN element
     * @param localName The local name of the extension element
     * @return The new extension element
     */
    static Element addExtension(
        final Element element,
        final String localName
    ) {
        final var document = element.getOwnerDocument();
        final var extension = document.createElementNS(ZENBPM, qualified(prefixOfTheEngine(document), localName));
        extensionElementsOf(element).appendChild(extension);
        return extension;
    }

    /**
     * The BPMN elements of a file which have an id, by their id.
     *
     * @param document The file
     * @return The elements, the first one where two share an id
     */
    static Map<String, Element> elementsById(final Document document) {
        final var elements = new HashMap<String, Element>();
        final var found = document.getElementsByTagNameNS(ZenBpmModelReader.BPMN, "*");
        for (var index = 0; index < found.getLength(); ++index) {
            final var element = (Element) found.item(index);
            if (element.hasAttribute("id")) {
                elements.putIfAbsent(element.getAttribute("id"), element);
            }
        }
        return elements;
    }

    // the element's own extensionElements, or a new one in front of everything but its
    // documentation, where the BPMN schema puts it
    private static Element extensionElementsOf(final Element element) {
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element existing && EXTENSION_ELEMENTS
                    .equals(existing.getLocalName()) && ZenBpmModelReader.BPMN.equals(existing.getNamespaceURI())) {
                return existing;
            }
        }
        final var extensionElements = element
                .getOwnerDocument()
                .createElementNS(ZenBpmModelReader.BPMN, qualified(element.getPrefix(), EXTENSION_ELEMENTS));
        var before = element.getFirstChild();
        while (before != null && (!(before instanceof Element) || "documentation".equals(before.getLocalName()))) {
            before = before.getNextSibling();
        }
        element.insertBefore(extensionElements, before);
        return extensionElements;
    }

    // the prefix the file binds the engine's namespace to on its root, declared there where it
    // binds none: 'zenbpm', or 'zenbpm' and a number where the file uses 'zenbpm' otherwise
    private static String prefixOfTheEngine(final Document document) {
        final var root = document.getDocumentElement();
        final var declared = root.lookupPrefix(ZENBPM);
        if (declared != null) {
            return declared;
        }
        var prefix = "zenbpm";
        for (var number = 1; root.lookupNamespaceURI(prefix) != null; ++number) {
            prefix = "zenbpm%d".formatted(number);
        }
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "%s:%s".formatted(XMLConstants.XMLNS_ATTRIBUTE,
                prefix), ZENBPM);
        return prefix;
    }

    private static Element firstChild(
        final Node parent,
        final String localName
    ) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String qualified(
        final String prefix,
        final String localName
    ) {
        return prefix == null ? localName : "%s:%s".formatted(prefix, localName);
    }

}
