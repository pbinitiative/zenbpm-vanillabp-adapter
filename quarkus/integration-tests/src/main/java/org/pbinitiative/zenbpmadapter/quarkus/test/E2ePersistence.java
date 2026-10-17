/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * The JPA persistence of {@link E2eAggregate}. It saves within the caller's JTA transaction, so an
 * aggregate a rolled-back start saved is gone with the transaction.
 */
@ApplicationScoped
public class E2ePersistence implements AggregatePersistenceAware<E2eAggregate> {

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2ePersistence() {
    }

    @Override
    public Class<E2eAggregate> getAggregateClass() {
        return E2eAggregate.class;
    }

    @Override
    public E2eAggregate save(final E2eAggregate aggregate) {
        if (entityManager.find(E2eAggregate.class, aggregate.getId()) == null) {
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
    public Object getAggregateId(final E2eAggregate aggregate) {
        return aggregate.getId();
    }

    @Override
    public E2eAggregate loadById(final Object aggregateId) {
        return entityManager.find(E2eAggregate.class, aggregateId);
    }

}
