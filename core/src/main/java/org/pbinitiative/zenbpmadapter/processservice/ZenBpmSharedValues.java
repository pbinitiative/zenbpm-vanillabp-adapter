/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The variables every command of this adapter writes into the engine on behalf of a workflow: the
 * values the aggregate shares, and the aggregate's id under the name of its id attribute.
 * <p>
 * The id is written whatever the sync model says, so an aggregate which shares nothing
 * (<code>@NoSyncWithBPMS</code>) still carries it. The correlation keys this adapter writes into a
 * model read the id from that variable, and the engine finds a workflow by its business key, which
 * is the same id. It is written as text, because the engine refuses a correlation key which is no
 * text, and it is written last, so an attribute of the same name cannot hide it.
 */
public final class ZenBpmSharedValues {

    private ZenBpmSharedValues() {
    }

    /**
     * The variables of a command about one workflow.
     *
     * @param aggregateIdName The name of the aggregate's id attribute
     * @param aggregateId The aggregate's id
     * @param sharedValues What the sync model shares of the aggregate, empty where it shares
     *            nothing
     * @return The variables, the id last; they cannot be changed
     */
    public static Map<String, Object> forCommand(
        final String aggregateIdName,
        final Object aggregateId,
        final Map<String, Object> sharedValues
    ) {
        final var variables = new LinkedHashMap<String, Object>(sharedValues);
        // removed first, so the id is the last entry also where an attribute has its name
        variables.remove(aggregateIdName);
        variables.put(aggregateIdName, String.valueOf(aggregateId));
        return Collections.unmodifiableMap(variables);
    }

}
