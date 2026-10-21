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
 * Stores the aggregate {@link E2eSorting} with JPA.
 */
@ApplicationScoped
public class E2eSortingPersistence implements AggregatePersistenceAware<E2eSorting> {

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eSortingPersistence() {
    }

    @Override
    public Class<E2eSorting> getAggregateClass() {
        return E2eSorting.class;
    }

    @Override
    public E2eSorting save(final E2eSorting aggregate) {
        if (entityManager.find(E2eSorting.class, aggregate.getId()) == null) {
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
    public Object getAggregateId(final E2eSorting aggregate) {
        return aggregate.getId();
    }

    @Override
    public E2eSorting loadById(final Object aggregateId) {
        return entityManager.find(E2eSorting.class, aggregateId);
    }

}
