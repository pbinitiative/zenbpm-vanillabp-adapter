/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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

/**
 * The walk-through of the README, through the REST API of the application, against a ZenBPM engine
 * of its own. Each test is one way a payment can go.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
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
    }

    @Test
    void anOutageLongerThanTheAttemptsIsAnIncidentAnOperatorResolves() {
        api
                .put()
                .uri("/simulation/payment-provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"available": false}
                        """)
                .exchange();
        final var orderId = pay("tok_visa");

        final var incidents = await()
                .atMost(STEP)
                .until(() -> ZenBpmEngine.unresolvedIncidents(ENGINE, orderId), found -> !found.isEmpty());
        assertThat(provider.attemptsFor(orderId)).isEqualTo(3);
        assertThat(show(orderId))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("PENDING");

        api
                .put()
                .uri("/simulation/payment-provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"available": true}
                        """)
                .exchange();
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
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", () -> ZenBpmEngine.restAddress(ENGINE));
        registry.add("vanillabp.adapters.zenbpm.grpc-address", () -> ZenBpmEngine.grpcAddress(ENGINE));
    }

    private String pay(final String cardToken) {
        final var orderId = "ORD-%s".formatted(UUID
                .randomUUID()
                .toString()
                .substring(0, 8));
        assertThat(api
                .post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payment(orderId, cardToken)))
                .hasStatus(201);
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
