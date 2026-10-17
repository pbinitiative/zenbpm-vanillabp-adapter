/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The bytes a BPMN file is deployed with: those it was read from where the pipeline changed nothing,
 * its document written out where it did.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmModelWriterTest {

    @Test
    @DisplayName("A file the pipeline left as it was written is deployed as the bytes it was read from")
    void anUnchangedFileIsDeployedAsItsBytes() {
        final var file = fixture("version-1.bpmn");
        final var model = read(file);

        assertArrayEquals(file, ZenBpmModelWriter.bytesOf(model));
    }

    @Test
    @DisplayName("A file the pipeline rewrote is written out as UTF-8 without indentation, and reads back as the rewritten document")
    void aRewrittenFileIsWrittenOut() {
        final var model = read(fixture("version-1.bpmn"));
        model
                .document()
                .getDocumentElement()
                .setAttribute("id", "Rewritten");

        final var bytes = ZenBpmModelWriter.bytesOf(model);

        final var text = new String(bytes, StandardCharsets.UTF_8);
        assertAll(
                () -> assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><bpmn:definitions "), text),
                () -> assertTrue(text.contains("id=\"Rewritten\""), text),
                () -> assertFalse(text.contains("standalone"), text),
                () -> assertTrue(read(bytes)
                        .document()
                        .isEqualNode(model.document())));
    }

    private static ZenBpmBpmnModel read(final byte[] file) {
        return ZenBpmModelReader
                .read("shop", "version-1.bpmn", new ByteArrayInputStream(file))
                .getFirst();
    }

    private static byte[] fixture(final String name) {
        try (var file = ZenBpmModelWriterTest.class.getResourceAsStream("/models/%s".formatted(name))) {
            return file.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
