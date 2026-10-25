/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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

/**
 * The walk-through of the README, through the REST API of the application, against a ZenBPM engine
 * of its own. Each test is one way a payment can go.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
// the application is closed after the tests, before the engine stops; otherwise it runs until the
// JVM ends and reports the engine gone
@DirtiesContext
class CardPaymentIT {

    /**
     * How long a step may take. Three attempts two seconds apart take about five seconds, so the
     * limit only turns a step which never comes into a failure.
     */
    private static final Duration STEP = Duration.ofSeconds(60);

    @Container
    private static final GenericContainer<?> ENGINE = ZenBpmEngine.container();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private LocalPaymentProvider provider;

    private MockMvcTester api;

    @BeforeEach
    void theApi() {
        api = MockMvcTester.from(context);
    }

    @AfterEach
    void theProviderIsBack() {
        provider.setAvailable(true);
    }

    @Test
    void aValidCardIsChargedAndTheOrderConfirmed() {
        final var orderId = pay("tok_visa");

        awaitStatus(orderId, "PAID");
        assertThat(show(orderId))
                .bodyJson()
                .extractingPath("$.transactionId")
                .asString()
                .startsWith("ch_");
        assertThat(provider.attemptsFor(orderId)).isEqualTo(1);
    }

    @Test
    void aDeclinedCardMakesTheShopAskForAnotherOne() {
        final var orderId = pay("tok_chargeDeclined");

        await()
                .atMost(STEP)
                .untilAsserted(() -> assertThat(show(orderId))
                        .bodyJson()
                        .extractingPath("$.customerAskedForAnotherCard")
                        .isEqualTo(true));
        assertThat(show(orderId))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("DECLINED");
        assertThat(provider.attemptsFor(orderId)).isEqualTo(1);
    }

    @Test
    void aProviderWhichDoesNotAnswerOnceIsTriedAgain() {
        final var orderId = pay("tok_processingError");

        awaitStatus(orderId, "PAID");
        assertThat(provider.attemptsFor(orderId)).isEqualTo(2);
        assertTheAttemptsWereTheBackoffApart(orderId);
    }

    @Test
    void anOutageLongerThanTheAttemptsIsAnIncidentAnOperatorResolves() {
        switchTheProvider(false);
        final var orderId = pay("tok_visa");

        final var incidents = await()
                .atMost(STEP)
                .until(() -> ZenBpmEngine.unresolvedIncidents(ENGINE, orderId), found -> !found.isEmpty());
        assertThat(provider.attemptsFor(orderId)).isEqualTo(3);
        assertTheAttemptsWereTheBackoffApart(orderId);
        assertThat(show(orderId))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("PENDING");

        switchTheProvider(true);
        incidents.forEach(incident -> ZenBpmEngine.resolve(ENGINE, incident));

        awaitStatus(orderId, "PAID");
        assertThat(provider.attemptsFor(orderId)).isEqualTo(4);
    }

    @Test
    void aSecondPaymentOfTheSameOrderIsRefused() {
        final var orderId = pay("tok_visa");

        assertThat(api
                .post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payment(orderId, "tok_visa")))
                .hasStatus(409);
        // the first payment goes on, and is done before the next test may take the provider down
        awaitStatus(orderId, "PAID");
    }

    @Test
    void paymentsOfTheSameOrderAtTheSameTimeChargeOnce() throws Exception {
        final var orderId = newOrderId();

        final var statuses = atTheSameTime(8, () -> status(payment(orderId, "tok_visa")));

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses)
                .filteredOn(status -> status == 201)
                .hasSize(1);
        awaitStatus(orderId, "PAID");
        assertThat(provider.attemptsFor(orderId)).isEqualTo(1);
    }

    @Test
    void anInvalidPaymentIsRefused() {
        final var orderId = newOrderId();

        assertThat(status("""
                {"orderId": "%s", "customerEmail": "jane.doe@example.com", "amount": -49.90, "cardToken": "tok_visa"}
                """.formatted(orderId))).isEqualTo(400);
        assertThat(status("""
                {"orderId": "%s", "customerEmail": "jane.doe@example.com", "amount": 49.905, "cardToken": "tok_visa"}
                """.formatted(orderId))).isEqualTo(400);
        assertThat(status("""
                {"orderId": "%s", "customerEmail": "jane.doe@example.com", "amount": 49.90}
                """.formatted(orderId))).isEqualTo(400);
        assertThat(status(payment("ORD 1001", "tok_visa"))).isEqualTo(400);
        assertThat(status(payment("ORD/1001", "tok_visa"))).isEqualTo(400);
        assertThat(show(orderId)).hasStatus(404);
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", () -> ZenBpmEngine.restAddress(ENGINE));
        registry.add("vanillabp.adapters.zenbpm.grpc-address", () -> ZenBpmEngine.grpcAddress(ENGINE));
    }

    private String pay(final String cardToken) {
        final var orderId = newOrderId();
        assertThat(api
                .post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payment(orderId, cardToken)))
                .hasStatus(201);
        return orderId;
    }

    private int status(final String payment) {
        final var result = api
                .post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payment)
                .exchange();
        // MockMvc hands an exception no handler answered to the test instead of answering 500
        return result.getUnresolvedException() == null
                ? result
                        .getResponse()
                        .getStatus()
                : 500;
    }

    private void switchTheProvider(final boolean available) {
        assertThat(api
                .put()
                .uri("/simulation/payment-provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"available": %s}
                        """.formatted(available)))
                .hasStatusOk();
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

    /**
     * The backoff of application.yaml is two seconds; an engine which hands the task out again at
     * once would answer within milliseconds.
     */
    private void assertTheAttemptsWereTheBackoffApart(final String orderId) {
        final var attempts = provider.attemptTimesFor(orderId);
        for (var attempt = 1; attempt < attempts.size(); attempt++) {
            assertThat(Duration.between(attempts.get(attempt - 1), attempts.get(attempt)))
                    .isGreaterThanOrEqualTo(Duration.ofMillis(1500));
        }
    }

    private MvcTestResult show(final String orderId) {
        return api
                .get()
                .uri("/payments/{orderId}", orderId)
                .exchange();
    }

    private void awaitStatus(
        final String orderId,
        final String status
    ) {
        await()
                .atMost(STEP)
                .untilAsserted(() -> assertThat(show(orderId))
                        .bodyJson()
                        .extractingPath("$.status")
                        .isEqualTo(status));
    }

}
