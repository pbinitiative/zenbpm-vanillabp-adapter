/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.TestStarts;
import org.pbinitiative.zenbpmadapter.TestWiring;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.FakeJobStreamEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.StreamSubscriptionRequest;
import org.pbinitiative.zenbpmadapter.wiring.ZenBpmJobHandler;
import org.pbinitiative.zenbpmadapter.wiring.ZenBpmRetryBackoffResolver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * The workflows of a BPMN process a workflow module declares without bringing a model for it, the
 * old id of a renamed process: the module serves them with job types composed from the task
 * definitions its methods name for the id, scoped by the id, and only where a model the engine holds
 * under the id gives the type to a task which creates a job for a worker, never to a user task. The
 * engine's REST API is a fake which holds the models of the old id, its job stream another one.
 * The module <code>shop</code> deploys the process <code>Orders</code> and declares
 * <code>OrdersBeforeTheRename</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDeclaredProcessWorkersTest {

    private static final String ADAPTER_ID = "orders";

    private static final String DECLARED = "OrdersBeforeTheRename";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FakeEngine rest = FakeEngine.started();

    private final FakeJobStreamEngine stream = new FakeJobStreamEngine().startOverTcp();

    private final ZenBpmClientRegistry registry = registry();

    @AfterEach
    void stopTheEngines() {
        registry.close();
        stream.close();
        rest.close();
    }

    @Test
    @DisplayName("Under use-prefix a declared process id is served by the job types its methods name, scoped by the id, where a model the engine holds gives them to a task: subscribed with their job-timeout, routed to the module, and named in the start's line with the user tasks and the task definitions no model holds")
    void aDeclaredIdIsServedByTheTaskTypesTheEngineHolds(final CapturedOutput output) throws Exception {
        final var held = "shop__%s".formatted(DECLARED);
        theEngineHolds(held, model(held, "shop__%s__charge".formatted(DECLARED), "shop__%s__review".formatted(
                DECLARED)), model(held, "shop__%s__refund".formatted(DECLARED), null));
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED, "charge", "legacy", "refund", "review"));
        final var context = wired(deploymentService);

        deploymentService.startWorkflowProcessing("shop", context);

        final var subscribed = stream
                .call(0)
                .subscriptions(6);
        final var handler = new ZenBpmJobHandler(ADAPTER_ID, factory(), collaborators(NameClashAvoidance.USE_PREFIX,
                TestWiring
                        .claiming("Orders")
                        .servedElsewhere("Notify")
                        .wiring()), context, ZenBpmRetryBackoffResolver.NONE);
        assertAll(
                () -> assertEquals(List.of(
                        "shop__Orders__charge",
                        "shop__Orders__ship",
                        "shop__Orders__pack",
                        // the start markers of the claimed process, which starts a workflow of its own
                        "vanillabp__started__shop__Orders",
                        "shop__OrdersBeforeTheRename__charge",
                        "shop__OrdersBeforeTheRename__refund"),
                        subscribed
                                .stream()
                                .map(StreamSubscriptionRequest::getJobType)
                                .toList()),
                () -> assertEquals(42_000L, subscribed
                        .get(4)
                        .getLockDurationMs()),
                () -> assertEquals(Set.of("shop__OrdersBeforeTheRename__charge",
                        "shop__OrdersBeforeTheRename__refund"),
                        context
                                .wiredProcesses()
                                .get(DECLARED)
                                .jobTypes()),
                () -> assertTrue(handler
                        .processServing(held, "shop__OrdersBeforeTheRename__charge")
                        .isPresent()),
                () -> assertTrue(handler
                        .processServing(held, "shop__OrdersBeforeTheRename__review")
                        .isEmpty()),
                () -> assertEquals(Optional.of("shop"), factory()
                        .deployedProcesses()
                        .moduleServing(held)),
                () -> assertTrue(rest
                        .requests()
                        .stream()
                        .allMatch(request -> "GET".equals(request.method())),
                        () -> rest
                                .requests()
                                .toString()),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains("""
                                ZenBPM[orders]: workflow module 'shop' declares BPMN process 'OrdersBeforeTheRename' \
                                without deploying a model under it, and the engine holds it as \
                                'shop__OrdersBeforeTheRename': its workflows are served by the job types \
                                [shop__OrdersBeforeTheRename__charge, shop__OrdersBeforeTheRename__refund] subscribed \
                                for it. Not served: the task definition(s) [review], which a user task of a model held \
                                under that id carries, and the job stream cannot tell its job from a task's; the task \
                                definition(s) [legacy], which no model held under that id names. A job of those \
                                workflows carries the variables of its scope, and inside a multi-instance body only \
                                what the model held maps into it."""), output::getOutOfThisTest));
    }

    @Test
    @DisplayName("Under none a declared process id subscribes no type of its own, because the deployed processes subscribe the same names, and its jobs are routed to the module all the same")
    void underNoneADeclaredIdSubscribesNoTypeOfItsOwn(final CapturedOutput output) throws Exception {
        theEngineHolds(DECLARED, model(DECLARED, "charge", null));
        final var deploymentService = deploymentService(NameClashAvoidance.NONE, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED, "charge"), Duration.ofMinutes(5));
        final var context = wired(deploymentService);

        deploymentService.startWorkflowProcessing("shop", context);

        final var subscribed = stream
                .call(0)
                .subscriptions(4);
        final var handler = new ZenBpmJobHandler(ADAPTER_ID, factory(), collaborators(NameClashAvoidance.NONE,
                TestWiring
                        .claiming("Orders")
                        .servedElsewhere("Notify")
                        .wiring()), context, ZenBpmRetryBackoffResolver.NONE);
        assertAll(
                () -> assertEquals(List.of("charge", "ship", "pack", "vanillabp__started__Orders"), subscribed
                        .stream()
                        .map(StreamSubscriptionRequest::getJobType)
                        .toList()),
                () -> assertEquals(4, stream
                        .call(0)
                        .subscriptions()
                        .size()),
                () -> assertTrue(handler
                        .processServing(DECLARED, "charge")
                        .isPresent()),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains(
                                "its workflows are served by the job types [charge] which the deployed processes subscribe. A job"),
                        output::getOutOfThisTest));
    }

    @Test
    @DisplayName("A declared process id whose task shares a job type with a deployed one, under none, needs the same job-timeout, and the start names both task-level keys")
    void aSharedTypeOfADeclaredIdNeedsTheSameJobTimeout() throws Exception {
        theEngineHolds(DECLARED, model(DECLARED, "charge", null));
        final var deploymentService = deploymentService(NameClashAvoidance.NONE, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED, "charge"));
        final var context = wired(deploymentService);

        final var refused = assertThrows(IllegalStateException.class, () -> deploymentService
                .startWorkflowProcessing("shop", context));

        assertAll(
                () -> assertTrue(refused
                        .getMessage()
                        .contains(
                                "vanillabp.workflow-modules.shop.workflows.Orders.tasks.charge.adapters.orders.job-timeout"),
                        refused::getMessage),
                () -> assertTrue(refused
                        .getMessage()
                        .contains(
                                "vanillabp.workflow-modules.shop.workflows.OrdersBeforeTheRename.tasks.charge.adapters.orders.job-timeout"),
                        refused::getMessage),
                () -> assertEquals(0, stream.callCount()));
    }

    @Test
    @DisplayName("A declared process id of one module whose type another module subscribes, under none, needs the same job-timeout, which the start of its processing says, naming both modules")
    void aTypeADeclaredIdSharesWithAnotherModuleNeedsTheSameJobTimeout() throws Exception {
        theEngineHolds(DECLARED, model(DECLARED, "charge", null));
        final var deploymentService = deploymentService(NameClashAvoidance.NONE, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED, "charge"));
        wired(deploymentService);

        final var refused = assertThrows(IllegalStateException.class, () -> deploymentService
                .startWorkflowProcessing("billing", new ZenBpmProcessingContext("billing")));

        assertTrue(refused
                .getMessage()
                .startsWith("""
                        Job type 'charge' is served by two workflow modules with different job-timeouts: PT5M for task \
                        'charge' of process 'Orders' in workflow module 'shop' and PT42S for task 'charge' of process \
                        'OrdersBeforeTheRename' in workflow module 'billing'."""), refused::getMessage);
    }

    @Test
    @DisplayName("Under none a process id one module declares without a model and another module claims is refused at the start, naming both modules and the keys, and stays the claiming module's")
    void aDeclaredIdAnotherModuleClaimsIsRefused() throws Exception {
        theEngineHolds("Orders", model("Orders", "charge", null));
        wired(deploymentService(NameClashAvoidance.NONE, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")));
        final var billing = deploymentService(NameClashAvoidance.NONE, TestWiring
                .claiming("Invoices")
                .declaringWithoutAModel("Orders", "charge"));

        final var refused = assertThrows(IllegalStateException.class, () -> billing.startWorkflowProcessing(
                "billing", new ZenBpmProcessingContext("billing")));

        assertAll(
                () -> assertEquals(
                        """
                                Workflow modules 'shop' and 'billing' both serve the workflows of BPMN process 'Orders', which \
                                ZenBPM (adapter 'orders') knows as 'Orders', so whose task a job of them is cannot be said. One \
                                of them declares the process without bringing a model for it, under name-clash-avoidance \
                                'none'. Let one workflow module only serve that process, or keep the modules' ids apart with \
                                vanillabp.adapters.orders.name-clash-avoidance: use-prefix (or \
                                vanillabp.workflow-modules.billing.adapters.orders.name-clash-avoidance) before any workflow of \
                                them runs: changing it later is a BPMS migration.""",
                        refused.getMessage()),
                () -> assertEquals(Optional.of("shop"), factory()
                        .deployedProcesses()
                        .moduleServing("Orders")));
    }

    @Test
    @DisplayName("A module which starts again names the types of a declared process id as it did the first time")
    void aRestartNamesTheSameTypes(final CapturedOutput output) throws Exception {
        final var held = "shop__%s".formatted(DECLARED);
        theEngineHolds(held, model(held, "shop__%s__charge".formatted(DECLARED), null));
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED, "charge"));
        final var context = wired(deploymentService);
        final var line = "its workflows are served by the job types [shop__OrdersBeforeTheRename__charge] subscribed for it.";

        deploymentService.startWorkflowProcessing("shop", context);
        deploymentService.stopWorkflowProcessing("shop", context);
        deploymentService.startWorkflowProcessing("shop", context);

        assertEquals(2, output
                .getOutOfThisTest()
                .split(Pattern.quote(line), -1).length - 1, output::getOutOfThisTest);
    }

    @Test
    @DisplayName("A declared process id whose methods are all wired by element id is named with both ways out, and the engine is not asked")
    void aDeclaredIdWiredByElementIdIsNamedWithBothWaysOut(final CapturedOutput output) {
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED));
        final var context = wired(deploymentService);

        deploymentService.startWorkflowProcessing("shop", context);

        assertAll(
                () -> assertEquals(4, stream
                        .call(0)
                        .subscriptions(4)
                        .size()),
                () -> assertEquals(List.of(), rest.requests()),
                () -> assertFalse(context
                        .wiredProcesses()
                        .containsKey(DECLARED)),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains("""
                                ZenBPM[orders]: workflow module 'shop' declares BPMN process 'OrdersBeforeTheRename' \
                                without deploying a model under it, and no @WorkflowTask method serving that id names \
                                a task definition"""), output::getOutOfThisTest),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains("""
                                Either wire those methods by task definition ('@WorkflowTask(taskDefinition = ...)', \
                                which is what the model's 'zenbpm:taskDefinition' carries), or keep deploying the old \
                                model under its old id until those workflows have ended."""),
                        output::getOutOfThisTest));
    }

    @Test
    @DisplayName("A declared process id whose models the engine does not hand out is not served, because a task definition alone cannot tell a task from a user task, which the start's line says")
    void aDeclaredIdWhoseModelsCannotBeReadIsNotServed(final CapturedOutput output) {
        rest.answers("GET", "/v1/process-definitions", 503, null);
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED, "charge"));
        final var context = wired(deploymentService);

        deploymentService.startWorkflowProcessing("shop", context);

        assertAll(
                () -> assertEquals(4, stream
                        .call(0)
                        .subscriptions(4)
                        .size()),
                () -> assertFalse(context
                        .wiredProcesses()
                        .containsKey(DECLARED)),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains(
                                """
                                        and the models the engine holds as 'shop__OrdersBeforeTheRename' could not be read \
                                        (ZenBPM adapter 'orders': GET /v1/process-definitions was answered with status 503), so \
                                        nothing serves"""),
                        output::getOutOfThisTest),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains("""
                                so nothing serves the workflows still running under that id: a task definition alone \
                                cannot tell the job of a task from a user task, which the engine hands out alike. The \
                                next start of the application reads them again."""), output::getOutOfThisTest));
    }

    @Test
    @DisplayName("A type a held model gives to a user task is not served, although another version gives it to a task, because the job stream cannot tell the two jobs apart")
    void aTypeAUserTaskOfAnyVersionCarriesIsNotServed(final CapturedOutput output) throws Exception {
        final var held = "shop__%s".formatted(DECLARED);
        theEngineHolds(held, model(held, "shop__%s__charge".formatted(DECLARED), null), model(held,
                "shop__%s__refund".formatted(DECLARED), "shop__%s__charge".formatted(DECLARED)));
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED, "charge", "refund"));
        final var context = wired(deploymentService);

        deploymentService.startWorkflowProcessing("shop", context);

        final var subscribed = stream
                .call(0)
                .subscriptions(5);
        assertAll(
                () -> assertEquals("shop__OrdersBeforeTheRename__refund", subscribed
                        .getLast()
                        .getJobType()),
                () -> assertEquals(Set.of("shop__OrdersBeforeTheRename__refund"), context
                        .wiredProcesses()
                        .get(DECLARED)
                        .jobTypes()),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains("""
                                Not served: the task definition(s) [charge], which a user task of a model held under \
                                that id carries"""), output::getOutOfThisTest));
    }

    @Test
    @DisplayName("The start markers a held model carries are served, with the start events of the held models which carry them, so a workflow started right before the rename passes its marker; a held model without markers adds none")
    void theStartMarkersOfAHeldModelAreServed() throws Exception {
        final var held = "shop__%s".formatted(DECLARED);
        theEngineHolds(held, model(held, "shop__%s__charge".formatted(DECLARED), null), modelWithStartMarkers(
                held));
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED, "charge"));
        final var context = wired(deploymentService);

        deploymentService.startWorkflowProcessing("shop", context);

        final var subscribed = stream
                .call(0)
                .subscriptions(6);
        assertAll(
                () -> assertEquals(Map.of("Placed", BpmsStartTrigger.Kind.NONE), context
                        .wiredProcesses()
                        .get(DECLARED)
                        .startMarkers()),
                () -> assertEquals("vanillabp__started__shop__OrdersBeforeTheRename", subscribed
                        .getLast()
                        .getJobType()));
    }

    @Test
    @DisplayName("The start markers of a held model are served also where none of the task definitions the methods name is served")
    void theStartMarkersAreServedWithoutATaskTypeOfTheId() throws Exception {
        final var held = "shop__%s".formatted(DECLARED);
        theEngineHolds(held, modelWithStartMarkers(held));
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED, "legacy"));
        final var context = wired(deploymentService);

        deploymentService.startWorkflowProcessing("shop", context);

        final var subscribed = stream
                .call(0)
                .subscriptions(5);
        assertAll(
                () -> assertEquals(Set.of(), context
                        .wiredProcesses()
                        .get(DECLARED)
                        .jobTypes()),
                () -> assertEquals("vanillabp__started__shop__OrdersBeforeTheRename", subscribed
                        .getLast()
                        .getJobType()));
    }

    @Test
    @DisplayName("A held model with a task served elsewhere, which names no task definition, is read all the same, and the types of the other tasks are served")
    void aTaskWithoutATaskDefinitionInAHeldModelIsPassedOver(final CapturedOutput output) throws Exception {
        final var held = "shop__%s".formatted(DECLARED);
        theEngineHolds(held, model(held, null, null), model(held, "shop__%s__charge".formatted(DECLARED), null));
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED, "charge"));
        final var context = wired(deploymentService);

        deploymentService.startWorkflowProcessing("shop", context);

        assertAll(
                () -> assertEquals(Set.of("shop__OrdersBeforeTheRename__charge"), context
                        .wiredProcesses()
                        .get(DECLARED)
                        .jobTypes()),
                () -> assertFalse(output
                        .getOutOfThisTest()
                        .contains("could not be read"), output::getOutOfThisTest));
    }

    @Test
    @DisplayName("A declared process id none of whose task definitions a held model gives to a task is named in a WARN which says that nothing serves its workflows")
    void aDeclaredIdNothingServesIsAWarn(final CapturedOutput output) throws Exception {
        final var held = "shop__%s".formatted(DECLARED);
        theEngineHolds(held, model(held, "shop__%s__charge".formatted(DECLARED), null));
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify")
                .declaringWithoutAModel(DECLARED, "legacy"));
        final var context = wired(deploymentService);

        deploymentService.startWorkflowProcessing("shop", context);

        assertTrue(output
                .getOutOfThisTest()
                .contains(
                        """
                                WARN  o.p.z.d.ZenBpmDeclaredProcessWorkers - ZenBPM[orders]: workflow module 'shop' declares \
                                BPMN process 'OrdersBeforeTheRename' without deploying a model under it, and the engine holds it \
                                as 'shop__OrdersBeforeTheRename': nothing serves its workflows. Not served: the task \
                                definition(s) [legacy], which no model held under that id names.
                                """),
                output::getOutOfThisTest);
    }

    @Test
    @DisplayName("A module which declares no process id without a model asks the engine nothing while it starts")
    void aModuleWhichDeclaresNothingAsksTheEngineNothing() {
        final var deploymentService = deploymentService(NameClashAvoidance.USE_PREFIX, TestWiring
                .claiming("Orders")
                .servedElsewhere("Notify"));
        final var context = wired(deploymentService);

        deploymentService.startWorkflowProcessing("shop", context);

        assertAll(
                () -> assertEquals(4, stream
                        .call(0)
                        .subscriptions(4)
                        .size()),
                () -> assertEquals(List.of(), rest.requests()));
    }

    private ZenBpmDeploymentService deploymentService(
        final NameClashAvoidance mode,
        final TestWiring wiring
    ) {
        return deploymentService(mode, wiring, Duration.ofSeconds(42));
    }

    // the job-timeout of every task of the declared process is the one given, all others PT5M
    private ZenBpmDeploymentService deploymentService(
        final NameClashAvoidance mode,
        final TestWiring wiring,
        final Duration jobTimeoutOfTheDeclaredProcess
    ) {
        return new ZenBpmDeploymentService(ADAPTER_ID, registry, collaborators(mode, wiring.wiring()), (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> DECLARED.equals(bpmnProcessId) ? jobTimeoutOfTheDeclaredProcess
                    : Duration.ofMinutes(5), ZenBpmRetryBackoffResolver.NONE);
    }

    private static io.vanillabp.integration.adapter.spi.AdapterCollaborators collaborators(
        final NameClashAvoidance mode,
        final io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring wiring
    ) {
        return TestCollaborators.of(ADAPTER_ID, TestScoping.of(mode), wiring, TestStarts
                .everyProcessStartingAWorkflow());
    }

    private ZenBpmClientFactory factory() {
        return registry.getFactory(ADAPTER_ID);
    }

    private void theEngineHolds(
        final String scopedProcessId,
        final String... models
    ) throws JsonProcessingException {
        final var items = new StringBuilder();
        for (var index = 0; index < models.length; index++) {
            final var key = 4503599627370401L + index;
            items
                    .append(index == 0 ? "" : ",")
                    .append("{\"key\": %d, \"version\": %d, \"bpmnProcessId\": \"%s\"}".formatted(key, index + 1,
                            scopedProcessId));
            rest.answers("GET", "/v1/process-definitions/%d".formatted(key), 200, JSON.writeValueAsString(java.util.Map
                    .of("key", key, "version", index + 1, "bpmnProcessId", scopedProcessId, "bpmnData",
                            models[index])));
        }
        rest.answers("GET", "/v1/process-definitions", 200, "{\"items\": [%s], \"totalCount\": %d}".formatted(items,
                models.length));
    }

    // a version of the process held under the declared id, without start markers: a start event, a
    // service task, without a task definition where no type is given for it, and a user task where
    // a type is given for one
    private static String model(
        final String scopedProcessId,
        final String serviceTaskType,
        final String userTaskType
    ) {
        final var userTask = userTaskType == null ? "" : """
                <bpmn:userTask id="Review">
                  <bpmn:extensionElements><zenbpm:taskDefinition type="%s" /></bpmn:extensionElements>
                </bpmn:userTask>""".formatted(userTaskType);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="held" targetNamespace="test">
                  <bpmn:process id="%s" isExecutable="true">
                    <bpmn:startEvent id="Old" />
                    <bpmn:serviceTask id="Work">%s</bpmn:serviceTask>
                    %s
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(scopedProcessId, serviceTaskType == null ? ""
                : "<bpmn:extensionElements><zenbpm:taskDefinition type=\"%s\" /></bpmn:extensionElements>"
                        .formatted(serviceTaskType),
                userTask);
    }

    // a version of the process held under the declared id which an earlier version of this adapter
    // gave a start marker
    private static String modelWithStartMarkers(final String held) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="held" targetNamespace="test">
                  <bpmn:process id="%s" isExecutable="true">
                    <bpmn:startEvent id="Placed" />
                    <bpmn:serviceTask id="vanillabp__started__Placed">
                      <bpmn:extensionElements><zenbpm:taskDefinition type="vanillabp__started__%s" /></bpmn:extensionElements>
                    </bpmn:serviceTask>
                    <bpmn:serviceTask id="Work">
                      <bpmn:extensionElements><zenbpm:taskDefinition type="shop__%s__charge" /></bpmn:extensionElements>
                    </bpmn:serviceTask>
                  </bpmn:process>
                </bpmn:definitions>
                """
                .formatted(held, held, DECLARED);
    }

    private static ZenBpmProcessingContext wired(final ZenBpmDeploymentService deploymentService) {
        final var bpmn = fixture("wiring.bpmn");
        final var model = deploymentService
                .readBpmn("shop", "wiring.bpmn", new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)), true)
                .getFirst()
                .getValue();
        final var context = deploymentService.prepareBpmn("shop", null, "wiring.bpmn", model.bpmnProcessId(), model);
        deploymentService.wireBpmn("shop", "wiring.bpmn", model.bpmnProcessId(), model, context);
        return context;
    }

    private ZenBpmClientRegistry registry() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(rest.address());
        configuration.setGrpcAddress(stream.address());
        configuration.setGrpcPlaintext(true);
        return new ZenBpmClientRegistry(List.of(new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")));
    }

    private static String fixture(final String name) {
        try (var file = ZenBpmDeclaredProcessWorkersTest.class.getResourceAsStream("/models/%s".formatted(name))) {
            return new String(file.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
