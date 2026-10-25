/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;

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
    void aLargeClaimIsPaidOnceTheAdjusterApprovesIt() {
        final var claimId = report("comprehensive", "6400.00");
        awaitStatus(claimId, "IN_REVIEW");

        review(claimId, true).statusCode(200);

        awaitStatus(claimId, "PAID");
        show(claimId).body("reviewed", equalTo(true));
    }

    @Test
    void aClaimTheAdjusterRejectsIsNotPaid() {
        final var claimId = report("partial", "900.00");
        awaitStatus(claimId, "IN_REVIEW");

        review(claimId, false).statusCode(200);

        awaitStatus(claimId, "REJECTED");
        show(claimId).body("rejectionReason", startsWith("rejected by the adjuster"));
    }

    @Test
    void aClaimALiabilityPolicyDoesNotCoverIsRejectedAtOnce() {
        final var claimId = report("liability", "300.00");

        awaitStatus(claimId, "REJECTED");
        show(claimId).body("reviewed", equalTo(false));
    }

    @Test
    void aReviewOfAClaimWhichWaitsForNoneIsRefused() {
        final var claimId = report("liability", "300.00");
        awaitStatus(claimId, "REJECTED");

        review(claimId, true).statusCode(409);
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"policyNumber": "MOT-1", "claimant": "Jane Doe", "coverage": "full", "damageAmount": 10}
                        """)
                .post("/claims")
                .then()
                .statusCode(400);
    }

    private static String report(
        final String coverage,
        final String damageAmount
    ) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"policyNumber": "MOT-2024-0815", "claimant": "Jane Doe", "coverage": "%s",
                         "damageAmount": %s, "description": "a dent in the rear door"}
                        """.formatted(coverage, damageAmount))
                .post("/claims")
                .then()
                .statusCode(201)
                .extract()
                .path("claimId");
    }

    private static ValidatableResponse review(
        final String claimId,
        final boolean approved
    ) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"approved": %s, "comment": "decided by the test"}
                        """.formatted(approved))
                .post("/claims/{claimId}/review", claimId)
                .then();
    }

    private static ValidatableResponse show(final String claimId) {
        return given()
                .get("/claims/{claimId}", claimId)
                .then()
                .statusCode(200);
    }

    private static void awaitStatus(
        final String claimId,
        final String status
    ) {
        await()
                .atMost(STEP)
                .untilAsserted(() -> show(claimId).body("status", equalTo(status)));
    }

}
