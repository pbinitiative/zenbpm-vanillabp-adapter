/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmCommandRetry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The iterations a task runs in, read from the pinned engine by a reader which kept nothing, as on
 * another node of the application, after a restart or once the body left the cache. The engine
 * iterates over the collection it evaluated when the element was entered, while the body's copy of
 * the variables may be written since, as a completion or a push of the aggregate's values does.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmMultiInstanceIT {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static ZenBpmRestClient rest;

    @BeforeAll
    static void deployTheModels() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        rest = new ZenBpmRestClient("multi-instance", configuration);
        rest.deployBpmn(resource("/contract/contract-multi-instance.bpmn"));
        rest.deployBpmn(resource("/wiring/multi-instance-expression.bpmn"));
    }

    @AfterAll
    static void closeTheClient() {
        rest.close();
    }

    @Test
    @DisplayName("Where the collection was changed in the body and in the workflow while the iterations run, every iteration of a parallel sub-process and of a sequential task is told the total the engine iterates over")
    void aCollectionChangedWhileTheIterationsRunKeepsItsTotal() {
        final var started = rest.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-multi-instance")
                .withVariables(Map.of("items", List.of("a", "b", "c"))));
        final var eachBody = eventually(() -> bodyOf(started.key(), null));
        final var eachIterations = eventually(() -> Optional
                .of(rest.children(eachBody.key()))
                .filter(children -> children.size() == 3));
        // the workflow keeps its collection, or the sequential task after the sub-process would
        // iterate over the changed one
        changeTheCollection(eachBody.key());

        final var toldInEach = new ArrayList<MultiInstanceValue>();
        for (final var iteration : eachIterations) {
            final var job = eventually(() -> rest
                    .jobsOf(iteration.key())
                    .stream()
                    .findFirst());
            toldInEach.add(readerKeepingNothing()
                    .iterationsOf(job.key(), iteration.key(), "EachTask", retry(job.key()))
                    .get("Each"));
        }
        toldInEach.sort(Comparator.comparing(MultiInstanceValue::index));
        eachIterations.forEach(iteration -> rest.completeJob(eventually(() -> rest
                .jobsOf(iteration.key())
                .stream()
                .findFirst()).key(), null));
        final var oneByOneBody = eventually(() -> bodyOf(started.key(), eachBody.key()));
        final var toldOneByOne = new ArrayList<MultiInstanceValue>();
        for (var iteration = 0; iteration < 3; ++iteration) {
            final var job = eventually(() -> rest
                    .jobsOf(oneByOneBody.key())
                    .stream()
                    .filter(waiting -> waiting.state() == JobRef.State.ACTIVE)
                    .findFirst());
            changeTheCollection(started.key(), oneByOneBody.key());
            toldOneByOne.add(readerKeepingNothing()
                    .iterationsOf(job.key(), oneByOneBody.key(), "OneByOne", retry(job.key()))
                    .get("OneByOne"));
            rest.completeJob(job.key(), null);
        }

        assertEquals(List.of(List.of(new MultiInstanceValue("a", 0, 3), new MultiInstanceValue("b", 1, 3),
                new MultiInstanceValue("c", 2, 3)), List.of(new MultiInstanceValue("a", 0, 3), new MultiInstanceValue(
                        "b", 1, 3), new MultiInstanceValue("c", 2, 3))),
                List.of(toldInEach, toldOneByOne));
    }

    @Test
    @DisplayName("The total of a collection written as an expression, of an element which names no input element, is the size of what the engine evaluated")
    void aCollectionWrittenAsAnExpressionIsCounted() {
        final var started = rest.createInstance(CreateInstanceRequest
                .latestVersionOf("multi-instance-expression")
                .withVariables(Map.of("numbers", List.of(1, 2, 3, 4))));
        final var body = eventually(() -> bodyOf(started.key(), null));
        final var job = eventually(() -> rest
                .jobsOf(body.key())
                .stream()
                .min(Comparator
                        .comparing(JobRef::createdAt)
                        .thenComparing(JobRef::key)));

        assertEquals(new MultiInstanceValue(null, 0, 4), readerKeepingNothing()
                .iterationsOf(job.key(), body.key(), "EachNumber", retry(job.key()))
                .get("EachNumber"));
    }

    private static Optional<ProcessInstanceRef> bodyOf(
        final long instanceKey,
        final Long otherThan
    ) {
        return rest
                .children(instanceKey)
                .stream()
                .filter(child -> child.processType() == ProcessInstanceRef.ProcessType.MULTI_INSTANCE)
                .filter(child -> (otherThan == null) || (child.key() != otherThan))
                .findFirst();
    }

    // as a completion or a push of a changed aggregate writes it
    private static void changeTheCollection(final long... instanceKeys) {
        for (final var instanceKey : instanceKeys) {
            rest.patchVariables(instanceKey, Map.of("items", List.of("replacement")));
        }
    }

    private static ZenBpmMultiInstance readerKeepingNothing() {
        return new ZenBpmMultiInstance("multi-instance", "contract", rest, new ZenBpmInstanceCache(rest, 0), 0);
    }

    private static ZenBpmCommandRetry retry(final long jobKey) {
        return new ZenBpmCommandRetry("multi-instance", jobKey, "contract", () -> Instant
                .now()
                .plus(Duration.ofMinutes(1)), () -> false);
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

    private static byte[] resource(final String path) {
        try (var model = ZenBpmMultiInstanceIT.class.getResourceAsStream(path)) {
            return model.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
