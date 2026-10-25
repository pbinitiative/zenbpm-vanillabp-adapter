/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.UnsupportedEncodingException;
import java.time.Duration;

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
 * of its own. Each test is one route the decision table sends a claim along.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
// the application is closed after the tests, before the engine stops; otherwise it runs until the
// JVM ends and reports the engine gone
@DirtiesContext
class InsuranceClaimIT {

    /**
     * How long a step may take. A user task reaches the application within the poll interval of
     * one second, so the limit only turns a step which never comes into a failure.
     */
    private static final Duration STEP = Duration.ofSeconds(60);

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
    void aSmallComprehensiveClaimIsPaidWithoutAReview() {
        final var claimId = report("comprehensive", "480.00");

        awaitStatus(claimId, "PAID");
        assertThat(show(claimId))
                .bodyJson()
                .extractingPath("$.reviewed")
                .isEqualTo(false);
        assertThat(show(claimId))
                .bodyJson()
                .extractingPath("$.payoutReference")
                .asString()
                .startsWith("PAY-");
    }

    @Test
    void aLargeClaimIsPaidOnceTheAdjusterApprovesIt() {
        final var claimId = report("comprehensive", "6400.00");
        awaitStatus(claimId, "IN_REVIEW");

        assertThat(review(claimId, true)).hasStatusOk();

        awaitStatus(claimId, "PAID");
        assertThat(show(claimId))
                .bodyJson()
                .extractingPath("$.reviewed")
                .isEqualTo(true);
    }

    @Test
    void aClaimTheAdjusterRejectsIsNotPaid() {
        final var claimId = report("partial", "900.00");
        awaitStatus(claimId, "IN_REVIEW");

        assertThat(review(claimId, false)).hasStatusOk();

        awaitStatus(claimId, "REJECTED");
        assertThat(show(claimId))
                .bodyJson()
                .extractingPath("$.rejectionReason")
                .asString()
                .startsWith("rejected by the adjuster");
    }

    @Test
    void aClaimALiabilityPolicyDoesNotCoverIsRejectedAtOnce() {
        final var claimId = report("liability", "300.00");

        awaitStatus(claimId, "REJECTED");
        assertThat(show(claimId))
                .bodyJson()
                .extractingPath("$.reviewed")
                .isEqualTo(false);
    }

    @Test
    void aReviewOfAClaimWhichWaitsForNoneIsRefused() {
        final var claimId = report("liability", "300.00");
        awaitStatus(claimId, "REJECTED");

        assertThat(review(claimId, true)).hasStatus(409);
        assertThat(api
                .post()
                .uri("/claims")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"policyNumber": "MOT-1", "claimant": "Jane Doe", "coverage": "full", "damageAmount": 10}
                        """))
                .hasStatus(400);
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", () -> ZenBpmEngine.restAddress(ENGINE));
        registry.add("vanillabp.adapters.zenbpm.grpc-address", () -> ZenBpmEngine.grpcAddress(ENGINE));
    }

    private String report(
        final String coverage,
        final String damageAmount
    ) {
        final var result = api
                .post()
                .uri("/claims")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"policyNumber": "MOT-2024-0815", "claimant": "Jane Doe", "coverage": "%s",
                         "damageAmount": %s, "description": "a dent in the rear door"}
                        """.formatted(coverage, damageAmount))
                .exchange();
        assertThat(result).hasStatus(201);
        try {
            return JsonPath.read(result
                    .getResponse()
                    .getContentAsString(), "$.claimId");
        } catch (final UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcTestResult review(
        final String claimId,
        final boolean approved
    ) {
        return api
                .post()
                .uri("/claims/{claimId}/review", claimId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"approved": %s, "comment": "decided by the test"}
                        """.formatted(approved))
                .exchange();
    }

    private MvcTestResult show(final String claimId) {
        return api
                .get()
                .uri("/claims/{claimId}", claimId)
                .exchange();
    }

    private void awaitStatus(
        final String claimId,
        final String status
    ) {
        await()
                .atMost(STEP)
                .untilAsserted(() -> assertThat(show(claimId))
                        .bodyJson()
                        .extractingPath("$.status")
                        .isEqualTo(status));
    }

}
