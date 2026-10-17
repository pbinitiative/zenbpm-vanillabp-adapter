/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.deploy;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.spi.AggregatePersistenceAware;

/**
 * The application <code>ZenBpmDeploymentIT</code> boots against the pinned engine: the workflow
 * module <code>deploy-it</code> with {@link ShipmentWorkflow}, the platform's JDBC outbox and delivery
 * log on an in-memory database. It scans this package only, so the application of another
 * integration test, in a package of its own, stays out of it.
 */
@SpringBootApplication
public class DeployItApplication {

    /**
     * Built by Spring.
     */
    public DeployItApplication() {
    }

    /**
     * Says who owns {@link Shipment}, which is no entity. Every method fails loudly, so a test
     * which starts to persist hears about it.
     *
     * @return The persistence of every aggregate without one of its own
     */
    @Bean
    public AggregatePersistenceAware<Object> noPersistenceForTheShipment() {
        return new AggregatePersistenceAware<>() {

            @Override
            public Class<Object> getAggregateClass() {
                return Object.class;
            }

            @Override
            public Object save(final Object aggregate) {
                throw new UnsupportedOperationException("no persistence in the integration tests");
            }

            @Override
            public Object getAggregateId(final Object aggregate) {
                throw new UnsupportedOperationException("no persistence in the integration tests");
            }

            @Override
            public Object loadById(final Object aggregateId) {
                throw new UnsupportedOperationException("no persistence in the integration tests");
            }

            @Override
            public Class<?> getAggregateIdType() {
                return null;
            }

        };
    }

}
