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
 * The JPA persistence of {@link E2eShipment}. It saves within the caller's JTA transaction.
 */
@ApplicationScoped
public class E2eShipmentPersistence implements AggregatePersistenceAware<E2eShipment> {

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eShipmentPersistence() {
    }

    @Override
    public Class<E2eShipment> getAggregateClass() {
        return E2eShipment.class;
    }

    @Override
    public E2eShipment save(final E2eShipment aggregate) {
        if (entityManager.find(E2eShipment.class, aggregate.getId()) == null) {
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
    public Object getAggregateId(final E2eShipment aggregate) {
        return aggregate.getId();
    }

    @Override
    public E2eShipment loadById(final Object aggregateId) {
        return entityManager.find(E2eShipment.class, aggregateId);
    }

}
