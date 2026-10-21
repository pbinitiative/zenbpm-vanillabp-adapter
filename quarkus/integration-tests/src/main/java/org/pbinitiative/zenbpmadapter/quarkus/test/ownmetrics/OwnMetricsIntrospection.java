/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test.ownmetrics;

import java.util.List;

import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * What the test sees of an application with its own measurement: which one the deployment
 * services of the adapter measure into.
 */
@Path("/own-metrics")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class OwnMetricsIntrospection {

    @Inject
    List<AdapterDeploymentService<Object, Object>> deploymentServices;

    /**
     * Built by CDI.
     */
    public OwnMetricsIntrospection() {
    }

    /**
     * What each deployment service of the adapter measures into.
     *
     * @return The simple class name of each deployment service's measurement
     */
    @GET
    public List<String> measuredInto() {
        return deploymentServices
                .stream()
                .map(ZenBpmDeploymentService.class::cast)
                .map(deploymentService -> deploymentService
                        .getMetrics()
                        .getClass()
                        .getSimpleName())
                .toList();
    }

}
