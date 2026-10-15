/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The deployment service of this version of the adapter: it says who it is, reports health, and
 * refuses every step of the pipeline with words which say why, instead of pretending to deploy.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDeploymentServiceTest {

    private static final String NOT_CONFIGURED = "ZenBPM adapter 'orders' is not configured: set the key";

    private static final String DESCRIPTOR = "META-INF/vanillabp/adapter-zenbpm.properties";

    private static final String PARTS_PACKAGE = "io.vanillabp.integration.spi.parts.";

    @Test
    @DisplayName("The deployment service names its adapter id, its type, its model and context types and its collaborators")
    void theDeploymentServiceSaysWhoItIs() {
        final var collaborators = TestCollaborators.of("orders");
        final var deploymentService = new ZenBpmDeploymentService("orders", unconfigured("orders"), collaborators);

        assertAll(
                () -> assertEquals("orders", deploymentService.getAdapterId()),
                () -> assertEquals("zenbpm", deploymentService.getAdapterType()),
                () -> assertSame(ZenBpmBpmnModel.class, deploymentService.getModelType()),
                () -> assertSame(ZenBpmProcessingContext.class, deploymentService.getProcessContextType()),
                () -> assertSame(collaborators, deploymentService.collaborators()));
    }

    @Test
    @DisplayName("The startup report is the one the platform module hands over")
    void theStartupReportIsTheOneHandedOver() {
        final var deploymentService = new ZenBpmDeploymentService("orders", unconfigured("orders"), TestCollaborators
                .of("orders"));
        final StartupReport report = new StartupReport() {

            @Override
            public void notice(
                final StartupTopic topic,
                final String scope,
                final String message
            ) {
            }

            @Override
            public void warn(
                final StartupTopic topic,
                final String scope,
                final String message
            ) {
            }

            @Override
            public void error(
                final StartupTopic topic,
                final String scope,
                final String message
            ) {
            }

            @Override
            public void refuse(
                final StartupTopic topic,
                final String scope,
                final String message
            ) {
            }

        };
        final var before = deploymentService.getStartupReport();

        deploymentService.setStartupReport(report);

        assertAll(
                () -> assertNull(before),
                () -> assertSame(report, deploymentService.getStartupReport()));
    }

    @Test
    @DisplayName("A factory or collaborators of another adapter id are refused, naming all three ids")
    void theThreeOfOneAdapterIdAreWiredTogether() {
        final var otherFactory = assertThrows(IllegalArgumentException.class, () -> new ZenBpmDeploymentService(
                "orders", unconfigured("billing"), TestCollaborators.of("orders")));
        final var otherCollaborators = assertThrows(IllegalArgumentException.class, () -> new ZenBpmDeploymentService(
                "orders", unconfigured("orders"), TestCollaborators.of("billing")));

        assertAll(
                () -> assertEquals(
                        "The deployment service of ZenBPM adapter id 'orders' was given the factory of adapter id 'billing' and the collaborators of adapter id 'orders'; the platform module wires the three of one id.",
                        otherFactory.getMessage()),
                () -> assertTrue(otherCollaborators
                        .getMessage()
                        .contains("the collaborators of adapter id 'billing'"), otherCollaborators.getMessage()));
    }

    @Test
    @DisplayName("Every step of the pipeline refuses with the message of the validation where the adapter id has no clients")
    void anAdapterIdWithoutClientsRefusesWithItsValidationMessage() {
        final var deploymentService = new ZenBpmDeploymentService("orders", unconfigured("orders"), TestCollaborators
                .of("orders"));

        assertAll(steps()
                .stream()
                .map(step -> () -> assertEquals(NOT_CONFIGURED, assertThrows(IllegalStateException.class,
                        () -> step.accept(deploymentService)).getMessage())));
    }

    @Test
    @DisplayName("Every step of the pipeline of a configured adapter id says that it is not built yet, and what it was asked to do")
    void aConfiguredAdapterIdSaysThatTheStepIsNotBuiltYet() {
        try (var factory = configured("orders")) {
            final var deploymentService = new ZenBpmDeploymentService("orders", factory, TestCollaborators.of(
                    "orders"));

            final var messages = steps()
                    .stream()
                    .map(step -> assertThrows(UnsupportedOperationException.class, () -> step.accept(
                            deploymentService)).getMessage())
                    .toList();

            assertEquals(List.of(
                    "ZenBPM adapter 'orders': reading BPMN file 'order.bpmn' of workflow module 'shop' is implemented in a later story",
                    "ZenBPM adapter 'orders': preparing BPMN process 'Order' of workflow module 'shop' is implemented in a later story",
                    "ZenBPM adapter 'orders': wiring BPMN process 'Order' of workflow module 'shop' is implemented in a later story",
                    "ZenBPM adapter 'orders': deploying workflow module 'shop' is implemented in a later story",
                    "ZenBPM adapter 'orders': starting the workflow processing of workflow module 'shop' is implemented in a later story"),
                    messages);
        }
    }

    @Test
    @DisplayName("Health is what the engine's readiness says: an adapter id without clients is UNKNOWN with its message")
    void healthIsAskedOfTheFactory() {
        final var deploymentService = new ZenBpmDeploymentService("orders", unconfigured("orders"), TestCollaborators
                .of("orders"));

        final var health = deploymentService.checkHealth();

        assertAll(
                () -> assertEquals(AdapterHealth.Status.UNKNOWN, health.status()),
                () -> assertEquals("orders", health.adapterId()),
                () -> assertEquals(NOT_CONFIGURED, health.description()));
    }

    @Test
    @DisplayName("An adapter built against a newer platform integration than the one on the classpath ends the start")
    void anAdapterWhichDoesNotFitThePlatformEndsTheStart(@TempDir final Path directory) throws IOException, ReflectiveOperationException {
        final var descriptor = directory.resolve("adapter-zenbpm.properties");
        Files.writeString(descriptor, """
                part.version=2.0.0-SNAPSHOT
                part.artifact=org.pbinitiative.zenbpm:zenbpm-vanillabp-adapter
                platform.version=99.0.0
                """);
        final var isolated = new WithAnotherDescriptor(getClass().getClassLoader(), descriptor
                .toUri()
                .toURL());
        final var deploymentServiceType = isolated.loadClass(ZenBpmDeploymentService.class.getName());
        final var constructor = deploymentServiceType.getConstructor(String.class, ZenBpmClientFactory.class,
                AdapterCollaborators.class);

        final var failure = assertThrows(InvocationTargetException.class, () -> constructor.newInstance("orders",
                unconfigured("orders"), TestCollaborators.of("orders")));

        final var cause = assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(cause
                .getMessage()
                .contains("was built against the VanillaBP platform integration 99.0.0"), cause.getMessage());
    }

    private static List<Consumer<ZenBpmDeploymentService>> steps() {
        return List.of(
                deploymentService -> deploymentService.readBpmn("shop", "order.bpmn", new ByteArrayInputStream(
                        new byte[0]), true),
                deploymentService -> deploymentService.prepareBpmn("shop", null, "order.bpmn", "Order", null),
                deploymentService -> deploymentService.wireBpmn("shop", "order.bpmn", "Order", null, null),
                deploymentService -> deploymentService.deployResources("shop", null),
                deploymentService -> deploymentService.startWorkflowProcessing("shop", null));
    }

    private static ZenBpmClientFactory unconfigured(final String adapterId) {
        return ZenBpmClientFactory.unconfigured(adapterId, new ZenBpmAdapterConfiguration(), NOT_CONFIGURED);
    }

    private static ZenBpmClientFactory configured(final String adapterId) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress("http://localhost:8080");
        return new ZenBpmClientFactory(adapterId, configuration, () -> "shop");
    }

    /**
     * A class loader which answers the adapter's descriptor with another file, like an application
     * whose adapter jar was built against another platform. It loads the deployment service itself,
     * so the descriptor is read through it, and the platform's check of the parts, which judges a
     * part once per class loader.
     */
    private static final class WithAnotherDescriptor extends ClassLoader {

        private final URL descriptor;

        private WithAnotherDescriptor(
            final ClassLoader parent,
            final URL descriptor
        ) {
            super(parent);
            this.descriptor = descriptor;
        }

        @Override
        public URL getResource(final String name) {
            return DESCRIPTOR.equals(name) ? descriptor : super.getResource(name);
        }

        @Override
        protected Class<?> loadClass(
            final String name,
            final boolean resolve
        ) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (!name.equals(ZenBpmDeploymentService.class.getName()) && !name.startsWith(PARTS_PACKAGE)) {
                    return super.loadClass(name, resolve);
                }
                final var loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                try (InputStream bytes = getParent().getResourceAsStream(name.replace('.', '/')
                        + ".class")) {
                    final var code = bytes.readAllBytes();
                    // with the code source of the original, which the coverage agent needs
                    return defineClass(name, code, 0, code.length, getParent()
                            .loadClass(name)
                            .getProtectionDomain());
                } catch (final IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }

    }

}
