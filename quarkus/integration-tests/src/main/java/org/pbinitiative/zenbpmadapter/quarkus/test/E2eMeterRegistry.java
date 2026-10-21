/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The metrics backend of the application: Quarkus adds every {@code MeterRegistry} bean to its
 * composite registry, and only a composite with a child records what is measured. An application
 * in production brings a backend of its own, Prometheus for example.
 */
@ApplicationScoped
public class E2eMeterRegistry {

    /**
     * Built by CDI.
     */
    public E2eMeterRegistry() {
    }

    /**
     * The registry which records what the application measures.
     *
     * @return The registry
     */
    @Produces
    @Singleton
    public SimpleMeterRegistry simpleMeterRegistry() {
        return new SimpleMeterRegistry();
    }

}
