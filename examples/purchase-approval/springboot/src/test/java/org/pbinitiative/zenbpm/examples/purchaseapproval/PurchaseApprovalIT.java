/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;

/**
 * The walk-through of the README, through the REST API of the application, against a ZenBPM engine
 * of its own. Each test is one way a purchase request can go.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
// the application is closed after the tests, before the engine stops; otherwise it runs until the
// JVM ends and reports the engine gone
@DirtiesContext
class PurchaseApprovalIT {

    /**
     * How long a step may take. A user task reaches the application within the poll interval of
     * one second, so the limit only turns a step which never comes into a failure.
     */
    private static final Duration STEP = Duration.ofSeconds(60);

    /** How many answers to the same check arrive at the same time. */
    private static final int ANSWERS = 8;

    @Container
    private static final GenericContainer<?> ENGINE = ZenBpmEngine.container();

    @Autowired
    private WebApplicationContext context;

    private MockMvcTester api;

    @BeforeEach
    void theApi() {
        api = MockMvcTester.from(context);
    }

    @Test
    void aSmallPurchaseTheClerkFindsInOrderIsOrdered() {
        final var id = submit(1_200);
        awaitStatus(id, "IN_CHECK");

        answer(id, "check", true);

        awaitStatus(id, "ORDERED");
        assertThat(show(id))
                .bodyJson()
                .extractingPath("$.purchaseOrderNumber")
                .asString()
                .startsWith("PO-");
    }

    @Test
    void aPurchaseAboveTheLimitIsOrderedOnlyOnceTheManagerApprovesIt() {
        final var id = submit(75_000);
        awaitStatus(id, "IN_CHECK");

        answer(id, "check", true);
        awaitStatus(id, "IN_HIGH_VALUE_APPROVAL");
        answer(id, "high-value-approval", true);

        awaitStatus(id, "ORDERED");
    }

    @Test
    void aPurchaseTheManagerRejectsIsReportedToTheRequester() {
        final var id = submit(75_000);
        awaitStatus(id, "IN_CHECK");
        answer(id, "check", true);
        awaitStatus(id, "IN_HIGH_VALUE_APPROVAL");

        answer(id, "high-value-approval", false);

        awaitRequesterNotified(id);
        assertThat(show(id))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("REJECTED");
    }

    @Test
    void aRequestTheClerkRejectsNeverReachesTheManager() {
        final var id = submit(75_000);
        awaitStatus(id, "IN_CHECK");

        answer(id, "check", false);

        awaitRequesterNotified(id);
        assertThat(show(id))
                .bodyJson()
                .extractingPath("$.purchaseOrderNumber")
                .isNull();
    }

    @Test
    void anAnswerForATaskWhichIsNotOpenIsRefused() {
        final var id = submit(1_200);
        awaitStatus(id, "IN_CHECK");

        assertThat(api
                .post()
                .uri("/purchase-requests/{id}/high-value-approval", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"approved": true}
                        """))
                .hasStatus(409);
    }

    @Test
    void answersGivenAtTheSameTimeDecideOnce() {
        final var id = submit(1_200);
        awaitStatus(id, "IN_CHECK");

        final var statuses = answerTheCheckAtTheSameTime(id);

        assertThat(statuses).hasSize(ANSWERS);
        assertThat(statuses.values()).containsOnly(200, 409);
        final var accepted = statuses
                .entrySet()
                .stream()
                .filter(answer -> answer.getValue() == 200)
                .map(Map.Entry::getKey)
                .toList();
        assertThat(accepted).hasSize(1);
        final var winner = accepted.getFirst();
        if (approves(winner)) {
            awaitStatus(id, "ORDERED");
        } else {
            awaitRequesterNotified(id);
            assertThat(show(id))
                    .bodyJson()
                    .extractingPath("$.status")
                    .isEqualTo("REJECTED");
        }
        assertThat(show(id))
                .bodyJson()
                .extractingPath("$.comment")
                .isEqualTo("answer %d".formatted(winner));
    }

    @Test
    void anInvalidRequestIsRefused() {
        List
                .of("""
                        {"item": "laptop", "amount": 1200}
                        """, """
                        {"requester": "Jane Doe", "item": " ", "amount": 1200}
                        """, """
                        {"requester": "Jane Doe", "item": "laptop", "amount": -100}
                        """, """
                        {"requester": "Jane Doe", "item": "laptop"}
                        """)
                .forEach(request -> assertThat(api
                        .post()
                        .uri("/purchase-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                        .hasStatus(400));
    }

    @Test
    void anUnknownRequestIsNotFound() {
        assertThat(show("no-such-request")).hasStatus(404);
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", () -> ZenBpmEngine.restAddress(ENGINE));
        registry.add("vanillabp.adapters.zenbpm.grpc-address", () -> ZenBpmEngine.grpcAddress(ENGINE));
    }

    private String submit(final int amount) {
        final var result = api
                .post()
                .uri("/purchase-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requester": "Jane Doe", "item": "laptop", "amount": %d}
                        """.formatted(amount))
                .exchange();
        assertThat(result).hasStatus(201);
        try {
            return JsonPath.read(result
                    .getResponse()
                    .getContentAsString(), "$.id");
        } catch (final UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void answer(
        final String id,
        final String task,
        final boolean approved
    ) {
        assertThat(api
                .post()
                .uri("/purchase-requests/{id}/{task}", id, task)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"approved": %s, "comment": "decided by the test"}
                        """.formatted(approved)))
                .hasStatusOk();
    }

    /**
     * Gives every answer to the open check at the same moment, on a thread of its own, alternating
     * approvals and rejections.
     *
     * @return The HTTP status each answer got, by its number
     */
    private Map<Integer, Integer> answerTheCheckAtTheSameTime(final String id) {
        final var start = new CountDownLatch(1);
        final var statuses = new ConcurrentHashMap<Integer, Integer>();
        try (var threads = Executors.newFixedThreadPool(ANSWERS)) {
            for (var answer = 0; answer < ANSWERS; answer++) {
                final var number = answer;
                threads.submit(() -> {
                    start.await();
                    statuses.put(number, api
                            .post()
                            .uri("/purchase-requests/{id}/check", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"approved": %s, "comment": "answer %d"}
                                    """.formatted(approves(number), number))
                            .exchange()
                            .getResponse()
                            .getStatus());
                    return null;
                });
            }
            start.countDown();
        }
        return statuses;
    }

    private static boolean approves(final int answer) {
        return answer % 2 == 0;
    }

    private MvcTestResult show(final String id) {
        return api
                .get()
                .uri("/purchase-requests/{id}", id)
                .exchange();
    }

    private void awaitStatus(
        final String id,
        final String status
    ) {
        await()
                .atMost(STEP)
                .untilAsserted(() -> assertThat(show(id))
                        .bodyJson()
                        .extractingPath("$.status")
                        .isEqualTo(status));
    }

    private void awaitRequesterNotified(final String id) {
        await()
                .atMost(STEP)
                .untilAsserted(() -> assertThat(show(id))
                        .bodyJson()
                        .extractingPath("$.requesterNotified")
                        .isEqualTo(true));
    }

}
