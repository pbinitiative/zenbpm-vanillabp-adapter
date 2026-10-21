/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;
import org.pbinitiative.zenbpmadapter.test.EngineLog;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The adapter's features end to end on a BOOTED Quarkus application against the pinned engine: the
 * packaged extension deploys the workflow module <code>zenbpm-e2e</code>, one BPMN and one DMN file,
 * under <code>use-prefix</code>, subscribes the job type of its task, and starts a workflow through
 * the platform's two-phase start, whose instance evaluates the deployed decision and reaches the
 * task, whose job the adapter delivers to the application's method and completes. The processes
 * <code>TaskMatrix</code>, <code>TaskOutcomes</code>, <code>TaskTakenAway</code> and
 * <code>TaskFailures</code>, copies of the Spring Boot task fixtures, have their tasks served the
 * same way: the outcomes of a handler, a task left open and completed later, a task the engine took
 * away, and a handler which throws. The processes <code>Letters</code>, <code>Postcards</code> and
 * <code>Shipments</code>, copies of the Spring Boot message and push fixtures, take messages
 * published after and before their workflows wait for them, are started by a message and found
 * through their start row, and decide on a value the application pushed, into the workflow or into
 * the scope of a task. The process <code>Reviewing</code>, a copy of the Spring Boot user-task
 * fixture, has a user task which the adapter looks for every second, tells the application of once
 * when it is created, and which the application completes, after which the gateway behind it decides
 * on what the application shared, or cancels with a BPMN error, which the error boundary event of
 * the user task catches and which the application is told of once as a cancellation. The process
 * <code>Packing</code>, a copy of the Spring Boot multi-instance fixture, tells the tasks of a
 * parallel multi-instance sub-process and of a sequential multi-instance task their item, index and
 * total, and reports its end with its end event; the process <code>Receiving</code>, which somebody
 * starts past VanillaBP, gets the aggregate the application's method builds and is found afterwards
 * through its start row. The process <code>Dispatching</code>, a copy of the Spring Boot viewer
 * fixture, is shown by the viewer/history API: its definitions and that of the process it calls, the
 * BPMN of both, its history and, through the history context of its call activity, the history of
 * the instance it called. Version 1 of the processes <code>Sorting</code> and <code>Storing</code> is
 * deployed before the application starts, and a workflow of <code>Storing</code> started on it, as an
 * earlier version of the application would have, so the application deploys version 2 of each: a
 * task of either version of <code>Sorting</code> runs the method of its version's tag, and the boot names
 * the older version of <code>Storing</code>, its workflow, the task no method serves any more and
 * what that workflow misses. The application uses the Micrometer extension, so the adapter id
 * publishes its meters, which move with the work.
 * <p>
 * The Spring Boot integration tests prove the same, and that is the point: a correct core says
 * nothing about whether the glue of a platform ever calls it, which is why coverage is measured per
 * platform. The application runs in a JVM of its own, so everything is observed through its
 * <code>introspect/...</code> endpoints and its log file, and the JaCoCo agent is passed on into that
 * JVM, without which the run would count for nothing.
 * <p>
 * A prod-mode test boots its application once per class, and this application needs the engine to
 * boot, because it deploys while it starts. So this is ONE class against one engine, and each later
 * feature adds its cases, and its workflows to the application, here.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmWorkflowLifecycleTest {

    private static final String SCOPED_PROCESS_ID = "zenbpm-e2e__StartProcess";

    private static final String SCOPED_JOB_TYPE = "zenbpm-e2e__StartProcess__prepare";

    /**
     * How long what the engine does after a commit or a start may take to show. It answers in
     * milliseconds, so the limit only turns what never comes into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Path APPLICATION_LOG = Path
            .of("target", "zenbpm-e2e-application.log")
            .toAbsolutePath();

    // the lines of the engine, which a failing test replays, are logged at DEBUG. This module logs
    // through JBoss LogManager, as the application does, so the logback-test.xml which switches
    // them on in the other modules does not reach it, and the level is set here instead
    private static final Logger ENGINE_LINES = Logger.getLogger(EngineLog.LOGGER_NAME);

    static {
        ENGINE_LINES.setLevel(Level.FINE);
    }

    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static final ObjectMapper JSON = new ObjectMapper();

    // JUnit initializes the field below to register the extension before it decides that the class
    // is skipped without Docker, so nothing on the way may need the container then
    private static final boolean DOCKER_IS_THERE = DockerClientFactory
            .instance()
            .isDockerAvailable();

    // started here and not by the Testcontainers extension: the application's configuration needs
    // the mapped ports, and a prod-mode test reads its runtime properties while the field below is
    // initialized, before any extension runs. Ryuk removes the container when this JVM exits
    static {
        if (DOCKER_IS_THERE) {
            ENGINE.start();
        }
    }

    /**
     * Version 1 of <code>Sorting</code>, tagged <code>old-belt</code>, as an earlier version of the
     * application deployed it, under the prefixed id and job type the adapter gives the process of
     * the module.
     */
    private static final String SORTING_VERSION_ONE = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="sorting-v1" targetNamespace="x">
              <bpmn:process id="zenbpm-e2e__Sorting" isExecutable="true">
                <bpmn:extensionElements><zenbpm:versionTag value="old-belt" /></bpmn:extensionElements>
                <bpmn:startEvent id="Start"><bpmn:outgoing>Flow_ToSort</bpmn:outgoing></bpmn:startEvent>
                <bpmn:serviceTask id="Sort" name="Sort on the old belt">
                  <bpmn:extensionElements><zenbpm:taskDefinition type="zenbpm-e2e__Sorting__sort" /></bpmn:extensionElements>
                  <bpmn:incoming>Flow_ToSort</bpmn:incoming>
                  <bpmn:outgoing>Flow_ToEnd</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:endEvent id="End"><bpmn:incoming>Flow_ToEnd</bpmn:incoming></bpmn:endEvent>
                <bpmn:sequenceFlow id="Flow_ToSort" sourceRef="Start" targetRef="Sort" />
                <bpmn:sequenceFlow id="Flow_ToEnd" sourceRef="Sort" targetRef="End" />
              </bpmn:process>
            </bpmn:definitions>
            """;

    /**
     * Version 1 of <code>Storing</code>, whose task <code>weigh</code> the application serves no
     * more; a workflow on it waits an hour at a timer before it, so it still runs while the
     * application starts.
     */
    private static final String STORING_VERSION_ONE = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="storing-v1" targetNamespace="x">
              <bpmn:process id="zenbpm-e2e__Storing" isExecutable="true">
                <bpmn:startEvent id="Start"><bpmn:outgoing>Flow_ToWait</bpmn:outgoing></bpmn:startEvent>
                <bpmn:intermediateCatchEvent id="Wait">
                  <bpmn:incoming>Flow_ToWait</bpmn:incoming>
                  <bpmn:outgoing>Flow_ToTask</bpmn:outgoing>
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:intermediateCatchEvent>
                <bpmn:serviceTask id="Weigh">
                  <bpmn:extensionElements><zenbpm:taskDefinition type="zenbpm-e2e__Storing__weigh" /></bpmn:extensionElements>
                  <bpmn:incoming>Flow_ToTask</bpmn:incoming>
                  <bpmn:outgoing>Flow_ToEnd</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:endEvent id="End"><bpmn:incoming>Flow_ToEnd</bpmn:incoming></bpmn:endEvent>
                <bpmn:sequenceFlow id="Flow_ToWait" sourceRef="Start" targetRef="Wait" />
                <bpmn:sequenceFlow id="Flow_ToTask" sourceRef="Wait" targetRef="Weigh" />
                <bpmn:sequenceFlow id="Flow_ToEnd" sourceRef="Weigh" targetRef="End" />
              </bpmn:process>
            </bpmn:definitions>
            """;

    /**
     * The key of version 1 of <code>Sorting</code>, deployed before the application starts.
     */
    private static final long SORTING_VERSION_ONE_KEY = DOCKER_IS_THERE ? deployTheVersionsBeforeTheChange() : 0;

    private static final int HTTP_PORT = OneFreePortPerJvm.getPort();

    @RegisterExtension
    static final QuarkusProdModeTest application = new QuarkusProdModeTest()
            // the archive is the application, not the directory: classes travel by package, and
            // every resource needs a line of its own, so a file added to src/main/resources reaches
            // the application only once it is listed here
            .withApplicationRoot(jar -> jar
                    .addPackage("org.pbinitiative.zenbpmadapter.quarkus.test")
                    .addAsResource("application.yaml")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/StartProcess.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/rating.dmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/TaskMatrix.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/TaskOutcomes.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/TaskTakenAway.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/TaskFailures.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/Letters.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/Postcards.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/Shipments.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/Reviews.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/Packing.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/Receiving.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/Dispatching.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/Boxing.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/Sorting.bpmn")
                    .addAsResource("zenbpm-e2e/processes/zenbpm/Storing.bpmn")
                    .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
            // the surefire configuration of this module hands 'jacoco.agent' to this JVM
            .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
            .setRun(true)
            .setRuntimeProperties(Map.of(
                    "quarkus.http.port", Integer.toString(HTTP_PORT),
                    // only the test knows the ports the container was given
                    "vanillabp.adapters.zenbpm.rest-address", whereDockerIsThere(ENGINE::restAddress),
                    "vanillabp.adapters.zenbpm.grpc-address", whereDockerIsThere(ENGINE::grpcAddress),
                    // what the application logs is read from its file: the job stream reports
                    // there what reached it, and a failure inside the application is read there too
                    "quarkus.log.file.enabled", "true",
                    "quarkus.log.file.path", APPLICATION_LOG.toString()));

    @Test
    @DisplayName("The boot deploys the BPMN and the DMN file under the prefixed ids, registers version 1 of the process, and subscribes the job type of its task")
    void theBootDeploysTheModelAndTheDecision() {
        final var definitions = ENGINE.json("/v1/process-definitions?bpmnProcessId=%s".formatted(SCOPED_PROCESS_ID));
        final var decisions = ENGINE.json("/v1/dmn-resource-definitions?dmnResourceDefinitionId=zenbpm-e2e-rating");
        final var decision = ENGINE.json("/v1/dmn-resource-definitions/%s".formatted(decisions
                .path("items")
                .path(0)
                .path("key")
                .asText()));
        final var subscribed = applicationLog()
                .lines()
                .filter(line -> line
                        .contains("ZenBPM[zenbpm]: workflow module 'zenbpm-e2e' subscribed the job type(s) "))
                .findFirst()
                .orElse("");

        assertAll(
                () -> assertEquals(1, definitions
                        .path("totalCount")
                        .asInt(), definitions::toPrettyString),
                () -> assertEquals(1, definitions
                        .path("items")
                        .path(0)
                        .path("version")
                        .asInt(), definitions::toPrettyString),
                () -> assertEquals(1, decisions
                        .path("totalCount")
                        .asInt(), decisions::toPrettyString),
                // the decision inside the deployed file carries the scoped id
                () -> assertTrue(decision
                        .path("dmnData")
                        .asText()
                        .contains("id=\"zenbpm-e2e__rating\""), decision::toPrettyString),
                () -> assertEquals("1", text("introspect/versions/StartProcess")),
                () -> assertTrue(subscribed.contains("%s (job-timeout PT5M)".formatted(SCOPED_JOB_TYPE)),
                        APPLICATION_LOG::toString));
    }

    @Test
    @DisplayName("A start creates no instance before the commit and one after it, which evaluates the deployed decision, and whose job of the subscribed type reaches the application's method, which completes it, so the workflow ends")
    void aStartCreatesTheInstanceAfterTheCommit() {
        final var id = uniqueAggregateId();

        final var started = post("introspect/start/%s".formatted(id));
        final var instanceKey = awaitTheInstanceOf(id).get("key");
        // every case of the class starts the same process on one engine, so the job is the one of
        // this instance, and the method is asked about this aggregate
        final var job = awaitTheJobOf(instanceKey);
        await(() -> Boolean.TRUE.equals(object("introspect/prepared/%s".formatted(id)).get("prepared")),
                "the method of the task to run for aggregate '%s'".formatted(id));
        await(() -> "COMPLETED".equals(stateOfJob(instanceKey, job)), "job %s of the started instance to be completed"
                .formatted(job));
        await(() -> "COMPLETED".equals(awaitTheInstanceOf(id).get("state")), "the workflow of aggregate '%s' to end"
                .formatted(id));
        final var instance = awaitTheInstanceOf(id);
        final var variables = variablesOf(instance);

        assertAll(
                () -> assertEquals(0, started.get("instancesInsideTheTransaction")),
                () -> assertEquals("COMPLETED", instance.get("state")),
                () -> assertEquals(SCOPED_PROCESS_ID, instance.get("bpmnProcessId")),
                () -> assertEquals(1, instance.get("version")),
                () -> assertEquals(id, instance.get("businessKey")),
                () -> assertEquals(id, variables.get("id")),
                () -> assertEquals("a book", variables.get("item")),
                () -> assertFalse(variables.containsKey("note"), variables::toString),
                // the engine hands a decision's result to the process only through an output
                // mapping: the second business rule task maps nothing, so its result variable
                // stays inside the task
                () -> assertEquals(Map.of("grade", "standard"), variables.get("rating"), variables::toString),
                () -> assertFalse(variables.containsKey("unmappedRating"), variables::toString));
    }

    @Test
    @DisplayName("A rolled-back start leaves no aggregate behind, and no instance appears while the outbox dispatches two later starts")
    void aRolledBackStartCreatesNothing() {
        final var rolledBackId = uniqueAggregateId();

        post("introspect/rollback/%s".formatted(rolledBackId));
        // a commit wakes the outbox, which then dispatches every entry that is due. So once the
        // instance of a later start exists, the outbox ran after the rollback, and an entry the
        // rollback had left behind would have been dispatched in that run
        for (var later = 0; later < 2; ++later) {
            final var laterId = uniqueAggregateId();
            post("introspect/start/%s".formatted(laterId));
            awaitTheInstanceOf(laterId);
        }

        assertAll(
                () -> assertEquals(false, object("introspect/aggregates/%s".formatted(rolledBackId)).get("exists")),
                () -> assertEquals(List.of(), instancesOf(rolledBackId)));
    }

    @Test
    @DisplayName("A service task, a send task, a business rule task served by a job and a service task in a sub-process each run once, and the gateways behind the task and behind the sub-process decide on what the handlers computed")
    void serviceTaskRunsAndGatewaySeesValues() {
        final var id = uniqueAggregateId();

        post("introspect/tasks/parcels/%s".formatted(id));
        final var instanceKey = awaitTheEndOf(id);

        final var parcel = object("introspect/tasks/parcels/%s".formatted(id));
        final var visited = visited(instanceKey);
        final var jobs = jobsOf(instanceKey);
        assertAll(
                () -> assertEquals("COMPLETED", awaitTheInstanceOf(id).get("state")),
                // the workflow started with express false, and the handler of the weighing made
                // the parcel express
                () -> assertTrue(visited.containsAll(List.of("NotifyCourier", "Packed")), visited::toString),
                () -> assertFalse(visited.contains("NotPacked"), visited::toString),
                // each handler committed exactly one run, the one of the sub-process's job too,
                // whose job belongs to the sub-process's instance and so is not among the jobs
                // below
                () -> assertEquals(1, parcel.get("weighings"), parcel::toString),
                () -> assertEquals(1, parcel.get("courierNotifications"), parcel::toString),
                () -> assertEquals(1, parcel.get("ratings"), parcel::toString),
                () -> assertEquals(1, parcel.get("packings"), parcel::toString),
                () -> assertEquals(true, parcel.get("packed"), parcel::toString),
                () -> assertEquals(List.of("COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED"), jobs
                        .stream()
                        .map(job -> job.get("state"))
                        .toList(), jobs::toString),
                // the first job is the start marker's, which the application's own start passes
                () -> assertEquals("vanillabp__started__Start", jobs
                        .getFirst()
                        .get("elementId"), jobs::toString));
    }

    @Test
    @DisplayName("A parameter the model maps into a task is bound to the method's @TaskParam")
    void declaredTaskParametersAreBound() {
        final var id = uniqueAggregateId();

        post("introspect/tasks/parcels/%s".formatted(id));
        awaitTheEndOf(id);

        assertEquals("standard", object("introspect/tasks/parcels/%s".formatted(id)).get("tariff"));
    }

    @Test
    @DisplayName("A BPMN error reaches the engine with the code the deployed model carries: the error boundary event takes over, the gateway behind it decides on what the handler computed, and the handler's work stays committed")
    void bpmnErrorRoutesBoundary() {
        final var id = uniqueAggregateId();

        post("introspect/tasks/claims/%s?scenario=error&errorCode=damaged".formatted(id));
        final var instanceKey = awaitTheEndOf(id);

        final var claim = object("introspect/tasks/claims/%s".formatted(id));
        final var visited = visited(instanceKey);
        assertAll(
                () -> assertEquals("COMPLETED", awaitTheInstanceOf(id).get("state")),
                () -> assertTrue(visited.containsAll(List.of("Damaged", "Refunded")), visited::toString),
                () -> assertFalse(visited.contains("Inspected"), visited::toString),
                () -> assertEquals("TERMINATED", jobOf(instanceKey, "Inspect").get("state")),
                () -> assertEquals(true, claim.get("inspected"), claim::toString),
                () -> assertEquals(true, claim.get("refundable"), claim::toString));
    }

    @Test
    @DisplayName("A task left open for its asynchronous completion stays open while the engine hands it out again, its handler runs once, and the application's completion ends the workflow on what it changed before")
    void asyncTaskIsCompletedLater() {
        final var id = uniqueAggregateId();

        post("introspect/tasks/claims/%s?scenario=pending".formatted(id));
        final var instanceKey = (String) awaitTheInstanceOf(id).get("key");
        final var taskId = await(() -> Optional.ofNullable((String) object("introspect/tasks/claims/%s".formatted(id))
                .get("approvalTaskId")), "the approval of claim '%s' to be delivered".formatted(id));
        final var firstDelivery = deliveryTokenOf(taskId);
        // the window of application.yaml is two seconds
        await(() -> deliveryTokenOf(taskId) > firstDelivery, "the approval to be handed out again");
        final var stillOpen = jobOf(instanceKey, "AwaitApproval").get("state");
        post("introspect/tasks/claims/%s/approval".formatted(id));
        awaitTheEndOf(id);

        final var visited = visited(instanceKey);
        assertAll(
                () -> assertEquals("ACTIVE", stillOpen),
                () -> assertEquals("COMPLETED", awaitTheInstanceOf(id).get("state")),
                () -> assertTrue(visited.containsAll(List.of("ApprovalDecision", "Approved")), visited::toString),
                () -> assertFalse(visited.contains("Rejected"), visited::toString),
                () -> assertEquals("COMPLETED", jobOf(instanceKey, "AwaitApproval").get("state")),
                () -> assertEquals(1, object("introspect/tasks/claims/%s".formatted(id)).get("approvalRequests")));
    }

    @Test
    @DisplayName("A task an interrupting timer boundary event took away reaches the application as cancelled at a later delivery of its workflow, and only once")
    void aTaskTakenAwayIsReportedCanceled() {
        final var id = uniqueAggregateId();

        post("introspect/tasks/reservations/%s".formatted(id));
        final var holdTaskId = await(() -> Optional.ofNullable((String) reservation(id).get("holdTaskId")),
                "the hold of reservation '%s' to be delivered".formatted(id));
        final var checkTaskId = await(() -> Optional.ofNullable((String) reservation(id).get("checkTaskId")),
                "the check of reservation '%s' to be delivered".formatted(id));
        // the check comes back every two seconds, and the timer takes the hold away after two
        await(() -> ((Integer) reservation(id).get("holdCancellations")) > 0, "the hold to be reported cancelled");
        final var deliveredBefore = deliveryTokenOf(checkTaskId);
        await(() -> deliveryTokenOf(checkTaskId) >= deliveredBefore + 2, "two more deliveries of the check");

        assertAll(
                () -> assertEquals(1, reservation(id).get("holdCancellations")),
                () -> assertEquals("terminated", ENGINE
                        .json("/v1/jobs/%s".formatted(holdTaskId))
                        .path("state")
                        .asText()),
                () -> assertEquals("active", ENGINE
                        .json("/v1/jobs/%s".formatted(checkTaskId))
                        .path("state")
                        .asText()));
    }

    @Test
    @DisplayName("A handler which throws is failed at the engine once per delivery: a model with two retries is delivered twice, the backoff apart, and then raises an incident naming the attempts, the exception and the aggregate")
    void aThrownHandlerIsFailedUntilItsRetriesRunOut() {
        final var id = uniqueAggregateId();

        post("introspect/tasks/payments/%s".formatted(id));
        final var instanceKey = (String) awaitTheInstanceOf(id).get("key");
        final var incident = await(() -> {
            final var incidents = ENGINE
                    .json("/v1/process-instances/%s/incidents".formatted(instanceKey))
                    .path("items");
            return incidents.isEmpty() ? Optional.empty() : Optional.of(incidents.get(0));
        }, "an incident of the payment '%s'".formatted(id));

        final List<Long> attempts = api()
                .get("introspect/tasks/payments/%s/attempts".formatted(id))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$", Long.class);
        final var charge = jobOf(instanceKey, "Charge");
        assertAll(
                () -> assertEquals(2, attempts.size(), attempts::toString),
                () -> assertTrue(attempts.get(1) - attempts.get(0) >= 1000, attempts::toString),
                () -> assertEquals("Charge", incident
                        .path("elementId")
                        .asText()),
                () -> assertEquals(
                        "Task 'charge' of workflow aggregate '%s' threw java.lang.IllegalStateException: the card was declined (job %s: 2 attempts, retries exhausted)"
                                .formatted(id, charge.get("key")),
                        incident
                                .path("message")
                                .asText()),
                () -> assertEquals("FAILED", charge.get("state")),
                () -> assertEquals(2, ENGINE
                        .json("/v1/jobs/%s/failures".formatted(charge.get("key")))
                        .path("items")
                        .size()));
    }

    @Test
    @DisplayName("A correlation resumes the workflow which waits for the message, and the message carries the values the aggregate shares now")
    void correlateMessageResumesTheWorkflow() {
        final var id = uniqueAggregateId();

        post("introspect/messages/letters/%s?scenario=await".formatted(id));
        final var instanceKey = awaitTheRootOf(id);
        await(() -> waitingElementsOf(instanceKey).contains("AwaitGoAhead"), "the letter '%s' to await the go-ahead"
                .formatted(id));
        post("introspect/messages/letters/%s/go-ahead".formatted(id));
        final var ended = awaitTheEndOfInstance(instanceKey);

        assertAll(
                () -> assertEquals("completed", ended
                        .path("state")
                        .asText()),
                () -> assertEquals("approved", ended
                        .path("variables")
                        .path("status")
                        .asText()),
                () -> assertTrue(visited(instanceKey).contains("Sent"), () -> visited(instanceKey).toString()));
    }

    @Test
    @DisplayName("A message published before the workflow waits for it is repeated every workflow-visibility-timeout without counting, until it correlates once the workflow passed its timer")
    void aMessagePublishedBeforeTheSubscriptionIsRepeatedUntilItCorrelates() {
        final var id = uniqueAggregateId();
        final var startedAt = Instant.now();

        post("introspect/messages/letters/%s?scenario=delay".formatted(id));
        final var instanceKey = awaitTheRootOf(id);
        post("introspect/messages/letters/%s/go-ahead".formatted(id));
        awaitTheEndOfInstance(instanceKey);
        final var tookFromTheStart = Duration.between(startedAt, Instant.now());

        final var handedBack = applicationLog()
                .lines()
                .filter(line -> line.contains("for aggregate '%s' cannot run yet - the outbox entry".formatted(id)))
                .toList();
        assertAll(
                () -> assertTrue(visited(instanceKey).contains("SentLater"), () -> visited(instanceKey).toString()),
                () -> assertTrue(tookFromTheStart.compareTo(Duration.ofSeconds(5)) >= 0, tookFromTheStart::toString),
                () -> assertFalse(handedBack.isEmpty(), APPLICATION_LOG::toString),
                () -> assertTrue(handedBack
                        .stream()
                        .allMatch(line -> line.contains("is dispatched again in PT2S")), handedBack::toString));
    }

    @Test
    @DisplayName("A workflow started by a message under the default message-start-lookup start-row is found through the start row its start marker wrote, so a correlation reaches it")
    void startWorkflowByMessageIsFoundThroughItsStartRow() {
        final var id = uniqueAggregateId();

        post("introspect/messages/postcards/%s".formatted(id));
        final var startRow = await(() -> Optional
                .of(object("introspect/messages/postcards/%s/start-row".formatted(id)))
                .filter(row -> !row.isEmpty()), "the start row of postcard '%s'".formatted(id));
        final var instanceKey = (String) startRow.get("workflowId");
        await(() -> waitingElementsOf(instanceKey).contains("AwaitStamp"), "the postcard '%s' to await the stamp"
                .formatted(id));
        final var started = ENGINE.json("/v1/process-instances/%s".formatted(instanceKey));
        post("introspect/messages/postcards/%s/stamp".formatted(id));
        final var ended = awaitTheEndOfInstance(instanceKey);

        assertAll(
                () -> assertEquals("Postcards", startRow.get("bpmnProcessId")),
                () -> assertEquals("zenbpm", startRow.get("adapterId")),
                () -> assertEquals("zenbpm-e2e__Postcards", started
                        .path("bpmnProcessId")
                        .asText()),
                () -> assertTrue(started
                        .path("businessKey")
                        .asText()
                        .isEmpty(), started::toPrettyString),
                () -> assertEquals(id, started
                        .path("variables")
                        .path("id")
                        .asText()),
                () -> assertEquals("completed", ended
                        .path("state")
                        .asText()),
                () -> assertEquals("stamped", ended
                        .path("variables")
                        .path("status")
                        .asText()));
    }

    @Test
    @DisplayName("A push of the workflow reaches the gateway behind the timer the workflow waits at, which takes the branch of the pushed value")
    void aGlobalPushReachesTheGateway() {
        final var id = uniqueAggregateId();

        post("introspect/messages/shipments/%s?scenario=gateway".formatted(id));
        final var instanceKey = awaitTheRootOf(id);
        await(() -> waitingElementsOf(instanceKey).contains("WaitToShip"), "the shipment '%s' to wait".formatted(id));
        post("introspect/messages/shipments/%s/push".formatted(id));
        final var ended = awaitTheEndOfInstance(instanceKey);

        final var visited = visited(instanceKey);
        assertAll(
                () -> assertTrue(visited.contains("Shipped"), visited::toString),
                () -> assertFalse(visited.contains("Held"), visited::toString),
                () -> assertTrue(ended
                        .path("variables")
                        .path("approved")
                        .asBoolean(), ended::toPrettyString));
    }

    @Test
    @DisplayName("A push into the scope of a task writes the instance of the sub-process the task waits in and leaves the root's values as they were; the completion then writes them everywhere")
    void aTaskScopedPushReachesTheChildScope() {
        final var id = uniqueAggregateId();

        post("introspect/messages/shipments/%s?scenario=task".formatted(id));
        final var instanceKey = awaitTheRootOf(id);
        await(() -> Optional.ofNullable((String) object("introspect/messages/shipments/%s".formatted(id)).get(
                "inspection")), "the inspection of shipment '%s' to be left open".formatted(id));
        final var inspection = ENGINE
                .json("/v1/process-instances/%s/child-processes".formatted(instanceKey))
                .path("partitions")
                .path(0)
                .path("items")
                .path(0)
                .path("key")
                .asText();
        post("introspect/messages/shipments/%s/push-into-the-inspection".formatted(id));
        await(() -> ENGINE
                .json("/v1/process-instances/%s".formatted(inspection))
                .path("variables")
                .path("approved")
                .asBoolean(), "the sub-process's instance to carry the pushed value");
        final var rootAfterThePush = ENGINE
                .json("/v1/process-instances/%s".formatted(instanceKey))
                .path("variables")
                .path("approved");
        post("introspect/messages/shipments/%s/inspection".formatted(id));
        awaitTheEndOfInstance(instanceKey);

        final var visited = visited(instanceKey);
        assertAll(
                () -> assertTrue(rootAfterThePush.isBoolean(), rootAfterThePush::toString),
                () -> assertFalse(rootAfterThePush.asBoolean(), rootAfterThePush::toString),
                () -> assertTrue(visited.contains("InspectedForShipping"), visited::toString));
    }

    @Test
    @DisplayName("A user task is told to the application once as created, with its job key as the task id, and its completion continues the workflow: the gateway behind it decides on what the application changed before the completion, and nothing is told as cancelled")
    void userTaskNotificationAndCompletion() {
        final var id = uniqueAggregateId();

        post("introspect/user-tasks/reviews/%s".formatted(id));
        final var instanceKey = (String) awaitTheInstanceOf(id).get("key");
        final var taskId = await(() -> Optional.ofNullable((String) review(id).get("reviewTaskId")),
                "the creation of the review of '%s' to be told".formatted(id));
        watchThreeLooks();
        final var creationsBefore = review(id).get("creations");
        post("introspect/user-tasks/reviews/%s/acceptance".formatted(id));
        awaitTheEndOf(id);
        watchThreeLooks();

        final var visited = visited(instanceKey);
        final var review = review(id);
        assertAll(
                () -> assertEquals(1, creationsBefore, review::toString),
                () -> assertEquals("USER_TASK", ENGINE
                        .json("/v1/jobs/%s".formatted(taskId))
                        .path("elementType")
                        .asText()),
                () -> assertEquals(taskId, jobOf(instanceKey, "Review").get("key")),
                () -> assertEquals("COMPLETED", awaitTheInstanceOf(id).get("state")),
                () -> assertTrue(visited.containsAll(List.of("Decision", "Accepted")), visited::toString),
                () -> assertFalse(visited.contains("Rejected"), visited::toString),
                () -> assertEquals("COMPLETED", jobOf(instanceKey, "Review").get("state")),
                () -> assertEquals(1, review.get("creations"), review::toString),
                () -> assertEquals(0, review.get("cancellations"), review::toString));
    }

    @Test
    @DisplayName("A user task the application cancels with a BPMN error is caught by the error boundary event of the user task, and the application is told once that it was cancelled")
    void userTaskCancellationByError() {
        final var id = uniqueAggregateId();

        post("introspect/user-tasks/reviews/%s".formatted(id));
        final var instanceKey = (String) awaitTheInstanceOf(id).get("key");
        await(() -> Optional.ofNullable((String) review(id).get("reviewTaskId")),
                "the creation of the review of '%s' to be told".formatted(id));
        post("introspect/user-tasks/reviews/%s/withdrawal?errorCode=withdrawn".formatted(id));
        awaitTheEndOf(id);
        await(() -> ((Integer) review(id).get("cancellations")) > 0, "the cancellation to be told");
        watchThreeLooks();

        final var visited = visited(instanceKey);
        final var review = review(id);
        assertAll(
                () -> assertEquals("COMPLETED", awaitTheInstanceOf(id).get("state")),
                () -> assertTrue(visited.containsAll(List.of("Withdrawn", "ReviewWithdrawn")), visited::toString),
                () -> assertFalse(visited.contains("Decision"), visited::toString),
                () -> assertEquals("TERMINATED", jobOf(instanceKey, "Review").get("state")),
                () -> assertEquals(1, review.get("creations"), review::toString),
                () -> assertEquals(1, review.get("cancellations"), review::toString));
    }

    @Test
    @DisplayName("The end of a workflow reaches the application's @WorkflowEnded method with its end event, after which the instance completes, and the boot subscribed the type of the end markers")
    void theWorkflowEndIsReported() {
        final var id = uniqueAggregateId();
        final var subscribed = applicationLog()
                .lines()
                .filter(line -> line
                        .contains("ZenBPM[zenbpm]: workflow module 'zenbpm-e2e' subscribed the job type(s) "))
                .findFirst()
                .orElse("");

        post("introspect/lifecycle/packings/%s".formatted(id));
        final var endEvent = await(() -> Optional
                .of((String) object("introspect/lifecycle/packings/%s".formatted(id)).get("endEvent"))
                .filter(reported -> !reported.isEmpty()), "the end of packing '%s'".formatted(id));
        final var ended = awaitTheEndOfInstance(awaitTheRootOf(id));

        assertAll(
                () -> assertEquals("Packed", endEvent),
                () -> assertEquals("completed", ended
                        .path("state")
                        .asText()),
                () -> assertTrue(subscribed.contains("vanillabp__ended__zenbpm-e2e__Packing"), subscribed));
    }

    @Test
    @DisplayName("A workflow somebody started over the engine's REST API without a business key gets the aggregate the application's @WorkflowStartedByBpms method built and named, and is found afterwards through its start row, so a correlation reaches it")
    void theEngineStartsAWorkflowOnItsOwn() throws IOException, InterruptedException {
        final var orderRef = "order-%s".formatted(UUID.randomUUID());

        final var started = startPastVanillaBp(orderRef);
        final var receipt = await(() -> receiptOf(orderRef), "the receipt of order '%s'".formatted(orderRef));
        final var startRow = await(() -> Optional
                .of(object("introspect/lifecycle/receipts/%s/start-row".formatted(orderRef)))
                .filter(row -> !row.isEmpty()), "the start row of order '%s'".formatted(orderRef));
        await(() -> waitingElementsOf(started).contains("Unloaded"), "the receipt of order '%s' to await the unloading"
                .formatted(orderRef));
        post("introspect/lifecycle/receipts/%s/unloaded".formatted(orderRef));
        final var ended = awaitTheEndOfInstance(started);

        assertAll(
                () -> assertEquals("receipt-%s".formatted(orderRef), receipt.get("id")),
                () -> assertEquals(orderRef, receipt.get("orderRef")),
                () -> assertEquals(started, startRow.get("workflowId")),
                () -> assertEquals("Receiving", startRow.get("bpmnProcessId")),
                () -> assertTrue(ended
                        .path("businessKey")
                        .asText()
                        .isEmpty(), ended::toPrettyString),
                () -> assertEquals("receipt-%s".formatted(orderRef), ended
                        .path("variables")
                        .path("id")
                        .asText()),
                () -> assertEquals("completed", ended
                        .path("state")
                        .asText()));
    }

    @Test
    @DisplayName("The tasks of a parallel multi-instance sub-process over three items and of a sequential multi-instance task are told the item, the index counted from 0 and the total of their iteration")
    void multiInstanceBindsElementIndexAndTotal() {
        final var id = uniqueAggregateId();

        post("introspect/lifecycle/packings/%s".formatted(id));
        final var told = await(() -> Optional
                .of(object("introspect/lifecycle/packings/%s".formatted(id)))
                .filter(packing -> ((List<?>) packing.get("weighed")).size() == 3), "the weighing of packing '%s'"
                        .formatted(id));

        assertAll(
                () -> assertEquals(List.of("a:0/3", "b:1/3", "c:2/3"), ((List<?>) told.get("packed"))
                        .stream()
                        .map(String::valueOf)
                        .sorted()
                        .toList()),
                () -> assertEquals(List.of("a:0/3", "b:1/3", "c:2/3"), told.get("weighed")));
    }

    @Test
    @DisplayName("The viewer serves the definitions of a workflow with a call activity, the BPMN the engine holds of both, its history, which names the instance the call activity started, and that instance's history and definitions through it")
    void theViewerServesDefinitionXmlAndHistory() {
        final var id = "dispatch-%s".formatted(UUID.randomUUID());

        post("introspect/viewer/dispatches/%s".formatted(id));
        final var history = await(() -> Optional
                .of(object("introspect/viewer/dispatches/%s/history".formatted(id)))
                .filter(read -> !"".equals(read.get("endTime"))), "the end of dispatch %s".formatted(id));
        final var definitions = list("introspect/viewer/dispatches/%s/definitions".formatted(id));
        final var dispatching = definitions.getFirst();
        final var boxing = definitions.getLast();
        final var dispatchingXml = bpmnXmlOf(dispatching);
        final var boxingXml = bpmnXmlOf(boxing);
        final var elements = elementsOf(history);
        final var context = elements
                .stream()
                .filter(element -> "CallBoxing".equals(element.get("elementId")))
                .map(element -> (String) element.get("context"))
                .findFirst()
                .orElse("");
        final var boxingHistory = object("introspect/viewer/dispatches/%s/history?context=%s".formatted(id, context));
        final var boxingDefinitions = list("introspect/viewer/dispatches/%s/definitions?context=%s".formatted(id,
                context));

        assertAll(
                () -> assertEquals(2, definitions.size(), definitions::toString),
                () -> assertTrue(String
                        .valueOf(dispatching.get("id"))
                        .startsWith("zenbpm#"), definitions::toString),
                () -> assertEquals("Dispatching", dispatching.get("bpmnProcessId")),
                () -> assertEquals("1", dispatching.get("version")),
                () -> assertEquals(List.of(), dispatching.get("usedByElements")),
                () -> assertEquals("Boxing", boxing.get("bpmnProcessId")),
                () -> assertEquals(List.of("CallBoxing"), boxing.get("usedByElements")),
                () -> assertTrue(dispatchingXml.contains("id=\"zenbpm-e2e__Dispatching\""), dispatchingXml),
                () -> assertTrue(dispatchingXml.contains("id=\"vanillabp__ended__Dispatched\""), dispatchingXml),
                () -> assertTrue(boxingXml.contains("id=\"zenbpm-e2e__Boxing\""), boxingXml),
                () -> assertEquals(dispatching.get("id"), history.get("processDefinitionId")),
                () -> assertEquals(List.of("Ordered", "Flow_ToInspect", "Inspect", "Flow_ToWrapping", "Wrapping",
                        "WrappingStarted", "Flow_ToWrap", "Wrap", "Flow_ToWrapped", "Wrapped", "Flow_ToBoxing",
                        "CallBoxing", "Flow_ToLabel", "Label", "Label", "Label", "Flow_ToDispatched", "Dispatched"),
                        elements
                                .stream()
                                .map(element -> element.get("elementId"))
                                .toList()),
                () -> assertEquals(List.of("MULTI_INSTANCE", "SERVICE_TASK", "SERVICE_TASK"), elements
                        .stream()
                        .filter(element -> "Label".equals(element.get("elementId")))
                        .map(element -> element.get("elementType"))
                        .toList()),
                () -> assertFalse(context.isEmpty(), elements::toString),
                () -> assertEquals(boxing.get("id"), boxingHistory.get("processDefinitionId")),
                () -> assertEquals(List.of("BoxingStarted", "Flow_ToBox", "Box", "Flow_ToBoxed", "Boxed"),
                        elementsOf(boxingHistory)
                                .stream()
                                .map(element -> element.get("elementId"))
                                .toList()),
                () -> assertEquals(List.of(boxing.get("id")), boxingDefinitions
                        .stream()
                        .map(definition -> definition.get("id"))
                        .toList()),
                // a context which names no instance a call activity of the workflow started
                () -> api()
                        .get("introspect/viewer/dispatches/%s/history?context=no-key".formatted(id))
                        .then()
                        .statusCode(404));
    }

    @Test
    @DisplayName("A task runs the method of the tag its version carries: one of version 1, which an earlier version of the application deployed under the tag old-belt, the method of version = \"old-belt\", and one of the version the application deployed the method of version = \"new-belt\"")
    void aVersionedMethodRoutes() {
        final var onTheLatestVersion = "sorting-%s".formatted(UUID.randomUUID());
        final var onVersionOne = "sorting-%s".formatted(UUID.randomUUID());

        post("introspect/sortings/%s".formatted(onTheLatestVersion));
        post("introspect/sortings/%s/kept".formatted(onVersionOne));
        startOnVersionOneOfSorting(onVersionOne);
        final var latest = await(() -> servedBy(onTheLatestVersion), "the task of %s".formatted(onTheLatestVersion));
        final var first = await(() -> servedBy(onVersionOne), "the task of %s".formatted(onVersionOne));
        final var versions = ENGINE.json("/v1/process-definitions?bpmnProcessId=zenbpm-e2e__Sorting");

        assertAll(
                () -> assertEquals(2, versions
                        .path("totalCount")
                        .asInt(), versions::toPrettyString),
                () -> assertEquals("new-belt", latest),
                () -> assertEquals("old-belt", first));
    }

    @Test
    @DisplayName("The boot names the older version of Storing, the workflow which still runs on it, its task which no method serves any more and what that workflow misses")
    void theBootNamesAnOlderVersionItsWorkflowAndWhatItMisses() {
        final var log = applicationLog();

        assertAll(
                () -> assertTrue(log.contains(DeployedProcessVersionsCheck.A_VERSION_OF_A_PROCESS.formatted("1",
                        "Storing")), log),
                () -> assertTrue(log.contains("1 workflow(s) %s".formatted(
                        DeployedProcessVersionsCheck.STILL_RUN_ON_THIS_VERSION)), log),
                () -> assertTrue(log.contains("'weigh'"), log),
                () -> assertTrue(log.contains(DeployedProcessVersionsCheck.SERVED_BY_NO_METHOD), log),
                () -> assertTrue(log.contains("the end of such a workflow is not reported to a @WorkflowEnded method"),
                        log));
    }

    @Test
    @DisplayName("Where the application uses the Micrometer extension, the adapter id publishes its eleven meters, and they move with the work: a workflow which ends adds received and completed jobs, and the looks at the user tasks go on every second")
    void theMetersOfTheAdapterIdMoveWithTheWork() {
        final var before = metersOf("zenbpm");
        final var id = uniqueAggregateId();

        post("introspect/start/%s".formatted(id));
        awaitTheEndOf(id);
        await(() -> metersOf("zenbpm").get(ZenBpmMetrics.USER_TASK_POLLS) > before.get(ZenBpmMetrics.USER_TASK_POLLS),
                "a further look at the user tasks of 'Reviewing'");
        final var after = metersOf("zenbpm");

        assertAll(
                () -> assertEquals(Set.of(ZenBpmMetrics.JOBS_RECEIVED, ZenBpmMetrics.JOBS_COMPLETED,
                        ZenBpmMetrics.JOBS_FAILED, ZenBpmMetrics.JOBS_QUEUED, ZenBpmMetrics.JOBS_REDELIVERED,
                        ZenBpmMetrics.LOCK_RENEWED, ZenBpmMetrics.LOCK_LOST, ZenBpmMetrics.STREAM_RECONNECTS,
                        ZenBpmMetrics.USER_TASK_POLLS, ZenBpmMetrics.SLOTS_BUSY, ZenBpmMetrics.SLOTS_FREE),
                        after
                                .keySet()),
                // the job of the task and that of the end marker, and the others the class runs meanwhile
                () -> assertTrue(after.get(ZenBpmMetrics.JOBS_RECEIVED) >= before.get(ZenBpmMetrics.JOBS_RECEIVED) + 1,
                        () -> "%s, then %s".formatted(before, after)),
                () -> assertTrue(
                        after.get(ZenBpmMetrics.JOBS_COMPLETED) >= before.get(ZenBpmMetrics.JOBS_COMPLETED) + 1,
                        () -> "%s, then %s".formatted(before, after)),
                () -> assertTrue(after.get(ZenBpmMetrics.SLOTS_FREE) + after.get(ZenBpmMetrics.SLOTS_BUSY) > 0,
                        after::toString),
                () -> assertEquals(Map.of(), metersOf("an-adapter-id-nobody-configured")));
    }

    // the ids of every test are new, because all tests of the class share one engine
    private static String uniqueAggregateId() {
        return "e2e-%s".formatted(UUID.randomUUID());
    }

    // the root instance of the aggregate's workflow: the instances below it carry the business key
    // as well
    private static String awaitTheRootOf(final String id) {
        return await(() -> {
            final var roots = new ArrayList<String>();
            ENGINE
                    .json("/v1/process-instances?businessKey=%s".formatted(id))
                    .path("partitions")
                    .forEach(partition -> partition
                            .path("items")
                            .forEach(instance -> {
                                if (instance
                                        .path("parentProcessInstanceKey")
                                        .isMissingNode()) {
                                    roots.add(instance
                                            .path("key")
                                            .asText());
                                }
                            }));
            return roots
                    .stream()
                    .findFirst();
        }, "the workflow of aggregate '%s'".formatted(id));
    }

    private static JsonNode awaitTheEndOfInstance(final String instanceKey) {
        return await(() -> Optional
                .of(ENGINE.json("/v1/process-instances/%s".formatted(instanceKey)))
                .filter(instance -> !"active".equals(instance
                        .path("state")
                        .asText())),
                "instance %s to end".formatted(instanceKey));
    }

    // the elements an instance is in now
    private static List<String> waitingElementsOf(final String instanceKey) {
        final var waiting = new ArrayList<String>();
        ENGINE
                .json("/v1/process-instances/%s".formatted(instanceKey))
                .path("activeElementInstances")
                .forEach(element -> waiting.add(element
                        .path("elementId")
                        .asText()));
        return waiting;
    }

    // the adapter's client is not on the test's classpath, and the engine's helper only reads
    private static String startPastVanillaBp(final String orderRef) throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            final var answer = client.send(HttpRequest
                    .newBuilder(URI.create("%s/v1/process-instances".formatted(ENGINE.restAddress())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"bpmnProcessId": "zenbpm-e2e__Receiving", "variables": {"orderRef": "%s"}}"""
                            .formatted(orderRef)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, answer.statusCode(), answer::body);
            return JSON
                    .readTree(answer.body())
                    .path("key")
                    .asText();
        }
    }

    // empty while the application built no receipt for the order
    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> receiptOf(final String orderRef) {
        final var answer = api().get("introspect/lifecycle/receipts/%s".formatted(orderRef));
        if (answer.statusCode() == 404) {
            return Optional.empty();
        }
        return Optional.of(answer
                .then()
                .statusCode(200)
                .extract()
                .as(Map.class));
    }

    private static Map<String, Object> awaitTheInstanceOf(final String id) {
        await(() -> !instancesOf(id).isEmpty(), "an instance of aggregate '%s'".formatted(id));
        final var instances = instancesOf(id);
        assertEquals(1, instances.size(), instances::toString);
        return instances.getFirst();
    }

    // the job of the instance's task: the job of its start marker, which comes first, is left out
    private static String awaitTheJobOf(final Object instanceKey) {
        await(() -> !tasksOf(instanceKey).isEmpty(), "a job of a task of instance %s".formatted(instanceKey));
        final var jobs = tasksOf(instanceKey);
        assertEquals(1, jobs.size(), jobs::toString);
        return (String) jobs
                .getFirst()
                .get("key");
    }

    private static List<Map<String, Object>> tasksOf(final Object instanceKey) {
        return jobsOf(instanceKey)
                .stream()
                .filter(job -> !String
                        .valueOf(job.get("elementId"))
                        .startsWith("vanillabp__started__"))
                .toList();
    }

    private static String stateOfJob(
        final Object instanceKey,
        final String jobKey
    ) {
        return jobsOf(instanceKey)
                .stream()
                .filter(job -> jobKey.equals(job.get("key")))
                .map(job -> (String) job.get("state"))
                .findFirst()
                .orElse(null);
    }

    // the key of the instance, once it ended
    private static String awaitTheEndOf(final String id) {
        await(() -> !"ACTIVE".equals(awaitTheInstanceOf(id).get("state")), "the workflow of aggregate '%s' to end"
                .formatted(id));
        return (String) awaitTheInstanceOf(id).get("key");
    }

    private static Map<String, Object> jobOf(
        final String instanceKey,
        final String elementId
    ) {
        return await(() -> jobsOf(instanceKey)
                .stream()
                .filter(job -> elementId.equals(job.get("elementId")))
                .findFirst(), "the job of %s of instance %s".formatted(elementId, instanceKey));
    }

    // raised by the engine with every delivery of the job
    private static long deliveryTokenOf(final String jobKey) {
        return ENGINE
                .json("/v1/jobs/%s".formatted(jobKey))
                .path("deliveryToken")
                .asLong();
    }

    private static Map<String, Object> review(final String id) {
        return object("introspect/user-tasks/reviews/%s".formatted(id));
    }

    // what an earlier version of the application left at the engine: version 1 of Sorting, and a
    // workflow which waits on version 1 of Storing
    private static long deployTheVersionsBeforeTheChange() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        try (var rest = new ZenBpmRestClient("before-the-change", configuration)) {
            final var sorting = rest.deployBpmn(SORTING_VERSION_ONE.getBytes(StandardCharsets.UTF_8));
            rest.deployBpmn(STORING_VERSION_ONE.getBytes(StandardCharsets.UTF_8));
            rest.createInstance(CreateInstanceRequest
                    .latestVersionOf("zenbpm-e2e__Storing")
                    .withBusinessKey("stock-before-the-change"));
            return sorting.key();
        }
    }

    // past the application, as a workflow an earlier version of it started would run on
    private static void startOnVersionOneOfSorting(final String id) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        try (var rest = new ZenBpmRestClient("on-version-one", configuration)) {
            rest.createInstance(CreateInstanceRequest
                    .version(SORTING_VERSION_ONE_KEY)
                    .withBusinessKey(id)
                    .withVariables(Map.of("id", id)));
        }
    }

    private static Optional<String> servedBy(final String id) {
        return Optional
                .of(String.valueOf(object("introspect/sortings/%s".formatted(id)).get("servedBy")))
                .filter(method -> !method.isEmpty());
    }

    private static String bpmnXmlOf(final Map<String, Object> definition) {
        return api()
                .pathParam("definitionId", definition.get("id"))
                .get("introspect/viewer/definitions/{definitionId}")
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> elementsOf(final Map<String, Object> history) {
        return (List<Map<String, Object>>) history.get("elements");
    }

    private static List<Map<String, Object>> list(final String path) {
        return api()
                .get(path)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
    }

    // long enough for three looks of the user-task poller, which application.yaml times every
    // second, each of which would tell a user task again
    private static void watchThreeLooks() {
        LockSupport.parkNanos(Duration
                .ofMillis(3_500)
                .toNanos());
    }

    private static Map<String, Object> reservation(final String id) {
        return object("introspect/tasks/reservations/%s".formatted(id));
    }

    private static List<String> visited(final String instanceKey) {
        return api()
                .get("introspect/visited/%s".formatted(instanceKey))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$", String.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variablesOf(final Map<String, Object> instance) {
        return (Map<String, Object>) instance.get("variables");
    }

    private static List<Map<String, Object>> instancesOf(final String id) {
        return api()
                .get("introspect/instances/%s".formatted(id))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
    }

    private static List<Map<String, Object>> jobsOf(final Object instanceKey) {
        return api()
                .get("introspect/jobs/%s".formatted(instanceKey))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
    }

    private static void await(
        final BooleanSupplier condition,
        final String what
    ) {
        await(() -> condition.getAsBoolean() ? Optional.of(Boolean.TRUE) : Optional.empty(), what);
    }

    private static <T> T await(
        final Supplier<Optional<T>> read,
        final String what
    ) {
        final var deadline = Instant
                .now()
                .plus(TIMEOUT);
        while (true) {
            final var found = read.get();
            if (found.isPresent()) {
                return found.get();
            }
            assertTrue(Instant
                    .now()
                    .isBefore(deadline), () -> "waited %s for %s".formatted(TIMEOUT, what));
            LockSupport.parkNanos(Duration
                    .ofMillis(100)
                    .toNanos());
        }
    }

    private static String applicationLog() {
        try {
            return Files.exists(APPLICATION_LOG) ? Files.readString(APPLICATION_LOG) : "";
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read the log of the application at '%s'".formatted(
                    APPLICATION_LOG), e);
        }
    }

    private static RequestSpecification api() {
        return RestAssured
                .given()
                .baseUri("http://localhost")
                .port(HTTP_PORT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> post(final String path) {
        return api()
                .post(path)
                .then()
                .statusCode(200)
                .extract()
                .as(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final String path) {
        return api()
                .get(path)
                .then()
                .statusCode(200)
                .extract()
                .as(Map.class);
    }

    private static Map<String, Double> metersOf(final String adapterId) {
        final var values = new TreeMap<String, Double>();
        object("introspect/meters/%s".formatted(adapterId)).forEach((
            name,
            value) -> values.put(name, ((Number) value).doubleValue()));
        return values;
    }

    private static String text(final String path) {
        return api()
                .get(path)
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    private static String whereDockerIsThere(final Supplier<String> address) {
        // the application is never started without Docker; the address only has to be one
        return DOCKER_IS_THERE ? address.get() : "http://localhost:1";
    }

}
