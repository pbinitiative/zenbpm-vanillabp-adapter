/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.springboot.it.drain.DrainItApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.drain.DrainedParcel;
import org.pbinitiative.zenbpmadapter.springboot.it.drain.DrainedParcelRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.drain.PackingGate;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * What the shutdown of a booted Spring Boot application does with the work it has in flight,
 * against the pinned engine: the module's job types are unsubscribed, a handler which returns
 * within <code>shutdown-grace</code> still reports its outcome, one still running after it is
 * named and cut off without a failure, so its job keeps its retries, and the job stream closes
 * last, which releases every lock of the application's client: another node gets the jobs the
 * stopped one held at once, not after their <code>job-timeout</code>.
 * <p>
 * Each case boots the application {@link DrainItApplication} itself, once per node, with
 * <code>drain-it.yaml</code>: one handler slot and a job-timeout of a minute. The nodes of a case
 * share one in-memory database, as the nodes of one application share theirs, and the engine is one
 * for the class, so every case uses parcels of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmShutdownDrainIT {

    /**
     * How long a case waits for what the engine or a node does. Each step takes milliseconds or a
     * round of the engine's distribution, so the limit only turns a step which never happens into a
     * failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * The job-timeout of <code>drain-it.yaml</code>, which a job the engine hands out again only
     * after its lock lapsed would wait for.
     */
    private static final Duration JOB_TIMEOUT = Duration.ofSeconds(60);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private String database;

    private ZenBpmRestClient engine;

    private final List<ConfigurableApplicationContext> nodes = new ArrayList<>();

    @BeforeEach
    void anEngineClientAndADatabaseOfThisCase() {
        database = "jdbc:h2:mem:drain-it-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID());
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        engine = new ZenBpmRestClient("drain-it", configuration);
    }

    @AfterEach
    void closeEveryNode() {
        nodes.forEach(ConfigurableApplicationContext::close);
        engine.close();
    }

    @Test
    @DisplayName("A handler which returns within the shutdown-grace finishes: the shutdown waits for it, and its completion reaches the engine while the module stops")
    void aHandlerWithinTheGraceFinishes(final CapturedOutput output) throws Exception {
        final var parcelId = uniqueParcelId();
        PackingGate.hold(parcelId, "a");
        final var nodeA = boot("a", "PT20S");
        final var instance = start(nodeA, parcelId);
        eventually(() -> PackingGate.isWaiting(parcelId), "the packing to wait in its handler");

        final var startedClosing = Instant.now();
        final var closing = CompletableFuture.runAsync(nodeA::close);
        eventually(() -> output
                .getAllOfThisTest()
                .contains("workflow module 'deploy-it' unsubscribed the job type(s)"), "the module to stop");
        PackingGate.release(parcelId, false);
        closing.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        final var closedAfter = Duration.between(startedClosing, Instant.now());

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(JobRef.State.COMPLETED, jobOf(instance, "Pack").state()),
                () -> assertTrue(closedAfter.compareTo(Duration.ofSeconds(20)) < 0, closedAfter::toString),
                () -> assertTrue(log.contains("workflow module 'deploy-it' drained after"), log),
                () -> assertFalse(log.contains("is cut off"), log));
    }

    @Test
    @DisplayName("A handler which the shutdown cut off costs no failure: it threw while its module stopped, its job is still active afterwards with all its retries, and has no failure and no incident")
    void aCutOffHandlerCostsNoFailure(final CapturedOutput output) throws Exception {
        final var parcelId = uniqueParcelId();
        PackingGate.hold(parcelId, "a");
        final var nodeA = boot("a", "PT20S");
        final var instance = start(nodeA, parcelId);
        eventually(() -> PackingGate.isWaiting(parcelId), "the packing to wait in its handler");
        final var pack = jobOf(instance, "Pack");

        final var closing = CompletableFuture.runAsync(nodeA::close);
        eventually(() -> output
                .getAllOfThisTest()
                .contains("workflow module 'deploy-it' unsubscribed the job type(s)"), "the module to stop");
        // the clients are still open: the shutdown waits for this handler
        PackingGate.release(parcelId, true);
        closing.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertTrue(log.contains(
                        "the handler of job %d (task 'pack', workflow aggregate '%s') threw while workflow module 'deploy-it' shuts down, so the job is not failed and keeps its retries"
                                .formatted(pack.key(), parcelId)),
                        log),
                () -> assertTrue(log.contains("workflow module 'deploy-it' drained after"), log),
                () -> assertEquals(JobRef.State.ACTIVE, jobOf(instance, "Pack").state()),
                () -> assertEquals(3, retriesOf(pack.key())),
                () -> assertEquals(0, engineJson("/v1/jobs/%d/failures".formatted(pack.key()))
                        .path("items")
                        .size()),
                () -> assertEquals(0, engineJson("/v1/process-instances/%d/incidents".formatted(instance))
                        .path("items")
                        .size()));
    }

    @Test
    @DisplayName("A handler still running after the shutdown-grace is named with its job, task and aggregate, and the shutdown goes on without it")
    void aHandlerAfterTheGraceIsNamed(final CapturedOutput output) {
        final var parcelId = uniqueParcelId();
        PackingGate.hold(parcelId, "a");
        final var nodeA = boot("a", "PT1S");
        final var instance = start(nodeA, parcelId);
        eventually(() -> PackingGate.isWaiting(parcelId), "the packing to wait in its handler");
        final var pack = jobOf(instance, "Pack");

        final var startedClosing = Instant.now();
        nodeA.close();
        final var closedAfter = Duration.between(startedClosing, Instant.now());
        PackingGate.release(parcelId, true);

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertTrue(log.contains(
                        "the handler of job %d (task 'pack', workflow aggregate '%s', workflow module 'deploy-it') still runs after the shutdown waited PT1S for it"
                                .formatted(pack.key(), parcelId)),
                        log),
                () -> assertTrue(closedAfter.compareTo(Duration.ofSeconds(1)) >= 0, closedAfter::toString),
                () -> assertTrue(closedAfter.compareTo(Duration.ofSeconds(15)) < 0, closedAfter::toString),
                () -> assertEquals(3, retriesOf(pack.key())));
    }

    @Test
    @DisplayName("Another node gets the jobs a stopped node held at once, not after their job-timeout: the closed job stream released the lock of the cut-off task and of the one which waited for its slot")
    void anotherNodeGetsTheOpenJobsAtOnce() {
        final var parcelId = uniqueParcelId();
        // node a has one handler slot: whichever of the two tasks the engine hands out first is held
        // in it, and the other one waits for the slot
        PackingGate.holdEither(parcelId, "a");
        final var nodeA = boot("a", "PT1S");
        final var instance = start(nodeA, parcelId);
        eventually(() -> PackingGate.isWaiting(parcelId), "a task of the parcel to wait in its handler on node a");
        eventually(() -> (deliveryTokenOf(jobOf(instance, "Pack").key()) > 0) && (deliveryTokenOf(jobOf(instance,
                "Label").key()) > 0), "both tasks to reach node a");
        final var nodeB = boot("b", "PT1S");

        nodeA.close();
        final var closed = Instant.now();
        final var parcel = eventually(() -> parcels(nodeB)
                .findById(parcelId)
                .filter(drained -> "b".equals(drained.getPackedBy()) && "b".equals(drained.getLabelledBy())),
                "node b to pack and label the parcel");
        final var tookOver = Duration.between(closed, Instant.now());
        PackingGate.release(parcelId, true);

        assertAll(
                () -> assertTrue(tookOver.compareTo(Duration.ofSeconds(10)) < 0, tookOver::toString),
                () -> assertTrue(tookOver.compareTo(JOB_TIMEOUT) < 0, tookOver::toString),
                () -> assertEquals("b", parcel.getPackedBy()),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, eventually(() -> engine
                        .instance(instance)
                        .filter(ended -> ended.state() != ProcessInstanceRef.State.ACTIVE), "the workflow to end")
                        .state()));
    }

    private ConfigurableApplicationContext boot(
        final String node,
        final String shutdownGrace
    ) {
        final var context = new SpringApplicationBuilder(DrainItApplication.class)
                .properties(
                        "spring.config.name=drain-it",
                        "spring.datasource.url=%s".formatted(database),
                        "drain-it.node=%s".formatted(node),
                        "vanillabp.adapters.zenbpm.rest-address=%s".formatted(ENGINE.restAddress()),
                        "vanillabp.adapters.zenbpm.grpc-address=%s".formatted(ENGINE.grpcAddress()),
                        "vanillabp.adapters.zenbpm.client-id=drain-it-%s-%s".formatted(node, UUID.randomUUID()),
                        "vanillabp.adapters.zenbpm.shutdown-grace=%s".formatted(shutdownGrace))
                .run();
        nodes.add(context);
        return context;
    }

    private long start(
        final ConfigurableApplicationContext node,
        final String parcelId
    ) {
        final ProcessService<DrainedParcel> processService = node
                .<ProcessService<DrainedParcel>>getBeanProvider(ResolvableType.forClassWithGenerics(
                        ProcessService.class, DrainedParcel.class))
                .getObject();
        node
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> processService.startWorkflow(new DrainedParcel(parcelId)));
        return eventually(() -> engine
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(parcelId))
                .items()
                .stream()
                .findFirst(), "the workflow to start")
                .key();
    }

    private static DrainedParcelRepository parcels(final ConfigurableApplicationContext node) {
        return node.getBean(DrainedParcelRepository.class);
    }

    private JobRef jobOf(
        final long instanceKey,
        final String elementId
    ) {
        return eventually(() -> engine
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(instanceKey))
                .items()
                .stream()
                .filter(job -> elementId.equals(job.elementId()))
                .findFirst(), "the job of %s".formatted(elementId));
    }

    private static long deliveryTokenOf(final long jobKey) {
        return engineJson("/v1/jobs/%d".formatted(jobKey))
                .path("deliveryToken")
                .asLong();
    }

    private static int retriesOf(final long jobKey) {
        return engineJson("/v1/jobs/%d".formatted(jobKey))
                .path("retries")
                .asInt();
    }

    // what the engine answers, read past the adapter's client, which keeps only what it needs
    private static JsonNode engineJson(final String path) {
        try (var client = HttpClient.newHttpClient()) {
            final var answer = client.send(HttpRequest
                    .newBuilder(URI.create("%s%s".formatted(ENGINE.restAddress(), path)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, answer.statusCode(), answer::body);
            return JsonMapper
                    .builder()
                    .build()
                    .readTree(answer.body());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String uniqueParcelId() {
        return "parcel-%s".formatted(UUID.randomUUID());
    }

    private static void eventually(
        final BooleanSupplier condition,
        final String what
    ) {
        eventually(() -> condition.getAsBoolean() ? Optional.of(Boolean.TRUE) : Optional.empty(), what);
    }

    private static <T> T eventually(
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
                    .ofMillis(50)
                    .toNanos());
        }
    }

}
