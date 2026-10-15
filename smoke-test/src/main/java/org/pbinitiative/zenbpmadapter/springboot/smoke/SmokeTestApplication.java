/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.spi.AggregatePersistenceAware;

/**
 * The application the smoke tests boot: the ZenBPM adapter on Spring Boot, without an engine. It
 * keeps the platform's JDBC outbox and delivery log on an in-memory database, so the start runs as
 * it runs in production, deployment included. Its workflow module <code>smoke-app</code> has no
 * BPMN file, so the platform deploys nothing to the adapter and calls none of the steps this
 * version of the adapter refuses.
 * <p>
 * It lives in a Maven module of its own, and so does its workflow module: a workflow module is a
 * classpath entry with <code>META-INF/workflow-module</code>, and an application which shares that
 * entry with another one shares its workflow module.
 */
@SpringBootApplication
public class SmokeTestApplication {

    /**
     * Built by Spring.
     */
    public SmokeTestApplication() {
    }

    /**
     * Says who owns {@link Aggregate}, which is no entity. Without it the start ends, because the
     * fallback persistence would look for a Spring Data repository this application has no reason
     * to have. Every method fails loudly, so a test which starts to persist hears about it.
     *
     * @return The persistence of every aggregate without one of its own
     */
    @Bean
    public AggregatePersistenceAware<Object> noPersistenceForTheSampleAggregate() {
        return new AggregatePersistenceAware<>() {

            @Override
            public Class<Object> getAggregateClass() {
                // every aggregate is an Object, at the greatest inheritance distance there is, so
                // a persistence declared for a specific class always wins over this one
                return Object.class;
            }

            @Override
            public Object save(final Object aggregate) {
                throw new UnsupportedOperationException("no persistence in the smoke tests");
            }

            @Override
            public Object getAggregateId(final Object aggregate) {
                throw new UnsupportedOperationException("no persistence in the smoke tests");
            }

            @Override
            public Object loadById(final Object aggregateId) {
                throw new UnsupportedOperationException("no persistence in the smoke tests");
            }

            @Override
            public Class<?> getAggregateIdType() {
                // the contract's "not determinable"
                return null;
            }

        };
    }

}
