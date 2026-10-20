/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.time.Instant;
import java.util.Map;

/**
 * One process instance as the engine reports it.
 *
 * @param key The process instance key
 * @param definitionKey The key of the process definition it runs
 * @param bpmnProcessId The id of the process; <code>null</code> where the engine does not say
 * @param version The version of the process definition
 * @param versionTag The <code>zenbpm:versionTag</code> of that version, <code>null</code> where it
 *            has none
 * @param businessKey The business key given at the start, <code>null</code> where none was given
 * @param state The state of the instance
 * @param variables The variables of the instance, see {@link Variables} for the Java types
 * @param parentKey The key of the instance which started this one as a sub-process, call activity
 *            or multi-instance iteration; <code>null</code> for a top-level instance
 * @param processType What started the instance
 * @param createdAt When the instance was created
 * @param incidentCount How many incidents of the instance itself are unresolved, as a list of
 *            instances tells it; <code>null</code> where the answer does not say, as the answer
 *            for one instance by its key
 */
public record ProcessInstanceRef(
    long key,
    long definitionKey,
    String bpmnProcessId,
    int version,
    String versionTag,
    String businessKey,
    State state,
    Map<String, Object> variables,
    Long parentKey,
    ProcessType processType,
    Instant createdAt,
    Integer incidentCount
) {

    /**
     * Keeps the variables unmodifiable, so the record stays as it was read. A variable may be
     * <code>null</code>, so they are not copied with {@link Map#copyOf(Map)}.
     */
    public ProcessInstanceRef {
        variables = Variables.unmodifiable(variables);
    }

    /**
     * The state of a process instance.
     */
    public enum State {

        /** The instance runs. */
        ACTIVE,

        /** The instance reached its end. */
        COMPLETED,

        /** The instance was cancelled or ended by a terminate event. */
        TERMINATED,

        /** The instance ended with a failure. */
        FAILED

    }

    /**
     * What started a process instance.
     */
    public enum ProcessType {

        /** A start of the process itself: a request, a message or a timer. */
        DEFAULT,

        /**
         * The body of one activation of a multi-instance element, which holds its iterations: the
         * jobs of a multi-instance task, the child instances of a multi-instance sub-process or
         * call activity.
         */
        MULTI_INSTANCE,

        /** An embedded or event sub-process, or one iteration of a multi-instance sub-process. */
        SUBPROCESS,

        /** A call activity. */
        CALL_ACTIVITY

    }

}
