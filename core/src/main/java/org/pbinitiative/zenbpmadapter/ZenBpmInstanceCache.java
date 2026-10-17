/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;

/**
 * What the engine said about the process instances of one adapter id, as far as it does not change
 * while an instance runs: its process, version and definition, its business key, its parent and
 * what started it. The state is not kept, because it changes; a caller which needs it asks the
 * engine. Of the variables only the one which carries the aggregate's id is kept, and only once it
 * is there: a workflow the engine started gets it written after its start.
 * <p>
 * The cache also answers the workflow id of an instance: the key of the root instance, found by
 * walking up from a child instance of the same process (an embedded or event sub-process, an
 * iteration of a multi-instance element) to its parent, and stopping at an instance a call activity
 * started, see decision 28 in the repository's DECISIONS.md. The walk reads the facts the cache
 * keeps, so it asks the engine only for an instance on the way which the cache does not hold.
 * <p>
 * An instance the engine answers <code>404</code> for is not kept, because a node which lags
 * behind may know it a moment later. The cache holds at most <code>instance-cache-max-entries</code>
 * instances and forgets the one used longest ago first; <code>0</code> keeps none. It may be used
 * by several threads at once. The engine is asked without a lock held, so two threads asking about
 * one instance at once may both ask.
 */
public final class ZenBpmInstanceCache {

    private final ZenBpmRestClient rest;

    /**
     * The instances by key, the one used longest ago first. Guarded by <code>this</code>.
     */
    private final Map<Long, InstanceFacts> entries;

    /**
     * A cache which holds nothing yet. Contacts nothing.
     *
     * @param rest The REST client of the adapter id
     * @param maxEntries How many instances it holds at most, <code>0</code> for none
     */
    public ZenBpmInstanceCache(
        final ZenBpmRestClient rest,
        final int maxEntries
    ) {
        this.rest = rest;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {

            @Override
            protected boolean removeEldestEntry(final Map.Entry<Long, InstanceFacts> eldest) {
                return size() > maxEntries;
            }

        };
    }

    /**
     * The facts of an instance, without its variables.
     *
     * @param instanceKey The key of the instance
     * @return The facts, empty where the engine answers <code>404</code>
     * @throws RuntimeException What the REST client throws where the engine was asked and did not
     *             answer
     */
    public Optional<InstanceFacts> facts(final long instanceKey) {
        final var cached = cached(instanceKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        return read(instanceKey, null);
    }

    /**
     * The facts of an instance with the variable which carries the aggregate's id, where the
     * instance has it.
     *
     * @param instanceKey The key of the instance
     * @param idVariableName The name of the variable
     * @return The facts, empty where the engine answers <code>404</code>
     * @throws RuntimeException What the REST client throws where the engine was asked and did not
     *             answer
     */
    public Optional<InstanceFacts> facts(
        final long instanceKey,
        final String idVariableName
    ) {
        final var cached = cached(instanceKey);
        if ((cached != null) && cached
                .variables()
                .containsKey(idVariableName)) {
            return Optional.of(cached);
        }
        // not kept yet, or not there or null when the instance was read: a workflow the engine
        // started gets its id written after the start, and a present id never changes
        return read(instanceKey, idVariableName);
    }

    /**
     * The workflow id of an instance: the key of its root instance, or of the instance a call
     * activity started, see decision 28 in the repository's DECISIONS.md. The walk reads the facts
     * the cache keeps, so every instance on the way up which it does not hold yet costs one
     * request, and a walk over kept instances costs none.
     *
     * @param instanceKey The key of the instance
     * @return The key, empty where the engine answers <code>404</code> for an instance on the way
     * @throws RuntimeException What the REST client throws where the engine was asked and did not
     *             answer
     */
    public OptionalLong workflowIdOf(final long instanceKey) {
        var key = instanceKey;
        while (true) {
            final var facts = facts(key);
            if (facts.isEmpty()) {
                return OptionalLong.empty();
            }
            if (!facts
                    .get()
                    .isPartOfItsParent()) {
                return OptionalLong.of(key);
            }
            key = facts
                    .get()
                    .parentKey();
        }
    }

    private synchronized InstanceFacts cached(final long instanceKey) {
        return entries.get(instanceKey);
    }

    private Optional<InstanceFacts> read(
        final long instanceKey,
        final String idVariableName
    ) {
        final var read = rest.instance(instanceKey);
        if (read.isEmpty()) {
            return Optional.empty();
        }
        final var facts = InstanceFacts.of(read.get(), idVariableName);
        synchronized (this) {
            entries.put(instanceKey, facts);
        }
        return Optional.of(facts);
    }

    /**
     * What the engine said about one instance.
     *
     * @param key The key of the instance
     * @param scopedProcessId The id of its process as the engine knows it
     * @param version The version of its process
     * @param definitionKey The key of its process definition
     * @param businessKey Its business key, <code>null</code> where it has none
     * @param parentKey The key of the instance it is part of or was called from, <code>null</code>
     *            for a root instance
     * @param processType What started it
     * @param variables The variable which carries the aggregate's id where it was asked for and
     *            the instance has it, else none
     */
    public record InstanceFacts(
        long key,
        String scopedProcessId,
        int version,
        long definitionKey,
        String businessKey,
        Long parentKey,
        ProcessInstanceRef.ProcessType processType,
        Map<String, Object> variables
    ) {

        /**
         * Keeps the variables unmodifiable. The id may be <code>null</code>, so they are not copied
         * with {@link Map#copyOf(Map)}.
         */
        public InstanceFacts {
            variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        }

        /**
         * Whether the instance runs inside another instance of the same workflow: an embedded or
         * event sub-process, or an iteration of a multi-instance element. An instance a call
         * activity started is a workflow of its own.
         *
         * @return <code>true</code> where its workflow id is that of its parent
         */
        public boolean isPartOfItsParent() {
            return (parentKey != null) && ((processType == ProcessInstanceRef.ProcessType.SUBPROCESS) || (processType == ProcessInstanceRef.ProcessType.MULTI_INSTANCE));
        }

        private static InstanceFacts of(
            final ProcessInstanceRef instance,
            final String idVariableName
        ) {
            final var variables = instance.variables();
            final Map<String, Object> kept = (idVariableName != null) && (variables.get(idVariableName) != null)
                    ? Collections.singletonMap(idVariableName, variables.get(idVariableName))
                    : Map.of();
            return new InstanceFacts(instance.key(), instance.bpmnProcessId(), instance.version(), instance
                    .definitionKey(), instance.businessKey(), instance.parentKey(), instance.processType(), kept);
        }

    }

}
