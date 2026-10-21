/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.nativeimage;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * The JPA persistence of {@link NativeImageAggregate}. It saves within the caller's JTA
 * transaction, which is the transaction a task handler runs in.
 */
@ApplicationScoped
public class NativeImagePersistence implements AggregatePersistenceAware<NativeImageAggregate> {

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public NativeImagePersistence() {
    }

    @Override
    public Class<NativeImageAggregate> getAggregateClass() {
        return NativeImageAggregate.class;
    }

    @Override
    public NativeImageAggregate save(final NativeImageAggregate aggregate) {
        if (entityManager.find(NativeImageAggregate.class, aggregate.getId()) == null) {
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
    public Object getAggregateId(final NativeImageAggregate aggregate) {
        return aggregate.getId();
    }

    @Override
    public NativeImageAggregate loadById(final Object aggregateId) {
        return entityManager.find(NativeImageAggregate.class, aggregateId);
    }

}
