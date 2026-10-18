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
 * The JPA persistence of {@link E2eParcel}. It saves within the caller's JTA transaction, which is
 * the transaction a task handler runs in, so what a handler which throws saved is gone with the
 * transaction.
 */
@ApplicationScoped
public class E2eParcelPersistence implements AggregatePersistenceAware<E2eParcel> {

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eParcelPersistence() {
    }

    @Override
    public Class<E2eParcel> getAggregateClass() {
        return E2eParcel.class;
    }

    @Override
    public E2eParcel save(final E2eParcel aggregate) {
        if (entityManager.find(E2eParcel.class, aggregate.getId()) == null) {
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
    public Object getAggregateId(final E2eParcel aggregate) {
        return aggregate.getId();
    }

    @Override
    public E2eParcel loadById(final Object aggregateId) {
        return entityManager.find(E2eParcel.class, aggregateId);
    }

}
