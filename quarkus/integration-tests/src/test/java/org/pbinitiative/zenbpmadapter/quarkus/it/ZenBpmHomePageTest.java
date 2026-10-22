/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.test.EngineLog;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The wiki's page <code>Home</code>, followed on Quarkus: an engine started as the page's command
 * starts it, and an application whose <code>vanillabp</code> configuration is the YAML of the page's
 * section "Minimal configuration", read from <code>wiki/Home.md</code>, beside the configuration of
 * the application's own database. It is the twin of the Spring Boot <code>ZenBpmHomePageIT</code>,
 * which says why the image is this build's and why the test points the page's
 * <code>rest-address</code> at a published port and sets <code>grpc-address</code>.
 * <p>
 * The application is the one of the package <code>org.pbinitiative.zenbpmadapter.quarkus.test.restart</code>:
 * the workflow module <code>zenbpm-restart</code>, whose booking of the kind <code>pack</code> runs one
 * task and ends.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmHomePageTest {

    private static final Path HOME = Path.of("..", "..", "wiki", "Home.md");

    private static final Path RUN = Path
            .of("target", "zenbpm-home-page-%s".formatted(UUID.randomUUID()))
            .toAbsolutePath();

    /** What the application needs beside the page: its database. */
    private static final String DATABASE = """
            quarkus:
              datasource:
                db-kind: h2
              hibernate-orm:
                schema-management:
                  strategy: drop-and-create
            """;

    /**
     * How long the workflow may take to end. The outbox dispatches right after the commit, and the
     * engine hands the job out in milliseconds, so the limit only turns a workflow which never ends
     * into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Pattern PUBLISHED_PORT = Pattern.compile("-p (\\d+):(\\d+)");

    // JUnit initializes the fields below to register the extension before it decides that the class
    // is skipped without Docker, so nothing on the way may need the container then
    private static final boolean DOCKER_IS_THERE = DockerClientFactory
            .instance()
            .isDockerAvailable();

    private static final GenericContainer<?> ENGINE = engineOfTheCommand();

    private static final Path APPLICATION_YAML = applicationYaml();

    // started here and not by the Testcontainers extension: the application's configuration needs
    // the published ports, and a prod-mode test reads its runtime properties while the field below is
    // initialized; Ryuk removes the engine when this JVM exits
    static {
        if (DOCKER_IS_THERE) {
            ENGINE.start();
        }
    }

    private static final int HTTP_PORT = OneFreePortPerJvm.getPort();

    @RegisterExtension
    static final QuarkusProdModeTest application = new QuarkusProdModeTest()
            // every resource needs a line of its own, see ZenBpmWorkflowLifecycleTest
            .withApplicationRoot(jar -> jar
                    .addPackage("org.pbinitiative.zenbpmadapter.quarkus.test.restart")
                    .addAsResource(APPLICATION_YAML.toFile(), "application.yaml")
                    .addAsResource("zenbpm-restart/processes/zenbpm/Restart.bpmn")
                    .addAsResource("workflow-module-descriptor/restart-workflow-module",
                            "META-INF/workflow-module"))
            // the surefire configuration of this module hands 'jacoco.agent' to this JVM
            .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
            .setRun(true)
            .setRuntimeProperties(Map.of(
                    "quarkus.http.port", Integer.toString(HTTP_PORT),
                    "quarkus.datasource.jdbc.url", "jdbc:h2:mem:zenbpm-home-page;DB_CLOSE_DELAY=-1",
                    "vanillabp.adapters.zenbpm.rest-address", whereDockerIsThere(() -> "http://%s:%d".formatted(
                            ENGINE.getHost(), ENGINE.getMappedPort(8080))),
                    "vanillabp.adapters.zenbpm.grpc-address", whereDockerIsThere(() -> "%s:%d".formatted(ENGINE
                            .getHost(), ENGINE.getMappedPort(9090))),
                    "zenbpm-restart.holds", RUN.toString()));

    @Test
    @DisplayName("A Quarkus application configured as the page Home says deploys its workflow module, starts a workflow after the commit, and runs it to its end over the job stream")
    void anApplicationConfiguredAsThePageSaysRunsAWorkflow() {
        final var booking = "booking-%s".formatted(UUID.randomUUID());

        RestAssured
                .given()
                .baseUri("http://localhost")
                .port(HTTP_PORT)
                .post("restart/bookings/%s?kind=pack".formatted(booking))
                .then()
                .statusCode(200);

        final var ended = eventually(() -> instancesOf(booking)
                .stream()
                .filter(instance -> "COMPLETED".equals(instance.get("state")))
                .findFirst());
        assertEquals("COMPLETED", ended.get("state"), ended::toString);
    }

    // the engine as the page's command starts it, from the image of this build
    private static GenericContainer<?> engineOfTheCommand() {
        final var command = codeBlockAfter("### An engine to try it against", "bash");
        final var ports = PUBLISHED_PORT
                .matcher(command)
                .results()
                .map(published -> Integer.valueOf(published.group(2)))
                .toArray(Integer[]::new);
        assertEquals(List.of(8080, 9090), List.of(ports), command);
        return new GenericContainer<>(EngineUnderTest.image())
                .withExposedPorts(ports)
                .withLogConsumer(EngineLog.consumer())
                .waitingFor(Wait
                        .forHttp("/system/health/ready")
                        .forPort(8080)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    // the application's database, and the page's configuration as it stands there
    private static Path applicationYaml() {
        final var page = codeBlockAfter("### Minimal configuration", "yaml");
        assertTrue(page.startsWith("vanillabp:\n"), page);
        try {
            Files.createDirectories(RUN);
            return Files.writeString(RUN.resolve("application.yaml"), "%s%s".formatted(DATABASE, page));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // the first code block of a language after a heading of the page
    private static String codeBlockAfter(
        final String heading,
        final String language
    ) {
        try {
            final var page = Files.readString(HOME);
            final var section = page.substring(page.indexOf(heading));
            final var start = section.indexOf("```%s\n".formatted(language)) + language.length() + 4;
            return section.substring(start, section.indexOf("```", start));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Map<String, Object>> instancesOf(final String booking) {
        return RestAssured
                .given()
                .baseUri("http://localhost")
                .port(HTTP_PORT)
                .get("restart/instances/%s".formatted(booking))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
    }

    private static <T> T eventually(final Supplier<Optional<T>> read) {
        final var deadline = Instant
                .now()
                .plus(TIMEOUT);
        var found = read.get();
        while (found.isEmpty() && Instant
                .now()
                .isBefore(deadline)) {
            LockSupport.parkNanos(Duration
                    .ofMillis(100)
                    .toNanos());
            found = read.get();
        }
        return found.orElseThrow(() -> new AssertionError("nothing within %s".formatted(TIMEOUT)));
    }

    private static String whereDockerIsThere(final Supplier<String> address) {
        // the application is never started without Docker; the address only has to be one
        return DOCKER_IS_THERE ? address.get() : "http://localhost:1";
    }

}
