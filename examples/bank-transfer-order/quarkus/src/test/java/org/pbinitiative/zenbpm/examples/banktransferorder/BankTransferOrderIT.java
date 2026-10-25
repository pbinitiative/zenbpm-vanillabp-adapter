/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

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

import org.junit.jupiter.api.Test;
import org.pbinitiative.zenbpm.examples.banktransferorder.model.Order;
import org.pbinitiative.zenbpm.examples.banktransferorder.model.OrderRepository;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;

/**
 * The walk-through of the README, through the REST API of the application, against a ZenBPM engine
 * of its own. Each test is one way an order paid by bank transfer can go; the model's timers fire
 * after ten and twenty-five seconds. Some moments cannot be timed through the engine, such as a
 * transfer accepted while the deadline fires; their tests call the business service the way the
 * task handler does.
 */
@QuarkusTest
@QuarkusTestResource(ZenBpmEngine.class)
class BankTransferOrderIT {

    /** Longer than the deadline of the model, so a step which never comes is a failure. */
    private static final Duration STEP = Duration.ofSeconds(60);

    /** How many deliveries of the same request arrive at once. */
    private static final int CALLERS = 8;

    private static final String EMAIL = "\"jane.doe@example.com\"";

    private static final String ITEM = "\"hiking boots\"";

    @Inject
    BankTransferOrders orders;

    @Inject
    OrderRepository repository;

    @Inject
    Warehouse warehouse;

    @Test
    void anOrderPaidInTimeIsShippedWithoutAReminder() {
        final var orderId = place();
        await()
                .atMost(STEP)
                .until(() -> field(orderId, "paymentInstructionsSent"), equalTo(true));

        transfer(orderId, "89.90").statusCode(200);

        awaitStatus(orderId, "SHIPPED");
        show(orderId).body("trackingNumber", startsWith("PARCEL-"));
        // a reminder which outlived the payment would keep the workflow running until it is sent
        await()
                .atMost(STEP)
                .until(() -> ZenBpmEngine.workflowStates(orderId), contains("completed"));
        show(orderId).body("reminderSent", equalTo(false));
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

    @Test
    void aTransferWhichArrivesWithTheDeadlineIsPaidBack() {
        final var order = new Order(orderNumber(), "jane.doe@example.com", "hiking boots", new BigDecimal(
                "89.90"));
        warehouse.reserve(order.getOrderId(), order.getItem());
        // accepted after the deadline fired, before the cancellation committed
        order.paid();

        orders.cancelOrder(order);

        assertThat(order.getStatus(), is(Order.Status.REFUND_DUE));
        assertThat(order.isStockReleased(), is(true));
        QuarkusTransaction
                .requiringNew()
                .run(() -> repository.persist(order));
        transfer(order.getOrderId(), "89.90").statusCode(409);
    }

    @Test
    void aCustomerWhoHasJustPaidIsNotReminded() {
        final var order = new Order(orderNumber(), "jane.doe@example.com", "hiking boots", new BigDecimal(
                "89.90"));
        // accepted before the reminder was due, while the message was on its way to the engine
        order.paid();

        orders.sendReminder(order);

        assertThat(order.isReminderSent(), is(false));
    }

    @Test
    void aShipmentRepeatedAfterAFailureSendsOneParcel() {
        final var orderId = orderNumber();
        warehouse.reserve(orderId, "hiking boots");

        final var trackingNumber = warehouse.ship(orderId);

        assertThat(warehouse.ship(orderId), equalTo(trackingNumber));
    }

    @Test
    void anInvalidOrderIsRefused() {
        final var orderIds = Stream
                .generate(BankTransferOrderIT::orderNumber)
                .limit(5)
                .toList();

        placing("ORD SPACE", EMAIL, ITEM, "89.90").statusCode(400);
        placing("ORD/SLASH", EMAIL, ITEM, "89.90").statusCode(400);
        placing(orderIds.get(0), "null", ITEM, "89.90").statusCode(400);
        placing(orderIds.get(1), EMAIL, "\" \"", "89.90").statusCode(400);
        placing(orderIds.get(2), EMAIL, ITEM, "-89.90").statusCode(400);
        placing(orderIds.get(3), EMAIL, ITEM, "89.999").statusCode(400);
        placing(orderIds.get(4), EMAIL, ITEM, "null").statusCode(400);
        showing("ORD SPACE").statusCode(404);
        orderIds.forEach(orderId -> showing(orderId).statusCode(404));
    }

    @Test
    void anIncompleteTransferIsRefused() {
        final var orderId = place();

        reporting("""
                {"amount": 89.90}
                """).statusCode(400);
        reporting("""
                {"reference": "%s"}
                """.formatted(orderId)).statusCode(400);
        transfer(orderId, "-89.90").statusCode(400);
        show(orderId).body("status", equalTo("AWAITING_PAYMENT"));
    }

    @Test
    void anOrderPlacedTwiceAtOnceIsTakenOnce() throws Exception {
        final var orderId = orderNumber();

        final var answers = atOnce(() -> placing(orderId, EMAIL, ITEM, "89.90")
                .extract()
                .statusCode());

        assertThat(answers, containsInAnyOrder(oneAnd(201, 409).toArray(Integer[]::new)));
    }

    @Test
    void aTransferReportedTwiceAtOnceIsAcceptedOnce() throws Exception {
        final var orderId = place();
        await()
                .atMost(STEP)
                .until(() -> field(orderId, "paymentInstructionsSent"), equalTo(true));

        final var answers = atOnce(() -> transfer(orderId, "89.90")
                .extract()
                .statusCode());

        assertThat(answers, containsInAnyOrder(oneAnd(200, 422).toArray(Integer[]::new)));
        awaitStatus(orderId, "SHIPPED");
    }

    private static String place() {
        final var orderId = orderNumber();
        placing(orderId, EMAIL, ITEM, "89.90").statusCode(201);
        return orderId;
    }

    /**
     * Places an order. The e-mail address, the item and the total are JSON values, written into the
     * body as they are.
     */
    private static ValidatableResponse placing(
        final String orderId,
        final String customerEmail,
        final String item,
        final String total
    ) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"orderId": "%s", "customerEmail": %s, "item": %s, "total": %s}
                        """.formatted(orderId, customerEmail, item, total))
                .post("/orders")
                .then();
    }

    private static ValidatableResponse transfer(
        final String reference,
        final String amount
    ) {
        return reporting("""
                {"reference": "%s", "amount": %s}
                """.formatted(reference, amount));
    }

    private static ValidatableResponse reporting(final String transfer) {
        return given()
                .contentType(ContentType.JSON)
                .body(transfer)
                .post("/bank/transfers")
                .then();
    }

    private static ValidatableResponse show(final String orderId) {
        return showing(orderId).statusCode(200);
    }

    private static ValidatableResponse showing(final String orderId) {
        return given()
                .get("/orders/{orderId}", orderId)
                .then();
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
