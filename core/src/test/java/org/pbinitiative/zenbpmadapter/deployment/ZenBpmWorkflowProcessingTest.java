/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.TestStarts;
import org.pbinitiative.zenbpmadapter.TestWiring;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.FakeJobStreamEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.JobStreamResponse;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.StreamSubscriptionRequest;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.WaitingJob;
import org.pbinitiative.zenbpmadapter.wiring.ZenBpmRetryBackoffResolver;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How a workflow module starts and stops its workflow processing on the job stream of its adapter
 * id: the job types of its claimed processes are subscribed with their job-timeout and the handler
 * slots and unsubscribed again, the hook of the client factory comes before the first subscription
 * and goes after the last unsubscription, a job type two modules share stays subscribed until both
 * stopped, a module without a job type asks the engine nothing, and a job of a type no module
 * subscribes is left to its lock.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmWorkflowProcessingTest {

    private static final String ADAPTER_ID = "orders";

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);

    private static final String PARTNER = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="partner">
              <bpmn:process id="Partner" isExecutable="true">
                <bpmn:serviceTask id="Answer">
                  <bpmn:extensionElements>
                    <zenbpm:taskDefinition type="answer" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private FakeJobStreamEngine engine;

    private ZenBpmClientRegistry registry;

    @BeforeEach
    void startTheEngine() {
        engine = new FakeJobStreamEngine().startOverTcp();
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress("http://localhost:8080");
        configuration.setGrpcAddress(engine.address());
        configuration.setGrpcPlaintext(true);
        registry = new ZenBpmClientRegistry(List.of(new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")));
    }

    @AfterEach
    void stopTheEngine() {
        registry.close();
        engine.close();
    }

    @Test
    @DisplayName("A module subscribes the job types of its claimed processes, each with the job-timeout of its task and the handler slots as the cap, none of a process nobody claims, registers the factory's hook, and unsubscribes them and removes the hook when it stops, once")
    void aModuleSubscribesTheJobTypesOfItsClaimedProcesses() {
        final var deploymentService = new ZenBpmDeploymentService(ADAPTER_ID, registry, TestCollaborators.of(
                ADAPTER_ID, TestScoping.of(NameClashAvoidance.USE_PREFIX), TestWiring
                        .claiming("Orders")
                        .servedElsewhere("Notify")
                        .wiring(),
                TestStarts.everyProcessStartingAWorkflow()), (
                    workflowModuleId,
                    bpmnProcessId,
                    taskDefinition) -> "ship".equals(taskDefinition) ? Duration.ofSeconds(90)
                            : Duration.ofMinutes(5), ZenBpmRetryBackoffResolver.NONE);
        final var context = wired(deploymentService, "wiring.bpmn", fixture("wiring.bpmn"), null);
        wired(deploymentService, "partner.bpmn", PARTNER, context);
        final var factory = registry.getFactory(ADAPTER_ID);

        deploymentService.startWorkflowProcessing("shop", context);
        final var subscribed = engine
                .call(0)
                .subscriptions(3);
        final var openWhileStarted = factory.getOpenWorkflowModules();
        deploymentService.stopWorkflowProcessing("shop", context);
        deploymentService.stopWorkflowProcessing("shop", context);
        final var all = engine
                .call(0)
                .subscriptions(6);

        assertAll(
                () -> assertEquals(List.of(
                        "TYPE_SUBSCRIBE shop__Orders__charge",
                        "TYPE_SUBSCRIBE shop__Orders__ship",
                        "TYPE_SUBSCRIBE shop__Orders__pack"),
                        described(subscribed)),
                () -> assertEquals(List.of(300_000L, 90_000L, 300_000L), subscribed
                        .stream()
                        .map(StreamSubscriptionRequest::getLockDurationMs)
                        .toList()),
                () -> assertTrue(subscribed
                        .stream()
                        .allMatch(subscription -> subscription.getMaxActiveJobs() == factory
                                .executor()
                                .getSlots()),
                        subscribed::toString),
                () -> assertEquals(Set.of("shop"), openWhileStarted),
                () -> assertEquals(List.of(
                        "TYPE_UNSUBSCRIBE shop__Orders__charge",
                        "TYPE_UNSUBSCRIBE shop__Orders__ship",
                        "TYPE_UNSUBSCRIBE shop__Orders__pack"),
                        described(all.subList(3, all.size()))),
                () -> assertEquals(6, all.size()),
                () -> assertEquals(Set.of(), factory.getOpenWorkflowModules()));
    }

    @Test
    @DisplayName("A module without a job type subscribes nothing and asks the engine nothing, also for an adapter id without clients")
    void aModuleWithoutAJobTypeSubscribesNothing() {
        final var deploymentService = new ZenBpmDeploymentService(ADAPTER_ID, new ZenBpmClientRegistry(List.of(
                ZenBpmClientFactory.unconfigured(ADAPTER_ID, new ZenBpmAdapterConfiguration(),
                        "not configured"))), TestCollaborators.of(ADAPTER_ID, TestScoping.of(NameClashAvoidance.NONE),
                                TestWiring
                                        .claiming()
                                        .wiring(),
                                TestStarts.everyProcessStartingAWorkflow()));
        final var context = new ZenBpmProcessingContext("shop");
        context.recordWiring(new WiredProcess("Order", "Order", Set.of(), true, false));

        deploymentService.startWorkflowProcessing("shop", context);
        deploymentService.stopWorkflowProcessing("shop", context);

        assertEquals(0, engine.callCount());
    }

    @Test
    @DisplayName("A job type two modules share stays subscribed until the second of them stopped; a module subscribes the job types of all its claimed processes")
    void aSharedJobTypeStaysSubscribedUntilBothStopped() {
        final var deploymentService = deploymentService(TestWiring.claiming());
        final var shop = withJobTypes("shop", "charge");
        final var billing = withJobTypes("billing", "charge");
        billing.recordWiring(new WiredProcess("Invoice", "Invoice", Set.of("invoice"), true, false));
        // a module started after the first stop marks on the stream where that stop's frames end
        final var audit = withJobTypes("audit", "audit");

        deploymentService.startWorkflowProcessing("shop", shop);
        deploymentService.startWorkflowProcessing("billing", billing);
        engine
                .call(0)
                .subscriptions(2);
        deploymentService.stopWorkflowProcessing("shop", shop);
        deploymentService.startWorkflowProcessing("audit", audit);
        deploymentService.stopWorkflowProcessing("billing", billing);
        final var all = engine
                .call(0)
                .subscriptions(5);

        assertEquals(List.of(
                "TYPE_SUBSCRIBE charge",
                "TYPE_SUBSCRIBE invoice",
                "TYPE_SUBSCRIBE audit",
                "TYPE_UNSUBSCRIBE charge",
                "TYPE_UNSUBSCRIBE invoice"),
                described(all));
    }

    @Test
    @DisplayName("A module the platform did not stop is stopped by the closing factory, which says so")
    void theClosingFactoryStopsAModuleThePlatformDidNotStop(final CapturedOutput output) {
        final var deploymentService = deploymentService(TestWiring.claiming());
        final var context = withJobTypes("shop", "charge");
        deploymentService.startWorkflowProcessing("shop", context);
        engine
                .call(0)
                .subscriptions(1);

        registry
                .getFactory(ADAPTER_ID)
                .close();

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(List.of("TYPE_SUBSCRIBE charge", "TYPE_UNSUBSCRIBE charge"), described(engine
                        .call(0)
                        .subscriptions())),
                () -> assertTrue(log.contains("still subscribe their job types"), log),
                () -> assertTrue(context
                        .processingStopped()
                        .isEmpty()));
    }

    @Test
    @DisplayName("A module cannot start once its factory closes, and subscribes nothing")
    void aModuleCannotStartOnAClosedFactory() {
        final var deploymentService = deploymentService(TestWiring.claiming());
        registry
                .getFactory(ADAPTER_ID)
                .close();

        final var failure = assertThrows(IllegalStateException.class, () -> deploymentService
                .startWorkflowProcessing("shop", withJobTypes("shop", "charge")));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .contains("workflow module 'shop' cannot start"), failure.getMessage()),
                () -> assertEquals(0, engine.callCount()));
    }

    @Test
    @DisplayName("A job type which cannot be subscribed ends the start, and nothing of the module stays subscribed or registered")
    void aSubscriptionWhichFailsStopsTheModule() {
        final var deploymentService = deploymentService(TestWiring.claiming());
        final var factory = registry.getFactory(ADAPTER_ID);
        factory
                .stream()
                .subscribe("an extension", "invoice", Duration.ofMinutes(1), 1);
        final var context = withJobTypes("shop", "charge", "invoice");

        assertThrows(IllegalStateException.class, () -> deploymentService.startWorkflowProcessing("shop", context));

        final var subscriptions = engine
                .call(0)
                .subscriptions(3);
        assertAll(
                () -> assertEquals(List.of(
                        "TYPE_SUBSCRIBE invoice",
                        "TYPE_SUBSCRIBE charge",
                        "TYPE_UNSUBSCRIBE charge"),
                        described(subscriptions)),
                () -> assertEquals(Set.of(), factory.getOpenWorkflowModules()));
    }

    @Test
    @DisplayName("A job of a type no workflow module subscribes is logged at WARN, naming its type, and left to its lock")
    void aJobNoModuleSubscribesIsLeftToItsLock(final CapturedOutput output) {
        final var deploymentService = deploymentService(TestWiring.claiming());
        deploymentService.startWorkflowProcessing("shop", withJobTypes("shop", "charge"));
        final var call = engine.call(0);
        call.subscriptions(1);

        call.send(JobStreamResponse
                .newBuilder()
                .setJob(WaitingJob
                        .newBuilder()
                        .setKey(42)
                        .setType("refund"))
                .build());

        final var warning = "ZenBPM[orders]: a job of type 'refund' (key 42) arrived, which no workflow module of this application subscribes; it is left to its lock";
        final var deadline = Instant
                .now()
                .plus(DELIVERY_TIMEOUT);
        while (!output
                .getAllOfThisTest()
                .contains(warning) && Instant
                        .now()
                        .isBefore(deadline)) {
            LockSupport.parkNanos(Duration
                    .ofMillis(20)
                    .toNanos());
        }
        assertTrue(output
                .getAllOfThisTest()
                .contains(warning), output::getAllOfThisTest);
    }

    private ZenBpmDeploymentService deploymentService(final TestWiring wiring) {
        return new ZenBpmDeploymentService(ADAPTER_ID, registry, TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.USE_PREFIX), wiring.wiring(), TestStarts.everyProcessStartingAWorkflow()));
    }

    private static ZenBpmProcessingContext withJobTypes(
        final String workflowModuleId,
        final String... jobTypes
    ) {
        final var context = new ZenBpmProcessingContext(workflowModuleId);
        context.recordWiring(new WiredProcess("Order", "Order", new LinkedHashSet<>(List.of(jobTypes)), true, false));
        return context;
    }

    private static ZenBpmProcessingContext wired(
        final ZenBpmDeploymentService deploymentService,
        final String filename,
        final String bpmn,
        final ZenBpmProcessingContext existing
    ) {
        final var model = deploymentService
                .readBpmn("shop", filename, new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)), true)
                .getFirst()
                .getValue();
        final var context = deploymentService.prepareBpmn("shop", existing, filename, model.bpmnProcessId(), model);
        deploymentService.wireBpmn("shop", filename, model.bpmnProcessId(), model, context);
        return context;
    }

    private static List<String> described(final List<StreamSubscriptionRequest> subscriptions) {
        return subscriptions
                .stream()
                .map(subscription -> "%s %s".formatted(subscription.getType(), subscription.getJobType()))
                .toList();
    }

    private static String fixture(final String name) {
        try (var file = ZenBpmWorkflowProcessingTest.class.getResourceAsStream("/models/%s".formatted(name))) {
            return new String(file.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
