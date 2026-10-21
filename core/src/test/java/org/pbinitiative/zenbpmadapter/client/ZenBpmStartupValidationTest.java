/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.CollectedStartupReport.Finding;

import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the platforms do with the ZenBPM adapter ids while an application starts, which both reach
 * through {@link ZenBpmStartupValidation}: the outcome of the validation decides, the findings go
 * to the startup report with the adapter id as their scope and without it as their subject, a
 * refusal included, and no factory is left open where building one fails.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmStartupValidationTest {

    private static final String ADDRESS = "http://localhost:8080";

    /**
     * The platform's <code>vanillabp.delivery.retention</code> by default.
     */
    private static final Duration RETENTION = Duration.ofDays(7);

    @Test
    @DisplayName("An adapter id without an address is a warning naming the key, and its factory refuses work with the same words, which name the adapter id where the warning's scope does")
    void anUnconfiguredAdapterIdIsAWarning() {
        final var report = new CollectedStartupReport();

        try (var registry = registryOf(List.of("orders"), Map.of(), report)) {
            final var factory = registry.getFactory("orders");
            final var message = factory
                    .unconfiguredMessage()
                    .orElseThrow();

            assertAll(
                    () -> assertEquals(List
                            .of(new Finding("warn", StartupTopic.CONFIGURATION, "zenbpm adapter 'orders'", message
                                    .replace("ZenBPM adapter 'orders'", "This adapter"))),
                            report.findings()),
                    () -> assertTrue(message.startsWith(
                            "ZenBPM adapter 'orders' is not configured: 'vanillabp.adapters.orders.rest-address' is missing."),
                            message),
                    () -> assertEquals(message, assertThrows(IllegalStateException.class, factory::rest).getMessage()));
        }
    }

    @Test
    @DisplayName("A complete adapter id reports nothing and gets a factory with clients")
    void aCompleteAdapterIdReportsNothing() {
        final var report = new CollectedStartupReport();

        try (var registry = registryOf(List.of("orders"), Map.of("orders", configured(ADDRESS)), report)) {
            assertAll(
                    () -> assertEquals(List.of(), report.findings()),
                    () -> assertTrue(registry
                            .getFactory("orders")
                            .isConfigured()));
        }
    }

    @Test
    @DisplayName("Every warning of a validation is a warning of the adapter id, also beside a complete configuration")
    void warningsAreWarnings() {
        final var report = new CollectedStartupReport();
        final var configuration = configured(ADDRESS);
        configuration.setShutdownGrace(Duration.ofMinutes(1));

        try (var registry = registryOf(List.of("orders"), Map.of("orders", configuration), report)) {
            final var warning = report
                    .findings()
                    .getFirst();
            assertAll(
                    () -> assertEquals(1, report
                            .findings()
                            .size(), report.findings()::toString),
                    () -> assertEquals("warn", warning.level()),
                    () -> assertEquals(StartupTopic.CONFIGURATION, warning.topic()),
                    () -> assertEquals("zenbpm adapter 'orders'", warning.scope()),
                    () -> assertTrue(warning
                            .message()
                            .contains("vanillabp.adapters.orders.shutdown-grace is PT1M, above the PT30S"),
                            warning.message()),
                    () -> assertTrue(registry
                            .getFactory("orders")
                            .isConfigured()));
        }
    }

    @Test
    @DisplayName("Every notice of a validation is a notice of the adapter id, also beside a complete configuration")
    void noticesAreNotices() {
        final var report = new CollectedStartupReport();

        try (var registry = registryOf(List.of("orders"), Map.of("orders", configured(ADDRESS
                + "/v1")), report)) {
            assertEquals(1, report
                    .findings()
                    .size(),
                    report
                            .findings()
                            .toString());
            final var notice = report
                    .findings()
                    .getFirst();
            assertAll(
                    () -> assertEquals("notice", notice.level()),
                    () -> assertEquals(StartupTopic.CONFIGURATION, notice.topic()),
                    () -> assertEquals("zenbpm adapter 'orders'", notice.scope()),
                    () -> assertTrue(notice
                            .message()
                            .contains("already ends with '/v1'"), notice.message()),
                    () -> assertTrue(registry
                            .getFactory("orders")
                            .isConfigured()));
        }
    }

    @Test
    @DisplayName("An address defect of an adapter id nowhere first with policy 'warn' boots degraded, as a warning")
    void anAddressDefectWhichMayDegradeIsAWarning() {
        final var report = new CollectedStartupReport();

        try (var registry = ZenBpmStartupValidation.registryOf(List.of("old"), id -> configured("ftp://engine"),
                id -> false, id -> true, RETENTION, () -> "shop", report)) {
            final var factory = registry.getFactory("old");
            final var message = factory
                    .unconfiguredMessage()
                    .orElseThrow();

            assertAll(
                    () -> assertEquals(
                            List.of(new Finding("warn", StartupTopic.CONFIGURATION, "zenbpm adapter 'old'", message
                                    .replace("ZenBPM adapter 'old'", "This adapter"))),
                            report.findings()),
                    () -> assertTrue(message.startsWith("ZenBPM adapter 'old' is configured inconsistently:"), message),
                    () -> assertTrue(message.contains("boots DEGRADED"), message),
                    () -> assertTrue(message.contains("vanillabp.adapters.old.rest-address"), message));
        }
    }

    @Test
    @DisplayName("Every adapter id which ends the start is refused in the startup report, scoped to it, and gets a factory without clients which refuses work naming it; the others are built")
    void everyAdapterIdWhichEndsTheStartIsRefused() {
        final var report = new CollectedStartupReport();
        final var built = new ArrayList<String>();
        final var workerThreads = configured(ADDRESS);
        workerThreads.setWorkerThreads("0");

        try (var registry = ZenBpmStartupValidation.registryOf(List.of("orders", "billing", "shipping"), Map.of(
                "orders", configured("ftp://engine"), "billing", workerThreads, "shipping", configured(ADDRESS))::get,
                id -> true, id -> false, RETENTION, report, Optional::empty, building(built))) {
            final var orders = registry
                    .getFactory("orders")
                    .unconfiguredMessage()
                    .orElseThrow();

            assertAll(
                    () -> assertEquals(List.of("refuse", "refuse"), report
                            .findings()
                            .stream()
                            .map(Finding::level)
                            .toList()),
                    () -> assertEquals(List.of("zenbpm adapter 'orders'", "zenbpm adapter 'billing'"), report
                            .findings()
                            .stream()
                            .map(Finding::scope)
                            .toList()),
                    () -> assertTrue(report
                            .findings()
                            .stream()
                            .allMatch(finding -> finding.topic() == StartupTopic.CONFIGURATION)),
                    () -> assertEquals(orders.replace("ZenBPM adapter 'orders'", "This adapter"), report
                            .findings()
                            .getFirst()
                            .message()),
                    () -> assertTrue(orders.startsWith("""
                            ZenBPM adapter 'orders' is configured inconsistently:
                              - vanillabp.adapters.orders.rest-address needs the scheme http or https"""), orders),
                    () -> assertTrue(report
                            .findings()
                            .getLast()
                            .message()
                            .startsWith("""
                                    This adapter is configured inconsistently:
                                      - vanillabp.adapters.billing.worker-threads is 0; without a thread the adapter \
                                    runs no handler"""), report.findings()::toString),
                    () -> assertFalse(registry
                            .getFactory("billing")
                            .isConfigured()),
                    () -> assertEquals(List.of("shipping"), built));
        }
    }

    @Test
    @DisplayName("Without a startup report every adapter id which ends the start is named in one exception, and no factory is built")
    void withoutAReportEveryAdapterIdWhichEndsTheStartIsNamedInOneException() {
        final var built = new ArrayList<String>();
        final var workerThreads = configured(ADDRESS);
        workerThreads.setWorkerThreads("0");

        final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmStartupValidation.registryOf(List
                .of("orders", "billing", "shipping"),
                Map.of("orders", configured("ftp://engine"), "billing",
                        workerThreads, "shipping", configured(ADDRESS))::get,
                id -> true, id -> false, RETENTION, null,
                Optional::empty, building(built)));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .startsWith("ZenBPM adapter 'orders' is configured inconsistently:"), failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .contains("\n\nZenBPM adapter 'billing' is configured inconsistently:"), failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .contains("vanillabp.adapters.billing.worker-threads"), failure.getMessage()),
                () -> assertFalse(failure
                        .getMessage()
                        .contains("'shipping'"), failure.getMessage()),
                () -> assertEquals(List.of(), built));
    }

    @Test
    @DisplayName("A value which cannot work below the adapter level is refused naming its full key")
    void aScopedValueWhichCannotWorkIsRefused() {
        final var report = new CollectedStartupReport();
        final var configuration = configured(ADDRESS);
        configuration.addScopedValue("vanillabp.workflow-modules.shop.adapters.orders.retry-backoff", Duration
                .ofSeconds(-1));

        try (var registry = registryOf(List.of("orders"), Map.of("orders", configuration), report)) {
            final var refusal = report
                    .findings()
                    .getFirst();
            assertAll(
                    () -> assertEquals("refuse", refusal.level()),
                    () -> assertTrue(refusal
                            .message()
                            .contains(
                                    "  - vanillabp.workflow-modules.shop.adapters.orders.retry-backoff is PT-1S and must not be negative"),
                            refusal.message()));
        }
    }

    @Test
    @DisplayName("A protobuf runtime which cannot run the job stream is refused under PARTS AND VERSIONS, scoped to the dependency, and no factory with clients is built")
    void anUnfitProtobufRuntimeIsRefused() {
        final var report = new CollectedStartupReport();
        final var built = new ArrayList<String>();

        try (var registry = ZenBpmStartupValidation.registryOf(List.of("orders"), id -> configured(ADDRESS),
                id -> true, id -> false, RETENTION, report,
                () -> Optional.of("raise com.google.protobuf:protobuf-java"),
                building(built))) {
            assertAll(
                    () -> assertEquals(List.of(
                            new Finding("refuse", StartupTopic.PARTS_AND_VERSIONS, "com.google.protobuf:protobuf-java", "raise com.google.protobuf:protobuf-java")),
                            report
                                    .findings()),
                    () -> assertEquals(List.of(), built),
                    () -> assertEquals(Optional.of("raise com.google.protobuf:protobuf-java"), registry
                            .getFactory("orders")
                            .unconfiguredMessage()));
        }
    }

    @Test
    @DisplayName("Without a startup report a protobuf runtime which cannot run the job stream ends the start before a factory with clients is built")
    void withoutAReportAnUnfitProtobufRuntimeEndsTheStart() {
        final var built = new ArrayList<String>();

        final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmStartupValidation.registryOf(List
                .of("orders"), id -> configured(ADDRESS), id -> true, id -> false, RETENTION, null,
                () -> Optional.of("raise com.google.protobuf:protobuf-java"), building(built)));

        assertAll(
                () -> assertEquals("raise com.google.protobuf:protobuf-java", failure.getMessage()),
                () -> assertEquals(List.of(), built));
    }

    @Test
    @DisplayName("Without a factory with clients nothing runs a job stream, so the protobuf runtime is not asked")
    void theProtobufRuntimeIsAskedOnlyForAFactoryWithClients() {
        final var asked = new AtomicBoolean();

        try (var registry = ZenBpmStartupValidation.registryOf(List.of("orders"),
                id -> new ZenBpmAdapterConfiguration(),
                id -> true, id -> false, RETENTION, new CollectedStartupReport(), () -> {
                    asked.set(true);
                    return Optional.of("raise com.google.protobuf:protobuf-java");
                }, building(new ArrayList<>()))) {
            assertAll(
                    () -> assertFalse(asked.get()),
                    () -> assertFalse(registry
                            .getFactory("orders")
                            .isConfigured()));
        }
    }

    @Test
    @DisplayName("The check of the protobuf runtime is protobuf's own: a runtime without RuntimeVersion is refused")
    void theProtobufCheckIsTheRealOne() throws ReflectiveOperationException {
        final var isolated = new WithoutRuntimeVersion(getClass().getClassLoader());
        final var validation = isolated.loadClass(ZenBpmStartupValidation.class.getName());
        final var configurationType = isolated.loadClass(ZenBpmAdapterConfiguration.class.getName());
        final var configuration = configurationType
                .getConstructor()
                .newInstance();
        configurationType
                .getMethod("setRestAddress", String.class)
                .invoke(configuration, ADDRESS);
        final var registryOf = validation.getMethod("registryOf", List.class, Function.class, Predicate.class,
                Predicate.class, Duration.class, Supplier.class, StartupReport.class);

        final var failure = assertThrows(InvocationTargetException.class, () -> registryOf.invoke(null, List.of(
                "orders"), (Function<String, Object>) id -> configuration, (Predicate<String>) id -> true,
                (Predicate<String>) id -> false, RETENTION, (Supplier<String>) () -> "shop", null));

        final var cause = assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(cause
                .getMessage()
                .contains("has no 'com.google.protobuf.RuntimeVersion'"), cause.getMessage());
    }

    @Test
    @DisplayName("A factory which fails to be built closes the ones built before it")
    void aFactoryWhichFailsClosesTheOthers() {
        final var opened = new ArrayList<ZenBpmClientFactory>();
        final var refused = new IllegalStateException("no HTTP client today");

        final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmStartupValidation.registryOf(List
                .of("orders", "billing"), id -> configured(ADDRESS), id -> true, id -> false, RETENTION,
                new CollectedStartupReport(), Optional::empty, (
                    adapterId,
                    configuration) -> {
                    if (adapterId.equals("billing")) {
                        throw refused;
                    }
                    final var factory = new ZenBpmClientFactory(adapterId, configuration, () -> "shop");
                    opened.add(factory);
                    return factory;
                }));

        assertAll(
                () -> assertSame(refused, failure),
                () -> assertEquals(1, opened.size()),
                () -> assertTrue(opened
                        .getFirst()
                        .isClosed()));
    }

    @Test
    @DisplayName("Where the registry refuses the factories, every one of them is closed, and a failing close is kept with the refusal")
    void factoriesTheRegistryRefusesAreClosed() {
        final var closed = new ArrayList<String>();

        final var failure = assertThrows(IllegalArgumentException.class, () -> ZenBpmStartupValidation.registryOf(
                List.of("orders", "orders"), id -> configured(ADDRESS), id -> true, id -> false, RETENTION,
                new CollectedStartupReport(), Optional::empty, (
                    adapterId,
                    configuration) -> new ZenBpmClientFactory(adapterId, configuration, "client", null, null, null) {

                        @Override
                        public void close() {
                            closed.add(adapterId);
                            throw new IllegalStateException("the stream would not close");
                        }

                    }));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .contains("was given two factories"), failure.getMessage()),
                () -> assertEquals(List.of("orders", "orders"), closed),
                () -> assertEquals(2, failure.getSuppressed().length));
    }

    @Test
    @DisplayName("Without a startup report the findings go to the log at their level, named after the adapter id")
    void withoutAStartupReportTheFindingsAreLogged(final CapturedOutput output) {
        try (var registry = registryOf(List.of("orders", "billing"), Map.of("billing", configured(ADDRESS
                + "/v1")),
                null)) {
            final var log = output.getAllOfThisTest();

            assertAll(
                    () -> assertTrue(lineOf(log, "zenbpm adapter 'orders': This adapter is not configured")
                            .contains(" WARN "), log),
                    () -> assertTrue(lineOf(log,
                            "zenbpm adapter 'billing': 'vanillabp.adapters.billing.rest-address' already ends with '/v1'. The adapter appends it itself, so the key may name the engine's address without it. No second '/v1' is appended.")
                            .contains(" INFO "), log));
        }
    }

    @Test
    @DisplayName("A platform which hands in no configuration is told that it has to hand in one with every key unset")
    void aMissingConfigurationIsADefectOfThePlatform() {
        final var failure = assertThrows(NullPointerException.class, () -> registryOf(List.of("orders"), null,
                new CollectedStartupReport()));

        assertEquals(
                "The platform has no configuration of ZenBPM adapter id 'orders'; it hands in one with every key unset where the id sets nothing but its type.",
                failure.getMessage());
    }

    @Test
    @DisplayName("The platform's delivery retention reaches the validation: an async-task-lock-renewal not below it is refused, naming both keys")
    void theDeliveryRetentionReachesTheValidation() {
        final var report = new CollectedStartupReport();
        final var configuration = configured(ADDRESS);
        configuration.setAsyncTaskLockRenewal(Duration.ofHours(2));

        try (var registry = ZenBpmStartupValidation.registryOf(List.of("orders"), id -> configuration, id -> true,
                id -> false, Duration.ofHours(2), () -> "shop", report)) {
            final var refusal = report
                    .findings()
                    .getFirst()
                    .message();
            assertAll(
                    () -> assertTrue(refusal.contains("vanillabp.adapters.orders.async-task-lock-renewal is PT2H"),
                            refusal),
                    () -> assertTrue(refusal.contains("vanillabp.delivery.retention (PT2H)"), refusal));
        }
    }

    private static ZenBpmClientRegistry registryOf(
        final List<String> adapterIds,
        final Map<String, ZenBpmAdapterConfiguration> configurations,
        final StartupReport report
    ) {
        return ZenBpmStartupValidation.registryOf(adapterIds, configurations == null
                ? id -> null
                : id -> configurations.getOrDefault(id, new ZenBpmAdapterConfiguration()), id -> true, id -> false,
                RETENTION,
                () -> "shop", report);
    }

    private static String lineOf(
        final String log,
        final String text
    ) {
        return log
                .lines()
                .filter(line -> line.contains(text))
                .findFirst()
                .orElse("");
    }

    private static ZenBpmAdapterConfiguration configured(final String restAddress) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        return configuration;
    }

    private static BiFunction<String, ZenBpmAdapterConfiguration, ZenBpmClientFactory> building(final List<String> built) {
        return (
            adapterId,
            configuration) -> {
            built.add(adapterId);
            return new ZenBpmClientFactory(adapterId, configuration, () -> "shop");
        };
    }

}
