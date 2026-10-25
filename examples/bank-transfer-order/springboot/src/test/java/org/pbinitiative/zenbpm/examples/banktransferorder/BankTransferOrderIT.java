/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

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
 * of its own. Each test is one way an order paid by bank transfer can go; the model's timers fire
 * after ten and twenty-five seconds.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class BankTransferOrderIT {

    /** Longer than the deadline of the model, so a step which never comes is a failure. */
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
    void anOrderPaidInTimeIsShippedWithoutAReminder() {
        final var orderId = place();
        await()
                .atMost(STEP)
                .untilAsserted(() -> assertThat(show(orderId))
                        .bodyJson()
                        .extractingPath("$.paymentInstructionsSent")
                        .isEqualTo(true));

        assertThat(transfer(orderId, "89.90")).hasStatusOk();

        awaitStatus(orderId, "SHIPPED");
        assertThat(show(orderId))
                .bodyJson()
                .extractingPath("$.trackingNumber")
                .asString()
                .startsWith("PARCEL-");
        assertThat(show(orderId))
                .bodyJson()
                .extractingPath("$.reminderSent")
                .isEqualTo(false);
    }

    @Test
    void aCustomerWhoPaysLateIsRemindedAndTheOrderShippedAllTheSame() {
        final var orderId = place();
        await()
                .atMost(STEP)
                .untilAsserted(() -> assertThat(show(orderId))
                        .bodyJson()
                        .extractingPath("$.reminderSent")
                        .isEqualTo(true));

        assertThat(transfer(orderId, "89.90")).hasStatusOk();

        awaitStatus(orderId, "SHIPPED");
    }

    @Test
    void anOrderNotPaidByTheDeadlineIsCancelledAndALateTransferRefused() {
        final var orderId = place();

        awaitStatus(orderId, "CANCELLED");
        assertThat(show(orderId))
                .bodyJson()
                .extractingPath("$.stockReleased")
                .isEqualTo(true);
        assertThat(transfer(orderId, "89.90")).hasStatus(409);
    }

    @Test
    void aTransferWhichMatchesNoWaitingOrderIsLeftToAccounting() {
        final var orderId = place();

        assertThat(transfer(orderId, "8.99")).hasStatus(422);
        assertThat(transfer("ORD-UNKNOWN", "89.90")).hasStatus(422);
        assertThat(show(orderId))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("AWAITING_PAYMENT");
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", () -> ZenBpmEngine.restAddress(ENGINE));
        registry.add("vanillabp.adapters.zenbpm.grpc-address", () -> ZenBpmEngine.grpcAddress(ENGINE));
    }

    private String place() {
        final var orderId = "ORD-%s".formatted(UUID
                .randomUUID()
                .toString()
                .substring(0, 8));
        assertThat(api
                .post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                                {"orderId": "%s", "customerEmail": "jane.doe@example.com", "item": "hiking boots", "total": 89.90}
                                """
                                .formatted(orderId)))
                .hasStatus(201);
        return orderId;
    }

    private MvcTestResult transfer(
        final String reference,
        final String amount
    ) {
        return api
                .post()
                .uri("/bank/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reference": "%s", "amount": %s}
                        """.formatted(reference, amount))
                .exchange();
    }

    private MvcTestResult show(final String orderId) {
        return api
                .get()
                .uri("/orders/{orderId}", orderId)
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
