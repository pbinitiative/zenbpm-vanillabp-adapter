/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongFunction;

/**
 * The BPMN of the definitions the engine of one adapter id holds, as this application read them,
 * by definition key. Several questions of a start read the same models: how many tasks are open,
 * which messages the held versions wait for, which job types a declared process needs, and what
 * the startup check of the older versions asks. A definition never changes once the engine
 * created it, so a model read for one of them answers every other, and a start reads each model
 * once, see decision 56 in the repository's DECISIONS.md.
 * <p>
 * It keeps at most {@value #MAXIMUM_CHARACTERS} characters of BPMN, dropping the model read
 * longest ago first, so an engine which holds many versions costs requests, not memory. A model
 * larger than that is handed out and not kept. The client factory of the adapter id keeps it, and
 * it is read from any thread.
 */
public final class ZenBpmHeldModels {

    /**
     * How many characters of BPMN are kept at most.
     */
    public static final int MAXIMUM_CHARACTERS = 8 * 1024 * 1024;

    private final int maximumCharacters;

    /**
     * The models by definition key, the one read longest ago first. Guarded by itself.
     */
    private final LinkedHashMap<Long, String> byKey = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * How many characters {@link #byKey} holds. Guarded by {@link #byKey}.
     */
    private long characters;

    /**
     * Nothing read yet: the client factory of an adapter id builds one.
     */
    public ZenBpmHeldModels() {
        this(MAXIMUM_CHARACTERS);
    }

    /**
     * Nothing read yet, with a bound of its own, for a test which fills it.
     *
     * @param maximumCharacters How many characters of BPMN are kept at most
     */
    ZenBpmHeldModels(final int maximumCharacters) {
        this.maximumCharacters = maximumCharacters;
    }

    /**
     * The BPMN of a definition: the one read before, or the one the given read brings, which is
     * then kept. The read runs outside of any lock, so two threads asking for the same model at
     * the same moment may both read it.
     *
     * @param key The definition key
     * @param read Reads the BPMN of a definition from the engine
     * @return The BPMN
     * @throws RuntimeException What the read threw; nothing is kept then
     */
    public String bpmnOf(
        final long key,
        final LongFunction<String> read
    ) {
        synchronized (byKey) {
            final var known = byKey.get(key);
            if (known != null) {
                return known;
            }
        }
        final var bpmn = read.apply(key);
        keep(key, bpmn);
        return bpmn;
    }

    /**
     * How many models it keeps.
     *
     * @return The number of models
     */
    public int size() {
        synchronized (byKey) {
            return byKey.size();
        }
    }

    private void keep(
        final long key,
        final String bpmn
    ) {
        if (bpmn == null || bpmn.length() > maximumCharacters) {
            return;
        }
        synchronized (byKey) {
            // another thread may have read and kept the same model meanwhile
            final var before = Objects.requireNonNullElse(byKey.put(key, bpmn), "");
            characters += bpmn.length() - before.length();
            final var eldest = byKey
                    .entrySet()
                    .iterator();
            while (characters > maximumCharacters) {
                final Map.Entry<Long, String> dropped = eldest.next();
                characters -= dropped
                        .getValue()
                        .length();
                eldest.remove();
            }
        }
    }

}
