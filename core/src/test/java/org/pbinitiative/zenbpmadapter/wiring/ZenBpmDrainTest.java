/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the drain of a workflow module counts and waits for: the handlers which entered and did not
 * leave, waited for until they returned or the grace is over, every module of an adapter id within
 * one grace, and named per job where the wait gave up.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDrainTest {

    @Test
    @DisplayName("A handler which entered runs until it leaves, and names its task and, once known, its aggregate")
    void aHandlerRunsFromEnteringToLeaving() {
        final var drain = new ZenBpmDrain("orders", "shop");

        drain.enter(42, "ship");
        final var entered = drain
                .running()
                .getFirst();
        drain.describe(42, "order-1");
        final var described = drain
                .running()
                .getFirst();
        drain.leave(42);
        drain.describe(42, "order-1");

        assertAll(
                () -> assertEquals(42, entered.jobKey()),
                () -> assertEquals("ship", entered.taskDefinition()),
                () -> assertEquals(null, entered.aggregateId()),
                () -> assertEquals("order-1", described.aggregateId()),
                () -> assertEquals(entered.since(), described.since()),
                () -> assertEquals(List.of(), drain.running()),
                () -> assertEquals("shop", drain.workflowModuleId()));
    }

    @Test
    @DisplayName("A module is not stopping until it is told so")
    void aModuleStopsWhenToldSo() {
        final var drain = new ZenBpmDrain("orders", "shop");
        final var before = drain.isStopping();

        drain.stopping();

        assertAll(
                () -> assertFalse(before),
                () -> assertTrue(drain.isStopping()));
    }

    @Test
    @DisplayName("The wait ends when the last handler returned, well before the grace is over, and says that the module drained")
    void theWaitEndsWithTheLastHandler(final CapturedOutput output) throws Exception {
        final var drain = new ZenBpmDrain("orders", "shop");
        drain.enter(42, "ship");
        final var leaving = CompletableFuture.runAsync(() -> {
            sleep(Duration.ofMillis(300));
            drain.leave(42);
        });

        final var started = System.nanoTime();
        final var quiet = ZenBpmDrain.awaitEveryModuleQuiet(List.of(drain), Duration.ofSeconds(20));
        final var waited = Duration.ofNanos(System.nanoTime() - started);
        leaving.get(5, TimeUnit.SECONDS);

        assertAll(
                () -> assertTrue(quiet),
                () -> assertTrue(waited.compareTo(Duration.ofMillis(250)) >= 0, waited::toString),
                () -> assertTrue(waited.compareTo(Duration.ofSeconds(5)) < 0, waited::toString),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("ZenBPM[orders]: workflow module 'shop' drained after"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A handler which still runs after the grace is named with its job, task, aggregate and module, the key to raise and the budgets of the runtime")
    void aHandlerAfterTheGraceIsNamed(final CapturedOutput output) {
        final var drain = new ZenBpmDrain("orders", "shop");
        drain.enter(42, "ship");
        drain.describe(42, "order-1");
        drain.enter(43, "pack");

        final var quiet = ZenBpmDrain.awaitEveryModuleQuiet(List.of(drain), Duration.ofMillis(200));

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertFalse(quiet),
                () -> assertTrue(log.contains(
                        "ZenBPM[orders]: the handler of job 42 (task 'ship', workflow aggregate 'order-1', workflow module 'shop') still runs after the shutdown waited PT0.2S for it"),
                        log),
                () -> assertTrue(log.contains(
                        "the handler of job 43 (task 'pack', workflow aggregate 'not read yet', workflow module 'shop')"),
                        log),
                () -> assertTrue(log.contains(
                        "The job is not failed, so it keeps its retries; its lock is released when the job stream closes"),
                        log),
                () -> assertTrue(log.contains(
                        "Raise vanillabp.adapters.orders.shutdown-grace where handlers legitimately run that long, and the shutdown budget of the runtime with it (spring.lifecycle.timeout-per-shutdown-phase, Kubernetes' terminationGracePeriodSeconds)"),
                        log));
    }

    @Test
    @DisplayName("Several modules are one wait: two modules with a handler each which never returns cost one grace, not two, and each module reports its own outcome")
    void severalModulesAreOneWait(final CapturedOutput output) {
        final var shop = new ZenBpmDrain("orders", "shop");
        final var billing = new ZenBpmDrain("orders", "billing");
        final var audit = new ZenBpmDrain("orders", "audit");
        shop.enter(42, "ship");
        billing.enter(43, "charge");

        final var started = System.nanoTime();
        ZenBpmDrain.awaitEveryModuleQuiet(List.of(shop, billing, audit), Duration.ofMillis(500));
        final var waited = Duration.ofNanos(System.nanoTime() - started);

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertTrue(waited.compareTo(Duration.ofMillis(500)) >= 0, waited::toString),
                () -> assertTrue(waited.compareTo(Duration.ofMillis(900)) < 0, waited::toString),
                () -> assertTrue(log.contains("the handler of job 42 (task 'ship'"), log),
                () -> assertTrue(log.contains("the handler of job 43 (task 'charge'"), log),
                () -> assertTrue(log.contains("workflow module 'audit' drained after"), log));
    }

    @Test
    @DisplayName("A grace of zero waits for nothing")
    void aGraceOfZeroWaitsForNothing() {
        final var drain = new ZenBpmDrain("orders", "shop");
        drain.enter(42, "ship");

        final var started = System.nanoTime();
        final var quiet = ZenBpmDrain.awaitEveryModuleQuiet(List.of(drain), Duration.ZERO);
        final var waited = Duration.ofNanos(System.nanoTime() - started);

        assertAll(
                () -> assertFalse(quiet),
                () -> assertTrue(waited.compareTo(Duration.ofMillis(200)) < 0, waited::toString));
    }

    @Test
    @DisplayName("An interrupted wait ends at once, reports what still runs and keeps the interrupt")
    void anInterruptedWaitEndsAtOnce(final CapturedOutput output) throws Exception {
        final var drain = new ZenBpmDrain("orders", "shop");
        drain.enter(42, "ship");
        final var interruptKept = new AtomicBoolean();

        final var waiting = new Thread(() -> {
            Thread
                    .currentThread()
                    .interrupt();
            ZenBpmDrain.awaitEveryModuleQuiet(List.of(drain), Duration.ofSeconds(20));
            interruptKept.set(Thread
                    .currentThread()
                    .isInterrupted());
        });
        waiting.start();
        waiting.join(Duration
                .ofSeconds(5)
                .toMillis());

        assertAll(
                () -> assertFalse(waiting.isAlive()),
                () -> assertTrue(interruptKept.get()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("the handler of job 42 (task 'ship'"), output::getAllOfThisTest));
    }

    private static void sleep(final Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
        }
    }

}
