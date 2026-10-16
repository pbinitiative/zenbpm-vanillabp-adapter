/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The build step which refuses a protobuf runtime that cannot run the job stream. Every boot test
 * of this module shows that the build's own runtime passes; this test shows the refusal, with the
 * step loaded where protobuf has no <code>RuntimeVersion</code>, as in an application on a runtime
 * older than 4.26.0.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmIntegrationProcessorTest {

    private static final String STEP = "theProtobufRuntimeCanRunTheJobStream";

    @Test
    @DisplayName("The build's own protobuf runtime passes, and the step reports nothing")
    void theBuildsOwnRuntimePasses() {
        final var errors = new Recorded();

        new ZenBpmIntegrationProcessor().theProtobufRuntimeCanRunTheJobStream(errors);

        assertEquals(List.of(), errors.items);
    }

    @Test
    @DisplayName("A runtime without RuntimeVersion fails the build of the application with the check's message")
    void aRuntimeWithoutRuntimeVersionFailsTheBuild() throws ReflectiveOperationException {
        final var isolated = new WithoutRuntimeVersion(getClass().getClassLoader()).loadClass(
                ZenBpmIntegrationProcessor.class.getName());
        final var constructor = isolated.getDeclaredConstructor();
        constructor.setAccessible(true);
        final var step = isolated.getDeclaredMethod(STEP, BuildProducer.class);
        step.setAccessible(true);
        final var errors = new Recorded();

        step.invoke(constructor.newInstance(), errors);

        assertEquals(1, errors.items.size(), errors.items::toString);
        final var refusal = assertInstanceOf(IllegalStateException.class, errors.items
                .getFirst()
                .getValues()
                .getFirst());
        assertAll(
                () -> assertTrue(refusal
                        .getMessage()
                        .startsWith(
                                "The protobuf runtime of the application has no 'com.google.protobuf.RuntimeVersion'"),
                        refusal.getMessage()),
                () -> assertTrue(refusal
                        .getMessage()
                        .contains("Raise 'com.google.protobuf:protobuf-java' to"), refusal.getMessage()));
    }

    @Test
    @DisplayName("The check is a build step, so Quarkus runs it while it builds the application")
    void theCheckIsABuildStep() throws NoSuchMethodException {
        assertNotNull(ZenBpmIntegrationProcessor.class
                .getDeclaredMethod(STEP, BuildProducer.class)
                .getAnnotation(BuildStep.class));
    }

    private static final class Recorded implements BuildProducer<ValidationErrorBuildItem> {

        private final List<ValidationErrorBuildItem> items = new ArrayList<>();

        @Override
        public void produce(final ValidationErrorBuildItem item) {
            items.add(item);
        }

    }

}
