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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.pbinitiative.zenbpmadapter.test.EngineLog;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
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
 * away, and a handler which throws.
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
                () -> assertEquals(List.of("COMPLETED", "COMPLETED", "COMPLETED"), jobs
                        .stream()
                        .map(job -> job.get("state"))
                        .toList(), jobs::toString));
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

    // the ids of every test are new, because all tests of the class share one engine
    private static String uniqueAggregateId() {
        return "e2e-%s".formatted(UUID.randomUUID());
    }

    private static Map<String, Object> awaitTheInstanceOf(final String id) {
        await(() -> !instancesOf(id).isEmpty(), "an instance of aggregate '%s'".formatted(id));
        final var instances = instancesOf(id);
        assertEquals(1, instances.size(), instances::toString);
        return instances.getFirst();
    }

    private static String awaitTheJobOf(final Object instanceKey) {
        await(() -> !jobsOf(instanceKey).isEmpty(), "a job of instance %s".formatted(instanceKey));
        final var jobs = jobsOf(instanceKey);
        assertEquals(1, jobs.size(), jobs::toString);
        return (String) jobs
                .getFirst()
                .get("key");
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
