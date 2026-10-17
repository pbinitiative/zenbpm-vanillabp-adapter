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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
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
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The adapter's features end to end on a BOOTED Quarkus application against the pinned engine: the
 * packaged extension deploys the workflow module <code>zenbpm-e2e</code>, one BPMN and one DMN file,
 * under <code>use-prefix</code>, subscribes the job type of its task, and starts a workflow through
 * the platform's two-phase start, whose instance evaluates the deployed decision and reaches the
 * task, whose job the adapter delivers to the application's method and completes.
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
    void theBootDeploysTheModelAndTheDecision() throws IOException, InterruptedException {
        final var definitions = engine("/v1/process-definitions?bpmnProcessId=%s".formatted(SCOPED_PROCESS_ID));
        final var decisions = engine("/v1/dmn-resource-definitions?dmnResourceDefinitionId=zenbpm-e2e-rating");
        final var decision = engine("/v1/dmn-resource-definitions/%s".formatted(decisions.getString("items[0].key")));

        assertAll(
                () -> assertEquals(1, definitions.getInt("totalCount"), definitions::prettify),
                () -> assertEquals(1, definitions.getInt("items[0].version"), definitions::prettify),
                () -> assertEquals(1, decisions.getInt("totalCount"), decisions::prettify),
                () -> assertTrue(decision
                        .prettify()
                        .contains("zenbpm-e2e__rating"), decision::prettify),
                () -> assertEquals("1", text("introspect/versions/StartProcess")),
                () -> assertTrue(applicationLog().contains(
                        "ZenBPM[zenbpm]: workflow module 'zenbpm-e2e' subscribed the job type(s) %s".formatted(
                                SCOPED_JOB_TYPE)),
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
        final Supplier<Boolean> condition,
        final String what
    ) {
        final var deadline = Instant
                .now()
                .plus(TIMEOUT);
        while (!condition.get()) {
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

    private static JsonPath engine(final String path) throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            final var response = client.send(HttpRequest
                    .newBuilder(URI.create("%s%s".formatted(ENGINE.restAddress(), path)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), () -> "GET %s: %s".formatted(path, response.body()));
            return JsonPath.from(response.body());
        }
    }

    private static String whereDockerIsThere(final Supplier<String> address) {
        // the application is never started without Docker; the address only has to be one
        return DOCKER_IS_THERE ? address.get() : "http://localhost:1";
    }

}
