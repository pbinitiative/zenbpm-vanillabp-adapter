/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.quarkus.runtime;

import org.pbinitiative.zenbpmadapter.observability.MicrometerZenBpmMetrics;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Publishes what this adapter measures on top of the core's meters, per adapter id: the jobs, the
 * locks, the job stream's reconnects, the looks at the user tasks and the handler slots. Quarkus
 * applies every {@code MeterBinder} bean to its registry, so producing the binder is enough.
 * <p>
 * This class is registered as a bean only where the application uses the Micrometer extension (see
 * {@code ZenBpmIntegrationProcessor}). It refers to Micrometer types, so it must not be loaded
 * without it.
 */
@ApplicationScoped
public class ZenBpmMetricsProducer {

    /**
     * Built by CDI, which needs a constructor without parameters to proxy an application-scoped
     * bean.
     */
    public ZenBpmMetricsProducer() {
    }

    /**
     * Publishes the adapter's meter binder, which every deployment service of the adapter measures
     * into and Quarkus applies to its registry.
     *
     * @return The meter binder of this adapter's own numbers
     */
    @Produces
    @Singleton
    public MicrometerZenBpmMetrics zenBpmMetrics() {
        return new MicrometerZenBpmMetrics();
    }

}
