/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.springboot.it.task.Reservation;
import org.pbinitiative.zenbpmadapter.springboot.it.task.ReservationRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.task.TaskItApplication;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * An engine which cannot be asked about a task never makes the platform report it cancelled: the
 * application of <code>ZenBpmTaskProcessingIT</code> reaches the engine through a proxy which
 * answers every read of a job with <code>502</code> and passes everything else on. The timer of the
 * process <code>TaskTakenAway</code> takes the hold away as there, and the check comes back every
 * <code>async-task-lock-renewal</code>, after which the platform asks about the hold; the probe
 * cannot say, so the hold is not reported.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TaskItApplication.class, properties = "spring.config.name=task-it")
@DirtiesContext
class ZenBpmProbeOutageIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static final JobReadsRefusingProxy PROXY = new JobReadsRefusingProxy(ENGINE::restAddress);

    @Autowired
    private ProcessService<Reservation> processService;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterAll
    static void stopTheProxy() {
        PROXY.close();
    }

    @Test
    @DisplayName("Where the engine cannot be asked about a task a timer boundary event took away, the platform asks again at every delivery of the workflow and reports nothing")
    void aProbeOutageCancelsNothing() {
        final var reservationId = "reservation-%s".formatted(UUID.randomUUID());
        transactionTemplate.executeWithoutResult(status -> processService.startWorkflow(new Reservation(
                reservationId)));
        final var holdTaskId = eventually(() -> reservations
                .findById(reservationId)
                .map(Reservation::getHoldTaskId));
        final var checkTaskId = eventually(() -> reservations
                .findById(reservationId)
                .map(Reservation::getCheckTaskId));

        eventually(() -> Optional
                .of(engineJson("/v1/jobs/%s".formatted(holdTaskId)))
                .filter(job -> "terminated".equals(job
                        .path("state")
                        .asText())));
        // two deliveries of the check after the hold was gone, each followed by the question
        final var deliveredBefore = deliveryTokenOf(checkTaskId);
        eventually(() -> Optional
                .of(deliveryTokenOf(checkTaskId))
                .filter(token -> token >= deliveredBefore + 2));
        final var asked = "/v1/jobs/%s".formatted(holdTaskId);
        eventually(() -> Optional
                .of(PROXY.refusedReads())
                .filter(refused -> refused.contains(asked)));

        assertAll(
                () -> assertEquals(0, reservations
                        .findById(reservationId)
                        .orElseThrow()
                        .getHoldCancellations()),
                () -> assertTrue(PROXY
                        .refusedReads()
                        .contains(asked), PROXY.refusedReads()::toString));
    }

    // the adapter reaches the engine's REST API through the proxy, its job stream directly
    @DynamicPropertySource
    static void theEngineBehindTheProxy(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", PROXY::address);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    // raised by the engine with every delivery of the job
    private static long deliveryTokenOf(final String jobKey) {
        return engineJson("/v1/jobs/%s".formatted(jobKey))
                .path("deliveryToken")
                .asLong();
    }

    // read from the engine itself, past the proxy
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

    private static <T> T eventually(final Supplier<Optional<T>> read) {
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
                    .isBefore(deadline), () -> "waited %s".formatted(TIMEOUT));
            LockSupport.parkNanos(Duration
                    .ofMillis(50)
                    .toNanos());
        }
    }

}
