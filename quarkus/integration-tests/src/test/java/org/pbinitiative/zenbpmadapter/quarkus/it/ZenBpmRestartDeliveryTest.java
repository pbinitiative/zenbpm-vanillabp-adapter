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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
 * A restart of a booted Quarkus application against the pinned engine: a task delivered before
 * the restart and completed after it runs its handler once, and the handler which the stop cut off
 * costs its job no attempt.
 * <p>
 * The Spring Boot <code>ZenBpmRestartDeliveryIT</code> proves the first half, and that is the
 * point: what keeps the handler from running twice is the platform's record in the application's
 * database, and which ending reaches it, the platform's glue decides; and only a real stop shows
 * that Quarkus' shutdown reaches the adapter's drain, which fails no job it cut off.
 * <p>
 * The test method itself stops and starts the application, not the extension: only that makes a
 * restart visible in a prod-mode test. The application of the package
 * <code>org.pbinitiative.zenbpmadapter.quarkus.test.restart</code> deploys the workflow module
 * <code>zenbpm-restart</code> into an engine of this class, and keeps its tables in a database file
 * which outlives both runs, as the database of an application outlives its restart. Both runs get
 * the JaCoCo agent, which adds to the same file, so both count for the Quarkus report.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmRestartDeliveryTest {

    /**
     * How long the test waits for what the engine or the application does. Each step takes
     * milliseconds, so the limit only turns a step which never happens into a failure; it is
     * shorter than the <code>async-task-lock-renewal</code> of <code>restart/application.yaml</code>.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Path APPLICATION_LOG = Path
            .of("target", "zenbpm-restart-application.log")
            .toAbsolutePath();

    private static final Path RUN = Path
            .of("target", "zenbpm-restart-%s".formatted(UUID.randomUUID()))
            .toAbsolutePath();

    /**
     * Where the test holds a packing, by a file named after its booking: the application reads it
     * from its own JVM, also while it stops.
     */
    private static final Path HOLDS = RUN.resolve("holds");

    // the lines of the engine, which a failing test replays, are logged at DEBUG; this module logs
    // through JBoss LogManager, see ZenBpmWorkflowLifecycleTest
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
    // initialized. The engine outlives both runs of the application; Ryuk removes it when this JVM
    // exits
    static {
        if (DOCKER_IS_THERE) {
            ENGINE.start();
        }
        try {
            Files.createDirectories(HOLDS);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final int HTTP_PORT = OneFreePortPerJvm.getPort();

    @RegisterExtension
    static final QuarkusProdModeTest application = new QuarkusProdModeTest()
            // every resource needs a line of its own, see ZenBpmWorkflowLifecycleTest
            .withApplicationRoot(jar -> jar
                    .addPackage("org.pbinitiative.zenbpmadapter.quarkus.test.restart")
                    .addAsResource("restart/application.yaml", "application.yaml")
                    .addAsResource("zenbpm-restart/processes/zenbpm/Restart.bpmn")
                    .addAsResource("workflow-module-descriptor/restart-workflow-module",
                            "META-INF/workflow-module"))
            // the surefire configuration of this module hands 'jacoco.agent' to this JVM, and the
            // agent adds what each run of the application ran to the same file
            .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
            .setRun(true)
            .setRuntimeProperties(Map.of(
                    "quarkus.http.port", Integer.toString(HTTP_PORT),
                    "quarkus.datasource.jdbc.url", "jdbc:h2:file:%s".formatted(RUN.resolve("database")),
                    "vanillabp.adapters.zenbpm.rest-address", whereDockerIsThere(ENGINE::restAddress),
                    "vanillabp.adapters.zenbpm.grpc-address", whereDockerIsThere(ENGINE::grpcAddress),
                    "zenbpm-restart.holds", HOLDS.toString(),
                    // what the application logs while it stops is read from its file, when no
                    // endpoint answers any more
                    "quarkus.log.file.enabled", "true",
                    "quarkus.log.file.path", APPLICATION_LOG.toString(),
                    // the second run adds to the file of the first, which it would otherwise
                    // rotate away
                    "quarkus.log.file.rotation.rotate-on-boot", "false"));

    @Test
    @DisplayName("A task delivered before a restart and completed after it is not run twice, and the handler the stop cut off costs no failure: its job keeps its retries and the restarted application runs it")
    void aTaskDeliveredBeforeARestartAndCompletedAfterItRunsOnce() throws Exception {
        final var confirmed = "booking-%s".formatted(UUID.randomUUID());
        final var packed = "booking-%s".formatted(UUID.randomUUID());
        final var hold = HOLDS.resolve(packed);
        Files.createFile(hold);
        post("restart/bookings/%s?kind=confirm".formatted(confirmed));
        post("restart/bookings/%s?kind=pack".formatted(packed));
        final var taskId = await(() -> Optional.ofNullable((String) booking(confirmed).get("confirmationTaskId")),
                "the task of booking '%s' to be delivered".formatted(confirmed));
        await(() -> Boolean.TRUE.equals(object("restart/bookings/%s/packing".formatted(packed)).get("held")),
                "the packing of booking '%s' to wait in its handler".formatted(packed));
        final var packing = jobOf(instanceKeyOf(packed), "Pack");
        final var deliveredBefore = deliveryTokenOf(taskId);

        // the hold is taken away once the module stopped, so the handler throws while it stops
        final var stopping = applicationLog().length();
        final var cutOff = CompletableFuture.runAsync(() -> {
            await(() -> logSince(stopping).contains("workflow module 'zenbpm-restart' unsubscribed the job type(s)"),
                    "the module to stop");
            try {
                Files.delete(hold);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        application.stop();
        cutOff.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        final var shutdown = logSince(stopping);
        final var packingWhileDown = ENGINE.json("/v1/jobs/%s".formatted(packing));
        final var packingFailures = ENGINE
                .json("/v1/jobs/%s/failures".formatted(packing))
                .path("items")
                .size();
        final var packingIncidents = ENGINE
                .json("/v1/process-instances/%s/incidents".formatted(packingWhileDown
                        .path("processInstanceKey")
                        .asText()))
                .path("items")
                .size();
        final var confirmationWhileDown = ENGINE
                .json("/v1/jobs/%s".formatted(taskId))
                .path("state")
                .asText();

        final var restarting = applicationLog().length();
        application.start();
        // the job stream of the stopped run released the lock of the task it left open, so the
        // task comes back before its window of a minute
        await(() -> deliveryTokenOf(taskId) > deliveredBefore, "the restarted application to get the task again");
        final var skipped = "Skipping the repeated delivery of task 'await-confirmation' (BPMN process 'Restart' of workflow module 'zenbpm-restart', aggregate '%s'): it was processed before, reporting the recorded outcome COMPLETION_PENDING again"
                .formatted(confirmed);
        await(() -> logSince(restarting).contains(skipped), "the restarted application to answer it from the record");
        post("restart/bookings/%s/confirmation".formatted(confirmed));
        await(() -> "COMPLETED".equals(stateOf(confirmed)), "the workflow of booking '%s' to end".formatted(confirmed));
        await(() -> "COMPLETED".equals(stateOf(packed)), "the workflow of booking '%s' to end".formatted(packed));

        assertAll(
                () -> assertTrue(shutdown.contains(
                        "the handler of job %s (task 'pack', workflow aggregate '%s') threw while workflow module 'zenbpm-restart' shuts down, so the job is not failed and keeps its retries"
                                .formatted(packing, packed)),
                        shutdown),
                () -> assertTrue(shutdown.contains("workflow module 'zenbpm-restart' drained after"), shutdown),
                () -> assertFalse(shutdown.contains("did not stop workflow processing"), shutdown),
                () -> assertEquals("active", packingWhileDown
                        .path("state")
                        .asText(), packingWhileDown::toPrettyString),
                () -> assertEquals(3, packingWhileDown
                        .path("retries")
                        .asInt(), packingWhileDown::toPrettyString),
                () -> assertEquals(0, packingFailures),
                () -> assertEquals(0, packingIncidents),
                () -> assertEquals("active", confirmationWhileDown),
                () -> assertEquals(1, booking(confirmed).get("confirmationRequests")),
                () -> assertEquals(true, booking(confirmed).get("confirmed")),
                () -> assertEquals(true, booking(packed).get("packed")),
                () -> assertEquals("completed", ENGINE
                        .json("/v1/jobs/%s".formatted(taskId))
                        .path("state")
                        .asText()));
    }

    private static Map<String, Object> booking(final String id) {
        return object("restart/bookings/%s".formatted(id));
    }

    private static String instanceKeyOf(final String id) {
        return (String) await(() -> instancesOf(id)
                .stream()
                .findFirst(), "an instance of booking '%s'".formatted(id))
                .get("key");
    }

    private static String stateOf(final String id) {
        return (String) instancesOf(id)
                .getFirst()
                .get("state");
    }

    private static String jobOf(
        final String instanceKey,
        final String elementId
    ) {
        return (String) await(() -> jobsOf(instanceKey)
                .stream()
                .filter(job -> elementId.equals(job.get("elementId")))
                .findFirst(), "the job of %s of instance %s".formatted(elementId, instanceKey))
                .get("key");
    }

    // raised by the engine with every delivery of the job
    private static long deliveryTokenOf(final String jobKey) {
        return ENGINE
                .json("/v1/jobs/%s".formatted(jobKey))
                .path("deliveryToken")
                .asLong();
    }

    private static List<Map<String, Object>> instancesOf(final String id) {
        return api()
                .get("restart/instances/%s".formatted(id))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
    }

    private static List<Map<String, Object>> jobsOf(final String instanceKey) {
        return api()
                .get("restart/jobs/%s".formatted(instanceKey))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
    }

    // what the application logged from a moment on; the second run adds to the file of the first
    private static String logSince(final int length) {
        return applicationLog().substring(length);
    }

    private static String applicationLog() {
        try {
            return Files.exists(APPLICATION_LOG) ? Files.readString(APPLICATION_LOG) : "";
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read the log of the application at '%s'".formatted(
                    APPLICATION_LOG), e);
        }
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

    private static String whereDockerIsThere(final Supplier<String> address) {
        // the application is never started without Docker; the address only has to be one
        return DOCKER_IS_THERE ? address.get() : "http://localhost:1";
    }

}
