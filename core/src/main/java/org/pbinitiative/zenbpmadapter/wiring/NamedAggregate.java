/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.util.Optional;

import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache.InstanceFacts;

/**
 * The workflow aggregate an instance names, read the way every delivery of this adapter reads it:
 * the variable which carries the aggregate's id first, because it is what VanillaBP reads the
 * aggregate by, else the business key. The business key is reported beside it, so the core refuses
 * an instance whose two name different aggregates.
 *
 * @param id The aggregate's id, as the variable or the business key carry it
 * @param businessKey The business key of the instance, <code>null</code> where it has none
 */
record NamedAggregate(
    String id,
    String businessKey
) {

    /**
     * The aggregate an instance names.
     *
     * @param facts The facts of the instance, with the variable of the aggregate's id where the
     *            instance has it
     * @param idVariableName The name of the variable which carries the aggregate's id
     * @return The aggregate, empty where the instance has neither the variable nor a business key
     */
    static Optional<NamedAggregate> in(
        final InstanceFacts facts,
        final String idVariableName
    ) {
        final var variable = facts
                .variables()
                .get(idVariableName);
        if (variable != null) {
            return Optional.of(new NamedAggregate(String.valueOf(variable), facts.businessKey()));
        }
        return Optional
                .ofNullable(facts.businessKey())
                .map(businessKey -> new NamedAggregate(businessKey, businessKey));
    }

}
