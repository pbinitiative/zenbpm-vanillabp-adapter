/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.springboot.it.start.Order;
import org.pbinitiative.zenbpmadapter.springboot.it.start.StartItApplication;
import org.pbinitiative.zenbpmadapter.test.EngineLog;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * The wiki's page <code>Home</code>, followed on Spring Boot as a reader follows it: an engine
 * started as the page's command starts it, and an application configured with nothing but the
 * configuration of the page's section "Minimal configuration". Both are read from
 * <code>wiki/Home.md</code>, so the page and this test cannot drift apart.
 * <p>
 * The engine runs with the ports of the command and no other setting. Its image is the one this
 * build tests against, see decision 62 in the repository's DECISIONS.md; <code>EnginePinTest</code>
 * holds the page's image to the pin. Testcontainers publishes the engine's ports on free ports of its
 * own, so the test points the page's <code>rest-address</code>, through the port of the container the
 * command publishes it on, at the port Testcontainers published, and sets
 * <code>grpc-address</code>, which the page leaves to its default, to the published gRPC port; the
 * wiki says so. The application is the one of <code>ZenBpmDeploymentAndStartIT</code>: the workflow
 * module <code>deploy-it</code> with the process <code>Ordering</code>, whose task the adapter runs.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = StartItApplication.class, properties = "spring.config.name=zenbpm-home-page")
@DirtiesContext
class ZenBpmHomePageIT {

    private static final Path HOME = Path.of("..", "wiki", "Home.md");

    private static final String REST_ADDRESS = "vanillabp.adapters.zenbpm.rest-address";

    /**
     * How long the workflow may take to pass its task. The outbox dispatches right after the commit,
     * and the engine hands the job out in milliseconds, so the limit only turns a workflow which never
     * gets there into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Pattern PUBLISHED_PORT = Pattern.compile("-p (\\d+):(\\d+)");

    @Container
    private static final GenericContainer<?> ENGINE = engineOfTheCommand();

    @Autowired
    private ProcessService<Order> processService;

    @Autowired
    private ZenBpmClientRegistry clients;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("The minimal configuration of the page Home configures the address of the engine and the name-clash-avoidance mode of the adapter id zenbpm, and nothing else")
    void theMinimalConfigurationIsTwoLines() {
        assertEquals(List.of("vanillabp.adapters.zenbpm.name-clash-avoidance", REST_ADDRESS), List.copyOf(
                minimalConfiguration().keySet()));
    }

    @Test
    @DisplayName("An application configured as the page Home says deploys its workflow module, starts a workflow after the commit, and runs its task over the job stream")
    void anApplicationConfiguredAsThePageSaysRunsAWorkflow() {
        final var orderId = "order-%s".formatted(UUID.randomUUID());

        transactionTemplate
                .executeWithoutResult(status -> processService.startWorkflow(new Order(orderId, "a book", null)));

        final var instance = eventually(() -> instancesOf(orderId)
                .stream()
                .findFirst());
        final var completedTask = eventually(() -> jobsOf(instance.key())
                .stream()
                .filter(job -> "Pack".equals(job.elementId()))
                .filter(job -> job.state() == JobRef.State.COMPLETED)
                .findFirst());
        assertAll(
                () -> assertEquals("deploy-it__Ordering", instance.bpmnProcessId()),
                () -> assertEquals(ProcessInstanceRef.State.ACTIVE, instance.state()),
                () -> assertEquals("deploy-it__Ordering__pack", completedTask.type()));
    }

    @DynamicPropertySource
    static void theConfigurationOfThePage(final DynamicPropertyRegistry registry) {
        minimalConfiguration().forEach((
            key,
            value) -> registry.add(key, REST_ADDRESS.equals(key)
                    ? () -> "http://%s:%d".formatted(ENGINE.getHost(), ENGINE.getMappedPort(containerPortOf(
                            publishedPort(value))))
                    : () -> value));
        registry.add("vanillabp.adapters.zenbpm.grpc-address", () -> "%s:%d".formatted(ENGINE.getHost(), ENGINE
                .getMappedPort(9090)));
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

    // the keys of the page's YAML, sorted, with their values as written
    private static Map<String, String> minimalConfiguration() {
        final var yaml = codeBlockAfter("### Minimal configuration", "yaml");
        try {
            final var keys = new TreeMap<String, String>();
            for (final var source : new YamlPropertySourceLoader().load("Home.md", new ByteArrayResource(yaml
                    .getBytes(StandardCharsets.UTF_8)))) {
                final var properties = (EnumerablePropertySource<?>) source;
                for (final var key : properties.getPropertyNames()) {
                    keys.put(key, String.valueOf(properties.getProperty(key)));
                }
            }
            return keys;
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

    private static int publishedPort(final String address) {
        return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
    }

    // the port of the container the page's command publishes on a port of the host
    private static int containerPortOf(final int hostPort) {
        return PUBLISHED_PORT
                .matcher(codeBlockAfter("### An engine to try it against", "bash"))
                .results()
                .filter(published -> Integer.parseInt(published.group(1)) == hostPort)
                .map(published -> Integer.parseInt(published.group(2)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the command publishes nothing on port %d".formatted(hostPort)));
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
                    .ofMillis(50)
                    .toNanos());
            found = read.get();
        }
        return found.orElseThrow(() -> new AssertionError("nothing within %s".formatted(TIMEOUT)));
    }

    private List<ProcessInstanceRef> instancesOf(final String orderId) {
        return clients
                .getFactory("zenbpm")
                .rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(orderId))
                .items();
    }

    private List<JobRef> jobsOf(final long instanceKey) {
        return clients
                .getFactory("zenbpm")
                .rest()
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(instanceKey))
                .items();
    }

}
