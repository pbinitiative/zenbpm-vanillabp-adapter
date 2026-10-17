/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.w3c.dom.ls.DOMImplementationLS;

/**
 * The bytes a BPMN file reaches the engine with.
 * <p>
 * A file the pipeline left as it was written is deployed as the bytes it was read from, so a model
 * which carries everything the adapter would add reaches the engine byte for byte as the modeller
 * saved it. Writing its document out again would not: the serializer drops the line breaks between
 * attributes and puts the namespace declarations in an order of its own. A file the pipeline
 * rewrote is written out as UTF-8, without indentation. The engine tells an unchanged deployment from
 * a new version by comparing the content after normalising the formatting, so the same file gives
 * the same bytes on every start, and an unchanged model no new version.
 */
public final class ZenBpmModelWriter {

    private ZenBpmModelWriter() {
    }

    /**
     * The bytes to deploy for a model.
     *
     * @param model The model as the pipeline left it
     * @return The bytes it was read from where nothing was changed, its document written out
     *         otherwise
     */
    public static byte[] bytesOf(final ZenBpmBpmnModel model) {
        if (ZenBpmModelReader.isAsWritten(model)) {
            return model.content();
        }
        final var document = model.document();
        final var implementation = (DOMImplementationLS) document.getImplementation();
        final var bytes = new ByteArrayOutputStream();
        final var output = implementation.createLSOutput();
        output.setEncoding(StandardCharsets.UTF_8.name());
        output.setByteStream(bytes);
        implementation
                .createLSSerializer()
                .write(document, output);
        return bytes.toByteArray();
    }

}
