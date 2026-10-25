/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        assertTheAttemptsWereTheBackoffApart(orderId);
    }

    @Test
    void anOutageLongerThanTheAttemptsIsAnIncidentAnOperatorResolves() {
        switchTheProvider(false);
        final var orderId = pay("tok_visa");

        final var incidents = await()
                .atMost(STEP)
                .until(() -> ZenBpmEngine.unresolvedIncidents(orderId), found -> !found.isEmpty());
        assertThat(provider.attemptsFor(orderId), equalTo(3));
        assertTheAttemptsWereTheBackoffApart(orderId);
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
        // the first payment goes on, and is done before the next test may take the provider down
        awaitStatus(orderId, "PAID");
    }

    @Test
    void paymentsOfTheSameOrderAtTheSameTimeChargeOnce() throws Exception {
        final var orderId = newOrderId();

        final var statuses = atTheSameTime(8, () -> status(payment(orderId, "tok_visa")));

        assertThat(statuses, everyItem(is(oneOf(201, 409))));
        assertThat(Collections.frequency(statuses, 201), equalTo(1));
        awaitStatus(orderId, "PAID");
        assertThat(provider.attemptsFor(orderId), equalTo(1));
    }

    @Test
    void anInvalidPaymentIsRefused() {
        final var orderId = newOrderId();

        assertThat(status("""
                {"orderId": "%s", "customerEmail": "jane.doe@example.com", "amount": -49.90, "cardToken": "tok_visa"}
                """.formatted(orderId)), equalTo(400));
        assertThat(status("""
                {"orderId": "%s", "customerEmail": "jane.doe@example.com", "amount": 49.905, "cardToken": "tok_visa"}
                """.formatted(orderId)), equalTo(400));
        assertThat(status("""
                {"orderId": "%s", "customerEmail": "jane.doe@example.com", "amount": 49.90}
                """.formatted(orderId)), equalTo(400));
        assertThat(status(payment("ORD 1001", "tok_visa")), equalTo(400));
        assertThat(status(payment("ORD/1001", "tok_visa")), equalTo(400));
        given()
                .get("/payments/{orderId}", orderId)
                .then()
                .statusCode(404);
    }

    /**
     * The backoff of application.yaml is two seconds; an engine which hands the task out again at
     * once would answer within milliseconds.
     */
    private void assertTheAttemptsWereTheBackoffApart(final String orderId) {
        final var attempts = provider.attemptTimesFor(orderId);
        for (var attempt = 1; attempt < attempts.size(); attempt++) {
            assertThat(Duration
                    .between(attempts.get(attempt - 1), attempts.get(attempt))
                    .toMillis(), greaterThanOrEqualTo(1500L));
        }
    }

    private static String pay(final String cardToken) {
        final var orderId = newOrderId();
        given()
                .contentType(ContentType.JSON)
                .body(payment(orderId, cardToken))
                .post("/payments")
                .then()
                .statusCode(201);
        return orderId;
    }

    private static int status(final String payment) {
        return given()
                .contentType(ContentType.JSON)
                .body(payment)
                .post("/payments")
                .statusCode();
    }

    private static String newOrderId() {
        return "ORD-%s".formatted(UUID
                .randomUUID()
                .toString()
                .substring(0, 8));
    }

    private static List<Integer> atTheSameTime(
        final int requests,
        final Callable<Integer> request
    ) throws Exception {
        final var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(requests)) {
            final var answers = new ArrayList<Future<Integer>>();
            for (var i = 0; i < requests; i++) {
                answers.add(threads.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            final var statuses = new ArrayList<Integer>();
            for (final var answer : answers) {
                statuses.add(answer.get(1, TimeUnit.MINUTES));
            }
            return statuses;
        }
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
     * Reads one field instead of asserting its value, so a poll which still sees the old value logs
     * nothing: RestAssured logs the request and the response of every assertion which fails.
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
