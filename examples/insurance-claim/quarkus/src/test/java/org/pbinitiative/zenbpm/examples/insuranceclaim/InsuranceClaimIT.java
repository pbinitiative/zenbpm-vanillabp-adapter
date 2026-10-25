/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

/**
 * The walk-through of the README, through the REST API of the application, against a ZenBPM engine
 * of its own. Each test is one route the decision table sends a claim along.
 */
@QuarkusTest
@QuarkusTestResource(ZenBpmEngine.class)
class InsuranceClaimIT {

    /**
     * How long a step may take. A user task reaches the application within the poll interval of
     * one second, so the limit only turns a step which never comes into a failure.
     */
    private static final Duration STEP = Duration.ofSeconds(60);

    @Test
    void aSmallComprehensiveClaimIsPaidWithoutAReview() {
        final var claimId = report("comprehensive", "480.00");

        awaitStatus(claimId, "PAID");
        show(claimId)
                .body("reviewed", equalTo(false))
                .body("payoutReference", startsWith("PAY-"));
    }

    @Test
    void aSmallPartialClaimIsPaidWithoutAReview() {
        final var claimId = report("partial", "450.00");

        awaitStatus(claimId, "PAID");
        show(claimId).body("reviewed", equalTo(false));
    }

    @Test
    void aComprehensiveClaimOfExactlyTheLimitIsPaidWithoutAReview() {
        final var claimId = report("comprehensive", "1000.00");

        awaitStatus(claimId, "PAID");
        show(claimId).body("reviewed", equalTo(false));
    }

    @Test
    void aLargeClaimIsPaidOnceTheAdjusterApprovesIt() {
        final var claimId = report("comprehensive", "6400.00");
        awaitStatus(claimId, "IN_REVIEW");

        review(claimId, true, "decided by the test").statusCode(200);

        awaitStatus(claimId, "PAID");
        show(claimId).body("reviewed", equalTo(true));
    }

    @Test
    void aClaimTheAdjusterRejectsIsNotPaid() {
        final var claimId = report("partial", "900.00");
        awaitStatus(claimId, "IN_REVIEW");

        review(claimId, false, "decided by the test").statusCode(200);

        awaitStatus(claimId, "REJECTED");
        show(claimId).body("rejectionReason", startsWith("rejected by the adjuster"));
    }

    @Test
    void aClaimALiabilityPolicyDoesNotCoverIsRejectedAtOnce() {
        final var claimId = report("liability", "300.00");

        awaitStatus(claimId, "REJECTED");
        show(claimId)
                .body("reviewed", equalTo(false))
                .body("rejectionReason", equalTo("the claim is not covered by the terms of policy MOT-2024-0815"));
    }

    @Test
    void aReviewOfAClaimWhichWaitsForNoneIsRefused() {
        final var claimId = report("liability", "300.00");
        awaitStatus(claimId, "REJECTED");

        review(claimId, true, "decided by the test").statusCode(409);
        send("""
                {"policyNumber": "MOT-1", "claimant": "Jane Doe", "coverage": "full", "damageAmount": 10}
                """).statusCode(400);
    }

    @Test
    void anIncompleteOrMisstatedClaimIsRefused() {
        send("""
                {"claimant": "Jane Doe", "coverage": "partial", "damageAmount": 10}
                """).statusCode(400);
        send("""
                {"policyNumber": "MOT-1", "claimant": " ", "coverage": "partial", "damageAmount": 10}
                """).statusCode(400);
        send("""
                {"policyNumber": "MOT-1", "claimant": "Jane Doe", "damageAmount": 10}
                """).statusCode(400);
        send("""
                {"policyNumber": "MOT-1", "claimant": "Jane Doe", "coverage": "partial", "damageAmount": 10.005}
                """).statusCode(400);
    }

    @Test
    void onlyOneOfTheAnswersGivenAtOnceIsAccepted() {
        final var claimId = report("comprehensive", "6400.00");
        awaitStatus(claimId, "IN_REVIEW");

        final var statuses = answerAtOnce(claimId, 8);

        assertThat(statuses
                .stream()
                .filter(status -> status == 200)
                .toList(), hasSize(1));
        assertThat(statuses
                .stream()
                .filter(status -> status != 200)
                .toList(), everyItem(equalTo(409)));
        // the even answers approve, the odd ones reject
        final var accepted = statuses.indexOf(200);
        final var approved = accepted % 2 == 0;
        awaitStatus(claimId, approved ? "PAID" : "REJECTED");
        show(claimId).body("rejectionReason", equalTo(approved
                ? null
                : "rejected by the adjuster: answer %d".formatted(accepted)));
    }

    private static String report(
        final String coverage,
        final String damageAmount
    ) {
        return send("""
                {"policyNumber": "MOT-2024-0815", "claimant": "Jane Doe", "coverage": "%s",
                 "damageAmount": %s, "description": "a dent in the rear door"}
                """.formatted(coverage, damageAmount))
                .statusCode(201)
                .extract()
                .path("claimId");
    }

    private static ValidatableResponse send(final String claim) {
        return given()
                .contentType(ContentType.JSON)
                .body(claim)
                .post("/claims")
                .then();
    }

    private static ValidatableResponse review(
        final String claimId,
        final boolean approved,
        final String comment
    ) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"approved": %s, "comment": "%s"}
                        """.formatted(approved, comment))
                .post("/claims/{claimId}/review", claimId)
                .then();
    }

    /**
     * Several adjusters answer the same review at the same moment: the even ones approve, the odd
     * ones reject, and each signs with its number.
     *
     * @return The HTTP status each adjuster got, in the order of their numbers
     */
    private static List<Integer> answerAtOnce(
        final String claimId,
        final int adjusters
    ) {
        final var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(adjusters)) {
            final var answers = IntStream
                    .range(0, adjusters)
                    .mapToObj(answer -> threads.submit(() -> {
                        start.await();
                        return review(claimId, answer % 2 == 0, "answer %d".formatted(answer))
                                .extract()
                                .statusCode();
                    }))
                    .toList();
            start.countDown();
            return answers
                    .stream()
                    .map(InsuranceClaimIT::statusOf)
                    .toList();
        }
    }

    private static ValidatableResponse show(final String claimId) {
        return given()
                .get("/claims/{claimId}", claimId)
                .then()
                .statusCode(200);
    }

    /**
     * Reads one field instead of asserting its value, so a poll which still sees the old value logs
     * nothing: RestAssured logs the request and the response of every assertion which fails.
     */
    private static <T> T field(
        final String claimId,
        final String name
    ) {
        return show(claimId)
                .extract()
                .path(name);
    }

    private static int statusOf(final Future<Integer> answer) {
        try {
            return answer.get();
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new IllegalStateException(e);
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private static void awaitStatus(
        final String claimId,
        final String status
    ) {
        await()
                .atMost(STEP)
                .until(() -> field(claimId, "status"), equalTo(status));
    }

}
