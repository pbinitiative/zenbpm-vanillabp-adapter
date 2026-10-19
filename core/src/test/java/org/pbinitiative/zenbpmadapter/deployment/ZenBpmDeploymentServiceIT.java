/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.util.Set;
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
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.CapturedOutput;
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
        // a process which starts a workflow of its own gets start markers, which the file does not
        // carry, so the process is a called one here
        final var deploymentService = deploymentService(NameClashAvoidance.NONE, TestWiring.claiming("Version1"),
                TestStarts
                        .everyProcessStartingAWorkflow()
                        .called("Version1"));
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
        complete(activeJob("vanillabp__started__params-it__Orders"));
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
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, wiring, TestStarts
                .everyProcessStartingAWorkflow()
                .called("Tracking"));
        final var context = prepared(deploymentService, "carry-it", "call-in-a-body.bpmn", "Shipping");
        prepared(deploymentService, context, "carry-it", "called-in-a-body.bpmn", "Tracking");
        deploymentService.deployResources("carry-it", context);

        rest.createInstance(new CreateInstanceRequest("carry-it__Shipping", null, "order-2", Map.of(
                "orderId", "order-2",
                "parcels", List.of("p-1")), null));
        complete(activeJob("vanillabp__started__carry-it__Shipping"));
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

    @Test
    @DisplayName("A process id declared without a model is served by the types the models the engine holds under it give to a task and to no user task, in any version, a task without a task definition passed over")
    void aDeclaredIdIsServedOnlyByTypesNoHeldUserTaskCarries(final CapturedOutput output) {
        rest.deployBpmn(heldUnderTheDeclaredId(
                """
                        <bpmn:serviceTask id="Ship">
                          <bpmn:extensionElements><zenbpm:taskDefinition type="declared-it__Old__ship" /></bpmn:extensionElements>
                        </bpmn:serviceTask>
                        <bpmn:serviceTask id="Label" />"""));
        rest.deployBpmn(heldUnderTheDeclaredId(
                """
                        <bpmn:userTask id="Ship">
                          <bpmn:extensionElements><zenbpm:taskDefinition type="declared-it__Old__ship" /></bpmn:extensionElements>
                        </bpmn:userTask>
                        <bpmn:serviceTask id="Pack">
                          <bpmn:extensionElements><zenbpm:taskDefinition type="declared-it__Old__pack" /></bpmn:extensionElements>
                        </bpmn:serviceTask>"""));
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring
                .claiming("Version1")
                .declaringWithoutAModel("Old", "pack", "ship"));
        final var context = prepared(deploymentService, "declared-it", "version-1.bpmn", "Version1");

        deploymentService.startWorkflowProcessing("declared-it", context);
        deploymentService.stopWorkflowProcessing("declared-it", context);

        assertAll(
                () -> assertEquals(2, rest
                        .processDefinitions("declared-it__Old", false)
                        .size()),
                () -> assertEquals(Set.of("declared-it__Old__pack"), context
                        .wiredProcesses()
                        .get("Old")
                        .jobTypes()),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains(
                                """
                                        its workflows are served by the job types [declared-it__Old__pack] subscribed for it. \
                                        Not served: the task definition(s) [ship], which a user task of a model held under that \
                                        id carries"""),
                        output::getOutOfThisTest),
                () -> assertFalse(output
                        .getOutOfThisTest()
                        .contains("could not be read"), output::getOutOfThisTest));
    }

    @Test
    @DisplayName("A message only an earlier version of a process waits for passes phase one of a correlation, because its workflows may still wait for it; a name no version knows is refused, once every version was read again")
    void aMessageAnEarlierVersionWaitsForPassesPhaseOne() {
        deployWaitingFor("paid");
        deployWaitingFor("settled");
        final var factory = registry.getFactory(ADAPTER_ID);
        final var correlation = new ZenBpmProcessService<Object>(ADAPTER_ID, factory, TestCollaborators.of(
                ADAPTER_ID, TestScoping.of(NameClashAvoidance.USE_PREFIX)), (
                    module,
                    process) -> null)
                .phaseOperations()
                .get(PhaseOperation.CORRELATE_MESSAGE);

        final var refusal = assertThrows(IllegalArgumentException.class, () -> correlation.phaseOne(correlationOf(
                "payed")));

        assertAll(
                () -> assertEquals(Optional.of(new ModuleMessages(List.of("messages-it__Payment"), Set.of(
                        "messages-it__paid", "messages-it__settled"), Set.of(), Set.of(), List.of())), factory
                                .deployedProcesses()
                                .messagesOf("messages-it")),
                () -> assertDoesNotThrow(() -> correlation.phaseOne(correlationOf("paid"))),
                () -> assertDoesNotThrow(() -> correlation.phaseOne(correlationOf("settled"))),
                () -> assertTrue(refusal
                        .getMessage()
                        .contains(
                                "the messages they wait for are: [messages-it__paid, messages-it__settled]"),
                        refusal::getMessage));
    }

    @Test
    @DisplayName("A message the newest version of a process starts with is refused, although an older version waits for it: ZenBPM would start a new workflow with a correlation which finds none waiting")
    void aMessageTheNewestVersionStartsWithIsRefused() {
        deploy("starting-it", "Refund", """
                <bpmn:message id="Message_Reused" name="reused" />
                <bpmn:process id="Refund" isExecutable="true">
                  <bpmn:startEvent id="Start" />
                  <bpmn:receiveTask id="Await" messageRef="Message_Reused" />
                </bpmn:process>""");
        deploy("starting-it", "Refund", """
                <bpmn:message id="Message_Reused" name="reused" />
                <bpmn:message id="Message_Remain" name="remain" />
                <bpmn:process id="Refund" isExecutable="true">
                  <bpmn:startEvent id="Start">
                    <bpmn:messageEventDefinition messageRef="Message_Reused" />
                  </bpmn:startEvent>
                  <bpmn:receiveTask id="Await" messageRef="Message_Remain" />
                </bpmn:process>""");

        final var refusal = assertThrows(IllegalArgumentException.class, () -> correlation().phaseOne(correlationOf(
                "starting-it", "Refund", "reused")));

        assertAll(
                () -> assertEquals(Optional.of(new ModuleMessages(List.of("starting-it__Refund"), Set.of(
                        "starting-it__reused", "starting-it__remain"), Set
                                .of("starting-it__reused"), Set.of("starting-it__Refund"), List.of())),
                        registry
                                .getFactory(ADAPTER_ID)
                                .deployedProcesses()
                                .messagesOf("starting-it")),
                () -> assertTrue(refusal
                        .getMessage()
                        .contains("the newest version of a process of the workflow module starts with that message"),
                        refusal::getMessage),
                () -> assertDoesNotThrow(() -> correlation().phaseOne(correlationOf("starting-it", "Refund",
                        "remain"))));
    }

    @Test
    @DisplayName("A message only a version another node deployed after this start waits for passes phase one, because a name is refused only by the versions the engine holds when it is refused")
    void aVersionAnotherNodeDeployedLaterIsReadBeforeARefusal() {
        deploy("rolling-it", "Return", """
                <bpmn:message id="Message_Old" name="oldMessage" />
                <bpmn:process id="Return" isExecutable="true">
                  <bpmn:startEvent id="Start" />
                  <bpmn:receiveTask id="Await" messageRef="Message_Old" />
                </bpmn:process>""");
        // another node of a newer version of the application deploys the next version
        rest.deployBpmn("""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="process" \
                targetNamespace="test">
                  <bpmn:message id="Message_New" name="rolling-it__newMessage" />
                  <bpmn:process id="rolling-it__Return" isExecutable="true">
                    <bpmn:startEvent id="Start" />
                    <bpmn:receiveTask id="Await" messageRef="Message_New" />
                  </bpmn:process>
                </bpmn:definitions>
                """.getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertDoesNotThrow(() -> correlation().phaseOne(correlationOf("rolling-it", "Return",
                        "newMessage"))),
                () -> assertEquals(Optional.of(new ModuleMessages(List.of("rolling-it__Return"), Set.of(
                        "rolling-it__oldMessage", "rolling-it__newMessage"), Set.of(), Set.of(), List.of())), registry
                                .getFactory(ADAPTER_ID)
                                .deployedProcesses()
                                .messagesOf("rolling-it")),
                () -> assertThrows(IllegalArgumentException.class, () -> correlation().phaseOne(correlationOf(
                        "rolling-it", "Return", "neverMessage"))));
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
        return deploymentService(mode, wiring, TestStarts.everyProcessStartingAWorkflow());
    }

    private static ZenBpmDeploymentService deploymentService(
        final NameClashAvoidance mode,
        final TestWiring wiring,
        final TestStarts starts
    ) {
        return new ZenBpmDeploymentService(ADAPTER_ID, registry, TestCollaborators.of(ADAPTER_ID, TestScoping.of(mode),
                wiring.wiring(), starts));
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

    // one version of a process which waits for the given message
    private static void deployWaitingFor(final String messageName) {
        deploy("messages-it", "Payment", """
                <bpmn:message id="Message_Awaited" name="%s" />
                <bpmn:process id="Payment" isExecutable="true">
                  <bpmn:startEvent id="Start" />
                  <bpmn:receiveTask id="Await" messageRef="Message_Awaited" />
                </bpmn:process>""".formatted(messageName));
    }

    // deployed by this application, as a workflow module which claims the process
    private static void deploy(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String messagesAndProcess
    ) {
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring.claiming(
                bpmnProcessId));
        final var model = deploymentService
                .readBpmn(workflowModuleId, "process.bpmn", new ByteArrayInputStream("""
                        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="process" \
                        targetNamespace="test">
                        %s
                        </bpmn:definitions>
                        """.formatted(messagesAndProcess).getBytes(StandardCharsets.UTF_8)), true)
                .getFirst()
                .getValue();
        final var context = deploymentService.prepareBpmn(workflowModuleId, null, "process.bpmn", bpmnProcessId,
                model);
        deploymentService.wireBpmn(workflowModuleId, "process.bpmn", bpmnProcessId, model, context);
        deploymentService.deployResources(workflowModuleId, context);
    }

    private static PhaseOneRequest<Object> correlationOf(final String messageName) {
        return correlationOf("messages-it", "Payment", messageName);
    }

    private static PhaseOneRequest<Object> correlationOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String messageName
    ) {
        return new PhaseOneRequest<>(workflowModuleId, bpmnProcessId, new AggregatePersistenceAware<>() {
        }, new Object(), Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, messageName));
    }

    private static PhaseOperationHandler<Object> correlation() {
        return new ZenBpmProcessService<Object>(ADAPTER_ID, registry.getFactory(ADAPTER_ID), TestCollaborators.of(
                ADAPTER_ID, TestScoping.of(NameClashAvoidance.USE_PREFIX)), (
                    module,
                    process) -> null)
                .phaseOperations()
                .get(PhaseOperation.CORRELATE_MESSAGE);
    }

    // a version of the process an earlier version of the application deployed under the old id
    private static byte[] heldUnderTheDeclaredId(final String tasks) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="declared-it" targetNamespace="test">
                  <bpmn:process id="declared-it__Old" isExecutable="true">
                    <bpmn:startEvent id="Start" />
                    %s
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(tasks).getBytes(StandardCharsets.UTF_8);
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
