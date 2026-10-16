/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.TestWiring;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelReader;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The deployment service of this version of the adapter: it says who it is, reports health, reads
 * and prepares BPMN files whether or not its adapter id has clients, and refuses every later step
 * of the pipeline with words which say why, instead of pretending to deploy.
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
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), collaborators);

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
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
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
    @DisplayName("Collaborators of another adapter id, or a registry without the adapter id, are refused, naming the ids")
    void theServiceIsWiredToItsOwnAdapterId() {
        final var otherCollaborators = assertThrows(IllegalArgumentException.class, () -> new ZenBpmDeploymentService(
                "orders", withoutClients("orders"), TestCollaborators.of("billing")));
        final var otherRegistry = assertThrows(IllegalStateException.class, () -> new ZenBpmDeploymentService(
                "orders", withoutClients("billing"), TestCollaborators.of("orders")));

        assertAll(
                () -> assertEquals(
                        "The deployment service of ZenBPM adapter id 'orders' was given the collaborators of adapter id 'billing'; the platform module wires the collaborators of one id.",
                        otherCollaborators.getMessage()),
                () -> assertTrue(otherRegistry
                        .getMessage()
                        .startsWith(
                                "No ZenBPM adapter with id 'orders' is configured; the configured ids of type 'zenbpm' are 'billing'."),
                        otherRegistry.getMessage()));
    }

    @Test
    @DisplayName("A BPMN file is read without an engine: an adapter id without clients reads it like a configured one, under its process id")
    void aBpmnFileIsReadWithoutAnEngine() {
        try (var registry = new ZenBpmClientRegistry(List.of(configured("orders", "http://localhost:8080")))) {
            final var configured = new ZenBpmDeploymentService("orders", registry, TestCollaborators.of("orders"));
            final var withoutClients = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                    .of("orders"));

            final var readByTheConfigured = configured.readBpmn("shop", "order.bpmn", bpmn(), true);
            final var readWithoutClients = withoutClients.readBpmn("shop", "order.bpmn", bpmn(), true);

            assertAll(
                    () -> assertEquals(1, readByTheConfigured.size()),
                    () -> assertEquals("Order", readByTheConfigured
                            .getFirst()
                            .getKey()),
                    () -> assertEquals("Order", readByTheConfigured
                            .getFirst()
                            .getValue()
                            .bpmnProcessId()),
                    () -> assertEquals(List.of("Order"), readWithoutClients
                            .stream()
                            .map(Map.Entry::getKey)
                            .toList()));
        }
    }

    @Test
    @DisplayName("A BPMN file the engine would misread is refused by an adapter id without clients as well, before its configuration is asked")
    void theChecksOfReadingSpeakBeforeTheConfiguration() {
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders"));

        final var failure = assertThrows(BpmnParseException.class, () -> deploymentService.readBpmn("shop",
                "order.bpmn", new ByteArrayInputStream("""
                        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="order">
                          <bpmn:process id="Order" isExecutable="true" />
                          <bpmn:process id="Partner" isExecutable="false" />
                        </bpmn:definitions>
                        """.getBytes(StandardCharsets.UTF_8)), true));

        assertTrue(failure
                .getMessage()
                .startsWith(
                        "BPMN file 'order.bpmn' of workflow module 'shop' holds the processes 'Order' and 'Partner',"),
                failure.getMessage());
    }

    @Test
    @DisplayName("Preparing needs no engine: the first process of a module gets a new context, a later one the context it was given")
    void preparingKeepsOneContextPerModule() {
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", TestScoping.of(NameClashAvoidance.NONE), claiming(new AtomicInteger())));
        final var model = deploymentService
                .readBpmn("shop", "order.bpmn", bpmn(), true)
                .getFirst()
                .getValue();

        final var first = deploymentService.prepareBpmn("shop", null, "order.bpmn", "Order", model);
        final var later = deploymentService.prepareBpmn("shop", first, "invoice.bpmn", "Invoice", model);

        assertAll(
                () -> assertEquals("shop", first.workflowModuleId()),
                () -> assertSame(first, later),
                () -> assertEquals(List.of("Order", "Invoice"), first.processes()));
    }

    @Test
    @DisplayName("Preparing refuses a file which uses what ZenBPM cannot run, asking once per file whether a workflow service claims its process")
    void preparingRefusesWhatZenBpmCannotRunOncePerFile() {
        final var claimsAsked = new AtomicInteger();
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", TestScoping.of(NameClashAvoidance.USE_PREFIX), claiming(claimsAsked)));
        final var model = deploymentService
                .readBpmn("shop", "order.bpmn", new ByteArrayInputStream("""
                        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="order">
                          <bpmn:process id="Order" isExecutable="true">
                            <bpmn:receiveTask id="Received" instantiate="true" />
                          </bpmn:process>
                        </bpmn:definitions>
                        """.getBytes(StandardCharsets.UTF_8)), true)
                .getFirst()
                .getValue();

        final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.prepareBpmn("shop",
                null, "order.bpmn", "Order", model));
        final var context = new ZenBpmProcessingContext("shop");
        context.firstPreparationOf("order.bpmn");
        final var again = deploymentService.prepareBpmn("shop", context, "order.bpmn", "Order", model);

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .startsWith("BPMN file 'order.bpmn' of workflow module 'shop' uses what ZenBPM cannot run:"),
                        failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .contains("Order/Received (receiveTask with instantiate=\"true\")"), failure.getMessage()),
                () -> assertSame(context, again),
                () -> assertEquals(1, claimsAsked.get()));
    }

    @Test
    @DisplayName("Under use-prefix a file is scoped once, however many of its processes are prepared")
    void preparingScopesAFileOnce() {
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", TestScoping.of(NameClashAvoidance.USE_PREFIX), claiming(new AtomicInteger())));
        final var model = deploymentService
                .readBpmn("shop", "order.bpmn", bpmn(), true)
                .getFirst()
                .getValue();

        final var context = deploymentService.prepareBpmn("shop", null, "order.bpmn", "Order", model);
        deploymentService.prepareBpmn("shop", context, "order.bpmn", "Order", model);

        assertEquals("shop__Order", processIdOf(model));
    }

    @Test
    @DisplayName("ZenBPM keeps the adapter SPI's default 'by-adapter', which the deployment refuses")
    void theDefaultModeIsByAdapter() {
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders"));

        assertEquals(NameClashAvoidance.BY_ADAPTER, deploymentService.defaultNameClashAvoidance());
    }

    @Test
    @DisplayName("'by-adapter' for the workflow module ends preparing with the platform's message naming both ways out, before the file is rewritten, and after the constructs ZenBPM cannot run")
    void byAdapterIsRefused() {
        final var scoping = TestScoping.of(NameClashAvoidance.BY_ADAPTER);
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", scoping, claiming(new AtomicInteger())));
        final var model = deploymentService
                .readBpmn("shop", "order.bpmn", bpmn(), true)
                .getFirst()
                .getValue();
        final var withAScriptTask = deploymentService
                .readBpmn("shop", "script.bpmn", new ByteArrayInputStream("""
                        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="script">
                          <bpmn:process id="Script" isExecutable="true">
                            <bpmn:scriptTask id="Calculate" />
                          </bpmn:process>
                        </bpmn:definitions>
                        """.getBytes(StandardCharsets.UTF_8)), true)
                .getFirst()
                .getValue();

        final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.prepareBpmn("shop",
                null, "order.bpmn", "Order", model));
        final var constructsFirst = assertThrows(IllegalStateException.class, () -> deploymentService.prepareBpmn(
                "shop", null, "script.bpmn", "Script", withAScriptTask));

        assertAll(
                () -> assertEquals(
                        """
                                ZenBPM has no isolation mechanism of its own, so the name-clash-avoidance mode 'by-adapter' \
                                cannot be served by adapter 'orders' - but it applies to workflow module 'shop'. Choose explicitly:
                                  vanillabp.adapters.orders.name-clash-avoidance: use-prefix
                                  vanillabp.adapters.orders.name-clash-avoidance: none""",
                        failure.getMessage()),
                () -> assertEquals(List.of("validateNativeIsolationSupported(orders, shop, ZenBPM)"), scoping.calls()),
                () -> assertEquals("Order", processIdOf(model)),
                () -> assertTrue(constructsFirst
                        .getMessage()
                        .startsWith("BPMN file 'script.bpmn' of workflow module 'shop' uses what ZenBPM cannot run:"),
                        constructsFirst.getMessage()));
    }

    @Test
    @DisplayName("'by-adapter' set for one workflow ends preparing, naming the workflow's key and both ways out")
    void byAdapterOfAWorkflowIsRefused() {
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", TestScoping
                        .of(NameClashAvoidance.USE_PREFIX)
                        .withWorkflow("shop", "Order", NameClashAvoidance.BY_ADAPTER), claiming(new AtomicInteger())));
        final var model = deploymentService
                .readBpmn("shop", "order.bpmn", bpmn(), true)
                .getFirst()
                .getValue();

        final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.prepareBpmn("shop",
                null, "order.bpmn", "Order", model));

        assertAll(
                () -> assertEquals(
                        """
                                ZenBPM has no isolation mechanism of its own, so the name-clash-avoidance mode 'by-adapter' \
                                cannot be served by adapter 'orders' - but it is configured for workflow 'Order' of workflow \
                                module 'shop'. Choose explicitly:
                                  vanillabp.workflow-modules.shop.workflows.Order.adapters.orders.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers
                                  vanillabp.workflow-modules.shop.workflows.Order.adapters.orders.name-clash-avoidance: none         # your identifiers are unique already
                                or remove the key, so the mode of the workflow module applies.""",
                        failure.getMessage()),
                () -> assertEquals("Order", processIdOf(model)));
    }

    @Test
    @DisplayName("'none' warns, naming what ZenBPM offers instead - use-prefix or an engine per workflow module - and the key which accepts unscoped identifiers")
    void noneWarnsNamingTheAlternatives(final CapturedOutput output) {
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders"));

        deploymentService.warnAboutUnscopedIdentifiers("shop", false);

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertTrue(log.contains(
                        "WARN  o.p.z.d.ZenBpmDeploymentService - Workflow module 'shop' is deployed to ZenBPM (adapter 'orders') with name-clash-avoidance 'none'."),
                        log),
                () -> assertTrue(log.contains("ZenBPM has no tenants, so 'by-adapter' is no choice; choose instead:\n"
                        + "  vanillabp.adapters.orders.name-clash-avoidance: use-prefix"), log),
                () -> assertTrue(log.contains(
                        "an adapter id of its own with its own vanillabp.adapters.<id>.rest-address"), log),
                () -> assertTrue(log.contains("(vanillabp.workflow-modules.shop.adapters.orders.name-clash-avoidance)"),
                        log),
                () -> assertTrue(log.contains("  vanillabp.adapters.orders.accept-unscoped-identifiers: true\n"), log),
                () -> assertTrue(log.contains(
                        "  vanillabp.workflow-modules.shop.adapters.orders.accept-unscoped-identifiers: true"), log));
    }

    @ParameterizedTest
    @MethodSource("acceptingConfigurations")
    @DisplayName("accept-unscoped-identifiers turns the warning into a DEBUG line naming the key which accepted 'none'; the workflow module's key wins over the adapter's")
    void acceptedUnscopedIdentifiersAreNotWarnedAbout(
        final Boolean ofTheAdapter,
        final Boolean ofTheModule,
        final String acceptedBy,
        final CapturedOutput output
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setAcceptUnscopedIdentifiers(ofTheAdapter);
        configuration.setAcceptUnscopedIdentifiers("shop", ofTheModule);
        final var deploymentService = new ZenBpmDeploymentService("orders", new ZenBpmClientRegistry(List.of(
                ZenBpmClientFactory.unconfigured("orders", configuration, NOT_CONFIGURED))), TestCollaborators.of(
                        "orders"));

        atDebug(() -> deploymentService.warnAboutUnscopedIdentifiers("shop", false));

        final var log = output.getAllOfThisTest();
        if (acceptedBy == null) {
            assertTrue(log.contains("WARN  o.p.z.d.ZenBpmDeploymentService - Workflow module 'shop'"), log);
            return;
        }
        assertAll(
                () -> assertTrue(log.contains(
                        "DEBUG o.p.z.d.ZenBpmDeploymentService - ZenBPM[orders]: workflow module 'shop' is deployed with name-clash-avoidance 'none', accepted by '%s'"
                                .formatted(acceptedBy)),
                        log),
                () -> assertFalse(log.contains("WARN"), log));
    }

    @Test
    @DisplayName("A DMN file is kept for the deployment: under use-prefix with the ids of its decisions and the references to them scoped by the workflow module, otherwise as it was read")
    void aDmnFileIsKeptWithItsDecisionIdsScoped() {
        final var prefixing = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators.of(
                "orders", TestScoping.of(NameClashAvoidance.USE_PREFIX)));
        final var plain = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators.of(
                "orders", TestScoping.of(NameClashAvoidance.NONE)));
        final var context = new ZenBpmProcessingContext("shop");

        assertSame(context, prefixing.readDmn("shop", context, "rating.dmn", dmn()));
        plain.readDmn("shop", context, "rating-as-read.dmn", dmn());

        final var scoped = new String(context
                .decisions()
                .get("rating.dmn"), StandardCharsets.UTF_8);
        assertAll(
                () -> assertTrue(scoped.contains("<decision id=\"shop__rating\""), scoped),
                () -> assertTrue(scoped.contains("<requiredDecision href=\"#shop__risk\""), scoped),
                () -> assertTrue(scoped.contains("<decision id=\"shop__risk\""), scoped),
                () -> assertEquals(DMN, new String(context
                        .decisions()
                        .get("rating-as-read.dmn"), StandardCharsets.UTF_8)),
                () -> assertEquals(List.of("rating.dmn", "rating-as-read.dmn"), List.copyOf(context
                        .decisions()
                        .keySet())));
    }

    @Test
    @DisplayName("A DMN file which is no XML ends reading it under use-prefix, naming file and workflow module")
    void aDmnFileWhichIsNoXmlIsRefusedWhereItsIdsAreScoped() {
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", TestScoping.of(NameClashAvoidance.USE_PREFIX)));

        final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.readDmn("shop",
                new ZenBpmProcessingContext("shop"), "rating.dmn", new ByteArrayInputStream("no xml"
                        .getBytes(StandardCharsets.UTF_8))));

        assertTrue(failure
                .getMessage()
                .startsWith("DMN file 'rating.dmn' of workflow module 'shop' cannot be read: "), failure.getMessage());
    }

    @Test
    @DisplayName("Two files of one workflow module declaring the same process id end the preparation of the second, naming both files, before it is rewritten; the same file prepared again is no second declaration")
    void twoFilesDeclaringOneProcessAreRefused() {
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", TestScoping.of(NameClashAvoidance.USE_PREFIX), TestWiring
                        .claiming("Order")
                        .wiring()));
        final var first = read(deploymentService, "order.bpmn", WAITING_FOR_PAYMENT);
        final var second = read(deploymentService, "order-copy.bpmn", WAITING_FOR_PAYMENT);
        final var context = deploymentService.prepareBpmn("shop", null, "order.bpmn", "Order", first);
        deploymentService.prepareBpmn("shop", context, "order.bpmn", "Order", first);

        final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.prepareBpmn("shop",
                context, "order-copy.bpmn", "Order", second));

        assertAll(
                () -> assertEquals("""
                        Workflow module 'shop' declares BPMN process 'Order' in two files, 'order.bpmn' and \
                        'order-copy.bpmn'. ZenBPM keeps one definition per process id, so the later file would \
                        replace the earlier one as a new version of it. Keep the process in one file, or give one \
                        of the two processes another id.""", failure.getMessage()),
                () -> assertEquals("Order", processIdOf(second)),
                () -> assertEquals(List.of("Order"), context.processes()));
    }

    @Test
    @DisplayName("Deploying first hands the module's processes to the platform's check for colliding process ids, before anything else")
    void deployingChecksForCollidingProcessIdsFirst() {
        final var scoping = TestScoping.of(NameClashAvoidance.USE_PREFIX);
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", scoping));
        final var context = new ZenBpmProcessingContext("shop");
        context.recordProcess("Order", "order.bpmn");
        context.recordProcess("Invoice", "invoice.bpmn");

        final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.deployResources("shop",
                context));

        assertAll(
                () -> assertEquals(List.of(
                        "validateNoCollidingProcessIds(orders, [DeployedProcess[workflowModuleId=shop, bpmnProcessId=Order], DeployedProcess[workflowModuleId=shop, bpmnProcessId=Invoice]])"),
                        scoping.calls()),
                () -> assertEquals(NOT_CONFIGURED, failure.getMessage()));
    }

    @Test
    @DisplayName("A claimed process gets the aggregate id as the key of every message it waits for, once per file, asking the platform for the name once")
    void aClaimedProcessGetsItsCorrelationKeysOnce() {
        final var wiring = TestWiring.claiming("Order");
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", TestScoping.of(NameClashAvoidance.USE_PREFIX), wiring.wiring()));
        final var model = read(deploymentService, "order.bpmn", WAITING_FOR_PAYMENT);

        final var context = deploymentService.prepareBpmn("shop", null, "order.bpmn", "Order", model);
        deploymentService.prepareBpmn("shop", context, "order.bpmn", "Order", model);

        assertAll(
                () -> assertEquals(List.of("=orderId"), correlationKeysOf(model)),
                () -> assertEquals(List.of("isClaimedByAWorkflowService[shop, Order]",
                        "resolveWorkflowAggregateIdName[shop, Order]"), wiring.calls()),
                () -> assertEquals("shop__paid", messageNameOf(model)),
                () -> assertEquals(Map.of("paid", "Order/AwaitPayment"), context.awaitedMessages()),
                () -> assertEquals(Map.of(), context.startingMessages()),
                () -> assertThrows(UnsupportedOperationException.class, () -> context
                        .awaitedMessages()
                        .clear()));
    }

    @Test
    @DisplayName("A process nobody claims is scoped with its file, and gets no key: somebody else correlates its messages")
    void aProcessNobodyClaimsGetsNoKey() {
        final var wiring = TestWiring.claiming();
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", TestScoping.of(NameClashAvoidance.USE_PREFIX), wiring.wiring()));
        final var model = read(deploymentService, "order.bpmn", WAITING_FOR_PAYMENT);

        final var context = deploymentService.prepareBpmn("shop", null, "order.bpmn", "Order", model);

        assertAll(
                () -> assertEquals(List.of(), correlationKeysOf(model)),
                () -> assertEquals("shop__Order", processIdOf(model)),
                () -> assertEquals(List.of("isClaimedByAWorkflowService[shop, Order]"), wiring.calls()),
                () -> assertEquals(Map.of(), context.awaitedMessages()));
    }

    @Test
    @DisplayName("A message one file of the module starts a workflow with, and which a claimed workflow of another file waits for, ends preparing that file before it is rewritten")
    void aMessageWhichStartsAndIsAwaitedEndsPreparing() {
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", TestScoping.of(NameClashAvoidance.USE_PREFIX), TestWiring
                        .claiming("Order")
                        .wiring()));
        final var waiting = read(deploymentService, "order.bpmn", WAITING_FOR_PAYMENT);
        final var starting = read(deploymentService, "payment.bpmn", """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="payment">
                  <bpmn:message id="Message_Paid" name="paid" />
                  <bpmn:process id="Payment" isExecutable="true">
                    <bpmn:startEvent id="PaymentReceived">
                      <bpmn:messageEventDefinition messageRef="Message_Paid" />
                    </bpmn:startEvent>
                  </bpmn:process>
                </bpmn:definitions>
                """);
        final var context = deploymentService.prepareBpmn("shop", null, "order.bpmn", "Order", waiting);

        final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.prepareBpmn("shop",
                context, "payment.bpmn", "Payment", starting));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .startsWith(
                                """
                                        Workflow module 'shop' uses a message both to start a workflow and as a message a workflow waits for:
                                          - 'paid': starts Payment/PaymentReceived, awaited by Order/AwaitPayment
                                        """),
                        failure.getMessage()),
                () -> assertEquals("Payment", processIdOf(starting)));
    }

    @Test
    @DisplayName("A version-1 model whose messages all carry a key stays as it was written under 'none', and nothing is asked about its aggregate")
    void aVersion1ModelWithItsKeysStaysAsItWasWritten() throws IOException {
        final var wiring = TestWiring.claiming("Version1");
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", TestScoping.of(NameClashAvoidance.NONE), wiring.wiring()));
        final String text;
        try (var file = getClass().getResourceAsStream("/models/version-1.bpmn")) {
            text = new String(file.readAllBytes(), StandardCharsets.UTF_8);
        }
        final var model = read(deploymentService, "version-1.bpmn", text);

        deploymentService.prepareBpmn("shop", null, "version-1.bpmn", "Version1", model);

        assertAll(
                () -> assertTrue(read(deploymentService, "version-1.bpmn", text)
                        .document()
                        .isEqualNode(model.document())),
                () -> assertEquals(List.of("isClaimedByAWorkflowService[shop, Version1]"), wiring.calls()));
    }

    @Test
    @DisplayName("A claimed process gets the input mappings of its jobs inside multi-instance bodies once, however often its file is prepared; a process nobody claims gets none")
    void aClaimedProcessGetsItsTaskParameterMappingsOnce() throws IOException {
        final String text;
        try (var file = getClass().getResourceAsStream("/models/task-parameters.bpmn")) {
            text = new String(file.readAllBytes(), StandardCharsets.UTF_8);
        }
        final var claimed = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators.of(
                "orders", TestScoping.of(NameClashAvoidance.NONE), TestWiring
                        .claiming("Orders")
                        .withTaskParameters("ship", "weight")
                        .wiring()));
        final var unclaimed = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators.of(
                "orders", TestScoping.of(NameClashAvoidance.NONE), TestWiring
                        .claiming()
                        .wiring()));
        final var model = read(claimed, "task-parameters.bpmn", text);
        final var modelNobodyClaims = read(unclaimed, "task-parameters.bpmn", text);

        final var context = claimed.prepareBpmn("shop", null, "task-parameters.bpmn", "Orders", model);
        claimed.prepareBpmn("shop", context, "task-parameters.bpmn", "Orders", model);
        unclaimed.prepareBpmn("shop", null, "task-parameters.bpmn", "Orders", modelNobodyClaims);

        assertAll(
                () -> assertEquals(List.of("weight", "orderId"), inputTargetsOf(model, "EveryParcel")),
                () -> assertEquals(List.of("orderId"), inputTargetsOf(model, "NotifyEveryCustomer")),
                () -> assertTrue(read(unclaimed, "task-parameters.bpmn", text)
                        .document()
                        .isEqualNode(modelNobodyClaims.document())));
    }

    @Test
    @DisplayName("Every step after wiring refuses with the message of the validation where the adapter id has no clients")
    void anAdapterIdWithoutClientsRefusesWithItsValidationMessage() {
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
                .of("orders", TestScoping.of(NameClashAvoidance.USE_PREFIX)));

        assertAll(steps()
                .stream()
                .map(step -> () -> assertEquals(NOT_CONFIGURED, assertThrows(IllegalStateException.class,
                        () -> step.accept(deploymentService)).getMessage())));
    }

    @Test
    @DisplayName("Every step after wiring of a configured adapter id says that it is not built yet, and what it was asked to do")
    void aConfiguredAdapterIdSaysThatTheStepIsNotBuiltYet() {
        try (var registry = new ZenBpmClientRegistry(List.of(configured("orders", "http://localhost:8080")))) {
            final var deploymentService = new ZenBpmDeploymentService("orders", registry, TestCollaborators.of(
                    "orders", TestScoping.of(NameClashAvoidance.USE_PREFIX)));

            final var messages = steps()
                    .stream()
                    .map(step -> assertThrows(UnsupportedOperationException.class, () -> step.accept(
                            deploymentService)).getMessage())
                    .toList();

            assertEquals(List.of(
                    "ZenBPM adapter 'orders': deploying workflow module 'shop' is implemented in a later story",
                    "ZenBPM adapter 'orders': starting the workflow processing of workflow module 'shop' is implemented in a later story"),
                    messages);
        }
    }

    @Test
    @DisplayName("Health is what the engine's readiness says: an adapter id without clients is UNKNOWN with its message")
    void healthIsAskedOfTheFactory() {
        final var deploymentService = new ZenBpmDeploymentService("orders", withoutClients("orders"), TestCollaborators
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
        final var constructor = deploymentServiceType.getConstructor(String.class, ZenBpmClientRegistry.class,
                AdapterCollaborators.class);

        final var failure = assertThrows(InvocationTargetException.class, () -> constructor.newInstance("orders",
                withoutClients("orders"), TestCollaborators.of("orders")));

        final var cause = assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(cause
                .getMessage()
                .contains("was built against the VanillaBP platform integration 99.0.0"), cause.getMessage());
    }

    @Test
    @DisplayName("Two adapter ids on one engine with the same mode end the start, compared with the modes the platform's scoping knows")
    void twoAdapterIdsOfOneInstanceEndTheStart() {
        try (var registry = new ZenBpmClientRegistry(List.of(configured("new", "http://zenbpm:8080"), configured(
                "old", "http://zenbpm:8080")))) {
            final var deploymentService = new ZenBpmDeploymentService("new", registry, TestCollaborators.of("new",
                    modes(Map.of("new", NameClashAvoidance.USE_PREFIX, "old", NameClashAvoidance.USE_PREFIX))));

            final var failure = assertThrows(IllegalStateException.class, () -> deploymentService
                    .validateDistinctAdapterInstances(List.of("new", "old")));

            assertTrue(failure
                    .getMessage()
                    .startsWith(
                            "ZenBPM adapter ids 'new' and 'old' address the same engine, 'http://zenbpm:8080/v1', with the same name-clash-avoidance 'use-prefix',"),
                    failure.getMessage());
        }
    }

    @Test
    @DisplayName("Two adapter ids on one engine with two modes are the migration from one mode to the other")
    void twoAdapterIdsWithTwoModesAreTwoInstances() {
        try (var registry = new ZenBpmClientRegistry(List.of(configured("new", "http://zenbpm:8080"), configured(
                "old", "http://zenbpm:8080")))) {
            final var deploymentService = new ZenBpmDeploymentService("new", registry, TestCollaborators.of("new",
                    modes(Map.of("new", NameClashAvoidance.USE_PREFIX, "old", NameClashAvoidance.NONE))));

            assertDoesNotThrow(() -> deploymentService.validateDistinctAdapterInstances(List.of("new", "old")));
        }
    }

    private static Stream<Arguments> acceptingConfigurations() {
        return Stream.of(
                Arguments.of(true, null, "vanillabp.adapters.orders.accept-unscoped-identifiers"),
                Arguments.of(null, true, "vanillabp.workflow-modules.shop.adapters.orders.accept-unscoped-identifiers"),
                Arguments.of(true, false, null),
                Arguments.of(false, null, null));
    }

    private static List<Consumer<ZenBpmDeploymentService>> steps() {
        return List.of(
                deploymentService -> deploymentService.deployResources("shop", new ZenBpmProcessingContext("shop")),
                deploymentService -> deploymentService.startWorkflowProcessing("shop", null));
    }

    private static ByteArrayInputStream bpmn() {
        return new ByteArrayInputStream("""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="order">
                  <bpmn:process id="Order" isExecutable="true">
                    <bpmn:startEvent id="Start" />
                  </bpmn:process>
                </bpmn:definitions>
                """.getBytes(StandardCharsets.UTF_8));
    }

    private static final String DMN = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions" name="Rating">
              <decision id="rating" name="Rating">
                <informationRequirement id="requirement">
                  <requiredDecision href="#risk" />
                </informationRequirement>
              </decision>
              <decision id="risk" name="Risk" />
            </definitions>
            """;

    private static final String WAITING_FOR_PAYMENT = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="order">
              <bpmn:message id="Message_Paid" name="paid" />
              <bpmn:process id="Order" isExecutable="true">
                <bpmn:receiveTask id="AwaitPayment" messageRef="Message_Paid" />
              </bpmn:process>
            </bpmn:definitions>
            """;

    private static ZenBpmBpmnModel read(
        final ZenBpmDeploymentService deploymentService,
        final String filename,
        final String bpmn
    ) {
        return deploymentService
                .readBpmn("shop", filename, new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)), true)
                .getFirst()
                .getValue();
    }

    private static List<String> correlationKeysOf(final ZenBpmBpmnModel model) {
        final var subscriptions = model
                .document()
                .getElementsByTagNameNS("*", "subscription");
        final var keys = new ArrayList<String>();
        for (var index = 0; index < subscriptions.getLength(); ++index) {
            keys.add(((Element) subscriptions.item(index)).getAttribute("correlationKey"));
        }
        return keys;
    }

    private static List<String> inputTargetsOf(
        final ZenBpmBpmnModel model,
        final String elementId
    ) {
        final var targets = new ArrayList<String>();
        final var inputs = model
                .document()
                .getElementsByTagNameNS("*", "input");
        for (var index = 0; index < inputs.getLength(); ++index) {
            final var input = (Element) inputs.item(index);
            final var element = (Element) input
                    .getParentNode()
                    .getParentNode()
                    .getParentNode();
            if (elementId.equals(element.getAttribute("id"))) {
                targets.add(input.getAttribute("target"));
            }
        }
        return targets;
    }

    private static String messageNameOf(final ZenBpmBpmnModel model) {
        return ((Element) model
                .document()
                .getElementsByTagNameNS(ZenBpmModelReader.BPMN, "message")
                .item(0)).getAttribute("name");
    }

    private static ByteArrayInputStream dmn() {
        return new ByteArrayInputStream(DMN.getBytes(StandardCharsets.UTF_8));
    }

    private static String processIdOf(final ZenBpmBpmnModel model) {
        return ((Element) model
                .document()
                .getElementsByTagNameNS(ZenBpmModelReader.BPMN, "process")
                .item(0)).getAttribute("id");
    }

    // the logger of the deployment service at DEBUG while the work runs
    private static void atDebug(final Runnable work) {
        final var logger = (Logger) LoggerFactory.getLogger(ZenBpmDeploymentService.class);
        final var level = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            work.run();
        } finally {
            logger.setLevel(level);
        }
    }

    // the platform's wiring, as far as preparing asks it: every process is claimed, and the
    // questions are counted
    private static WorkflowTaskWiring claiming(final AtomicInteger asked) {
        return (WorkflowTaskWiring) Proxy.newProxyInstance(ZenBpmDeploymentServiceTest.class.getClassLoader(),
                new Class<?>[]{
                        WorkflowTaskWiring.class
                }, (
                    proxy,
                    method,
                    arguments) -> {
                    if (!"isClaimedByAWorkflowService".equals(method.getName())) {
                        throw new AssertionError("WorkflowTaskWiring.%s is not expected to be called".formatted(method
                                .getName()));
                    }
                    asked.incrementAndGet();
                    return true;
                });
    }

    // a registry with the one adapter id, which has no clients
    private static ZenBpmClientRegistry withoutClients(final String adapterId) {
        return new ZenBpmClientRegistry(List.of(ZenBpmClientFactory.unconfigured(adapterId,
                new ZenBpmAdapterConfiguration(), NOT_CONFIGURED)));
    }

    private static ZenBpmClientFactory configured(
        final String adapterId,
        final String restAddress
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        return new ZenBpmClientFactory(adapterId, configuration, () -> "shop");
    }

    // the platform's scoping, as far as the comparison asks it: the mode of an adapter id
    private static NameClashAvoidanceSupport modes(final Map<String, NameClashAvoidance> modes) {
        return (NameClashAvoidanceSupport) Proxy.newProxyInstance(ZenBpmDeploymentServiceTest.class
                .getClassLoader(), new Class<?>[]{
                        NameClashAvoidanceSupport.class
        }, (
            proxy,
            method,
            arguments) -> modes.get((String) arguments[2]));
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
