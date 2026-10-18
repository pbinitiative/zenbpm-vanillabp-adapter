/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.MessageStartLookup;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.ValidationResult.Kind;
import org.pbinitiative.zenbpmadapter.quarkus.runtime.VanillaBpZenBpmProperties.ZenBpmAdapterKeys;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the overlay reads the keys of a ZenBPM adapter id through SmallRye Config, as Quarkus maps it:
 * every key of the core configuration at the adapter level, the scoped ones below it with the most
 * specific level winning, and every value below the adapter level handed to the validation. The
 * mapping is built without Quarkus, so these cases need no application to boot;
 * <code>ZenBpmOverlayResolutionTest</code> of the deployment module reads it from a booted one.
 */
@ExtendWith(SuppressOutputExtension.class)
class VanillaBpZenBpmPropertiesTest {

    private static final String ADAPTER = "vanillabp.adapters.orders.";

    private static final String MODULE = "vanillabp.workflow-modules.shop.";

    private static final String WORKFLOW = MODULE
            + "workflows.Order.";

    private static final String TASK = WORKFLOW
            + "tasks.ship.";

    /**
     * Every key of an adapter id, each with a value which is not its default.
     */
    private static final Map<String, String> EVERY_ADAPTER_KEY = Map.ofEntries(
            Map.entry(ADAPTER
                    + "rest-address", "http://engine:8080"),
            Map.entry(ADAPTER
                    + "grpc-address", "engine:9191"),
            Map.entry(ADAPTER
                    + "grpc-plaintext", "false"),
            Map.entry(ADAPTER
                    + "client-id", "shop-1"),
            Map.entry(ADAPTER
                    + "request-timeout", "PT3S"),
            Map.entry(ADAPTER
                    + "startup-wait", "PT1M"),
            Map.entry(ADAPTER
                    + "worker-threads", "virtual"),
            Map.entry(ADAPTER
                    + "worker-threads-bound", "7"),
            Map.entry(ADAPTER
                    + "job-timeout", "PT8M"),
            Map.entry(ADAPTER
                    + "async-task-lock-renewal", "PT20M"),
            Map.entry(ADAPTER
                    + "async-task-max-age-action", "incident"),
            Map.entry(ADAPTER
                    + "retry-backoff", "PT4S"),
            Map.entry(ADAPTER
                    + "shutdown-grace", "PT5S"),
            Map.entry(ADAPTER
                    + "workflow-visibility-timeout", "PT6S"),
            Map.entry(ADAPTER
                    + "user-task-poll-interval", "PT7S"),
            Map.entry(ADAPTER
                    + "history-time-to-live", "P30D"),
            Map.entry(ADAPTER
                    + "instance-cache-max-entries", "99"),
            Map.entry(ADAPTER
                    + "message-start-lookup", "scan"),
            Map.entry(ADAPTER
                    + "message-start-lookup-max-instances", "77"),
            Map.entry(ADAPTER
                    + "accept-unscoped-identifiers", "true"));

