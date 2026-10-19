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
 * The JPA persistence of {@link E2eLetter}. It saves within the caller's JTA transaction.
 */
@ApplicationScoped
public class E2eLetterPersistence implements AggregatePersistenceAware<E2eLetter> {

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eLetterPersistence() {
    }

    @Override
    public Class<E2eLetter> getAggregateClass() {
        return E2eLetter.class;
    }

    @Override
    public E2eLetter save(final E2eLetter aggregate) {
        if (entityManager.find(E2eLetter.class, aggregate.getId()) == null) {
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
    public Object getAggregateId(final E2eLetter aggregate) {
        return aggregate.getId();
    }

    @Override
    public E2eLetter loadById(final Object aggregateId) {
        return entityManager.find(E2eLetter.class, aggregateId);
    }

}
