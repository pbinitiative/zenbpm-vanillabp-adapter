/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
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
 * The markers on the pinned engine: the fixture <code>models/markers.bpmn</code>, rewritten with its
 * start and end markers, deploys, and nothing of a workflow runs before the job of its start marker
 * is completed, whether a request or a message started it. The marker's job carries the variables
 * of the instance, an instance started by a request without a business key or by a message carries
 * none, and a variable written into the instance while it waits at the marker reaches the task
 * behind it. A workflow which reaches a plain or a terminate end event of its own waits at the end
 * event's marker, and completes once the marker's job is completed. At an error end event which
 * nothing catches, which gets no marker, the engine raises an incident and the instance does not
 * end.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmMarkerTasksIT {

    private static final String JOB_TYPE = ZenBpmMarkerTasks.startMarkerJobType("Orders");

    private static final String END_JOB_TYPE = ZenBpmMarkerTasks.endMarkerJobType("Orders");

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static ZenBpmRestClient rest;

    @BeforeAll
    static void deployTheRewrittenFixture() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        rest = new ZenBpmRestClient("markers", configuration);
        final var model = read();
        ZenBpmMarkerTasks.insertStartMarkers(model, JOB_TYPE);
        ZenBpmMarkerTasks.insertEndMarkers(model, END_JOB_TYPE);
        rest.deployBpmn(ZenBpmModelWriter.bytesOf(model));
    }

    @AfterAll
    static void closeTheClient() {
        rest.close();
    }

    @Test
    @DisplayName("A workflow started by a request waits at the marker of its plain start event, whose job carries its variables; what is written into it meanwhile reaches the task behind")
    void aPlainStartWaitsAtItsMarker() {
        final var started = rest.createInstance(CreateInstanceRequest
                .latestVersionOf("Orders")
                .withVariables(Map.of("orderId", "order-1")));
        final var marker = waitingJobOf(started.key());

        rest.patchVariables(started.key(), Map.of("id", "order-1"));
        rest.completeJob(marker.key(), null);
        final var weigh = eventually(() -> rest
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(started.key())
                        .withJobType("weigh"))
                .items()
                .stream()
                .findFirst());

        assertAll(
                () -> assertNull(started.businessKey()),
                () -> assertEquals(JOB_TYPE, marker.type()),
                () -> assertEquals("vanillabp__started__Plain", marker.elementId()),
                () -> assertEquals("order-1", marker
                        .inputVariables()
                        .get("orderId")),
                () -> assertEquals("order-1", weigh
                        .inputVariables()
                        .get("id")));
    }

    @Test
    @DisplayName("A workflow started by a message waits at the marker of its message start event")
    void aMessageStartWaitsAtItsMarker() {
        rest.publishMessage("ordered", null, Map.of("orderId", "order-2"));

        final var marker = eventually(() -> rest
                .jobs(JobFilter
                        .anyJob()
                        .withJobType(JOB_TYPE))
                .items()
                .stream()
                .filter(job -> "vanillabp__started__ByMessage".equals(job.elementId()))
                .findFirst());

        assertAll(
                () -> assertEquals(JobRef.State.ACTIVE, marker.state()),
                () -> assertEquals("order-2", marker
                        .inputVariables()
                        .get("orderId")),
                () -> assertNull(rest
                        .instance(marker.instanceKey())
                        .orElseThrow()
                        .businessKey()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "Shipped, false", "Rejected, true"
    })
    @DisplayName("A workflow which reaches a plain or a terminate end event of its own waits at the end event's marker, and completes once the marker's job is completed")
    void aWorkflowWaitsAtTheMarkerOfItsEndEvent(
        final String endEvent,
        final boolean rejected
    ) {
        final var started = startedPastItsStartMarker(endEvent, rejected, false);

        final var marker = eventually(() -> activeJobOf(started, END_JOB_TYPE));
        final var whileItWaits = rest
                .instance(started)
                .orElseThrow()
                .state();
        rest.completeJob(marker.key(), null);

        assertAll(
                () -> assertEquals("vanillabp__ended__%s".formatted(endEvent), marker.elementId()),
                () -> assertEquals(ProcessInstanceRef.State.ACTIVE, whileItWaits),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, eventually(() -> rest
                        .instance(started)
                        .filter(instance -> instance.state() != ProcessInstanceRef.State.ACTIVE))
                        .state()));
    }

    @Test
    @DisplayName("At an error end event which nothing catches, which gets no marker, the engine raises an incident, and the instance stays active with no token left")
    void anUncaughtErrorEndEventRaisesAnIncident() {
        final var started = startedPastItsStartMarker("Lost", false, true);

        final var incident = eventually(() -> Optional
                .of(ENGINE.json("/v1/process-instances/%d/incidents".formatted(started)))
                .filter(incidents -> incidents
                        .path("totalCount")
                        .asInt() > 0));

        assertAll(
                () -> assertEquals("unhandled error end event Lost: lost", incident
                        .path("items")
                        .path(0)
                        .path("message")
                        .asText()),
                () -> assertEquals(ProcessInstanceRef.State.ACTIVE, rest
                        .instance(started)
                        .orElseThrow()
                        .state()),
                () -> assertEquals(Optional.of(List.of()), rest.activeElementsOf(started)),
                () -> assertEquals(List.of(), rest
                        .jobs(JobFilter
                                .anyJob()
                                .withProcessInstanceKey(started)
                                .withJobType(END_JOB_TYPE))
                        .items()));
    }

    // started by a request and taken past its start marker and its task, so it goes on to the end
    // event the variables lead to
    private static long startedPastItsStartMarker(
        final String endEvent,
        final boolean rejected,
        final boolean lost
    ) {
        final var started = rest.createInstance(CreateInstanceRequest
                .latestVersionOf("Orders")
                .withVariables(Map.of("orderId", "order-%s".formatted(endEvent), "rejected", rejected, "lost",
                        lost)));
        rest.completeJob(waitingJobOf(started.key()).key(), null);
        rest.completeJob(eventually(() -> activeJobOf(started.key(), "weigh")).key(), null);
        return started.key();
    }

    private static Optional<JobRef> activeJobOf(
        final long instanceKey,
        final String jobType
    ) {
        return rest
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(instanceKey)
                        .withJobType(jobType)
                        .withState(JobRef.State.ACTIVE))
                .items()
                .stream()
                .findFirst();
    }

    private static JobRef waitingJobOf(final long instanceKey) {
        return eventually(() -> rest
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(instanceKey)
                        .withState(JobRef.State.ACTIVE))
                .items()
                .stream()
                .findFirst());
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

    private static ZenBpmBpmnModel read() {
        try (InputStream file = ZenBpmMarkerTasksIT.class.getResourceAsStream("/models/markers.bpmn")) {
            return ZenBpmModelReader
                    .read("markers", "markers.bpmn", file)
                    .getFirst();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
