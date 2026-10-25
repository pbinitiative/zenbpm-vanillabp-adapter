/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pbinitiative.zenbpm.examples.banktransferorder.model.Order;
import org.pbinitiative.zenbpm.examples.banktransferorder.model.OrderRepository;
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
 * of its own. Each test is one way an order paid by bank transfer can go; the model's timers fire
 * after ten and twenty-five seconds. Some moments cannot be timed through the engine, such as a
 * transfer accepted while the deadline fires; their tests call the business service the way the
 * task handler does.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
// the application is closed after the tests, before the engine stops; otherwise it runs until the
// JVM ends and reports the engine gone
@DirtiesContext
class BankTransferOrderIT {

    /** Longer than the deadline of the model, so a step which never comes is a failure. */
    private static final Duration STEP = Duration.ofSeconds(60);

    /** How many deliveries of the same request arrive at once. */
    private static final int CALLERS = 8;

    private static final String EMAIL = "\"jane.doe@example.com\"";

    private static final String ITEM = "\"hiking boots\"";

    @Container
    private static final GenericContainer<?> ENGINE = ZenBpmEngine.container();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private BankTransferOrders orders;

    @Autowired
    private OrderRepository repository;

    @Autowired
    private Warehouse warehouse;

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
        // a reminder which outlived the payment would keep the workflow running until it is sent
        await()
                .atMost(STEP)
                .until(() -> ZenBpmEngine.workflowStates(ENGINE, orderId), List.of("completed")::equals);
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

    @Test
    void aTransferWhichArrivesWithTheDeadlineIsPaidBack() {
        final var order = new Order(orderNumber(), "jane.doe@example.com", "hiking boots", new BigDecimal(
                "89.90"));
        warehouse.reserve(order.getOrderId(), order.getItem());
        // accepted after the deadline fired, before the cancellation committed
        order.paid();

        orders.cancelOrder(order);

        assertThat(order.getStatus()).isEqualTo(Order.Status.REFUND_DUE);
        assertThat(order.isStockReleased()).isTrue();
        repository.save(order);
        assertThat(transfer(order.getOrderId(), "89.90")).hasStatus(409);
    }

    @Test
    void aCustomerWhoHasJustPaidIsNotReminded() {
        final var order = new Order(orderNumber(), "jane.doe@example.com", "hiking boots", new BigDecimal(
                "89.90"));
        // accepted before the reminder was due, while the message was on its way to the engine
        order.paid();

        orders.sendReminder(order);

        assertThat(order.isReminderSent()).isFalse();
    }

    @Test
    void aShipmentRepeatedAfterAFailureSendsOneParcel() {
        final var orderId = orderNumber();
        warehouse.reserve(orderId, "hiking boots");

        final var trackingNumber = warehouse.ship(orderId);

        assertThat(warehouse.ship(orderId)).isEqualTo(trackingNumber);
    }

    @Test
    void anInvalidOrderIsRefused() {
        final var orderIds = Stream
                .generate(BankTransferOrderIT::orderNumber)
                .limit(5)
                .toList();

        assertThat(placing("ORD SPACE", EMAIL, ITEM, "89.90")).hasStatus(400);
        assertThat(placing("ORD/SLASH", EMAIL, ITEM, "89.90")).hasStatus(400);
        assertThat(placing(orderIds.get(0), "null", ITEM, "89.90")).hasStatus(400);
        assertThat(placing(orderIds.get(1), EMAIL, "\" \"", "89.90")).hasStatus(400);
        assertThat(placing(orderIds.get(2), EMAIL, ITEM, "-89.90")).hasStatus(400);
        assertThat(placing(orderIds.get(3), EMAIL, ITEM, "89.999")).hasStatus(400);
        assertThat(placing(orderIds.get(4), EMAIL, ITEM, "null")).hasStatus(400);
        assertThat(show("ORD SPACE")).hasStatus(404);
        orderIds.forEach(orderId -> assertThat(show(orderId)).hasStatus(404));
    }

    @Test
    void anIncompleteTransferIsRefused() {
        final var orderId = place();

        assertThat(reporting("""
                {"amount": 89.90}
                """)).hasStatus(400);
        assertThat(reporting("""
                {"reference": "%s"}
                """.formatted(orderId))).hasStatus(400);
        assertThat(transfer(orderId, "-89.90")).hasStatus(400);
        assertThat(show(orderId))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void anOrderPlacedTwiceAtOnceIsTakenOnce() throws Exception {
        final var orderId = orderNumber();

        final var answers = atOnce(() -> placing(orderId, EMAIL, ITEM, "89.90")
                .getResponse()
                .getStatus());

        assertThat(answers).containsExactlyInAnyOrderElementsOf(oneAnd(201, 409));
    }

    @Test
    void aTransferReportedTwiceAtOnceIsAcceptedOnce() throws Exception {
        final var orderId = place();
        await()
                .atMost(STEP)
                .untilAsserted(() -> assertThat(show(orderId))
                        .bodyJson()
                        .extractingPath("$.paymentInstructionsSent")
                        .isEqualTo(true));

        final var answers = atOnce(() -> transfer(orderId, "89.90")
                .getResponse()
                .getStatus());

        assertThat(answers).containsExactlyInAnyOrderElementsOf(oneAnd(200, 422));
        awaitStatus(orderId, "SHIPPED");
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", () -> ZenBpmEngine.restAddress(ENGINE));
        registry.add("vanillabp.adapters.zenbpm.grpc-address", () -> ZenBpmEngine.grpcAddress(ENGINE));
    }

    private String place() {
        final var orderId = orderNumber();
        assertThat(placing(orderId, EMAIL, ITEM, "89.90")).hasStatus(201);
        return orderId;
    }

    /**
     * Places an order. The e-mail address, the item and the total are JSON values, written into the
     * body as they are.
     */
    private MvcTestResult placing(
        final String orderId,
        final String customerEmail,
        final String item,
        final String total
    ) {
        return api
                .post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderId": "%s", "customerEmail": %s, "item": %s, "total": %s}
                        """.formatted(orderId, customerEmail, item, total))
                .exchange();
    }

    private MvcTestResult transfer(
        final String reference,
        final String amount
    ) {
        return reporting("""
                {"reference": "%s", "amount": %s}
                """.formatted(reference, amount));
    }

    private MvcTestResult reporting(final String transfer) {
        return api
                .post()
                .uri("/bank/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transfer)
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

    private static String orderNumber() {
        return "ORD-%s".formatted(UUID
                .randomUUID()
                .toString()
                .substring(0, 8));
    }

    /**
     * The answers to the same request, sent by {@link #CALLERS} callers released at the same moment.
     */
    private static List<Integer> atOnce(final Callable<Integer> request) throws Exception {
        final var start = new CountDownLatch(1);
        try (var callers = Executors.newFixedThreadPool(CALLERS)) {
            final var answers = IntStream
                    .range(0, CALLERS)
                    .mapToObj(caller -> callers.submit(() -> {
                        start.await();
                        return request.call();
                    }))
                    .toList();
            start.countDown();
            final var statuses = new ArrayList<Integer>();
            for (final var answer : answers) {
                statuses.add(answer.get(1, TimeUnit.MINUTES));
            }
            return statuses;
        }
    }

    /**
     * The status of the one caller who wins, and the status of every other caller.
     */
    private static List<Integer> oneAnd(
        final int winner,
        final int others
    ) {
        return IntStream
                .range(0, CALLERS)
                .mapToObj(caller -> caller == 0 ? winner : others)
                .toList();
    }

}
