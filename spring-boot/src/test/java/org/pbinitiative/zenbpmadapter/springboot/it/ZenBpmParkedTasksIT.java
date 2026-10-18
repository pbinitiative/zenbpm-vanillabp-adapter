/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.springboot.it.task.Claim;
import org.pbinitiative.zenbpmadapter.springboot.it.task.ClaimRepository;
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
 * A task left open does not keep the next task of its type from arriving: the engine counts a
 * parked job against its type's cap of active jobs until its lock lapses, so the adapter raises
 * the cap by every job it parked, see decision 37 in the repository's DECISIONS.md. The
 * application of <code>ZenBpmTaskProcessingIT</code> runs here with one handler slot, so its cap
 * without the raise is one, and with a window of a minute, so a parked job does not come back
 * while the test waits.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TaskItApplication.class, properties = {
        "spring.config.name=task-it", "vanillabp.adapters.zenbpm.worker-threads=1", "vanillabp.adapters.zenbpm.async-task-lock-renewal=PT1M"
})
@DirtiesContext
class ZenBpmParkedTasksIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Autowired
    private ProcessService<Claim> processService;

    @Autowired
    private ClaimRepository claims;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("With one handler slot, a second task left open of the same type arrives while the first is parked, and both stay open")
    void aParkedTaskLeavesRoomForTheNextOfItsType() {
        final var first = startAClaimLeftOpen();
        final var firstTaskId = eventually(() -> claims
                .findById(first)
                .map(Claim::getApprovalTaskId));
        final var second = startAClaimLeftOpen();

        final var secondTaskId = eventually(() -> claims
                .findById(second)
                .map(Claim::getApprovalTaskId));

        assertAll(
                () -> assertNotEquals(firstTaskId, secondTaskId),
                () -> assertEquals(1, approvalRequestsOf(first)),
                () -> assertEquals(1, approvalRequestsOf(second)),
                () -> assertEquals("active", stateOf(firstTaskId)),
                () -> assertEquals("active", stateOf(secondTaskId)));
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", ENGINE::restAddress);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    private String startAClaimLeftOpen() {
        final var claimId = "claim-%s".formatted(UUID.randomUUID());
        transactionTemplate
                .executeWithoutResult(status -> processService.startWorkflow(new Claim(claimId, Claim.PENDING, null)));
        return claimId;
    }

    private int approvalRequestsOf(final String claimId) {
        return claims
                .findById(claimId)
                .orElseThrow()
                .getApprovalRequests();
    }

    private static String stateOf(final String jobKey) {
        return engineJson("/v1/jobs/%s".formatted(jobKey))
                .path("state")
                .asText();
    }

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
