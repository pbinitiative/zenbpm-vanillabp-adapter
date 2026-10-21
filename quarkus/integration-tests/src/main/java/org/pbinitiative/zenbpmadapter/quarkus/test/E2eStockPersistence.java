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
 * Stores the aggregate {@link E2eStock} with JPA.
 */
@ApplicationScoped
public class E2eStockPersistence implements AggregatePersistenceAware<E2eStock> {

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eStockPersistence() {
    }

    @Override
    public Class<E2eStock> getAggregateClass() {
        return E2eStock.class;
    }

    @Override
    public E2eStock save(final E2eStock aggregate) {
        if (entityManager.find(E2eStock.class, aggregate.getId()) == null) {
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
    public Object getAggregateId(final E2eStock aggregate) {
        return aggregate.getId();
    }

    @Override
    public E2eStock loadById(final Object aggregateId) {
        return entityManager.find(E2eStock.class, aggregateId);
    }

}
