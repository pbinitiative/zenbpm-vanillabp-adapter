/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.springboot.it.message.Letter;
import org.pbinitiative.zenbpmadapter.springboot.it.message.LetterRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.message.MessageItApplication;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * What a booted Spring Boot application does with the messages it correlates, against the pinned
 * engine: a waiting workflow continues on a message, with the values its aggregate shares now; a
 * message published before anything waits for it is answered <code>404</code> by the engine, which
 * keeps no such message, and the outbox repeats the publication every
 * <code>workflow-visibility-timeout</code> without counting an attempt until the workflow waits; a
 * correlation id is the correlation key, so it selects which of two waiting elements of one
 * workflow takes the message; a second correlation with the same correlation id is not planned
 * while the first still waits in the outbox; a message no model waits for is refused where the
 * application calls; and a publication nothing ever waits for is blocked once
 * <code>vanillabp.outbox.wait-for-visibility-at-most</code> ran out.
 * <p>
 * The application {@link MessageItApplication} runs with <code>message-it.yaml</code>, the default
 * <code>workflow-visibility-timeout</code> of two seconds and fifteen seconds of
 * <code>wait-for-visibility-at-most</code>. Its one process has a path per scenario of
 * {@link Letter}. One engine serves the class, so every case uses letters of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = MessageItApplication.class, properties = "spring.config.name=message-it")
@DirtiesContext
class ZenBpmMessageIT {

    /**
     * How long a case waits for what the engine or the application does. Each step takes
     * milliseconds, or the five seconds of the timer and one window of two seconds after it, so the
     * limit only turns a step which never happens into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * The timer in front of the go-ahead of the path {@link Letter#DELAY}.
     */
    private static final Duration DELAY = Duration.ofSeconds(5);

    /**
     * The <code>vanillabp.outbox.wait-for-visibility-at-most</code> of <code>message-it.yaml</code>,
     * longer than the timer and a window after it.
     */
    private static final Duration WAIT_FOR_VISIBILITY_AT_MOST = Duration.ofSeconds(15);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Autowired
    private ProcessService<Letter> processService;

    @Autowired
    private LetterRepository letters;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ZenBpmClientRegistry clients;

