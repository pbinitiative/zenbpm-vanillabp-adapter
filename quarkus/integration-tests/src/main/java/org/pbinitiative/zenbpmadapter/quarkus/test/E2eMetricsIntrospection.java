/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import java.util.Map;
import java.util.TreeMap;

import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * What the Quarkus end-to-end tests of the adapter's meters see of the running application: the
 * meters of an adapter id in the application's registry, through this
 * <code>introspect/meters/...</code> endpoint, because the application runs in a JVM of its own.
 */
@Path("/introspect/meters")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class E2eMetricsIntrospection {

    @Inject
    MeterRegistry registry;

    /**
     * Built by CDI.
     */
    public E2eMetricsIntrospection() {
    }

    /**
     * The adapter's meters of an adapter id: each counter's count and each gauge's value, by name.
     *
     * @param adapterId The adapter id
     * @return The values by meter name, empty where the adapter id publishes none
     */
    @GET
    @Path("/{adapterId}")
    public Map<String, Double> metersOf(@PathParam("adapterId") final String adapterId) {
        final var values = new TreeMap<String, Double>();
        registry
                .getMeters()
                .stream()
                .filter(meter -> meter
                        .getId()
                        .getName()
                        .startsWith("vanillabp.zenbpm.") && adapterId.equals(meter
                                .getId()
                                .getTag(ZenBpmMetrics.TAG_ADAPTER)))
                .forEach(meter -> {
                    if (meter instanceof Gauge gauge) {
                        values.put(meter
                                .getId()
                                .getName(), gauge.value());
                        return;
                    }
                    values.put(meter
                            .getId()
                            .getName(), ((FunctionCounter) meter).count());
                });
        return values;
    }

}
