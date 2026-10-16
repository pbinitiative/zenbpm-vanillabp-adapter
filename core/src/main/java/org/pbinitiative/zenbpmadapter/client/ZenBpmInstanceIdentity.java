/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * What makes two configured adapter ids of type <code>zenbpm</code> two instances. ZenBPM is
 * remote and has no tenants and no credentials, so two ids are distinct where they address
 * different engines, or one engine with different <code>name-clash-avoidance</code> modes:
 * <ul>
 * <li>The engine is {@link ZenBpmAdapterConfiguration#instanceIdentity()}, the normalised
 * <code>rest-address</code>, so one engine stays one engine however its address is spelled.</li>
 * <li>The mode is the one of the adapter id as a whole. The identifiers of the same workflow
 * module reach the engine differently in each mode, plain or prefixed. That is exactly the setup
 * of a migration from one mode to the other: two ids on one engine which differ only in that
 * mode, with the new one first in <code>prioritized-adapters</code>. The setup needs no key of
 * its own, as on Camunda 8, whose adapter compares the mode the same way.</li>
 * </ul>
 * Two ids with the same engine and the same mode reach it with the same identifiers. They would
 * deploy the same processes and work the same jobs, so configuring them as two adapters is an
 * error.
 * <p>
 * An adapter id without clients has no engine. It is not compared: its start already reported
 * the keys it misses, and it refuses all work until they are set.
 */
public final class ZenBpmInstanceIdentity {

    private ZenBpmInstanceIdentity() {
    }

    /**
     * Ends the start where two of the given adapter ids address the same engine with the same
     * mode. Every such group is named in one message, so one restart shows them all. The adapter
     * SPI hook <code>AdapterDeploymentService#validateDistinctAdapterInstances</code> calls it.
     *
     * @param adapterIds The configured adapter ids of type <code>zenbpm</code>, in the order of
     *            their priority
     * @param registry The factories of the adapter ids
     * @param scoping The platform's name-clash scoping, which knows the mode of each adapter id
     * @throws IllegalStateException Where two adapter ids are one instance, naming the keys which
     *             tell them apart
     */
    public static void validateDistinct(
        final List<String> adapterIds,
        final ZenBpmClientRegistry registry,
        final NameClashAvoidanceSupport scoping
    ) {
        final var idsByInstance = new LinkedHashMap<Instance, List<String>>();
        adapterIds
                .stream()
                .map(registry::getFactory)
                .filter(ZenBpmClientFactory::isConfigured)
                .forEach(factory -> idsByInstance
                        .computeIfAbsent(new Instance(factory
                                .configuration()
                                .instanceIdentity(), scoping.modeFor(null, null, factory.getAdapterId())),
                                instance -> new LinkedList<>())
                        .add(factory.getAdapterId()));
        final var oneInstance = idsByInstance
                .entrySet()
                .stream()
                .filter(group -> group
                        .getValue()
                        .size() > 1)
                .map(group -> sameInstance(group.getKey(), group.getValue()))
                .toList();
        if (!oneInstance.isEmpty()) {
            throw new IllegalStateException(String.join("\n\n", oneInstance));
        }
    }

    private static String sameInstance(
        final Instance instance,
        final List<String> adapterIds
    ) {
        final var mode = keyOf(instance.mode());
        return """
                ZenBPM adapter ids %s address the same engine, '%s', with the same name-clash-avoidance \
                '%s', so they reach it with the same identifiers: they would deploy the same processes and \
                work the same jobs, and the BPMS election would ask one engine twice. Tell them apart:
                  - for two engines, give each id its own address: %s;
                  - for one engine on purpose, as in a migration from 'none' to 'use-prefix', give each id \
                its own mode: %s;
                  - or remove all but one of these adapter ids.""".formatted(listed(adapterIds), instance
                .engine(), mode, keys(adapterIds, "rest-address"), keys(adapterIds, "name-clash-avoidance"));
    }

    // 'a', 'b' and 'c'
    private static String listed(final List<String> adapterIds) {
        final var quoted = adapterIds
                .stream()
                .map("'%s'"::formatted)
                .toList();
        return "%s and %s".formatted(String.join(", ", quoted.subList(0, quoted.size() - 1)), quoted.getLast());
    }

    private static String keys(
        final List<String> adapterIds,
        final String key
    ) {
        return adapterIds
                .stream()
                .map(adapterId -> "'%s'".formatted(ZenBpmAdapterConfiguration.propertyKey(adapterId, key)))
                .collect(Collectors.joining(", "));
    }

    // the mode as the application writes it, e.g. use-prefix
    private static String keyOf(final NameClashAvoidance mode) {
        return mode
                .name()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    /**
     * What an adapter id reaches: an engine, with the identifiers of one mode.
     *
     * @param engine The normalised base of the engine's REST API
     * @param mode The adapter id's name-clash-avoidance mode
     */
    private record Instance(
        String engine,
        NameClashAvoidance mode
    ) {
    }

}
