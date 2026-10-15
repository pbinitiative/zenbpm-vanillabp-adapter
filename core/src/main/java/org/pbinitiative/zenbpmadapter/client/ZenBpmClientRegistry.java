/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;

import lombok.extern.slf4j.Slf4j;

/**
 * The factory of every configured ZenBPM adapter id, which the platform builds once per
 * application and closes when the application stops. The platform decides from the validation
 * whether an adapter id gets a factory with clients or one without; the registry does not repeat
 * that rule.
 */
@Slf4j
public class ZenBpmClientRegistry implements AutoCloseable {

    private final Map<String, ZenBpmClientFactory> factories = new LinkedHashMap<>();

    /**
     * Holds the factories of the configured adapter ids.
     *
     * @param factories One factory per adapter id of type <code>zenbpm</code>, in the order the
     *            platform reads the ids
     * @throws IllegalArgumentException Where two factories belong to one adapter id
     */
    public ZenBpmClientRegistry(final List<ZenBpmClientFactory> factories) {
        factories.forEach(factory -> {
            if (this.factories.putIfAbsent(factory.getAdapterId(), factory) != null) {
                throw new IllegalArgumentException(
                        "ZenBPM adapter id '%s' was given two factories; the platform builds one per adapter id."
                                .formatted(factory.getAdapterId()));
            }
        });
    }

    /**
     * The factory of one adapter id, through which everything else in this adapter reaches the
     * engine.
     *
     * @param adapterId The adapter id
     * @return Its factory
     * @throws IllegalStateException Where no ZenBPM adapter of that id is configured. The ids come
     *             from the platform's configuration, so an unknown one is a defect of the wiring.
     */
    public ZenBpmClientFactory getFactory(final String adapterId) {
        final var factory = factories.get(adapterId);
        if (factory == null) {
            throw new IllegalStateException(
                    "No ZenBPM adapter with id '%s' is configured; the configured ids of type '%s' are '%s'. The ids come from 'vanillabp.adapters.<id>.type', so an unknown one is a defect of the wiring rather than of the configuration."
                            .formatted(adapterId, ZenBpmAdapter.ADAPTER_TYPE, String.join("', '", factories.keySet())));
        }
        return factory;
    }

    /**
     * Every factory.
     *
     * @return The factories, in the order they were given
     */
    public List<ZenBpmClientFactory> factories() {
        return List.copyOf(factories.values());
    }

    /**
     * Which other adapter ids talk to the same engine as this one. Keys are unique per engine, so
     * two such adapter ids are handed each other's keys and have to tell them apart. Two addresses
     * name the same engine where {@link ZenBpmAdapterConfiguration#instanceIdentity()} is equal,
     * however they are spelled. An adapter id without clients shares nothing.
     *
     * @param adapterId The adapter id
     * @return The other adapter ids with the same engine, in the order the factories were given
     * @throws IllegalStateException Where no ZenBPM adapter of that id is configured
     */
    public List<String> sharesEngineWith(final String adapterId) {
        final var factory = getFactory(adapterId);
        if (!factory.isConfigured()) {
            return List.of();
        }
        final var engine = factory
                .configuration()
                .instanceIdentity();
        return factories
                .values()
                .stream()
                .filter(other -> other != factory)
                .filter(ZenBpmClientFactory::isConfigured)
                .filter(other -> Objects.equals(engine, other
                        .configuration()
                        .instanceIdentity()))
                .map(ZenBpmClientFactory::getAdapterId)
                .toList();
    }

    /**
     * Closes every factory. One which fails is logged, and the others are closed all the same.
     */
    @Override
    public void close() {
        factories
                .values()
                .forEach(factory -> {
                    try {
                        factory.close();
                    } catch (final RuntimeException e) {
                        log.warn("ZenBPM[{}]: closing the clients failed; the other adapter ids are closed anyway",
                                factory.getAdapterId(), e);
                    }
                });
    }

}
