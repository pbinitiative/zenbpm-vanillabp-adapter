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
 * The JPA persistence of {@link E2ePostcard}. It saves within the caller's JTA transaction.
 */
@ApplicationScoped
public class E2ePostcardPersistence implements AggregatePersistenceAware<E2ePostcard> {

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2ePostcardPersistence() {
    }

    @Override
    public Class<E2ePostcard> getAggregateClass() {
        return E2ePostcard.class;
    }

    @Override
    public E2ePostcard save(final E2ePostcard aggregate) {
        if (entityManager.find(E2ePostcard.class, aggregate.getId()) == null) {
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
    public Object getAggregateId(final E2ePostcard aggregate) {
        return aggregate.getId();
    }

    @Override
    public E2ePostcard loadById(final Object aggregateId) {
        return entityManager.find(E2ePostcard.class, aggregateId);
    }

}
