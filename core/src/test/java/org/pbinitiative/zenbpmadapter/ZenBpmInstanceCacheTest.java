/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmApiException;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The cache of instance facts: what it keeps and for how long, what it never keeps, and the walk up
 * to the workflow id. The engine is a fake which answers what each test tells it to and writes down
 * what it was asked, so every request the cache saves is seen.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmInstanceCacheTest {

    private static final long ROOT = 4503599627370501L;

    private static final long BODY = 4503599627370510L;

    private static final long ITERATION = 4503599627370520L;

    private static final long CALLED = 4503599627370530L;

    private final FakeEngine engine = FakeEngine.started();

    private final ZenBpmRestClient rest = restClient(engine);

    @AfterEach
    void stopTheEngine() {
        rest.close();
        engine.close();
    }

    @Test
    @DisplayName("The facts of an instance are read once, without its variables, and the state is not among them")
    void theFactsAreReadOnce() {
        holds(ROOT, "default", null, Map.of("orderId", "order-1", "amount", 1000));
        final var cache = new ZenBpmInstanceCache(rest, 10);

        final var first = cache.facts(ROOT);
        final var second = cache.facts(ROOT);

        assertAll(
                () -> assertEquals(Optional.of(
                        new ZenBpmInstanceCache.InstanceFacts(ROOT, "shop__Order", 3, 4503599627370498L, "order-1", null, ProcessInstanceRef.ProcessType.DEFAULT, Map
                                .of())),
                        first),
                () -> assertEquals(first, second),
                () -> assertEquals(1, requestsFor(ROOT)));
    }

    @Test
    @DisplayName("Of the variables only the aggregate's id is kept, and only once the instance has it")
    void onlyThePresentIdVariableIsKept() {
        holds(ROOT, "default", null, Map.of("amount", 1000));
        final var cache = new ZenBpmInstanceCache(rest, 10);

        final var withoutTheId = cache.facts(ROOT, "orderId");
        holds(ROOT, "default", null, Map.of("orderId", "order-1", "amount", 1000));
        final var withTheId = cache.facts(ROOT, "orderId");
        final var keptWithTheId = cache.facts(ROOT, "orderId");

        assertAll(
                () -> assertEquals(Map.of(), withoutTheId
                        .orElseThrow()
                        .variables()),
                () -> assertEquals(Map.of("orderId", "order-1"), withTheId
                        .orElseThrow()
                        .variables()),
                () -> assertEquals(withTheId, keptWithTheId),
                () -> assertEquals(2, requestsFor(ROOT)),
                () -> assertThrows(UnsupportedOperationException.class, () -> keptWithTheId
                        .orElseThrow()
                        .variables()
                        .clear()));
    }

    @Test
    @DisplayName("An id variable which is null is not kept, so the id written after the start is read")
    void anIdVariableWhichIsNullIsNotKept() {
        final var withoutAValue = new HashMap<String, Object>();
        withoutAValue.put("orderId", null);
        holds(ROOT, "default", null, withoutAValue);
        final var cache = new ZenBpmInstanceCache(rest, 10);

        final var beforeTheId = cache.facts(ROOT, "orderId");
        holds(ROOT, "default", null, Map.of("orderId", "order-1"));
        final var afterTheId = cache.facts(ROOT, "orderId");

        assertAll(
                () -> assertEquals(Map.of(), beforeTheId
                        .orElseThrow()
                        .variables()),
                () -> assertEquals(Map.of("orderId", "order-1"), afterTheId
                        .orElseThrow()
                        .variables()),
                () -> assertEquals(2, requestsFor(ROOT)));
    }

    @Test
    @DisplayName("An instance the engine does not know is empty and asked for again, because a node which lags behind may know it a moment later")
    void anUnknownInstanceIsNotKept() {
        engine.answers("GET", "/v1/process-instances/%d".formatted(ROOT), 404, """
                {"code":"NOT_FOUND","message":"process instance not found"}""");
        final var cache = new ZenBpmInstanceCache(rest, 10);

        assertAll(
                () -> assertEquals(Optional.empty(), cache.facts(ROOT)),
                () -> assertEquals(Optional.empty(), cache.facts(ROOT, "orderId")),
                () -> assertEquals(OptionalLong.empty(), cache.workflowIdOf(ROOT)),
                () -> assertEquals(3, requestsFor(ROOT)));
    }

    @Test
    @DisplayName("A full cache forgets the instance used longest ago first")
    void aFullCacheForgetsTheInstanceUsedLongestAgo() {
        holds(ROOT, "default", null, Map.of());
        holds(BODY, "default", null, Map.of());
        holds(CALLED, "default", null, Map.of());
        final var cache = new ZenBpmInstanceCache(rest, 2);

        cache.facts(ROOT);
        cache.facts(BODY);
        cache.facts(ROOT);
        cache.facts(CALLED);
        cache.facts(ROOT);
        cache.facts(BODY);

        assertAll(
                () -> assertEquals(1, requestsFor(ROOT)),
                () -> assertEquals(2, requestsFor(BODY)),
                () -> assertEquals(1, requestsFor(CALLED)));
    }

    @Test
    @DisplayName("A cache of no entries keeps nothing")
    void aCacheOfNoEntriesKeepsNothing() {
        holds(ROOT, "default", null, Map.of("orderId", "order-1"));
        final var cache = new ZenBpmInstanceCache(rest, 0);

        cache.facts(ROOT);
        cache.facts(ROOT, "orderId");
        cache.workflowIdOf(ROOT);

        assertEquals(3, requestsFor(ROOT));
    }

    @Test
    @DisplayName("The workflow id of an iteration inside a sub-process is the root instance's key, and a walk asks for each instance on the way once")
    void theWorkflowIdIsTheRootKey() {
        holds(ROOT, "default", null, Map.of());
        holds(BODY, "subprocess", ROOT, Map.of());
        holds(ITERATION, "multiInstance", BODY, Map.of());
        final var cache = new ZenBpmInstanceCache(rest, 10);

        final var ofTheIteration = cache.workflowIdOf(ITERATION);
        final var ofTheIterationAgain = cache.workflowIdOf(ITERATION);
        final var ofTheBody = cache.workflowIdOf(BODY);
        final var ofTheRoot = cache.workflowIdOf(ROOT);

        assertAll(
                () -> assertEquals(OptionalLong.of(ROOT), ofTheIteration),
                () -> assertEquals(OptionalLong.of(ROOT), ofTheIterationAgain),
                () -> assertEquals(OptionalLong.of(ROOT), ofTheBody),
                () -> assertEquals(OptionalLong.of(ROOT), ofTheRoot),
                () -> assertEquals(List.of(1L, 1L, 1L), List.of(requestsFor(ITERATION), requestsFor(BODY),
                        requestsFor(ROOT))));
    }

    @Test
    @DisplayName("The walk stops at an instance a call activity started: a task of a called process names the called instance")
    void theWalkStopsAtACallActivity() {
        holds(CALLED, "callActivity", ROOT, Map.of());
        holds(ITERATION, "subprocess", CALLED, Map.of());
        final var cache = new ZenBpmInstanceCache(rest, 10);

        final var workflowId = cache.workflowIdOf(ITERATION);

        assertAll(
                () -> assertEquals(OptionalLong.of(CALLED), workflowId),
                () -> assertEquals(0, requestsFor(ROOT)),
                () -> assertFalse(cache
                        .facts(CALLED)
                        .orElseThrow()
                        .isPartOfItsParent()),
                () -> assertTrue(cache
                        .facts(ITERATION)
                        .orElseThrow()
                        .isPartOfItsParent()));
    }

    @Test
    @DisplayName("A walk which meets an instance the engine does not know answers nothing and keeps no workflow id")
    void aWalkThroughAnUnknownInstanceAnswersNothing() {
        holds(ITERATION, "multiInstance", BODY, Map.of());
        engine.answers("GET", "/v1/process-instances/%d".formatted(BODY), 404, """
                {"code":"NOT_FOUND","message":"process instance not found"}""");
        final var cache = new ZenBpmInstanceCache(rest, 10);

        final var first = cache.workflowIdOf(ITERATION);
        final var second = cache.workflowIdOf(ITERATION);

        assertAll(
                () -> assertEquals(OptionalLong.empty(), first),
                () -> assertEquals(OptionalLong.empty(), second),
                () -> assertEquals(1, requestsFor(ITERATION)),
                () -> assertEquals(2, requestsFor(BODY)));
    }

    @Test
    @DisplayName("A sub-process the engine names no parent for is a root")
    void anInstanceWithoutAParentIsARoot() {
        holds(BODY, "subprocess", null, Map.of());
        final var cache = new ZenBpmInstanceCache(rest, 10);

        assertEquals(OptionalLong.of(BODY), cache.workflowIdOf(BODY));
    }

    @Test
    @DisplayName("An answer the engine could not give is thrown as the client throws it, and nothing is kept")
    void aFailureIsThrownAsTheClientThrowsIt() {
        engine.answers("GET", "/v1/process-instances/%d".formatted(ROOT), 502, """
                {"code":"CLUSTER_ERROR","message":"partition not found"}""");
        final var cache = new ZenBpmInstanceCache(rest, 10);

        final var failure = assertThrows(ZenBpmApiException.class, () -> cache.facts(ROOT));
        assertThrows(ZenBpmApiException.class, () -> cache.workflowIdOf(ROOT));

        assertAll(
                () -> assertEquals(502, failure.getStatus()),
                () -> assertEquals(2, requestsFor(ROOT)));
    }

    // an instance of the process shop__Order, version 3, of the business key order-1
    private void holds(
        final long key,
        final String processType,
        final Long parentKey,
        final Map<String, Object> variables
    ) {
        final var parent = parentKey == null ? "" : "\"parentProcessInstanceKey\": %d,".formatted(parentKey);
        final var json = new StringBuilder();
        variables.forEach((
            name,
            value) -> json
                    .append(json.isEmpty() ? "" : ",")
                    .append(value instanceof String ? "\"%s\": \"%s\"".formatted(name, value)
                            : "\"%s\": %s".formatted(name, value)));
        engine.answers("GET", "/v1/process-instances/%d".formatted(key), 200, """
                {
                  "key": %d,
                  "processDefinitionKey": 4503599627370498,
                  "bpmnProcessId": "shop__Order",
                  "version": 3,
                  "businessKey": "order-1",
                  "createdAt": "2026-10-17T14:30:25.123Z",
                  "state": "active",
                  %s
                  "processType": "%s",
                  "variables": {%s},
                  "activeElementInstances": []
                }
                """.formatted(key, parent, processType, json));
    }

    private long requestsFor(final long key) {
        return engine
                .requests()
                .stream()
                .filter(request -> request
                        .path()
                        .equals("/v1/process-instances/%d".formatted(key)))
                .count();
    }

    private static ZenBpmRestClient restClient(final FakeEngine engine) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        return new ZenBpmRestClient("orders", configuration);
    }

}
