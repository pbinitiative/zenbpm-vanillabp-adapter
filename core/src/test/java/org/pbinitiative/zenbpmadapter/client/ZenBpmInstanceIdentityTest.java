/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * When two adapter ids of type <code>zenbpm</code> are one instance: the same engine, however its
 * address is spelled, and the same name-clash-avoidance mode, so both reach the engine with the
 * same identifiers. One engine with two modes is the migration from one mode to the other, and it
 * boots.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmInstanceIdentityTest {

    private static final String SAME_ENGINE = """
            ZenBPM adapter ids 'new' and 'old' address the same engine, 'http://zenbpm:8080/v1', with \
            the same name-clash-avoidance 'use-prefix', so they reach it with the same identifiers: they \
            would deploy the same processes and work the same jobs, and the BPMS election would ask one \
            engine twice. Tell them apart:
              - for two engines, give each id its own address: 'vanillabp.adapters.new.rest-address', \
            'vanillabp.adapters.old.rest-address';
              - for one engine on purpose, as in a migration from 'none' to 'use-prefix', give each id \
            its own mode: 'vanillabp.adapters.new.name-clash-avoidance', \
            'vanillabp.adapters.old.name-clash-avoidance';
              - or remove all but one of these adapter ids.""";

    @Test
    @DisplayName("Two adapter ids on one engine with the same mode end the start, naming both ids, the engine and the keys which tell them apart")
    void oneEngineWithOneModeIsOneInstance() {
        try (var registry = registryOf(configured("new", "http://zenbpm:8080"), configured("old",
                "http://zenbpm:8080"))) {
            final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmInstanceIdentity
                    .validateDistinct(List.of("new", "old"), registry, modes(Map.of("new",
                            NameClashAvoidance.USE_PREFIX, "old", NameClashAvoidance.USE_PREFIX))));

            assertEquals(SAME_ENGINE, failure.getMessage());
        }
    }

    @Test
    @DisplayName("One engine is one engine however its address is spelled")
    void theAddressIsComparedNormalised() {
        try (var registry = registryOf(configured("new", "http://ZenBPM:8080/"), configured("old",
                "http://zenbpm:8080/v1"))) {
            final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmInstanceIdentity
                    .validateDistinct(List.of("new", "old"), registry, modes(Map.of("new",
                            NameClashAvoidance.USE_PREFIX, "old", NameClashAvoidance.USE_PREFIX))));

            assertEquals(SAME_ENGINE, failure.getMessage());
        }
    }

    @Test
    @DisplayName("One engine with two modes is the migration from one mode to the other, and it boots")
    void oneEngineWithTwoModesIsTwoInstances() {
        try (var registry = registryOf(configured("new", "http://zenbpm:8080"), configured("old",
                "http://zenbpm:8080"))) {
            assertDoesNotThrow(() -> ZenBpmInstanceIdentity.validateDistinct(List.of("new", "old"), registry,
                    modes(Map.of("new", NameClashAvoidance.USE_PREFIX, "old", NameClashAvoidance.NONE))));
        }
    }

    @Test
    @DisplayName("Two engines with the same mode are two instances")
    void twoEnginesAreTwoInstances() {
        try (var registry = registryOf(configured("new", "http://new-engine:8080"), configured("old",
                "http://old-engine:8080"))) {
            assertDoesNotThrow(() -> ZenBpmInstanceIdentity.validateDistinct(List.of("new", "old"), registry,
                    modes(Map.of("new", NameClashAvoidance.USE_PREFIX, "old", NameClashAvoidance.USE_PREFIX))));
        }
    }

    @Test
    @DisplayName("An adapter id which boots degraded is compared by its address: it names an engine, and the collision would end the next start")
    void aDegradedAdapterIdIsComparedByItsAddress() {
        final var degraded = new ZenBpmAdapterConfiguration();
        degraded.setRestAddress("http://zenbpm:8080");
        degraded.setGrpcAddress("zenbpm:no-port");
        try (var registry = registryOf(configured("new", "http://zenbpm:8080"), ZenBpmClientFactory.unconfigured(
                "old", degraded, "ZenBPM adapter 'old' is configured inconsistently"))) {
            final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmInstanceIdentity
                    .validateDistinct(List.of("new", "old"), registry, modes(Map.of("new",
                            NameClashAvoidance.USE_PREFIX, "old", NameClashAvoidance.USE_PREFIX))));

            assertEquals(SAME_ENGINE, failure.getMessage());
        }
    }

    @Test
    @DisplayName("An adapter id without a valid address is not compared: it names no engine, and its start already said so")
    void anAdapterIdWithoutAnAddressIsNotCompared() {
        final var malformed = new ZenBpmAdapterConfiguration();
        malformed.setRestAddress("ftp://zenbpm:8080");
        try (var registry = registryOf(unconfigured("a"), ZenBpmClientFactory.unconfigured("b", malformed,
                "ZenBPM adapter 'b' is configured inconsistently"), configured("c", "http://zenbpm:8080"))) {
            final var asked = new ArrayList<String>();

            assertDoesNotThrow(() -> ZenBpmInstanceIdentity.validateDistinct(List.of("a", "b", "c"), registry,
                    modes(Map.of("c", NameClashAvoidance.NONE), asked)));
            assertEquals(List.of("c"), asked);
        }
    }

    @Test
    @DisplayName("Every group of adapter ids which are one instance is named in one message, in the order of the ids")
    void everyGroupIsNamedInOneMessage() {
        try (var registry = registryOf(configured("a", "http://one:8080"), configured("b", "http://two:8080"),
                configured("c", "http://one:8080"), configured("d", "http://two:8080"), configured("e",
                        "http://one:8080"))) {
            final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmInstanceIdentity
                    .validateDistinct(List.of("a", "b", "c", "d", "e"), registry, modes(Map.of("a",
                            NameClashAvoidance.NONE, "b", NameClashAvoidance.NONE, "c", NameClashAvoidance.NONE,
                            "d", NameClashAvoidance.NONE, "e", NameClashAvoidance.NONE))));
            final var message = failure.getMessage();

            assertAll(
                    () -> assertTrue(message.startsWith(
                            "ZenBPM adapter ids 'a', 'c' and 'e' address the same engine, 'http://one:8080/v1', with the same name-clash-avoidance 'none',"),
                            message),
                    () -> assertTrue(message.contains(
                            "'vanillabp.adapters.a.rest-address', 'vanillabp.adapters.c.rest-address', 'vanillabp.adapters.e.rest-address';"),
                            message),
                    () -> assertTrue(message.contains(
                            "\n\nZenBPM adapter ids 'b' and 'd' address the same engine, 'http://two:8080/v1', with the same name-clash-avoidance 'none',"),
                            message));
        }
    }

    @Test
    @DisplayName("Fewer than two adapter ids have nothing to compare")
    void oneAdapterIdHasNothingToCompare() {
        try (var registry = registryOf(configured("only", "http://zenbpm:8080"))) {
            assertDoesNotThrow(() -> ZenBpmInstanceIdentity.validateDistinct(List.of("only"), registry, modes(Map
                    .of())));
        }
    }

    private static ZenBpmClientRegistry registryOf(final ZenBpmClientFactory... factories) {
        return new ZenBpmClientRegistry(Arrays.asList(factories));
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

    private static NameClashAvoidanceSupport modes(final Map<String, NameClashAvoidance> modes) {
        return modes(modes, new ArrayList<>());
    }

    // the platform's scoping, as far as the comparison asks it: the mode of an adapter id as a whole
    private static NameClashAvoidanceSupport modes(
        final Map<String, NameClashAvoidance> modes,
        final List<String> asked
    ) {
        return (NameClashAvoidanceSupport) Proxy.newProxyInstance(ZenBpmInstanceIdentityTest.class
                .getClassLoader(), new Class<?>[]{
                        NameClashAvoidanceSupport.class
        }, (
            proxy,
            method,
            arguments) -> {
            if (!method
                    .getName()
                    .equals("modeFor") || arguments[0] != null || arguments[1] != null) {
                throw new AssertionError("only the mode of an adapter id as a whole is asked, not %s%s"
                        .formatted(method.getName(), Arrays.toString(arguments)));
            }
            final var adapterId = (String) arguments[2];
            asked.add(adapterId);
            return modes.get(adapterId);
        });
    }

}
