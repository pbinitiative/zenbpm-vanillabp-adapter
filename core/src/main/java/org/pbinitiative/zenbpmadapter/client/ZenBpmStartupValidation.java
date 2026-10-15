/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.ValidationResult;

import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import lombok.extern.slf4j.Slf4j;

/**
 * What both platforms do with the ZenBPM adapter ids while an application starts: validate the
 * configuration of each, say what was found, and build the registry of their factories. The
 * platform modules only read their configuration source and call {@link #registryOf}.
 * <p>
 * The outcome of {@link ZenBpmAdapterConfiguration#validate} decides, and nothing here repeats its
 * rule:
 * <ul>
 * <li>every notice goes to the startup report as a notice;</li>
 * <li>an adapter id which is not configured is a warning, and its factory has no clients;</li>
 * <li>an inconsistent one which may boot degraded is a warning as well, with a factory without
 * clients;</li>
 * <li>an inconsistent one which may not ends the start, and so does a protobuf runtime which
 * cannot run the job stream of an adapter id with clients;</li>
 * <li>a complete one gets a factory with clients, which contacts nothing yet.</li>
 * </ul>
 * Findings are reported under {@link StartupTopic#CONFIGURATION} with the adapter id as their
 * scope, see {@link #scopeOf(String)}. Where the platform offers no startup report, they go to
 * the log at the same levels.
 */
@Slf4j
public final class ZenBpmStartupValidation {

    private ZenBpmStartupValidation() {
    }

    /**
     * The scope an adapter id's findings carry in the startup report.
     *
     * @param adapterId The adapter id
     * @return E.g. <code>zenbpm adapter 'orders'</code>
     */
    public static String scopeOf(final String adapterId) {
        return "%s adapter '%s'".formatted(ZenBpmAdapter.ADAPTER_TYPE, adapterId);
    }

    /**
     * Validates every adapter id of type <code>zenbpm</code>, reports what was found and builds
     * one factory per adapter id. Nothing contacts the engine.
     * <p>
     * Every adapter id is validated before the start ends, so a developer who got two of them
     * wrong reads both messages in one exception. Where the start ends, no factory was built yet.
     * Where building one fails, or the registry refuses them, the factories built so far are
     * closed before the failure is thrown, because each has started a thread of its HTTP client.
     *
     * @param adapterIds The adapter ids of type <code>zenbpm</code>, as the platform's core
     *            properties name them, never as the adapter's own configuration names them
     * @param configurationOf The configuration of an adapter id at the adapter level, with the
     *            values of lower levels handed in; never <code>null</code>, an id which sets
     *            nothing has one with every key unset
     * @param firstPriorityAnywhere Whether an adapter id is first in any prioritized-adapters list
     * @param deploymentFailureWarns Whether an adapter id's <code>deployment-failure</code> policy is
     *            <code>warn</code>
     * @param applicationName The application's name as the platform knows it
     * @param startupReport Where the findings go; <code>null</code> sends them to the log
     * @return The registry, with a factory per adapter id in the given order
     * @throws IllegalStateException With the messages of every adapter id whose configuration ends
     *             the start, or the message of {@link ProtobufRuntimeCheck}
     */
    public static ZenBpmClientRegistry registryOf(
        final List<String> adapterIds,
        final Function<String, ZenBpmAdapterConfiguration> configurationOf,
        final Predicate<String> firstPriorityAnywhere,
        final Predicate<String> deploymentFailureWarns,
        final Supplier<String> applicationName,
        final StartupReport startupReport
    ) {
        return registryOf(adapterIds, configurationOf, firstPriorityAnywhere, deploymentFailureWarns,
                startupReport, ProtobufRuntimeCheck::defect, (
                    adapterId,
                    configuration) -> new ZenBpmClientFactory(adapterId, configuration, applicationName));
    }

    /**
     * The same, with the protobuf check and the factory of a complete adapter id given, so a test
     * can make the check fail and watch the factories.
     */
    static ZenBpmClientRegistry registryOf(
        final List<String> adapterIds,
        final Function<String, ZenBpmAdapterConfiguration> configurationOf,
        final Predicate<String> firstPriorityAnywhere,
        final Predicate<String> deploymentFailureWarns,
        final StartupReport startupReport,
        final Supplier<Optional<String>> protobufDefect,
        final BiFunction<String, ZenBpmAdapterConfiguration, ZenBpmClientFactory> factoryWithClients
    ) {
        final var validated = new ArrayList<Validated>();
        final var endingTheStart = new ArrayList<String>();
        for (final var adapterId : adapterIds) {
            final var configuration = Objects.requireNonNull(configurationOf.apply(adapterId),
                    () -> "The platform has no configuration of ZenBPM adapter id '%s'; it hands in one with every key unset where the id sets nothing but its type."
                            .formatted(adapterId));
            final var result = configuration.validate(adapterId, !firstPriorityAnywhere.test(adapterId),
                    deploymentFailureWarns.test(adapterId));
            final var scope = scopeOf(adapterId);
            result
                    .notices()
                    .forEach(notice -> notice(startupReport, scope, notice));
            if (result.endsTheBoot()) {
                endingTheStart.add(result.message());
            } else if (result.kind() != ValidationResult.Kind.COMPLETE) {
                warn(startupReport, scope, result.message());
            }
            validated.add(new Validated(adapterId, configuration, result));
        }
        if (!endingTheStart.isEmpty()) {
            throw new IllegalStateException(String.join("\n\n", endingTheStart));
        }

        // only where a job stream will run: an application whose adapter ids have no clients does
        // not run one, and the stream checks again when it is built once the keys are set
        if (validated
                .stream()
                .anyMatch(Validated::isComplete)) {
            protobufDefect
                    .get()
                    .ifPresent(defect -> {
                        throw new IllegalStateException(defect);
                    });
        }

        final var factories = new ArrayList<ZenBpmClientFactory>();
        try {
            for (final var adapter : validated) {
                factories.add(adapter.isComplete()
                        ? factoryWithClients.apply(adapter.adapterId, adapter.configuration)
                        : ZenBpmClientFactory.unconfigured(adapter.adapterId, adapter.configuration, adapter.result
                                .message()));
            }
            return new ZenBpmClientRegistry(factories);
        } catch (final RuntimeException | Error e) {
            closeAll(factories, e);
            throw e;
        }
    }

    // without a startup report, as in a test which builds the registry by hand, at the same level
    // in the log
    private static void notice(
        final StartupReport startupReport,
        final String scope,
        final String message
    ) {
        if (startupReport == null) {
            log.info("{}: {}", scope, message);
        } else {
            startupReport.notice(StartupTopic.CONFIGURATION, scope, message);
        }
    }

    private static void warn(
        final StartupReport startupReport,
        final String scope,
        final String message
    ) {
        if (startupReport == null) {
            log.warn("{}: {}", scope, message);
        } else {
            startupReport.warn(StartupTopic.CONFIGURATION, scope, message);
        }
    }

    private static void closeAll(
        final List<ZenBpmClientFactory> factories,
        final Throwable failure
    ) {
        for (final var factory : factories) {
            try {
                factory.close();
            } catch (final RuntimeException | Error e) {
                failure.addSuppressed(e);
            }
        }
    }

    private record Validated(
        String adapterId,
        ZenBpmAdapterConfiguration configuration,
        ValidationResult result
    ) {

        private boolean isComplete() {
            return result.kind() == ValidationResult.Kind.COMPLETE;
        }

    }

}