    @Test
    @DisplayName("A correlation resumes the workflow which waits for the message, and the message carries the values the aggregate shares now; the publication is sent once and counts one attempt")
    void correlateMessageResumesTheWaitingWorkflow(final CapturedOutput output) {
        final var letterId = uniqueLetterId();
        final var instance = start(letterId, Letter.AWAIT);
        awaitTheWaitAt(instance, "AwaitGoAhead");

        correlate(letterId, letter -> {
            letter.setStatus("approved");
            processService.correlateMessage(letter, "goAhead");
        });
        final var ended = awaitTheEndOf(instance);

        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(Set.of("Sent"), endsOf(instance)),
                () -> assertEquals("approved", ended
                        .variables()
                        .get("status")),
                () -> assertEquals(List.of(1), attemptsOfTheCorrelationsOf(letterId)),
                () -> assertFalse(output
                        .getAllOfThisTest()
                        .contains("cannot run yet"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A message published before the workflow waits for it is repeated every workflow-visibility-timeout until it correlates: the workflow continues after its timer, the repetitions count no attempt, and the entry is done, never blocked")
    void aMessagePublishedBeforeTheSubscriptionIsRepeatedUntilItCorrelates(final CapturedOutput output) {
        final var letterId = uniqueLetterId();
        final var startedAt = Instant.now();
        final var instance = start(letterId, Letter.DELAY);

        correlate(letterId, letter -> processService.correlateMessage(letter, "goAhead"));
        final var ended = awaitTheEndOf(instance);
        final var tookFromTheStart = Duration.between(startedAt, Instant.now());

        final var log = output.getAllOfThisTest();
        final var handedBack = "for aggregate '%s' cannot run yet - the outbox entry".formatted(letterId);
        final var reason = """
                ZenBPM adapter 'zenbpm': nothing waits for message 'goAhead' (scoped: 'deploy-it__goAhead') under \
                correlation key '%s' yet, in workflow 'Letters' of workflow module 'deploy-it' for aggregate '%s', \
                and ZenBPM keeps no message nothing waits for. The publication is repeated every PT2S until \
                'vanillabp.outbox.wait-for-visibility-at-most' passed""".formatted(letterId, letterId);
        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(Set.of("SentLater"), endsOf(instance)),
                () -> assertTrue(tookFromTheStart.compareTo(DELAY) >= 0, tookFromTheStart::toString),
                () -> assertTrue(occurrences(log, handedBack) >= 2, log),
                () -> assertTrue(log.contains(reason), log),
                () -> assertTrue(log.contains("is dispatched again in PT2S"), log),
                () -> assertEquals(List.of(1), attemptsOfTheCorrelationsOf(letterId)),
                () -> assertEquals(List.of(PhaseTwoOutboxReader.State.DISPATCHED),
                        statesOfTheCorrelationsOf(letterId)));
    }

    @Test
    @DisplayName("A correlation id is the correlation key: where two elements of one workflow wait for the same message under modelled keys, it selects the one whose key it is")
    void aCorrelationIdSelectsTheWaitingOccurrence() {
        final var letterId = uniqueLetterId();
        final var instance = start(letterId, Letter.REVIEW);
        awaitTheWaitAt(instance, "FirstReview");
        awaitTheWaitAt(instance, "SecondReview");

        correlate(letterId, letter -> processService.correlateMessage(letter, "reviewed", letter
                .getSecondReviewer()));
        eventually(() -> !waitingElementsOf(instance).contains("SecondReview"), "the second review to be taken");
        final var waitingAfterTheSecond = waitingElementsOf(instance);
        correlate(letterId, letter -> processService.correlateMessage(letter, "reviewed", letter.getFirstReviewer()));
        final var ended = awaitTheEndOf(instance);

        assertAll(
                () -> assertTrue(waitingAfterTheSecond.contains("FirstReview"), waitingAfterTheSecond::toString),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(Set.of("Reviewed"), endsOf(instance)));
    }

    @Test
    @DisplayName("A second correlation with the same correlation id is not planned while the first still waits in the outbox, so the workflow gets the message once")
    void aSecondCorrelationWithTheSameIdIsNotPlannedWhileTheFirstWaits() {
        final var letterId = uniqueLetterId();
        final var instance = start(letterId, Letter.DELAY);

        // the waiting element evaluates the aggregate id, so that is the correlation id which
        // reaches it
        correlate(letterId, letter -> processService.correlateMessage(letter, "goAhead", letterId));
        correlate(letterId, letter -> processService.correlateMessage(letter, "goAhead", letterId));
        final var plannedWhileWaiting = statesOfTheCorrelationsOf(letterId);
        final var ended = awaitTheEndOf(instance);

        assertAll(
                () -> assertEquals(List.of(PhaseTwoOutboxReader.State.WAITING), plannedWhileWaiting),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(List.of(PhaseTwoOutboxReader.State.DISPATCHED),
                        statesOfTheCorrelationsOf(letterId)));
    }

    @Test
    @DisplayName("A publication nothing ever waits for, a correlation id no waiting element evaluates, is repeated until wait-for-visibility-at-most ran out, and then its entry is blocked; the workflow keeps waiting")
    void aPublicationNothingEverWaitsForIsBlockedOnceTheWindowRanOut(final CapturedOutput output) {
        final var letterId = uniqueLetterId();
        final var instance = start(letterId, Letter.AWAIT);
        awaitTheWaitAt(instance, "AwaitGoAhead");
        final var plannedAt = Instant.now();

        correlate(letterId, letter -> processService.correlateMessage(letter, "goAhead", "%s/nobody".formatted(
                letterId)));
        eventually(() -> statesOfTheCorrelationsOf(letterId).equals(List.of(PhaseTwoOutboxReader.State.BLOCKED)),
                "the entry to be blocked");
        final var blockedAfter = Duration.between(plannedAt, Instant.now());

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertTrue(blockedAfter.compareTo(WAIT_FOR_VISIBILITY_AT_MOST) >= 0, blockedAfter::toString),
                () -> assertTrue(log.contains(
                        "for its BPMS to report the workflow, which is longer than 'vanillabp.outbox.wait-for-visibility-at-most' allows"),
                        log),
                () -> assertTrue(occurrences(log, "for aggregate '%s' cannot run yet - the outbox entry".formatted(
                        letterId)) >= 2, log),
                () -> assertEquals(Set.of("AwaitGoAhead"), waitingElementsOf(instance)));
    }

    @Test
    @DisplayName("A message no model of the workflow module waits for is refused where the application calls, which rolls its transaction back, and nothing is planned")
    void aMessageNoModelWaitsForIsRefusedWhereTheApplicationCalls() {
        final var letterId = uniqueLetterId();
        final var instance = start(letterId, Letter.AWAIT);

        final var refusal = assertThrows(IllegalArgumentException.class, () -> correlate(letterId, letter -> {
            letter.setStatus("misrouted");
            processService.correlateMessage(letter, "goAheda");
        }));

        assertAll(
                () -> assertTrue(refusal
                        .getMessage()
                        .startsWith(
                                "ZenBPM adapter 'zenbpm' cannot correlate message 'goAheda' (scoped: 'deploy-it__goAheda') to workflow 'Letters' of workflow module 'deploy-it': no model of the workflow module waits for that message."),
                        refusal::getMessage),
                () -> assertEquals("written", letters
                        .findById(letterId)
                        .orElseThrow()
                        .getStatus()),
                () -> assertEquals(List.of(), statesOfTheCorrelationsOf(letterId)),
                () -> assertEquals(ProcessInstanceRef.State.ACTIVE, rest()
                        .instance(instance)
                        .orElseThrow()
                        .state()));
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", ENGINE::restAddress);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    private static String uniqueLetterId() {
        return "letter-%s".formatted(UUID.randomUUID());
    }

    private long start(
        final String letterId,
        final String scenario
    ) {
        transactionTemplate
                .executeWithoutResult(status -> processService.startWorkflow(new Letter(letterId, scenario)));
        return eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(letterId))
                .items()
                .stream()
                .findFirst(), "the workflow to start")
                .key();
    }

    // in a transaction of its own, as an application correlates
    private void correlate(
        final String letterId,
        final Consumer<Letter> correlation
    ) {
        transactionTemplate.executeWithoutResult(status -> correlation.accept(letters
                .findById(letterId)
                .orElseThrow()));
    }

    private void awaitTheWaitAt(
        final long instanceKey,
        final String elementId
    ) {
        eventually(() -> waitingElementsOf(instanceKey).contains(elementId), "the workflow to wait at %s".formatted(
                elementId));
    }

    private ProcessInstanceRef awaitTheEndOf(final long instanceKey) {
        return eventually(() -> rest()
                .instance(instanceKey)
                .filter(instance -> instance.state() != ProcessInstanceRef.State.ACTIVE), "the workflow to end");
    }

    // the elements an instance is in now, read past the adapter's client, which keeps no element
    private static Set<String> waitingElementsOf(final long instanceKey) {
        final var waiting = new TreeSet<String>();
        ENGINE
                .json("/v1/process-instances/%d".formatted(instanceKey))
                .path("activeElementInstances")
                .forEach(element -> waiting.add(element
                        .path("elementId")
                        .asText()));
        return waiting;
    }

    // the end events the instance passed, from its history
    private static Set<String> endsOf(final long instanceKey) {
        final var ends = new TreeSet<String>();
        ENGINE
                .json("/v1/process-instances/%d/history?size=100".formatted(instanceKey))
                .path("items")
                .forEach(element -> {
                    final var elementId = element
                            .path("elementId")
                            .asText();
                    if (Set
                            .of("Sent", "SentLater", "Reviewed")
                            .contains(elementId)) {
                        ends.add(elementId);
                    }
                });
        return ends;
    }

    private List<Integer> attemptsOfTheCorrelationsOf(final String letterId) {
        return correlationsOf(letterId)
                .stream()
                .map(PhaseTwoOutboxReader.Entry::attempts)
                .toList();
    }

    private List<PhaseTwoOutboxReader.State> statesOfTheCorrelationsOf(final String letterId) {
        return correlationsOf(letterId)
                .stream()
                .map(PhaseTwoOutboxReader.Entry::state)
                .toList();
    }

    private List<PhaseTwoOutboxReader.Entry> correlationsOf(final String letterId) {
        return PhaseTwoOutboxReader
                .of(dataSource)
                .entries()
                .stream()
                .filter(entry -> letterId.equals(entry.aggregateId()))
                .filter(entry -> "CORRELATE_MESSAGE".equals(entry.operation()))
                .toList();
    }

    private static int occurrences(
        final String log,
        final String line
    ) {
        var found = 0;
        for (var at = log.indexOf(line); at >= 0; at = log.indexOf(line, at + 1)) {
            ++found;
        }
        return found;
    }

    private ZenBpmRestClient rest() {
        return clients
                .getFactory("zenbpm")
                .rest();
    }

    private static void eventually(
        final BooleanSupplier condition,
        final String what
    ) {
        eventually(() -> condition.getAsBoolean() ? Optional.of(Boolean.TRUE) : Optional.empty(), what);
    }

    private static <T> T eventually(
        final Supplier<Optional<T>> read,
        final String what
    ) {
        final var deadline = Instant
                .now()
                .plus(TIMEOUT);
        while (true) {
            final var found = read.get();
            if (found.isPresent()) {
                return found.get();
            }
            assertTrue(Instant
                    .now()
                    .isBefore(deadline), () -> "waited %s for %s".formatted(TIMEOUT, what));
            LockSupport.parkNanos(Duration
                    .ofMillis(50)
                    .toNanos());
        }
    }

}
