/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;

/**
 * The walk-through of the README, through the REST API of the application, against a ZenBPM engine
 * of its own. Each test is one way a payment can go.
 */
@QuarkusTest
@QuarkusTestResource(ZenBpmEngine.class)
class CardPaymentIT {

    /**
     * How long a step may take. Three attempts two seconds apart take about five seconds, so the
     * limit only turns a step which never comes into a failure.
     */
    private static final Duration STEP = Duration.ofSeconds(60);

    @Inject
    LocalPaymentProvider provider;

    @AfterEach
    void theProviderIsBack() {
        provider.setAvailable(true);
    }

    @Test
    void aValidCardIsChargedAndTheOrderConfirmed() {
        final var orderId = pay("tok_visa");

        awaitStatus(orderId, "PAID");
        show(orderId).body("transactionId", startsWith("ch_"));
        assertThat(provider.attemptsFor(orderId), equalTo(1));
    }

    @Test
    void aDeclinedCardMakesTheShopAskForAnotherOne() {
        final var orderId = pay("tok_chargeDeclined");

        await()
                .atMost(STEP)
                .until(() -> field(orderId, "customerAskedForAnotherCard"), equalTo(true));
        show(orderId).body("status", equalTo("DECLINED"));
        assertThat(provider.attemptsFor(orderId), equalTo(1));
    }

    @Test
    void aProviderWhichDoesNotAnswerOnceIsTriedAgain() {
        final var orderId = pay("tok_processingError");

        awaitStatus(orderId, "PAID");
        assertThat(provider.attemptsFor(orderId), equalTo(2));
    }

    @Test
    void anOutageLongerThanTheAttemptsIsAnIncidentAnOperatorResolves() {
        switchTheProvider(false);
        final var orderId = pay("tok_visa");

        final var incidents = await()
                .atMost(STEP)
                .until(() -> ZenBpmEngine.unresolvedIncidents(orderId), found -> !found.isEmpty());
        assertThat(provider.attemptsFor(orderId), equalTo(3));
        show(orderId).body("status", equalTo("PENDING"));

        switchTheProvider(true);
        incidents.forEach(ZenBpmEngine::resolve);

        awaitStatus(orderId, "PAID");
        assertThat(provider.attemptsFor(orderId), equalTo(4));
    }

    @Test
    void aSecondPaymentOfTheSameOrderIsRefused() {
        final var orderId = pay("tok_visa");

        given()
                .contentType(ContentType.JSON)
                .body(payment(orderId, "tok_visa"))
                .post("/payments")
                .then()
                .statusCode(409);
    }

    private static String pay(final String cardToken) {
        final var orderId = "ORD-%s".formatted(UUID
                .randomUUID()
                .toString()
                .substring(0, 8));
        given()
                .contentType(ContentType.JSON)
                .body(payment(orderId, cardToken))
                .post("/payments")
                .then()
                .statusCode(201);
        return orderId;
    }

    private static String payment(
        final String orderId,
        final String cardToken
    ) {
        return """
                {"orderId": "%s", "customerEmail": "jane.doe@example.com", "amount": 49.90, "cardToken": "%s"}
                """.formatted(orderId, cardToken);
    }

    private static void switchTheProvider(final boolean available) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"available": %s}
                        """.formatted(available))
                .put("/simulation/payment-provider")
                .then()
                .statusCode(200);
    }

    private static ValidatableResponse show(final String orderId) {
        return given()
                .get("/payments/{orderId}", orderId)
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
