/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.TestStarts;
import org.pbinitiative.zenbpmadapter.TestWiring;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The deployment pipeline against the pinned engine: what preparing and wiring wrote reaches the
 * engine and runs there, a file the pipeline left as it was reaches it byte for byte, an unchanged
 * file keeps its version and a changed one gets the next, and a file the engine refuses ends the
 * deployment with the engine's reason.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmDeploymentServiceIT {

    private static final String ADAPTER_ID = "deploy";

    /**
     * How long the engine may take to hand out a job after a command. It answers in milliseconds,
     * so the limit only turns a job which never comes into a failure.
     */
    private static final Duration JOB_TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static ZenBpmClientRegistry registry;

    private static ZenBpmRestClient rest;

    @BeforeAll
    static void buildTheClients() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        configuration.setGrpcAddress(ENGINE.grpcAddress());
        registry = new ZenBpmClientRegistry(List
                .of(new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "deploy-it")));
        rest = registry
                .getFactory(ADAPTER_ID)
                .rest();
    }

    @AfterAll
    static void closeTheClients() {
        registry.close();
    }

    @Test
    @DisplayName("Under use-prefix the rewritten file deploys, and the engine holds it under the prefixed id as it was sent")
    void theFileRewrittenUnderUsePrefixDeploys() {
        // the send task names an empty task definition, so it is served elsewhere or refused
        final var wiring = TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify");
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, wiring);
        final var context = prepared(deploymentService, "scoping-it", "scoping.bpmn", "Orders");
        deploymentService.readDmn("scoping-it", context, "rating.dmn", new ByteArrayInputStream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="rating-definitions" name="Rating">
                  <decision id="rating" name="Rating" />
                </definitions>
                """.getBytes(StandardCharsets.UTF_8)));

        deploymentService.deployResources("scoping-it", context);

        final var definitions = rest.processDefinitions("scoping-it__Orders", false);
        final var held = rest.processDefinition(definitions
                .getFirst()
                .key());
        assertAll(
                () -> assertEquals(1, definitions.size(), definitions::toString),
                () -> assertEquals(1, held.version()),
                () -> assertTrue(held
                        .bpmnData()
                        .contains("type=\"scoping-it__Orders__charge\""), held::bpmnData),
                () -> assertTrue(held
                        .bpmnData()
                        .contains("decisionId=\"scoping-it__rating\""), held::bpmnData),
                () -> assertEquals(List.of("registerDeployedVersion[deploy, scoping-it, Orders, 1]"), wiring.callsOf(
                        "registerDeployedVersion")));
    }

    @Test
    @DisplayName("A version-1 model which carries every key the adapter would write reaches the engine byte for byte under none")
    void aVersion1ModelDeploysByteForByte() {
        final var deploymentService = deploymentService(NameClashAvoidance.NONE, TestWiring.claiming("Version1"));
        final var context = prepared(deploymentService, "version-1-it", "version-1.bpmn", "Version1");

        deploymentService.deployResources("version-1-it", context);

        final var held = rest.processDefinition(registry
                .getFactory(ADAPTER_ID)
                .deployedProcesses()
                .keyOf("version-1-it", "Version1")
                .orElseThrow());
        assertArrayEquals(fixture("version-1.bpmn"), held
                .bpmnData()
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A name a method reads as @TaskParam, and a name only an extension's method reads, reach a real job inside a multi-instance body; the aggregate id reaches it as well")
    void aTaskParameterReachesAJobInsideAMultiInstanceBody() {
        final var wiring = TestWiring
                .claiming("Orders")
                .servedElsewhere("Label")
                .withTaskParameters("ship", "customerName")
                .withExtensionTaskParameters("Ship", "trackingSystem");
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, wiring);
        final var context = prepared(deploymentService, "params-it", "task-parameters.bpmn", "Orders");
        deploymentService.deployResources("params-it", context);

        rest.createInstance(new CreateInstanceRequest("params-it__Orders", null, "order-1", Map.of(
                "orderId", "order-1",
                "customerName", "Ada",
                "trackingSystem", "parcel-tracker",
                "customers", List.of("c-1"),
                "parcelWeight", 3,
                "parcels", List.of(Map.of("address", "Main Street 1", "items", List.of("i-1")))), null));
        complete(activeJob("params-it__Orders__charge"));
        final var notify = activeJob("params-it__Orders__notify");
        complete(notify);
        final var ship = activeJob("params-it__Orders__ship");

        assertAll(
                () -> assertEquals("c-1", notify
                        .inputVariables()
                        .get("customer")),
                () -> assertEquals("order-1", notify
                        .inputVariables()
                        .get("orderId")),
                () -> assertEquals("Ada", ship
                        .inputVariables()
                        .get("customerName")),
                () -> assertEquals("parcel-tracker", ship
                        .inputVariables()
                        .get("trackingSystem")),
                () -> assertEquals("order-1", ship
                        .inputVariables()
                        .get("orderId")),
                () -> assertEquals("Main Street 1", ship
                        .inputVariables()
                        .get("address")),
                () -> assertFalse(ship
                        .inputVariables()
                        .containsKey("parcels"),
                        () -> ship
                                .inputVariables()
                                .toString()));
    }

    @Test
    @DisplayName("A process called on the same aggregate from inside a multi-instance body gets the aggregate id, which the engine does not hand into the body by itself")
    void theAggregateIdReachesAProcessCalledFromInsideAMultiInstanceBody() {
        final var wiring = TestWiring
                .claiming("Shipping", "Tracking")
                .sharingTheAggregateWith("Tracking");
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, wiring);
        final var context = prepared(deploymentService, "carry-it", "call-in-a-body.bpmn", "Shipping");
        prepared(deploymentService, context, "carry-it", "called-in-a-body.bpmn", "Tracking");
        deploymentService.deployResources("carry-it", context);

        rest.createInstance(new CreateInstanceRequest("carry-it__Shipping", null, "order-2", Map.of(
                "orderId", "order-2",
                "parcels", List.of("p-1")), null));
        final var track = activeJob("carry-it__Tracking__track");

        assertEquals("order-2", track
                .inputVariables()
                .get("orderId"),
                () -> track
                        .inputVariables()
                        .toString());
    }

    @Test
    @DisplayName("An unchanged file keeps its version and is registered with it; a changed file gets the next version")
    void anUnchangedFileKeepsItsVersion() {
        final var first = TestWiring.claiming("Version1");
        final var unchanged = TestWiring.claiming("Version1");
        final var changed = TestWiring.claiming("Version1");

        deploy(first, "versions-it", "version-1.bpmn");
        deploy(unchanged, "versions-it", "version-1.bpmn");
        final var changedModel = new String(fixture("version-1.bpmn"), StandardCharsets.UTF_8).replace(
                "name=\"Charge the card\"", "name=\"Charge the credit card\"");
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, changed);
        final var model = deploymentService
                .readBpmn("versions-it", "version-1.bpmn", new ByteArrayInputStream(changedModel.getBytes(
                        StandardCharsets.UTF_8)), true)
                .getFirst()
                .getValue();
        final var context = deploymentService.prepareBpmn("versions-it", null, "version-1.bpmn", "Version1", model);
        deploymentService.wireBpmn("versions-it", "version-1.bpmn", "Version1", model, context);
        deploymentService.deployResources("versions-it", context);

        assertAll(
                () -> assertEquals(List.of("registerDeployedVersion[deploy, versions-it, Version1, 1]"), first.callsOf(
                        "registerDeployedVersion")),
                () -> assertEquals(List.of("registerDeployedVersion[deploy, versions-it, Version1, 1]"), unchanged
                        .callsOf("registerDeployedVersion")),
                () -> assertEquals(List.of("registerDeployedVersion[deploy, versions-it, Version1, 2]"), changed
                        .callsOf("registerDeployedVersion")),
                () -> assertEquals(2, rest
                        .processDefinitions("versions-it__Version1", false)
                        .size()));
    }

    @Test
    @DisplayName("A file the engine refuses ends the deployment naming file, workflow module, process and the engine's reason")
    void aFileTheEngineRefusesEndsTheDeployment() {
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring.claiming(
                "Refused"));
        final var model = deploymentService
                .readBpmn("refused-it", "refused.bpmn", new ByteArrayInputStream("""
                        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="refused">
                          <bpmn:process id="Refused" isExecutable="true">
                            <bpmn:callActivity id="Invoice">
                              <bpmn:extensionElements>
                                <zenbpm:calledElement processId="Invoicing" version="latest" />
                              </bpmn:extensionElements>
                            </bpmn:callActivity>
                          </bpmn:process>
                        </bpmn:definitions>
                        """.getBytes(StandardCharsets.UTF_8)), true)
                .getFirst()
                .getValue();
        final var context = deploymentService.prepareBpmn("refused-it", null, "refused.bpmn", "Refused", model);
        deploymentService.wireBpmn("refused-it", "refused.bpmn", "Refused", model, context);

        final var failure = assertThrows(IllegalStateException.class, () -> deploymentService.deployResources(
                "refused-it", context));

        assertEquals("""
                ZenBPM refuses BPMN file 'refused.bpmn' of workflow module 'refused-it' (process 'Refused'): POST \
                /v1/process-definitions was answered with status 400, BAD_REQUEST: failed to get process definition \
                id: failed to unmarshal xml data: failed to unmarshal TDefinitions: strconv.ParseInt: parsing \
                "latest": invalid syntax. Correct the model where the engine's reason points, or take the file out \
                of the workflow module.""", failure.getMessage());
    }

    private static void deploy(
        final TestWiring wiring,
        final String workflowModuleId,
        final String filename
    ) {
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, wiring);
        deploymentService.deployResources(workflowModuleId, prepared(deploymentService, workflowModuleId, filename,
                "Version1"));
    }

    private static ZenBpmDeploymentService deploymentService(
        final NameClashAvoidance mode,
        final TestWiring wiring
    ) {
        return new ZenBpmDeploymentService(ADAPTER_ID, registry, TestCollaborators.of(ADAPTER_ID, TestScoping.of(mode),
                wiring.wiring(), TestStarts.everyProcessStartingAWorkflow()));
    }

    private static ZenBpmProcessingContext prepared(
        final ZenBpmDeploymentService deploymentService,
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId
    ) {
        return prepared(deploymentService, null, workflowModuleId, filename, bpmnProcessId);
    }

    private static ZenBpmProcessingContext prepared(
        final ZenBpmDeploymentService deploymentService,
        final ZenBpmProcessingContext existingContext,
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId
    ) {
        final var model = deploymentService
                .readBpmn(workflowModuleId, filename, new ByteArrayInputStream(fixture(filename)), true)
                .getFirst()
                .getValue();
        final var context = deploymentService.prepareBpmn(workflowModuleId, existingContext, filename, bpmnProcessId,
                model);
        deploymentService.wireBpmn(workflowModuleId, filename, bpmnProcessId, model, context);
        return context;
    }

    private static JobRef activeJob(final String jobType) {
        return eventually(() -> rest
                .jobs(JobFilter
                        .anyJob()
                        .withJobType(jobType)
                        .withState(JobRef.State.ACTIVE))
                .items()
                .stream()
                .findFirst());
    }

    private static void complete(final JobRef job) {
        rest.completeJob(job.key(), Map.of());
    }

    private static <T> T eventually(final Supplier<Optional<T>> read) {
        final var deadline = Instant
                .now()
                .plus(JOB_TIMEOUT);
        while (true) {
            final var found = read.get();
            if (found.isPresent() || Instant
                    .now()
                    .isAfter(deadline)) {
                return found.orElseThrow();
            }
            LockSupport.parkNanos(Duration
                    .ofMillis(50)
                    .toNanos());
        }
    }

    private static byte[] fixture(final String name) {
        try (var file = ZenBpmDeploymentServiceIT.class.getResourceAsStream("/models/%s".formatted(name))) {
            return file.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
