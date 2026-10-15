/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.health;

import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;

import io.vanillabp.integration.adapter.spi.health.AdapterHealth;

/**
 * What one ZenBPM adapter id can say about its engine. It asks
 * <code>GET /system/health/ready</code>, the engine's own readiness probe, once per check:
 * <ul>
 * <li>an adapter id without clients is UNKNOWN, with the message of the validation, which names
 * the key to set or correct. An application which booted with that message as a warning must not
 * be reported as an outage as well;</li>
 * <li>a ready engine is UP. The version and the commit the engine reported at startup are named
 * beside it, as <code>versionAtStart</code> and <code>commitAtStart</code>: the check does not ask
 * for them again, so they are what the engine ran when the application started;</li>
 * <li>an engine which is not ready is DOWN, with the reasons it gives;</li>
 * <li>an engine out of reach, too slow or answering something else is DOWN, with what the REST
 * client reported.</li>
 * </ul>
 * The check ends within <code>request-timeout</code>, the deadline of every REST call, and never
 * throws. Every answer of an adapter id with clients names the engine's address, so an operator
 * can act without opening the configuration first.
 */
public final class ZenBpmHealth {

    private ZenBpmHealth() {
    }

    /**
     * Asks the engine of an adapter id whether it is ready.
     *
     * @param factory The factory of the adapter id
     * @return What was found, never <code>null</code>
     */
    public static AdapterHealth check(final ZenBpmClientFactory factory) {
        final var adapterId = factory.getAdapterId();
        final var unconfigured = factory.unconfiguredMessage();
        if (unconfigured.isPresent()) {
            return AdapterHealth.unknown(adapterId, ZenBpmAdapter.ADAPTER_TYPE, unconfigured.get(), AdapterHealth
                    .detailsBuilder()
                    .build());
        }
        final var configuration = factory.configuration();
        final var address = configuration
                .restBaseUri()
                .toString();
        try {
            final var readiness = factory
                    .rest()
                    .ready();
            if (!readiness.ready()) {
                return AdapterHealth.down(adapterId, ZenBpmAdapter.ADAPTER_TYPE, readiness
                        .reasons()
                        .isEmpty()
                                ? "The engine reports not ready"
                                : "The engine reports not ready: %s".formatted(String.join("; ", readiness.reasons())),
                        AdapterHealth
                                .detailsBuilder()
                                .with("address", address)
                                .build());
            }
            final var details = AdapterHealth
                    .detailsBuilder()
                    .with("address", address);
            factory
                    .engineStatusAtStart()
                    .ifPresent(status -> details
                            .with("versionAtStart", status.version())
                            .with("commitAtStart", status.commit()));
            return AdapterHealth.up(adapterId, ZenBpmAdapter.ADAPTER_TYPE, "The engine reports ready", details.build());
        } catch (final RuntimeException e) {
            // a call after the factory was closed included: a health endpoint has to answer
            return AdapterHealth.down(adapterId, ZenBpmAdapter.ADAPTER_TYPE, e.getMessage(), AdapterHealth
                    .detailsBuilder()
                    .with("address", address)
                    .with("requestTimeout", configuration
                            .getRequestTimeout()
                            .toString())
                    .build());
        }
    }

}
