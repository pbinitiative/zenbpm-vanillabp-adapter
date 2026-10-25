/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.UnsupportedEncodingException;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class PurchaseApprovalIT {

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
