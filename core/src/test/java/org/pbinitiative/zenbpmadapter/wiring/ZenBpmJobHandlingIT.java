/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmApiException;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the pinned engine tells the handler of a job, and how it answers the update of variables
 * before a completion, as far as the handler relies on it: an instance of an embedded sub-process
 * and an instance a call activity started carry the business key and the id variable of their
 * workflow; a sub-process holds its own copy of the variables, and what is written into it is gone
 * once it ended; an update of an ended or cancelled workflow is refused with <code>409</code>, and
 * one of an instance the engine does not hold with <code>404</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmJobHandlingIT {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static ZenBpmRestClient rest;

    @BeforeAll
    static void deployTheFixtures() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        rest = new ZenBpmRestClient("job-handling", configuration);
        rest.deployBpmn(fixture("probe-order.bpmn"));
        rest.deployBpmn(fixture("probe-caller.bpmn"));
    }

    @AfterAll
    static void closeTheClient() {
        rest.close();
    }

    @Test
    @DisplayName("The instance of an embedded sub-process, and the one a call activity started, carry the business key and the id variable of their workflow")
    void childInstancesCarryTheBusinessKeyAndTheId() {
        final var orderId = uniqueOrderId();
        final var order = start("probe-order", orderId);
        final var callerId = uniqueOrderId();
        final var caller = start("probe-caller", callerId);

        final var subProcess = rest
                .instance(waitingJobOf(order.key()).instanceKey())
                .orElseThrow();
        final var called = rest
                .children(caller.key())
                .getFirst();
        final var subProcessOfTheCalled = rest
                .instance(waitingJobOf(caller.key()).instanceKey())
                .orElseThrow();

        assertAll(
                () -> assertEquals(ProcessInstanceRef.ProcessType.SUBPROCESS, subProcess.processType()),
                () -> assertEquals(orderId, subProcess.businessKey()),
                () -> assertEquals(orderId, subProcess
                        .variables()
                        .get("orderId")),
                () -> assertEquals(ProcessInstanceRef.ProcessType.CALL_ACTIVITY, called.processType()),
                () -> assertEquals(callerId, called.businessKey()),
                () -> assertEquals(callerId, called
                        .variables()
                        .get("orderId")),
                () -> assertEquals(callerId, subProcessOfTheCalled.businessKey()),
                () -> assertEquals(callerId, subProcessOfTheCalled
                        .variables()
                        .get("orderId")));
    }

    @Test
    @DisplayName("A sub-process holds a copy of the variables taken when it started: what is written into the root later does not reach it, and what is written into it is gone once it ended")
    void aSubProcessHoldsACopyOfTheVariables() {
        final var order = start("probe-order", uniqueOrderId());
        final var job = waitingJobOf(order.key());

        rest.patchVariables(order.key(), Map.of("writtenIntoTheRoot", true));
        rest.patchVariables(job.instanceKey(), Map.of("writtenIntoTheSubProcess", true));
        final var subProcess = rest
                .instance(job.instanceKey())
                .orElseThrow();
        rest.completeJob(job.key(), Map.of("sentWithTheCompletion", true));
        final var ended = eventually(() -> rest
                .instance(order.key())
                .filter(root -> root.state() == ProcessInstanceRef.State.COMPLETED));

        assertAll(
                () -> assertFalse(subProcess
                        .variables()
                        .containsKey("writtenIntoTheRoot"), subProcess::toString),
                () -> assertTrue(ended
                        .variables()
                        .containsKey("writtenIntoTheRoot"), ended::toString),
                () -> assertFalse(ended
                        .variables()
                        .containsKey("writtenIntoTheSubProcess"), ended::toString),
                () -> assertFalse(ended
                        .variables()
                        .containsKey("sentWithTheCompletion"), ended::toString));
    }

    @Test
    @DisplayName("An update of the variables of a completed or a cancelled workflow is refused with 409, one of an instance the engine does not hold with 404")
    void anUpdateOfAnEndedWorkflowIsAConflict() throws IOException, InterruptedException {
        final var completed = start("probe-order", uniqueOrderId());
        final var job = waitingJobOf(completed.key());
        rest.completeJob(job.key(), Map.of());
        eventually(() -> rest
                .instance(completed.key())
                .filter(root -> root.state() == ProcessInstanceRef.State.COMPLETED));
        final var cancelled = start("probe-order", uniqueOrderId());
        waitingJobOf(cancelled.key());
        cancel(cancelled.key());
        eventually(() -> rest
                .instance(cancelled.key())
                .filter(root -> root.state() == ProcessInstanceRef.State.TERMINATED));
        // a key of the same partition, from a moment far ahead, which nobody holds
        final var nobodyHoldsIt = completed.key() + (1L << 40);

        final var ofTheCompleted = assertThrows(ZenBpmApiException.class, () -> rest.patchVariables(completed.key(),
                Map.of("late", true)));
        final var ofTheCompletedSubProcess = assertThrows(ZenBpmApiException.class, () -> rest.patchVariables(job
                .instanceKey(), Map.of("late", true)));
        final var ofTheCancelled = assertThrows(ZenBpmApiException.class, () -> rest.patchVariables(cancelled.key(),
                Map.of("late", true)));
        final var ofNone = assertThrows(ZenBpmApiException.class, () -> rest.patchVariables(nobodyHoldsIt, Map.of(
                "late", true)));

        assertAll(
                () -> assertTrue(ZenBpmErrors.variablesFoundTheInstanceEnded(ofTheCompleted), ofTheCompleted::toString),
                () -> assertTrue(ZenBpmErrors.variablesFoundTheInstanceEnded(ofTheCompletedSubProcess),
                        ofTheCompletedSubProcess::toString),
                () -> assertTrue(ZenBpmErrors.variablesFoundTheInstanceEnded(ofTheCancelled), ofTheCancelled::toString),
                () -> assertTrue(ZenBpmErrors.variablesFoundNoInstance(ofNone), ofNone::toString));
    }

    private static ProcessInstanceRef start(
        final String bpmnProcessId,
        final String orderId
    ) {
        return rest.createInstance(CreateInstanceRequest
                .latestVersionOf(bpmnProcessId)
                .withBusinessKey(orderId)
                .withVariables(Map.of("orderId", orderId)));
    }

    // the job may wait in a child instance, so the instances below the root are asked one by one
    private static JobRef waitingJobOf(final long rootKey) {
        return eventually(() -> {
            final var instances = new ArrayList<Long>(List.of(rootKey));
            for (var index = 0; index < instances.size(); index++) {
                final var key = instances.get(index);
                final var waiting = rest
                        .jobs(JobFilter
                                .anyJob()
                                .withProcessInstanceKey(key)
                                .withState(JobRef.State.ACTIVE))
                        .items();
                if (!waiting.isEmpty()) {
                    return Optional.of(waiting.getFirst());
                }
                rest
                        .children(key)
                        .forEach(child -> instances.add(child.key()));
            }
            return Optional.empty();
        });
    }

    // the adapter's client has no call for it, because the adapter never cancels a workflow
    private static void cancel(final long key) throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            final var answer = client.send(HttpRequest
                    .newBuilder(URI.create("%s/v1/process-instances/%d/cancel".formatted(ENGINE.restAddress(), key)))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, answer.statusCode(), answer::body);
        }
    }

    private static <T> T eventually(final Supplier<Optional<T>> read) {
        final var deadline = Instant
                .now()
                .plus(PATIENCE);
        while (true) {
            final var found = read.get();
            if (found.isPresent() || Instant
                    .now()
                    .isAfter(deadline)) {
                return found.orElseThrow();
            }
            LockSupport.parkNanos(Duration
                    .ofMillis(50)
                    .toNanos());
        }
    }

    private static String uniqueOrderId() {
        return "order-%s".formatted(UUID.randomUUID());
    }

    private static byte[] fixture(final String name) {
        try (var file = ZenBpmJobHandlingIT.class.getResourceAsStream("/probes/%s".formatted(name))) {
            return file.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