    @Test
    @DisplayName("The overlay binds every key the core configuration has, and nothing else")
    void theOverlayKnowsEveryKeyOfTheCoreConfiguration() {
        assertEquals(settersOf(ZenBpmAdapterConfiguration.class), keysOf(ZenBpmAdapterKeys.class)
                .stream()
                .map(Method::getName)
                .map(name -> "set%s%s".formatted(Character.toUpperCase(name.charAt(0)), name.substring(1)))
                .collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("Every key at the adapter level reaches the core configuration as written")
    void everyAdapterKeyReachesTheCoreConfiguration() {
        final var configuration = bind(EVERY_ADAPTER_KEY)
                .configurationOf("orders");

        assertAll(
                () -> assertEquals("http://engine:8080", configuration.getRestAddress()),
                () -> assertEquals("engine:9191", configuration.getGrpcAddress()),
                () -> assertFalse(configuration.isGrpcPlaintext()),
                () -> assertEquals("shop-1", configuration.getClientId()),
                () -> assertEquals(Duration.ofSeconds(3), configuration.getRequestTimeout()),
                () -> assertEquals(Duration.ofMinutes(1), configuration.getStartupWait()),
                () -> assertEquals("virtual", configuration.getWorkerThreads()),
                () -> assertEquals(7, configuration.getWorkerThreadsBound()),
                () -> assertEquals(Duration.ofMinutes(8), configuration.getJobTimeout()),
                () -> assertEquals(Duration.ofMinutes(20), configuration.getAsyncTaskLockRenewal()),
                () -> assertEquals("incident", configuration.getAsyncTaskMaxAgeAction()),
                () -> assertEquals(Duration.ofSeconds(4), configuration.getRetryBackoff()),
                () -> assertEquals(Duration.ofSeconds(5), configuration.getShutdownGrace()),
                () -> assertEquals(Duration.ofSeconds(6), configuration.getWorkflowVisibilityTimeout()),
                () -> assertEquals(Duration.ofSeconds(7), configuration.getUserTaskPollInterval()),
                () -> assertEquals(Duration.ofDays(30), configuration.getHistoryTimeToLive()),
                () -> assertEquals(99, configuration.getInstanceCacheMaxEntries()),
                () -> assertEquals(MessageStartLookup.SCAN, configuration.getMessageStartLookup()),
                () -> assertEquals(77, configuration.getMessageStartLookupMaxInstances()),
                () -> assertTrue(configuration.isAcceptUnscopedIdentifiers()),
                () -> assertEquals(Kind.COMPLETE, configuration
                        .validate("orders", false, false)
                        .kind()));
    }

    @Test
    @DisplayName("Every key at the adapter level binds")
    void everyAdapterKeyBinds() throws ReflectiveOperationException {
        final var keys = bind(EVERY_ADAPTER_KEY)
                .adapters()
                .get("orders");

        for (final var key : keysOf(ZenBpmAdapterKeys.class)) {
            assertTrue(((Optional<?>) key.invoke(keys)).isPresent(), key.getName());
        }
        assertEquals(EVERY_ADAPTER_KEY.size(), keysOf(ZenBpmAdapterKeys.class).size());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MessageStartLookup.class)
    @DisplayName("Each value of message-start-lookup binds as the documentation writes it")
    void eachValueOfTheMessageStartLookupBindsAsWritten(final MessageStartLookup lookup) {
        final var configuration = bind(Map.of(ADAPTER
                + "rest-address", "http://engine:8080",
                ADAPTER
                        + "message-start-lookup",
                lookup.configured())).configurationOf("orders");

        assertEquals(lookup, configuration.getMessageStartLookup());
    }

    @Test
    @DisplayName("An adapter id which sets nothing gets a configuration with every key unset")
    void anAdapterIdWithoutKeysGetsTheDefaults() {
        final var configuration = bind(Map.of(ADAPTER
                + "rest-address", "http://engine:8080")).configurationOf(
                        "billing");

        assertAll(
                () -> assertNull(configuration.getRestAddress()),
                () -> assertEquals(ZenBpmAdapterConfiguration.DEFAULT_REQUEST_TIMEOUT, configuration
                        .getRequestTimeout()),
                () -> assertEquals(Kind.UNCONFIGURED, configuration
                        .validate("billing", false, false)
                        .kind()));
    }

    @Test
    @DisplayName("retry-backoff of a task comes from the most specific level which sets it, and from none where no level sets it")
    void theMostSpecificLevelWinsForATask() {
        final var levels = Map.of(
                ADAPTER
                        + "retry-backoff",
                "PT1S",
                MODULE
                        + "adapters.orders.retry-backoff",
                "PT2S",
                WORKFLOW
                        + "adapters.orders.retry-backoff",
                "PT3S",
                TASK
                        + "adapters.orders.retry-backoff",
                "PT4S");
        final var overlay = bind(levels);

        assertAll(
                () -> assertEquals(Optional.of(Duration.ofSeconds(4)),
                        overlay.retryBackoffFor("shop", "Order", "ship", "orders")),
                () -> assertEquals(Optional.of(Duration.ofSeconds(3)),
                        overlay.retryBackoffFor("shop", "Order", "pack", "orders")),
                () -> assertEquals(Optional.of(Duration.ofSeconds(2)),
                        overlay.retryBackoffFor("shop", "Invoice", "send", "orders")),
                () -> assertEquals(Optional.of(Duration.ofSeconds(1)),
                        overlay.retryBackoffFor("crm", "Order", "ship", "orders")),
                () -> assertEquals(Optional.empty(), overlay.retryBackoffFor("shop", "Order", "ship", "billing")));
    }

    @Test
    @DisplayName("job-timeout of a task comes from the most specific level which sets it, else the default")
    void theJobTimeoutOfATaskComesFromTheMostSpecificLevel() {
        final var overlay = bind(Map.of(
                ADAPTER
                        + "job-timeout",
                "PT1M",
                MODULE
                        + "adapters.orders.job-timeout",
                "PT2M",
                WORKFLOW
                        + "adapters.orders.job-timeout",
                "PT3M",
                TASK
                        + "adapters.orders.job-timeout",
                "PT4M"));

        assertAll(
                () -> assertEquals(Duration.ofMinutes(4), overlay.jobTimeoutFor("shop", "Order", "ship", "orders")),
                () -> assertEquals(Duration.ofMinutes(3), overlay.jobTimeoutFor("shop", "Order", "pack", "orders")),
                () -> assertEquals(Duration.ofMinutes(2), overlay.jobTimeoutFor("shop", "Invoice", "send", "orders")),
                () -> assertEquals(Duration.ofMinutes(1), overlay.jobTimeoutFor("crm", "Order", "ship", "orders")),
                () -> assertEquals(ZenBpmAdapterConfiguration.DEFAULT_JOB_TIMEOUT, overlay.jobTimeoutFor("shop",
                        "Order", "ship", "billing")));
    }

    @Test
    @DisplayName("A level which does not set a key leaves it to the level above")
    void aLevelWithoutTheKeyLeavesItToTheLevelAbove() {
        final var overlay = bind(Map.of(
                MODULE
                        + "adapters.orders.retry-backoff",
                "PT2S",
                TASK
                        + "adapters.orders.job-timeout",
                "PT4M"));

        assertAll(
                () -> assertEquals(Optional.of(Duration.ofSeconds(2)),
                        overlay.retryBackoffFor("shop", "Order", "ship", "orders")),
                () -> assertEquals(ZenBpmAdapterConfiguration.DEFAULT_JOB_TIMEOUT,
                        overlay.jobTimeoutFor("shop", "Order",
                                "pack", "orders")));
    }

    @Test
    @DisplayName("history-time-to-live of a workflow comes from the workflow, its module or the adapter, else the engine's own")
    void theHistoryTimeToLiveOfAWorkflow() {
        final var overlay = bind(Map.of(
                ADAPTER
                        + "history-time-to-live",
                "P1D",
                MODULE
                        + "adapters.orders.history-time-to-live",
                "P2D",
                WORKFLOW
                        + "adapters.orders.history-time-to-live",
                "P3D"));

        assertAll(
                () -> assertEquals(Duration.ofDays(3), overlay.historyTimeToLiveFor("shop", "Order", "orders")),
                () -> assertEquals(Duration.ofDays(2), overlay.historyTimeToLiveFor("shop", "Invoice", "orders")),
                () -> assertEquals(Duration.ofDays(1), overlay.historyTimeToLiveFor("crm", "Order", "orders")),
                () -> assertNull(overlay.historyTimeToLiveFor("shop", "Order", "billing")));
    }

    @Test
    @DisplayName("accept-unscoped-identifiers of a workflow module wins over the adapter's, and a module which does not set it leaves it to the adapter")
    void acceptUnscopedIdentifiersOfAWorkflowModuleWins() {
        final var configuration = bind(Map.of(
                ADAPTER
                        + "accept-unscoped-identifiers",
                "true",
                MODULE
                        + "adapters.orders.accept-unscoped-identifiers",
                "false",
                "vanillabp.workflow-modules.crm.adapters.orders.accept-unscoped-identifiers",
                "true",
                "vanillabp.workflow-modules.billing.adapters.orders.retry-backoff",
                "PT1S"))
                .configurationOf("orders");

        assertAll(
                () -> assertEquals(Optional.empty(), configuration.keyAcceptingUnscopedIdentifiers("orders", "shop")),
                () -> assertEquals(Optional.of(
                        "vanillabp.workflow-modules.crm.adapters.orders.accept-unscoped-identifiers"),
                        configuration
                                .keyAcceptingUnscopedIdentifiers("orders", "crm")),
                () -> assertEquals(Optional.of("vanillabp.adapters.orders.accept-unscoped-identifiers"),
                        configuration.keyAcceptingUnscopedIdentifiers("orders", "billing")));
    }

    @Test
    @DisplayName("Every value below the adapter level is validated under the key the application wrote")
    void everyScopedValueIsValidated() {
        final var validation = bind(Map.of(
                ADAPTER
                        + "rest-address",
                "http://engine:8080",
                MODULE
                        + "adapters.orders.retry-backoff",
                "-PT2S",
                WORKFLOW
                        + "adapters.orders.history-time-to-live",
                "-P3D",
                TASK
                        + "adapters.orders.retry-backoff",
                "PT1.5S",
                TASK
                        + "adapters.orders.job-timeout",
                "PT0S",
                TASK
                        + "adapters.billing.retry-backoff",
                "-PT5S"))
                .configurationOf("orders")
                .validate("orders", false, false);

        assertAll(
                () -> assertTrue(validation.endsTheBoot()),
                () -> assertTrue(validation
                        .message()
                        .contains("vanillabp.workflow-modules.shop.adapters.orders.retry-backoff is PT-2S"),
                        validation.message()),
                () -> assertTrue(validation
                        .message()
                        .contains(
                                "vanillabp.workflow-modules.shop.workflows.Order.adapters.orders.history-time-to-live is PT-72H"),
                        validation.message()),
                () -> assertTrue(validation
                        .message()
                        .contains(
                                "vanillabp.workflow-modules.shop.workflows.Order.tasks.ship.adapters.orders.retry-backoff is PT1.5S, but the engine takes a backoff in whole seconds"),
                        validation.message()),
                () -> assertTrue(validation
                        .message()
                        .contains(
                                "vanillabp.workflow-modules.shop.workflows.Order.tasks.ship.adapters.orders.job-timeout is PT0S"),
                        validation.message()),
                () -> assertFalse(validation
                        .message()
                        .contains("billing"), validation.message()));
    }

    private static VanillaBpZenBpmProperties bind(final Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(Map.copyOf(properties), "test", 100))
                .withMapping(VanillaBpZenBpmProperties.class)
                .build()
                .getConfigMapping(VanillaBpZenBpmProperties.class);
    }

    // what SmallRye maps: the methods without parameters and without a body
    private static List<Method> keysOf(final Class<?> type) {
        return Arrays
                .stream(type.getDeclaredMethods())
                .filter(method -> method.getParameterCount() == 0 && !method.isDefault() && !Modifier.isStatic(method
                        .getModifiers()) && !Modifier.isPrivate(method.getModifiers()))
                .toList();
    }

    private static Set<String> settersOf(final Class<?> type) {
        return Arrays
                .stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .collect(Collectors.toSet());
    }

}
