/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

/**
 * The walk-through of the README, through the REST API of the application, against a ZenBPM engine
 * of its own. Each test is one way a purchase request can go.
 */
@QuarkusTest
@QuarkusTestResource(ZenBpmEngine.class)
class PurchaseApprovalIT {

    /**
     * How long a step may take. A user task reaches the application within the poll interval of
     * one second, so the limit only turns a step which never comes into a failure.
     */
    private static final Duration STEP = Duration.ofSeconds(60);

    /** How many answers to the same check arrive at the same time. */
    private static final int ANSWERS = 8;

    @Test
    void aSmallPurchaseTheClerkFindsInOrderIsOrdered() {
        final var id = submit(1_200);
        awaitStatus(id, "IN_CHECK");

        answer(id, "check", true);

        awaitStatus(id, "ORDERED");
        show(id).body("purchaseOrderNumber", startsWith("PO-"));
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
        show(id).body("status", equalTo("REJECTED"));
    }

    @Test
    void aRequestTheClerkRejectsNeverReachesTheManager() {
        final var id = submit(75_000);
        awaitStatus(id, "IN_CHECK");

        answer(id, "check", false);

        awaitRequesterNotified(id);
        show(id).body("purchaseOrderNumber", nullValue());
    }

    @Test
    void anAnswerForATaskWhichIsNotOpenIsRefused() {
        final var id = submit(1_200);
        awaitStatus(id, "IN_CHECK");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"approved": true}
                        """)
                .post("/purchase-requests/{id}/high-value-approval", id)
                .then()
                .statusCode(409);
    }

    @Test
    void answersGivenAtTheSameTimeDecideOnce() {
        final var id = submit(1_200);
        awaitStatus(id, "IN_CHECK");

        final var statuses = answerTheCheckAtTheSameTime(id);

        assertThat(statuses, aMapWithSize(ANSWERS));
        assertThat(statuses.values(), everyItem(in(List.of(200, 409))));
        final var accepted = statuses
                .entrySet()
                .stream()
                .filter(answer -> answer.getValue() == 200)
                .map(Map.Entry::getKey)
                .toList();
        assertThat(accepted, hasSize(1));
        final var winner = accepted.getFirst();
        if (approves(winner)) {
            awaitStatus(id, "ORDERED");
        } else {
            awaitRequesterNotified(id);
            show(id).body("status", equalTo("REJECTED"));
        }
        show(id).body("comment", equalTo("answer %d".formatted(winner)));
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
                .forEach(request -> given()
                        .contentType(ContentType.JSON)
                        .body(request)
                        .post("/purchase-requests")
                        .then()
                        .statusCode(400));
    }

    @Test
    void anUnknownRequestIsNotFound() {
        given()
                .get("/purchase-requests/{id}", "no-such-request")
                .then()
                .statusCode(404);
    }

    private static String submit(final int amount) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"requester": "Jane Doe", "item": "laptop", "amount": %d}
                        """.formatted(amount))
                .post("/purchase-requests")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private static void answer(
        final String id,
        final String task,
        final boolean approved
    ) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"approved": %s, "comment": "decided by the test"}
                        """.formatted(approved))
                .post("/purchase-requests/{id}/{task}", id, task)
                .then()
                .statusCode(200);
    }

    /**
     * Gives every answer to the open check at the same moment, on a thread of its own, alternating
     * approvals and rejections.
     *
     * @return The HTTP status each answer got, by its number
     */
    private static Map<Integer, Integer> answerTheCheckAtTheSameTime(final String id) {
        final var start = new CountDownLatch(1);
        final var statuses = new ConcurrentHashMap<Integer, Integer>();
        try (var threads = Executors.newFixedThreadPool(ANSWERS)) {
            for (var answer = 0; answer < ANSWERS; answer++) {
                final var number = answer;
                threads.submit(() -> {
                    start.await();
                    statuses.put(number, given()
                            .contentType(ContentType.JSON)
                            .body("""
                                    {"approved": %s, "comment": "answer %d"}
                                    """.formatted(approves(number), number))
                            .post("/purchase-requests/{id}/check", id)
                            .statusCode());
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

    private static ValidatableResponse show(final String id) {
        return given()
                .get("/purchase-requests/{id}", id)
                .then()
                .statusCode(200);
    }

    /**
     * Reads one field instead of asserting its value, so a poll which still sees the old value logs
     * nothing: RestAssured logs the request and the response of every assertion which fails.
     */
    private static <T> T field(
        final String id,
        final String name
    ) {
        return show(id)
                .extract()
                .path(name);
    }

    private static void awaitStatus(
        final String id,
        final String status
    ) {
        await()
                .atMost(STEP)
                .until(() -> field(id, "status"), equalTo(status));
    }

    private static void awaitRequesterNotified(final String id) {
        await()
                .atMost(STEP)
                .until(() -> field(id, "requesterNotified"), equalTo(true));
    }

}
