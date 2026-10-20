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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmCommandRetry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The iterations a task runs in, read from what the engine keeps of a multi-instance element. The
 * engine is a fake which holds instances in the shape the pinned engine gives them: an activation
 * of a multi-instance element is a body of the process type <code>multiInstance</code> under the
 * instance the element stands in, holding a copy of that instance's variables; an iteration of a
 * sub-process or a call activity is a child instance of the body which holds the item, and an
 * iteration of a task is a job of the body which carries the item; every child takes the business
 * key and the definition of its parent, and the engine lists children and jobs by key, not in the
 * order they were created. The collection an activation iterates over is the one the history of the
 * instance the element stands in keeps for the entry of the token waiting at the element, under
 * the name of the input element, as the engine evaluated it when the element was entered.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmMultiInstanceTest {

    private static final long ORDER = 4503599627370498L;

    private static final long LABELLING = 4503599627370499L;

    private static final long ROOT = 100L;

    private static final long PARCELS = 200L;

    private static final long WEIGHING = 300L;

    private static final long STAMPING = 400L;

    private static final long LABELS = 500L;

    private static final long SCANNING = 600L;

    private static final long PRINTING = 700L;

    private static final String COLLECTION = "\"orderId\": \"order-1\", \"parcels\": [\"a\", \"b\", \"c\"]";

    private static final String PARCELS_EVALUATED = "[\"a\", \"b\", \"c\"]";

    private final FakeEngine engine = FakeEngine.started();

    private final ZenBpmRestClient rest = restClient(engine);

    /**
     * The jobs of each instance, which <code>GET /v1/jobs</code> answers by its
     * <code>processInstanceKey</code>.
     */
    private final Map<Long, List<String>> jobs = new ConcurrentHashMap<>();

    /**
     * The tokens which stand in each instance, which <code>GET /v1/process-instances/{key}</code>
     * answers as its <code>activeElementInstances</code>.
     */
    private final Map<Long, List<String>> tokens = new ConcurrentHashMap<>();

    /**
     * The entries of the history of each instance.
     */
    private final Map<Long, List<String>> histories = new ConcurrentHashMap<>();

    private final ZenBpmMultiInstance multiInstance = new ZenBpmMultiInstance("orders", "shop", rest, new ZenBpmInstanceCache(rest, 100), 100);

    @AfterEach
    void stopTheEngine() {
        rest.close();
        engine.close();
    }

    @Test
    @DisplayName("A task inside a parallel multi-instance sub-process is told the item of its iteration, its index counted from 0 and the size of the collection, keyed by the sub-process")
    void aTaskInsideAParallelSubProcessIsToldItsIteration() {
        holdsTheParcelsBody();

        final var iterations = multiInstance.iterationsOf(42L, 202L, "Pick", retry());

        assertEquals(Map.of("Parcels", new MultiInstanceValue("b", 1, 3)), iterations);
    }

    @Test
    @DisplayName("The iterations are counted in the order the engine created them, and two of the same millisecond by their keys, whatever order the engine lists them in")
    void theIterationsAreCountedInTheOrderTheyWereCreated() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Parcels", 150L, "parcel", PARCELS_EVALUATED);
        holds(PARCELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        holds(202L, "subprocess", PARCELS, ORDER, "shop__Order", "\"parcel\": \"b\"");
        hasChildren(PARCELS, child(203L, "2026-10-17T14:30:25.130Z", "subprocess", PARCELS, "\"parcel\": \"c\""),
                child(202L, "2026-10-17T14:30:25.123Z", "subprocess", PARCELS, "\"parcel\": \"b\""), child(201L,
                        "2026-10-17T14:30:25.123Z", "subprocess", PARCELS, "\"parcel\": \"a\""));

        // 201 and 202 were created in the same millisecond, so their keys alone tell their order
        assertEquals(new MultiInstanceValue("b", 1, 3), multiInstance
                .iterationsOf(42L, 202L, "Pick", retry())
                .get("Parcels"));
    }

    @Test
    @DisplayName("A second task of the same body asks the engine nothing but its own instance: the model, the body, its total and the iterations are kept")
    void aSecondTaskOfTheSameBodyReadsNothingAgain() {
        holdsTheParcelsBody();
        holds(203L, "subprocess", PARCELS, ORDER, "shop__Order", "\"parcel\": \"c\"");

        final var first = multiInstance.iterationsOf(42L, 202L, "Pick", retry());
        final var second = multiInstance.iterationsOf(43L, 203L, "Pick", retry());

        assertAll(
                () -> assertEquals(Map.of("Parcels", new MultiInstanceValue("b", 1, 3)), first),
                () -> assertEquals(Map.of("Parcels", new MultiInstanceValue("c", 2, 3)), second),
                () -> assertEquals(1, requestsFor("/v1/process-definitions/%d".formatted(ORDER))),
                () -> assertEquals(1, requestsFor("/v1/process-instances/%d".formatted(PARCELS))),
                // once by the walk up, and once for the token which waits for the iterations
                () -> assertEquals(2, requestsFor("/v1/process-instances/%d".formatted(ROOT))),
                () -> assertEquals(1, requestsFor("/v1/process-instances/%d/history".formatted(ROOT))),
                () -> assertEquals(1, requestsFor("/v1/process-instances/%d/child-processes".formatted(PARCELS))));
    }

    @Test
    @DisplayName("An iteration of a sequential multi-instance task is a job of its body, and one created after the iterations were kept is found by reading them again")
    void aSequentialTaskFindsAnIterationCreatedLater() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Weigh", 350L, "parcel", PARCELS_EVALUATED);
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "completed", "\"parcel\": \"a\""));

        final var first = multiInstance.iterationsOf(301L, WEIGHING, "Weigh", retry());
        hasJobs(WEIGHING, job(302L, "2026-10-17T14:30:26.123Z", "active", "\"parcel\": \"b\""), job(301L,
                "2026-10-17T14:30:25.123Z", "completed", "\"parcel\": \"a\""));
        final var second = multiInstance.iterationsOf(302L, WEIGHING, "Weigh", retry());

        assertAll(
                () -> assertEquals(Map.of("Weigh", new MultiInstanceValue("a", 0, 3)), first),
                () -> assertEquals(Map.of("Weigh", new MultiInstanceValue("b", 1, 3)), second),
                () -> assertEquals(2, requestsFor("/v1/jobs")),
                // the total of the body is kept with it
                () -> assertEquals(1, requestsFor("/v1/process-instances/%d/history".formatted(ROOT))));
    }

    @Test
    @DisplayName("A task inside two multi-instance elements is told both iterations, the outermost first")
    void nestedIterationsAreToldOutermostFirst() {
        holdsTheParcelsBody();
        // the item of the iteration and the collection the modeller mapped into it, which the inner
        // body copies
        final var mappedIn = "\"parcel\": \"b\", \"boxes\": [\"x\", \"y\"]";
        holds(202L, "subprocess", PARCELS, ORDER, "shop__Order", mappedIn);
        entered(202L, "Scan", 250L, "box", "[\"x\", \"y\"]");
        holds(SCANNING, "multiInstance", 202L, ORDER, "shop__Order", mappedIn);
        hasJobs(SCANNING, job(602L, "2026-10-17T14:30:27.123Z", "active", "\"box\": \"y\""), job(601L,
                "2026-10-17T14:30:26.123Z", "active", "\"box\": \"x\""));

        final var iterations = multiInstance.iterationsOf(602L, SCANNING, "Scan", retry());

        assertAll(
                () -> assertEquals(List.of("Parcels", "Scan"), List.copyOf(iterations.keySet())),
                () -> assertEquals(new MultiInstanceValue("b", 1, 3), iterations.get("Parcels")),
                () -> assertEquals(new MultiInstanceValue("y", 1, 2), iterations.get("Scan")));
    }

    @Test
    @DisplayName("The total of a collection written as an expression is the size of what the engine evaluated when the element was entered")
    void aCollectionWrittenAsAnExpressionIsCountedAsTheEngineEvaluatedIt() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Stamp", 450L, "parcel", "[\"A\", \"B\", \"C\"]");
        holds(STAMPING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(STAMPING, job(401L, "2026-10-17T14:30:25.123Z", "active", "\"parcel\": \"A\""));

        assertEquals(Map.of("Stamp", new MultiInstanceValue("A", 0, 3)), multiInstance.iterationsOf(401L, STAMPING,
                "Stamp", retry()));
    }

    @Test
    @DisplayName("The total is the size of the collection the engine iterates over, not of the body's copy of the variables, which a completion or a push of the aggregate's values writes")
    void theTotalIsNotReadFromTheBodysCopy() {
        holdsTheModels();
        final var changedSinceTheElementWasEntered = "\"orderId\": \"order-1\", \"parcels\": [\"a\"]";
        holds(ROOT, "default", null, ORDER, "shop__Order", changedSinceTheElementWasEntered);
        entered(ROOT, "Parcels", 150L, "parcel", PARCELS_EVALUATED);
        holds(PARCELS, "multiInstance", ROOT, ORDER, "shop__Order", changedSinceTheElementWasEntered);
        holds(202L, "subprocess", PARCELS, ORDER, "shop__Order", "\"parcel\": \"b\"");
        hasChildren(PARCELS, child(203L, "2026-10-17T14:30:25.127Z", "subprocess", PARCELS, "\"parcel\": \"c\""),
                child(202L, "2026-10-17T14:30:25.125Z", "subprocess", PARCELS, "\"parcel\": \"b\""), child(201L,
                        "2026-10-17T14:30:25.123Z", "subprocess", PARCELS, "\"parcel\": \"a\""));

        assertEquals(Map.of("Parcels", new MultiInstanceValue("b", 1, 3)), multiInstance.iterationsOf(42L, 202L,
                "Pick", retry()));
    }

    @Test
    @DisplayName("The total of an activation whose collection in the history is no list is -1")
    void aCollectionWhichIsNoListHasNoTotal() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Weigh", 350L, "parcel", "\"a, b, c\"");
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "active", "\"parcel\": \"a\""));

        assertEquals(Map.of("Weigh", new MultiInstanceValue("a", 0, ZenBpmMultiInstance.UNKNOWN_TOTAL)),
                multiInstance.iterationsOf(301L, WEIGHING, "Weigh", retry()));
    }

    @Test
    @DisplayName("Where the instance the element stands in shows no token at the element, as on a node which lags behind, it is asked again, and then the total is -1")
    void anActivationWithoutATokenHasNoTotal() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "active", "\"parcel\": \"a\""));

        final var iterations = multiInstance.iterationsOf(301L, WEIGHING, "Weigh", retry());

        assertAll(
                () -> assertEquals(Map.of("Weigh", new MultiInstanceValue("a", 0, ZenBpmMultiInstance.UNKNOWN_TOTAL)),
                        iterations),
                // once by the walk up, then more than once for the token
                () -> assertTrue(requestsFor("/v1/process-instances/%d".formatted(ROOT)) > 2),
                () -> assertEquals(0, requestsFor("/v1/process-instances/%d/history".formatted(ROOT))));
    }

    @Test
    @DisplayName("Where the history does not list the entry of the token, as where it moved between two pages, it is read again, and then the total is -1")
    void anEntryTheHistoryDoesNotListHasNoTotal() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        // the history holds the start event, but not the entry of the token at the element
        entered(ROOT, "Start", 349L, "parcel", "[]");
        waitsAt(ROOT, "Weigh", 350L);
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "active", "\"parcel\": \"a\""));

        final var iterations = multiInstance.iterationsOf(301L, WEIGHING, "Weigh", retry());

        assertAll(
                () -> assertEquals(Map.of("Weigh", new MultiInstanceValue("a", 0, ZenBpmMultiInstance.UNKNOWN_TOTAL)),
                        iterations),
                () -> assertTrue(requestsFor("/v1/process-instances/%d/history".formatted(ROOT)) > 1));
    }

    @Test
    @DisplayName("The entry of the token is found on a later page of the history")
    void theEntryIsFoundOnALaterPage() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        for (var visit = 0; visit < 100; ++visit) {
            entered(ROOT, "Flow_%d".formatted(visit), 1000L + visit, "parcel", "[]");
        }
        entered(ROOT, "Weigh", 350L, "parcel", PARCELS_EVALUATED);
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "active", "\"parcel\": \"a\""));

        final var iterations = multiInstance.iterationsOf(301L, WEIGHING, "Weigh", retry());

        assertAll(
                () -> assertEquals(Map.of("Weigh", new MultiInstanceValue("a", 0, 3)), iterations),
                () -> assertEquals(2, requestsFor("/v1/process-instances/%d/history".formatted(ROOT))));
    }

    @Test
    @DisplayName("Two activations of the element at once which iterate over collections of one size tell that size")
    void twoActivationsOfOneSizeTellIt() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Weigh", 350L, "parcel", PARCELS_EVALUATED);
        entered(ROOT, "Weigh", 351L, "parcel", "[\"d\", \"e\", \"f\"]");
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "active", "\"parcel\": \"a\""));

        assertEquals(Map.of("Weigh", new MultiInstanceValue("a", 0, 3)), multiInstance.iterationsOf(301L, WEIGHING,
                "Weigh", retry()));
    }

    @Test
    @DisplayName("Two activations of the element at once, of which the history lists one, cannot be told apart, so the total is -1")
    void twoActivationsOfWhichTheHistoryListsOneHaveNoTotal() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Weigh", 350L, "parcel", PARCELS_EVALUATED);
        waitsAt(ROOT, "Weigh", 351L);
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "active", "\"parcel\": \"a\""));

        assertEquals(Map.of("Weigh", new MultiInstanceValue("a", 0, ZenBpmMultiInstance.UNKNOWN_TOTAL)),
                multiInstance.iterationsOf(301L, WEIGHING, "Weigh", retry()));
    }

    @Test
    @DisplayName("Two activations of the element at once which iterate over collections of different sizes cannot be told apart, so the total is -1")
    void twoActivationsOfDifferentSizesHaveNoTotal() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Weigh", 350L, "parcel", PARCELS_EVALUATED);
        entered(ROOT, "Weigh", 351L, "parcel", "[\"d\"]");
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "active", "\"parcel\": \"a\""));

        assertEquals(Map.of("Weigh", new MultiInstanceValue("a", 0, ZenBpmMultiInstance.UNKNOWN_TOTAL)),
                multiInstance.iterationsOf(301L, WEIGHING, "Weigh", retry()));
    }

    @Test
    @DisplayName("A task of a process an iteration of a multi-instance call activity started is told the caller's iteration first, then its own")
    void aTaskOfACalledProcessIsToldTheIterationOfItsCaller() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Label", 160L, "parcel", PARCELS_EVALUATED);
        holds(LABELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        final var calledVariables = "\"parcel\": \"b\", \"copies\": [1, 2]";
        holds(502L, "callActivity", LABELS, LABELLING, "shop__Labelling", calledVariables);
        entered(502L, "Print", 560L, "copy", "[1, 2]");
        hasChildren(LABELS, child(503L, "2026-10-17T14:30:25.127Z", "callActivity", LABELS, "\"parcel\": \"c\""),
                child(502L, "2026-10-17T14:30:25.125Z", "callActivity", LABELS, calledVariables), child(501L,
                        "2026-10-17T14:30:25.123Z", "callActivity", LABELS, "\"parcel\": \"a\""));
        holds(PRINTING, "multiInstance", 502L, LABELLING, "shop__Labelling", calledVariables);
        hasJobs(PRINTING, job(701L, "2026-10-17T14:30:26.123Z", "active", "\"copy\": 1"));

        final var iterations = multiInstance.iterationsOf(701L, PRINTING, "Print", retry());

        assertAll(
                () -> assertEquals(List.of("Label", "Print"), List.copyOf(iterations.keySet())),
                () -> assertEquals(new MultiInstanceValue("b", 1, 3), iterations.get("Label")),
                () -> assertEquals(new MultiInstanceValue(1L, 0, 2), iterations.get("Print")));
    }

    @Test
    @DisplayName("A caller whose model calls the process from two multi-instance call activities is told the iteration of the one its body waits at")
    void aCallerWithTwoCallsOfTheProcessIsToldTheOneItsBodyWaitsAt() {
        holdsTheModel(ORDER, modelCalling(List.of("Label", "Relabel"), List.of()));
        holdsTheModel(LABELLING, modelOfLabelling());
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Relabel", 160L, "parcel", PARCELS_EVALUATED);
        holds(LABELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        waitsAt(LABELS, "Relabel", 161L);
        final var calledVariables = "\"parcel\": \"b\", \"copies\": [1, 2]";
        holds(502L, "callActivity", LABELS, LABELLING, "shop__Labelling", calledVariables);
        hasChildren(LABELS, child(502L, "2026-10-17T14:30:25.125Z", "callActivity", LABELS, calledVariables), child(
                501L, "2026-10-17T14:30:25.123Z", "callActivity", LABELS, "\"parcel\": \"a\""));
        entered(502L, "Print", 560L, "copy", "[1, 2]");
        holds(PRINTING, "multiInstance", 502L, LABELLING, "shop__Labelling", calledVariables);
        hasJobs(PRINTING, job(701L, "2026-10-17T14:30:26.123Z", "active", "\"copy\": 1"));

        final var iterations = multiInstance.iterationsOf(701L, PRINTING, "Print", retry());

        assertAll(
                () -> assertEquals(List.of("Relabel", "Print"), List.copyOf(iterations.keySet())),
                () -> assertEquals(new MultiInstanceValue("b", 1, 3), iterations.get("Relabel")));
    }

    @Test
    @DisplayName("A caller whose model calls the process from two call activities, of which its instance waits at none, is not told, and a WARN says once how to have it told; the called process's own iterations are told")
    void aCallerWithTwoCallsOfTheProcessWhichWaitsAtNoneIsNotTold(final CapturedOutput output) {
        holdsTheModel(ORDER, modelCalling(List.of("Label", "Relabel"), List.of()));
        holdsTheModel(LABELLING, modelOfLabelling());
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(LABELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        final var calledVariables = "\"parcel\": \"b\", \"copies\": [1, 2]";
        holds(502L, "callActivity", LABELS, LABELLING, "shop__Labelling", calledVariables);
        entered(502L, "Print", 560L, "copy", "[1, 2]");
        holds(PRINTING, "multiInstance", 502L, LABELLING, "shop__Labelling", calledVariables);
        hasJobs(PRINTING, job(701L, "2026-10-17T14:30:26.123Z", "active", "\"copy\": 1"));

        final var first = multiInstance.iterationsOf(701L, PRINTING, "Print", retry());
        final var second = multiInstance.iterationsOf(701L, PRINTING, "Print", retry());

        final var warning = """
                ZenBPM[orders]: an instance of process 'shop__Labelling' was started by version 3 of process \
                'shop__Order', whose model holds no single call activity which started it, so the methods serving \
                tasks of 'shop__Labelling' are told none of the caller's multi-instance iterations; call \
                'shop__Labelling' from one call activity of 'shop__Order' only, or hand the item of the caller's \
                iteration over as a @TaskParam""";
        final var log = output.getOutOfThisTest();
        assertAll(
                () -> assertEquals(Map.of("Print", new MultiInstanceValue(1L, 0, 2)), first),
                () -> assertEquals(first, second),
                () -> assertEquals(0, requestsFor("/v1/process-instances/%d/child-processes".formatted(LABELS))),
                () -> assertTrue(log.contains(warning), log),
                () -> assertFalse(log
                        .substring(log.indexOf(warning) + warning.length())
                        .contains(warning), log));
    }

    @Test
    @DisplayName("A caller whose model calls the process from a call activity which iterates and from one which does not is told the iteration of the one which started the instance")
    void aCallerWhichAlsoCallsOnceIsToldTheIteratingCall() {
        holdsTheModel(ORDER, modelCalling(List.of("Label"), List.of("LabelOnce")));
        holdsTheModel(LABELLING, modelOfLabelling());
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        holds(LABELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        final var calledVariables = "\"parcel\": \"a\", \"copies\": [1]";
        entered(ROOT, "Label", 160L, "parcel", PARCELS_EVALUATED);
        holds(501L, "callActivity", LABELS, LABELLING, "shop__Labelling", calledVariables);
        entered(501L, "Print", 560L, "copy", "[1]");
        hasChildren(LABELS, child(501L, "2026-10-17T14:30:25.123Z", "callActivity", LABELS, calledVariables));
        holds(PRINTING, "multiInstance", 501L, LABELLING, "shop__Labelling", calledVariables);
        hasJobs(PRINTING, job(701L, "2026-10-17T14:30:26.123Z", "active", "\"copy\": 1"));

        assertEquals(new MultiInstanceValue("a", 0, 3), multiInstance
                .iterationsOf(701L, PRINTING, "Print", retry())
                .get("Label"));
    }

    @Test
    @DisplayName("A task of a process which a call activity started inside an iteration of the caller's multi-instance sub-process is told that iteration first, then its own")
    void aCallInsideAnIterationOfTheCallerIsToldThatIteration() {
        holdsTheParcelsBody();
        final var calledVariables = "\"parcel\": \"b\", \"copies\": [1, 2]";
        holds(520L, "callActivity", 202L, LABELLING, "shop__Labelling", calledVariables);
        entered(520L, "Print", 560L, "copy", "[1, 2]");
        holds(PRINTING, "multiInstance", 520L, LABELLING, "shop__Labelling", calledVariables);
        hasJobs(PRINTING, job(701L, "2026-10-17T14:30:26.123Z", "active", "\"copy\": 1"));

        final var iterations = multiInstance.iterationsOf(701L, PRINTING, "Print", retry());

        assertAll(
                () -> assertEquals(List.of("Parcels", "Print"), List.copyOf(iterations.keySet())),
                () -> assertEquals(new MultiInstanceValue("b", 1, 3), iterations.get("Parcels")),
                () -> assertEquals(new MultiInstanceValue(1L, 0, 2), iterations.get("Print")));
    }

    @Test
    @DisplayName("A called process which no call activity iterates over is told its own iterations, and its caller is walked up without a read of its iterations")
    void aCallerWhichDoesNotIterateAddsNoIteration() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        final var calledVariables = "\"copies\": [1, 2]";
        holds(510L, "callActivity", ROOT, LABELLING, "shop__Labelling", calledVariables);
        entered(510L, "Print", 560L, "copy", "[1, 2]");
        holds(PRINTING, "multiInstance", 510L, LABELLING, "shop__Labelling", calledVariables);
        hasJobs(PRINTING, job(701L, "2026-10-17T14:30:26.123Z", "active", "\"copy\": 1"));

        assertEquals(Map.of("Print", new MultiInstanceValue(1L, 0, 2)), multiInstance.iterationsOf(701L, PRINTING,
                "Print", retry()));
    }

    @Test
    @DisplayName("A task outside every multi-instance element is told no iteration, and neither the model nor a body is read for it")
    void aTaskOutsideEveryIterationIsToldNone() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);

        final var iterations = multiInstance.iterationsOf(42L, ROOT, "Ship", retry());

        assertAll(
                () -> assertEquals(Map.of(), iterations),
                () -> assertEquals(0, requestsFor("/v1/process-definitions/%d".formatted(ORDER))));
    }

    @Test
    @DisplayName("A body which the model does not show around the task ends the walk, and nothing above it is told")
    void aBodyTheModelDoesNotShowEndsTheWalk() {
        holdsTheParcelsBody();

        assertEquals(Map.of(), multiInstance.iterationsOf(42L, 202L, "NotInTheModel", retry()));
    }

    @Test
    @DisplayName("A version whose model cannot be read shows no multi-instance element, so its task is told no iteration")
    void aModelWhichCannotBeReadShowsNoIteration() {
        holdsTheParcelsBody();
        holdsTheModel(ORDER, "no model at all");

        assertEquals(Map.of(), multiInstance.iterationsOf(42L, 202L, "Pick", retry()));
    }

    @Test
    @DisplayName("An element which names no input element hands no item, so the element of its iterations is null, and the engine keeps its collection under the empty name")
    void anElementWithoutAnInputElementHasNoItem() {
        holdsTheModel(ORDER, modelWithoutAnItem());
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Weigh", 350L, "", PARCELS_EVALUATED);
        holds(WEIGHING, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        hasJobs(WEIGHING, job(301L, "2026-10-17T14:30:25.123Z", "active", ""));

        assertEquals(Map.of("Weigh", new MultiInstanceValue(null, 0, 3)), multiInstance.iterationsOf(301L, WEIGHING,
                "Weigh", retry()));
    }

    @Test
    @DisplayName("An iteration which the engine node asked does not list yet, as one which lags behind, is asked for again and then is a failure which names it")
    void anIterationTheEngineDoesNotListIsAFailure() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Parcels", 150L, "parcel", PARCELS_EVALUATED);
        holds(PARCELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        holds(202L, "subprocess", PARCELS, ORDER, "shop__Order", "\"parcel\": \"b\"");
        hasChildren(PARCELS, child(201L, "2026-10-17T14:30:25.123Z", "subprocess", PARCELS, "\"parcel\": \"a\""));

        final var failure = assertThrows(IllegalStateException.class, () -> multiInstance.iterationsOf(42L, 202L,
                "Pick", retry()));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .contains("iteration 202 of multi-instance element 'Parcels'"), failure::getMessage),
                () -> assertTrue(requestsFor("/v1/process-instances/%d/child-processes".formatted(PARCELS)) > 1));
    }

    @Test
    @DisplayName("An instance on the way up which the engine node asked does not know is a failure which names it")
    void anInstanceTheEngineDoesNotKnowIsAFailure() {
        holdsTheModels();
        holds(202L, "subprocess", PARCELS, ORDER, "shop__Order", "\"parcel\": \"b\"");
        engine.answers("GET", "/v1/process-instances/%d".formatted(PARCELS), 404, """
                {"code":"NOT_FOUND","message":"process instance not found"}""");

        final var failure = assertThrows(IllegalStateException.class, () -> multiInstance.iterationsOf(42L, 202L,
                "Pick", retry()));

        assertTrue(failure
                .getMessage()
                .contains("instance %d".formatted(PARCELS)), failure::getMessage);
    }

    @Test
    @DisplayName("Where no body is to be kept, every task reads the iterations of its body anew")
    void aCacheOfNoEntriesReadsTheBodyEveryTime() {
        holdsTheParcelsBody();
        final var keepingNothing = new ZenBpmMultiInstance("orders", "shop", rest, new ZenBpmInstanceCache(rest, 100), 0);

        keepingNothing.iterationsOf(42L, 202L, "Pick", retry());
        keepingNothing.iterationsOf(42L, 202L, "Pick", retry());

        assertEquals(2, requestsFor("/v1/process-instances/%d/child-processes".formatted(PARCELS)));
    }

    private void holdsTheParcelsBody() {
        holdsTheModels();
        holds(ROOT, "default", null, ORDER, "shop__Order", COLLECTION);
        entered(ROOT, "Parcels", 150L, "parcel", PARCELS_EVALUATED);
        holds(PARCELS, "multiInstance", ROOT, ORDER, "shop__Order", COLLECTION);
        holds(202L, "subprocess", PARCELS, ORDER, "shop__Order", "\"parcel\": \"b\"");
        hasChildren(PARCELS, child(203L, "2026-10-17T14:30:25.127Z", "subprocess", PARCELS, "\"parcel\": \"c\""),
                child(202L, "2026-10-17T14:30:25.125Z", "subprocess", PARCELS, "\"parcel\": \"b\""), child(201L,
                        "2026-10-17T14:30:25.123Z", "subprocess", PARCELS, "\"parcel\": \"a\""));
    }

    private void holdsTheModels() {
        holdsTheModel(ORDER, modelCalling(List.of("Label"), List.of()));
        holdsTheModel(LABELLING, modelOfLabelling());
    }

    private void holds(
        final long key,
        final String processType,
        final Long parentKey,
        final long definitionKey,
        final String scopedProcessId,
        final String variables
    ) {
        engine.answers("GET", "/v1/process-instances/%d".formatted(key), exchange -> FakeEngine.respond(exchange, 200,
                """
                        {"key": %d, "processDefinitionKey": %d, "bpmnProcessId": "%s", "version": 3, \
                        "businessKey": "order-1", "createdAt": "2026-10-17T14:30:25.123Z", "state": "active", %s \
                        "processType": "%s", "variables": {%s}, "activeElementInstances": [%s]}"""
                        .formatted(key, definitionKey, scopedProcessId, parentKey == null ? ""
                                : "\"parentProcessInstanceKey\": %d,".formatted(parentKey), processType, variables,
                                String.join(",", tokens.getOrDefault(key, List.of())))));
    }

    // the element was entered under the entry key: its token waits there, and the history keeps
    // the collection under the name of the input element
    private void entered(
        final long instanceKey,
        final String elementId,
        final long entryKey,
        final String inputElement,
        final String collection
    ) {
        waitsAt(instanceKey, elementId, entryKey);
        histories
                .computeIfAbsent(instanceKey, key -> new ArrayList<>())
                .add("""
                        {"key": %d, "processInstanceKey": %d, "createdAt": "2026-10-17T14:30:25.120Z", \
                        "elementId": "%s", "elementType": "SUB_PROCESS", "inputVariables": {"%s": %s}}"""
                        .formatted(entryKey, instanceKey, elementId, inputElement, collection));
        engine.answers("GET", "/v1/process-instances/%d/history".formatted(instanceKey), exchange -> {
            final var page = Integer.parseInt(exchange
                    .getRequestURI()
                    .getRawQuery()
                    .replaceAll(".*page=(\\d+).*", "$1"));
            final var entries = histories.get(instanceKey);
            final var onThePage = entries.subList(Math.min((page - 1) * 100, entries.size()), Math.min(page * 100,
                    entries.size()));
            FakeEngine.respond(exchange, 200, """
                    {"items": [%s], "page": %d, "size": 100, "count": %d, "totalCount": %d}"""
                    .formatted(String.join(",", onThePage), page, onThePage.size(), entries.size()));
        });
    }

    private void waitsAt(
        final long instanceKey,
        final String elementId,
        final long tokenKey
    ) {
        tokens
                .computeIfAbsent(instanceKey, key -> new ArrayList<>())
                .add("""
                        {"elementInstanceKey": %d, "createdAt": "2026-10-17T14:30:25.120Z", \
                        "state": "TokenStateWaiting", "elementId": "%s"}""".formatted(tokenKey, elementId));
    }

    private void hasChildren(
        final long key,
        final String... children
    ) {
        engine.answers("GET", "/v1/process-instances/%d/child-processes".formatted(key), 200, """
                {"partitions": [{"partition": 1, "totalCount": %d, "items": [%s]}], "totalCount": %d}"""
                .formatted(children.length, String.join(",", children), children.length));
    }

    private void hasJobs(
        final long instanceKey,
        final String... jobsOfTheInstance
    ) {
        jobs.put(instanceKey, List.of(jobsOfTheInstance));
        engine.answers("GET", "/v1/jobs", exchange -> {
            final var query = exchange
                    .getRequestURI()
                    .getRawQuery();
            final var listed = jobs
                    .entrySet()
                    .stream()
                    .filter(entry -> query.contains("processInstanceKey=%d".formatted(entry.getKey())))
                    .flatMap(entry -> entry
                            .getValue()
                            .stream()
                            .map(job -> job.formatted(entry.getKey())))
                    .toList();
            FakeEngine.respond(exchange, 200, """
                    {"partitions": [{"partition": 1, "totalCount": %d, "items": [%s]}], "totalCount": %d}"""
                    .formatted(listed.size(), String.join(",", listed), listed.size()));
        });
    }

    private void holdsTheModel(
        final long definitionKey,
        final String bpmn
    ) {
        try {
            engine.answers("GET", "/v1/process-definitions/%d".formatted(definitionKey), 200, """
                    {"key": %d, "version": 3, "bpmnProcessId": "shop__Order", "bpmnData": %s}"""
                    .formatted(definitionKey, new ObjectMapper().writeValueAsString(bpmn)));
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private long requestsFor(final String path) {
        return engine
                .requests()
                .stream()
                .filter(request -> request
                        .path()
                        .equals(path))
                .count();
    }

    private static ZenBpmCommandRetry retry() {
        return new ZenBpmCommandRetry("orders", 42L, "ship", () -> Instant
                .now()
                .plus(Duration.ofMinutes(1)), () -> false);
    }

    private static String child(
        final long key,
        final String createdAt,
        final String processType,
        final long parentKey,
        final String variables
    ) {
        return """
                {"key": %d, "processDefinitionKey": %d, "bpmnProcessId": "shop__Order", "version": 3, \
                "businessKey": "order-1", "createdAt": "%s", "state": "active", "parentProcessInstanceKey": %d, \
                "processType": "%s", "variables": {%s}}""".formatted(key, ORDER, createdAt, parentKey, processType,
                variables);
    }

    // %d is left for the key of the instance the job waits in
    private static String job(
        final long key,
        final String createdAt,
        final String state,
        final String inputVariables
    ) {
        return """
                {"key": %d, "elementId": "MultiInstanceTask", "type": "shop__Order__task", "elementType": "SERVICE_TASK", \
                "processInstanceKey": %%d, "elementInstanceKey": %d, "state": "%s", "createdAt": "%s", \
                "inputVariables": {%s}}"""
                .formatted(key, key + 1000, state, createdAt, inputVariables);
    }

    // the call activities which iterate over the parcels, and those which call once
    private static String modelCalling(
        final List<String> iteratingCallActivityIds,
        final List<String> onceCallActivityIds
    ) {
        final var calls = new ArrayList<String>();
        for (final var callActivityId : onceCallActivityIds) {
            calls.add(
                    """
                            <bpmn:callActivity id="%s">
                              <bpmn:extensionElements><zenbpm:calledElement processId="shop__Labelling" /></bpmn:extensionElements>
                            </bpmn:callActivity>"""
                            .formatted(callActivityId));
        }
        for (final var callActivityId : iteratingCallActivityIds) {
            calls.add(
                    """
                            <bpmn:callActivity id="%s">
                              <bpmn:extensionElements><zenbpm:calledElement processId="shop__Labelling" /></bpmn:extensionElements>
                              <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                                <zenbpm:loopCharacteristics inputCollection="=parcels" inputElement="parcel" />
                              </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                            </bpmn:callActivity>"""
                            .formatted(callActivityId));
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="Definitions_Order" \
                targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="shop__Order" isExecutable="true">
                    <bpmn:serviceTask id="Ship" />
                    <bpmn:subProcess id="Parcels">
                      <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="= parcels " inputElement="parcel" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                      <bpmn:serviceTask id="Pick" />
                      <bpmn:callActivity id="LabelInside">
                        <bpmn:extensionElements><zenbpm:calledElement processId="shop__Labelling" /></bpmn:extensionElements>
                      </bpmn:callActivity>
                      <bpmn:serviceTask id="Scan">
                        <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                          <zenbpm:loopCharacteristics inputCollection="=boxes" inputElement="box" />
                        </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                      </bpmn:serviceTask>
                    </bpmn:subProcess>
                    <bpmn:serviceTask id="Weigh">
                      <bpmn:multiInstanceLoopCharacteristics isSequential="true"><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="=parcels" inputElement="parcel" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:serviceTask>
                    <bpmn:serviceTask id="Stamp">
                      <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="=for p in parcels return upper case(p)" \
                inputElement="parcel" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:serviceTask>
                    %s
                  </bpmn:process>
                </bpmn:definitions>
                """
                .formatted(String.join("\n", calls));
    }

    private static String modelOfLabelling() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="Definitions_Labelling" \
                targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="shop__Labelling" isExecutable="true">
                    <bpmn:serviceTask id="Print">
                      <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="=copies" inputElement="copy" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:serviceTask>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String modelWithoutAnItem() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="Definitions_Order" \
                targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="shop__Order" isExecutable="true">
                    <bpmn:serviceTask id="Weigh">
                      <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="=parcels" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:serviceTask>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static ZenBpmRestClient restClient(final FakeEngine engine) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        return new ZenBpmRestClient("orders", configuration);
    }

}
