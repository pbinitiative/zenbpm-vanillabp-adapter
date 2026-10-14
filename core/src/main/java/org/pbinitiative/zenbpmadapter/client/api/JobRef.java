/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.time.Instant;
import java.util.Map;

/**
 * One job as the engine reports it: the work a service, send or business rule task or a user
 * task waits for.
 *
 * @param key The job key
 * @param type The job type, which routes the job to a worker
 * @param state The state of the job
 * @param elementId The id of the BPMN element which created the job
 * @param elementType The type of that element, e.g. <code>SERVICE_TASK</code>
 * @param elementInstanceKey The key of the element instance which created the job
 * @param instanceKey The key of the process instance the job belongs to. For a job inside a
 *            sub-process or a multi-instance element it is the instance of that scope, not the
 *            top-level one.
 * @param assignee The user the job is assigned to, <code>null</code> where nobody is
 * @param inputVariables The variables the job was created with: those of its scope at that
 *            moment, with the targets of its element's input mappings set on top. See
 *            {@link Variables} for the Java types.
 * @param createdAt When the job was created
 */
public record JobRef(
    long key,
    String type,
    State state,
    String elementId,
    String elementType,
    long elementInstanceKey,
    long instanceKey,
    String assignee,
    Map<String, Object> inputVariables,
    Instant createdAt
) {

    /**
     * Keeps the input variables unmodifiable, so the record stays as it was read.
     */
    public JobRef {
        inputVariables = Variables.unmodifiable(inputVariables);
    }

    /**
     * The state of a job.
     */
    public enum State {

        /** The job waits for a worker. */
        ACTIVE,

        /** A worker completed the job. */
        COMPLETED,

        /** The job's element was left without the job: the instance was cancelled, a boundary event interrupted it. */
        TERMINATED,

        /** The job failed with an incident. */
        FAILED

    }

}
