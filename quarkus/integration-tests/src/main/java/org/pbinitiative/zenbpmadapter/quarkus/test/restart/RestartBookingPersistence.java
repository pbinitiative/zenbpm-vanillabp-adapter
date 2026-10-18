/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test.restart;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * The JPA persistence of {@link RestartBooking}. It saves within the caller's JTA transaction,
 * which is the transaction a task handler runs in.
 */
@ApplicationScoped
public class RestartBookingPersistence implements AggregatePersistenceAware<RestartBooking> {

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public RestartBookingPersistence() {
    }

    @Override
    public Class<RestartBooking> getAggregateClass() {
        return RestartBooking.class;
    }

    @Override
    public RestartBooking save(final RestartBooking aggregate) {
        if (entityManager.find(RestartBooking.class, aggregate.getId()) == null) {
            entityManager.persist(aggregate);
            return aggregate;
        }
        return entityManager.merge(aggregate);
    }

    /**
     * Named here, because a persistence written by hand has nothing to derive the name from. The
     * adapter names the aggregate's id variable after it.
     *
     * @return The name of the aggregate's id attribute
     */
    @Override
    public String getAggregateIdName() {
        return "id";
    }

    @Override
    public Object getAggregateId(final RestartBooking aggregate) {
        return aggregate.getId();
    }

    @Override
    public RestartBooking loadById(final Object aggregateId) {
        return entityManager.find(RestartBooking.class, aggregateId);
    }

}
