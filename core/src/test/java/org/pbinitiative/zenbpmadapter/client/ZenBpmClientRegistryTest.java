/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the platform finds the factory of an adapter id, and which adapter ids share an engine.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmClientRegistryTest {

    @Test
    @DisplayName("The factory of a configured adapter id is found, and the factories keep the order they were given in")
    void theFactoryOfAnAdapterIdIsFound() {
        final var orders = unconfigured("orders");
        final var billing = unconfigured("billing");

        try (var registry = new ZenBpmClientRegistry(List.of(orders, billing))) {
            assertAll(
                    () -> assertSame(orders, registry.getFactory("orders")),
                    () -> assertSame(billing, registry.getFactory("billing")),
                    () -> assertEquals(List.of(orders, billing), registry.factories()));
        }
    }

    @Test
    @DisplayName("An unknown adapter id fails with the ids which are configured and where they come from")
    void anUnknownAdapterIdFailsWithGuidance() {
        try (var registry = new ZenBpmClientRegistry(List.of(unconfigured("orders"), unconfigured("billing")))) {
            final var failure = assertThrows(IllegalStateException.class, () -> registry.getFactory("shipping"));

            assertEquals(
                    "No ZenBPM adapter with id 'shipping' is configured; the configured ids of type 'zenbpm' are 'orders', 'billing'. The ids come from 'vanillabp.adapters.<id>.type', so an unknown one is a defect of the wiring rather than of the configuration.",
                    failure.getMessage());
        }
    }

    @Test
    @DisplayName("An adapter id asked of a registry without any says that none is configured")
    void anEmptyRegistrySaysSo() {
        try (var registry = new ZenBpmClientRegistry(List.of())) {
            final var failure = assertThrows(IllegalStateException.class, () -> registry.getFactory("orders"));

            assertEquals(
                    "No ZenBPM adapter with id 'orders' is configured; no id is configured with type 'zenbpm'. The ids come from 'vanillabp.adapters.<id>.type', so an unknown one is a defect of the wiring rather than of the configuration.",
                    failure.getMessage());
        }
    }

    @Test
    @DisplayName("Two adapter ids share an engine where their addresses name the same one, however they are spelled")
    void adapterIdsShareAnEngineByItsIdentity() {
        final var a = configured("a", "http://ZenBPM:8080");
        final var b = configured("b", "http://zenbpm:8080/v1/");
        final var c = configured("c", "http://other:8080");
        final var d = unconfigured("d");

        try (var registry = new ZenBpmClientRegistry(List.of(a, b, c, d))) {
            assertAll(
                    () -> assertEquals(List.of("b"), registry.sharesEngineWith("a")),
                    () -> assertEquals(List.of("a"), registry.sharesEngineWith("b")),
                    () -> assertEquals(List.of(), registry.sharesEngineWith("c")),
                    () -> assertEquals(List.of(), registry.sharesEngineWith("d")),
                    () -> assertThrows(IllegalStateException.class, () -> registry.sharesEngineWith("e")));
        }
    }

    @Test
    @DisplayName("One adapter id cannot have two factories")
    void oneAdapterIdHasOneFactory() {
        final var first = unconfigured("orders");
        final var second = unconfigured("orders");
        final var factories = List.of(first, second);

        final var failure = assertThrows(IllegalArgumentException.class, () -> new ZenBpmClientRegistry(factories));

        assertEquals("ZenBPM adapter id 'orders' was given two factories; the platform builds one per adapter id.",
                failure.getMessage());
    }

    @Test
    @DisplayName("Closing the registry closes every factory, also where one of them fails")
    void closingClosesEveryFactory(final CapturedOutput output) {
        final var closed = new CopyOnWriteArrayList<String>();
        final var orders = closing("orders", closed, false);
        final var billing = closing("billing", closed, true);
        final var shipping = closing("shipping", closed, false);

        new ZenBpmClientRegistry(List.of(orders, billing, shipping)).close();

        assertAll(
                () -> assertEquals(List.of("orders", "billing", "shipping"), closed),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "ZenBPM[billing]: closing the clients failed; the other adapter ids are closed anyway"),
                        output.getAllOfThisTest()));
    }

    private static ZenBpmClientFactory unconfigured(final String adapterId) {
        return ZenBpmClientFactory.unconfigured(adapterId, new ZenBpmAdapterConfiguration(), "not configured");
    }

    private static ZenBpmClientFactory configured(
        final String adapterId,
        final String restAddress
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        return new ZenBpmClientFactory(adapterId, configuration, () -> "shop");
    }

    private static ZenBpmClientFactory closing(
        final String adapterId,
        final List<String> closed,
        final boolean failing
    ) {
        return new ZenBpmClientFactory(adapterId, new ZenBpmAdapterConfiguration(), "client", null, null, null) {

            @Override
            public void close() {
                closed.add(adapterId);
                if (failing) {
                    throw new IllegalStateException("the stream would not close");
                }
            }

        };
    }

}
