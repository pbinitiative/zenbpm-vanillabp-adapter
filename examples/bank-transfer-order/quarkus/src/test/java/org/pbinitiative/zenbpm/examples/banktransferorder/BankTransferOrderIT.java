/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

/**
 * The walk-through of the README, through the REST API of the application, against a ZenBPM engine
 * of its own. Each test is one way an order paid by bank transfer can go; the model's timers fire
 * after ten and twenty-five seconds.
 */
@QuarkusTest
@QuarkusTestResource(ZenBpmEngine.class)
class BankTransferOrderIT {

    /** Longer than the deadline of the model, so a step which never comes is a failure. */
    private static final Duration STEP = Duration.ofSeconds(60);

    @Test
    void anOrderPaidInTimeIsShippedWithoutAReminder() {
        final var orderId = place();
        await()
                .atMost(STEP)
                .until(() -> field(orderId, "paymentInstructionsSent"), equalTo(true));

        transfer(orderId, "89.90").statusCode(200);

        awaitStatus(orderId, "SHIPPED");
        show(orderId)
                .body("trackingNumber", startsWith("PARCEL-"))
                .body("reminderSent", equalTo(false));
    }

    @Test
    void aCustomerWhoPaysLateIsRemindedAndTheOrderShippedAllTheSame() {
        final var orderId = place();
        await()
                .atMost(STEP)
                .until(() -> field(orderId, "reminderSent"), equalTo(true));

        transfer(orderId, "89.90").statusCode(200);

        awaitStatus(orderId, "SHIPPED");
    }

    @Test
    void anOrderNotPaidByTheDeadlineIsCancelledAndALateTransferRefused() {
        final var orderId = place();

        awaitStatus(orderId, "CANCELLED");
        show(orderId).body("stockReleased", equalTo(true));
        transfer(orderId, "89.90").statusCode(409);
    }

    @Test
    void aTransferWhichMatchesNoWaitingOrderIsLeftToAccounting() {
        final var orderId = place();

        transfer(orderId, "8.99").statusCode(422);
        transfer("ORD-UNKNOWN", "89.90").statusCode(422);
        show(orderId).body("status", equalTo("AWAITING_PAYMENT"));
    }

    private static String place() {
        final var orderId = "ORD-%s".formatted(UUID
                .randomUUID()
                .toString()
                .substring(0, 8));
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"orderId": "%s", "customerEmail": "jane.doe@example.com", "item": "hiking boots", "total": 89.90}
                        """
                        .formatted(orderId))
                .post("/orders")
                .then()
                .statusCode(201);
        return orderId;
    }

    private static ValidatableResponse transfer(
        final String reference,
        final String amount
    ) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"reference": "%s", "amount": %s}
                        """.formatted(reference, amount))
                .post("/bank/transfers")
                .then();
    }

    private static ValidatableResponse show(final String orderId) {
        return given()
                .get("/orders/{orderId}", orderId)
                .then()
                .statusCode(200);
    }

    /**
     * Reads one field without asserting anything, so the polls of a wait log nothing: RestAssured
     * logs the request and the response of every assertion which fails.
     */
    private static <T> T field(
        final String orderId,
        final String name
    ) {
        return show(orderId)
                .extract()
                .path(name);
    }

    private static void awaitStatus(
        final String orderId,
        final String status
    ) {
        await()
                .atMost(STEP)
                .until(() -> field(orderId, "status"), equalTo(status));
    }

}
