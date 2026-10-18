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
 * The JPA persistence of {@link E2eClaim}. It saves within the caller's JTA transaction, which is
 * the transaction a task handler runs in, so what a handler which throws saved is gone with the
 * transaction.
 */
@ApplicationScoped
public class E2eClaimPersistence implements AggregatePersistenceAware<E2eClaim> {

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eClaimPersistence() {
    }

    @Override
    public Class<E2eClaim> getAggregateClass() {
        return E2eClaim.class;
    }

    @Override
    public E2eClaim save(final E2eClaim aggregate) {
        if (entityManager.find(E2eClaim.class, aggregate.getId()) == null) {
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
    public Object getAggregateId(final E2eClaim aggregate) {
        return aggregate.getId();
    }

    @Override
    public E2eClaim loadById(final Object aggregateId) {
        return entityManager.find(E2eClaim.class, aggregateId);
    }

}
