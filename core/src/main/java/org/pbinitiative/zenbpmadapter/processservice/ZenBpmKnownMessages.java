/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.util.Optional;
import java.util.function.BiFunction;

import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;

/**
 * What phase one of a correlation and of a start by a message know about the messages of a
 * workflow module: the picture the deployment remembered, see decision 42 in the repository's
 * DECISIONS.md, and only where it covers every model which may carry a name. A check which cannot
 * see every such model never refuses.
 */
final class ZenBpmKnownMessages {

    private ZenBpmKnownMessages() {
    }

    /**
     * The messages of the models, only where every model the engine holds for the module's
     * processes is known: one which is not may be the one which carries the name.
     *
     * @param factory The factory of the adapter id, which keeps the picture
     * @param workflowModuleId The workflow module
     * @param messages How the picture is read: as kept, or read again from the engine
     * @return The messages, empty where the module deployed no process in this start, where a
     *         version could not be read, or where the module serves a process it brought no model
     *         for
     */
    static Optional<ModuleMessages> everyModelOf(
        final ZenBpmClientFactory factory,
        final String workflowModuleId,
        final BiFunction<ZenBpmDeployedProcesses, String, Optional<ModuleMessages>> messages
    ) {
        final var deployed = factory.deployedProcesses();
        if (deployed.servesAProcessItDeployedNoModelFor(workflowModuleId)) {
            return Optional.empty();
        }
        return messages
                .apply(deployed, workflowModuleId)
                .filter(known -> known
                        .unread()
                        .isEmpty());
    }

    /**
     * A message name as a message names it: as the application wrote it, and as the engine holds
     * it where the two differ.
     *
     * @param messageName The name as the application wrote it
     * @param scopedName The name as the engine holds it
     * @return E.g. <code>'paid' (scoped: 'shop__paid')</code>
     */
    static String nameAsWritten(
        final String messageName,
        final String scopedName
    ) {
        return scopedName.equals(messageName)
                ? "'%s'".formatted(messageName)
                : "'%s' (scoped: '%s')".formatted(messageName, scopedName);
    }

}
