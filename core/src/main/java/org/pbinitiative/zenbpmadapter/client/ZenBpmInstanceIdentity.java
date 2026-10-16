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
 * <code>rest-address</code>, so one address stays one address however it is spelled. It is the
 * address as configured: two addresses of one engine, such as an alias, a proxy in front of a node
 * or two nodes of one cluster, are not recognised as one.</li>
 * <li>The mode is the one of the adapter id as a whole. The identifiers of the same workflow
 * module reach the engine differently in each mode, plain or prefixed. That is exactly the setup
 * of a migration from one mode to the other: two ids on one engine which differ only in that
 * mode, with the new one first in <code>prioritized-adapters</code>. The setup needs no key of
 * its own, as on Camunda 8, whose adapter compares the mode the same way.</li>
 * </ul>
 * Two ids with the same engine and the same mode reach it with the same identifiers. They would
 * deploy the same processes and work the same jobs, so configuring them as two adapters is an
 * error. Where they share <code>by-adapter</code>, the default where no mode is configured, the
 * message says that ZenBPM serves only <code>use-prefix</code> and <code>none</code>, because the
 * deployment refuses <code>by-adapter</code> (see decision 24 in the repository's DECISIONS.md).
 * <p>
 * An adapter id without a valid <code>rest-address</code> names no engine. It is not compared: its
 * start already reported the key it misses, and it refuses all work until the key is set. An id
 * which boots degraded because of another key has no clients either, but its address names an
 * engine, so it is compared: once the other key is fixed, the two ids would be one instance.
 */
public final class ZenBpmInstanceIdentity {

    // what an operator does where more than two ids share an engine and a mode
    private static final String THE_TWO_MODES_AND_MORE_ENGINES = """
            set 'use-prefix' on one id and 'none' on another, and give every further id an engine of its \
            own""";

    private ZenBpmInstanceIdentity() {
    }

    /**
     * Ends the start where two of the given adapter ids name the same <code>rest-address</code>
     * with the same mode. Every such group is named in one message, so one restart shows them
     * all. The adapter SPI hook
     * <code>AdapterDeploymentService#validateDistinctAdapterInstances</code> calls it.
     *
     * @param adapterIds The configured adapter ids of type <code>zenbpm</code>, in the order of
     *            their priority where all of them are prioritized
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
                .filter(factory -> factory
                        .configuration()
                        .hasValidRestAddress())
                .forEach(factory -> idsByInstance
                        .computeIfAbsent(new Instance(factory
                                .configuration()
                                .instanceIdentity(), scoping.modeFor(null, null, factory.getAdapterId())),
                                instance -> new LinkedList<>())
                        .add(factory.getAdapterId()));
        final var messages = idsByInstance
                .entrySet()
                .stream()
                .filter(group -> group
                        .getValue()
                        .size() > 1)
                .map(group -> whyTheyAreOneInstance(group.getKey(), group.getValue()))
                .toList();
        if (!messages.isEmpty()) {
            throw new IllegalStateException(String.join("\n\n", messages));
        }
    }

    private static String whyTheyAreOneInstance(
        final Instance instance,
        final List<String> adapterIds
    ) {
        final var mode = asConfigured(instance.mode());
        final var byAdapter = instance.mode() == NameClashAvoidance.BY_ADAPTER;
        final var oneEngineOnPurpose = oneEngineOnPurpose(adapterIds, byAdapter);
        if (byAdapter) {
            // ZenBPM refuses this mode while deploying, so giving each id "its own mode" would
            // lead an operator into that refusal for the id left on the default
            return """
                    ZenBPM adapter ids %s address the same engine, '%s', with the same name-clash-avoidance \
                    '%s', the default where none is configured. ZenBPM has no isolation of its own and serves \
                    only 'use-prefix' and 'none', so each of these ids needs one of them, and two ids on one \
                    engine need different ones. Tell them apart:
                      - for two engines, give each id its own address: %s;
                      - for one engine on purpose, as in a migration from 'none' to 'use-prefix', %s: %s;
                      - or remove all but one of these adapter ids.""".formatted(listed(adapterIds), instance
                    .engine(), mode, keys(adapterIds, "rest-address"), oneEngineOnPurpose,
                    keys(adapterIds,
                            "name-clash-avoidance"));
        }
        return """
                ZenBPM adapter ids %s address the same engine, '%s', with the same name-clash-avoidance \
                '%s', so they reach it with the same identifiers: they would deploy the same processes and \
                work the same jobs, and the BPMS election would ask one engine twice. Tell them apart:
                  - for two engines, give each id its own address: %s;
                  - for one engine on purpose, as in a migration from 'none' to 'use-prefix', %s: %s;
                  - or remove all but one of these adapter ids.""".formatted(listed(adapterIds), instance
                .engine(), mode, keys(adapterIds, "rest-address"), oneEngineOnPurpose,
                keys(adapterIds,
                        "name-clash-avoidance"));
    }

    // ZenBPM serves two modes, so one engine serves two adapter ids at most
    private static String oneEngineOnPurpose(
        final List<String> adapterIds,
        final boolean byAdapter
    ) {
        if (adapterIds.size() > 2) {
            return THE_TWO_MODES_AND_MORE_ENGINES;
        }
        return byAdapter ? "set 'use-prefix' on one id and 'none' on the other" : "give each id its own mode";
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
    private static String asConfigured(final NameClashAvoidance mode) {
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
