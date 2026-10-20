/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the models the engine of an adapter id holds are kept: read once per definition key, the one
 * read longest ago dropped first beyond the bound, and the one a deployment of this start got back
 * never read at all.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmHeldModelsTest {

    @Test
    @DisplayName("A model is read once and then handed out from what was kept")
    void aModelIsReadOnce() {
        final var reads = new ArrayList<Long>();
        final var held = new ZenBpmHeldModels();

        final var first = held.bpmnOf(9, key -> {
            reads.add(key);
            return "<model of %d/>".formatted(key);
        });
        final var second = held.bpmnOf(9, key -> {
            reads.add(key);
            return "<another model/>";
        });

        assertAll(
                () -> assertEquals("<model of 9/>", first),
                () -> assertSame(first, second),
                () -> assertEquals(List.of(9L), reads),
                () -> assertEquals(1, held.size()));
    }

    @Test
    @DisplayName("Beyond the bound the model read or asked for longest ago is dropped first, and is read again when it is asked for")
    void theModelAskedForLongestAgoIsDroppedFirst() {
        final var reads = new ArrayList<Long>();
        final var held = new ZenBpmHeldModels(10);
        final var read = (java.util.function.LongFunction<String>) key -> {
            reads.add(key);
            return "%04d".formatted(key);
        };

        held.bpmnOf(1, read);
        held.bpmnOf(2, read);
        held.bpmnOf(1, read);
        held.bpmnOf(3, read);
        held.bpmnOf(1, read);
        held.bpmnOf(2, read);

        assertEquals(List.of(1L, 2L, 3L, 2L), reads);
    }

    @Test
    @DisplayName("A model larger than the bound, no model and a read which fails are not kept, and do not drop what was kept")
    void whatCannotBeKeptIsNot() {
        final var reads = new ArrayList<Long>();
        final var held = new ZenBpmHeldModels(10);
        held.bpmnOf(1, key -> {
            reads.add(key);
            return "<kept/>";
        });

        final var large = held.bpmnOf(2, key -> "<far too large/>");
        final var none = held.bpmnOf(3, key -> null);
        final var failure = assertThrows(IllegalStateException.class, () -> held.bpmnOf(4, key -> {
            throw new IllegalStateException("engine down");
        }));
        final var kept = held.bpmnOf(1, key -> {
            reads.add(key);
            return "<read again/>";
        });

        assertAll(
                () -> assertEquals("<far too large/>", large),
                () -> assertNull(none),
                () -> assertEquals("engine down", failure.getMessage()),
                () -> assertEquals("<kept/>", kept),
                () -> assertEquals(List.of(1L), reads),
                () -> assertEquals(1, held.size()));
    }

    @Test
    @DisplayName("The client factory hands out the model a deployment of this start got back without a request, and reads any other one from the engine once")
    void theFactoryReadsAHeldModelOnce() throws JsonProcessingException {
        try (var engine = FakeEngine.started()) {
            engine.answers("GET", "/v1/process-definitions/9", 200,
                    new ObjectMapper().writeValueAsString(Map.of("key", 9,
                            "version", 1, "bpmnProcessId", "shop__Order", "bpmnData", "<version 1/>")));
            final var configuration = new ZenBpmAdapterConfiguration();
            configuration.setRestAddress(engine.address());
            try (var factory = new ZenBpmClientFactory("orders", configuration, () -> "shop")) {
                factory
                        .deployedProcesses()
                        .record(new ZenBpmDeployedProcesses.DeployedProcess("shop", "Order", "shop__Order", 11, 2, null, "<version 2/>"));

                final var deployed = factory.heldBpmnOf(11);
                final var older = factory.heldBpmnOf(9);
                final var olderAgain = factory.heldBpmnOf(9);

                assertAll(
                        () -> assertEquals("<version 2/>", deployed),
                        () -> assertEquals("<version 1/>", older),
                        () -> assertEquals(older, olderAgain),
                        () -> assertEquals(1, engine
                                .requests()
                                .size()),
                        () -> assertEquals(1, factory
                                .heldModels()
                                .size()));
            }
        }
    }

}
