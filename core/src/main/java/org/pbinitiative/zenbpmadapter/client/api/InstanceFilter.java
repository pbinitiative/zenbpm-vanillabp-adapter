/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.time.Instant;

/**
 * Which process instances a search asks for (<code>GET /process-instances</code>). Every criterion
 * left <code>null</code> matches every instance. Start with {@link #anyInstance()} and narrow it
 * down; every <code>with</code> method answers a new filter.
 *
 * @param businessKey Only instances started with this business key
 * @param bpmnProcessId Only instances of this process, in every version
 * @param processDefinitionKey Only instances of this one version
 * @param state Only instances in this state
 * @param createdFrom Only instances created at or after this moment
 * @param page The page to read, counted from 1
 * @param size How many items a page holds per partition of the engine, 1 to
 *            {@value #MAXIMUM_SIZE}
 */
public record InstanceFilter(
    String businessKey,
    String bpmnProcessId,
    Long processDefinitionKey,
    ProcessInstanceRef.State state,
    Instant createdFrom,
    int page,
    int size
) {

    /**
     * The largest page the engine hands out.
     */
    public static final int MAXIMUM_SIZE = 100;

    /**
     * Refuses a page or a size the engine would refuse.
     */
    public InstanceFilter {

        PageRange.check(page, size, MAXIMUM_SIZE);

    }

    /**
     * Every instance, the first page, {@value #MAXIMUM_SIZE} items per partition.
     *
     * @return The filter
     */
    public static InstanceFilter anyInstance() {

        return new InstanceFilter(null, null, null, null, null, 1, MAXIMUM_SIZE);

    }

    /**
     * This filter, narrowed to one business key.
     *
     * @param businessKey The business key
     * @return A new filter
     */
    public InstanceFilter withBusinessKey(
        final String businessKey
    ) {

        return new InstanceFilter(businessKey, bpmnProcessId, processDefinitionKey, state, createdFrom, page, size);

    }

    /**
     * This filter, narrowed to one process in every version.
     *
     * @param bpmnProcessId The id of the process
     * @return A new filter
     */
    public InstanceFilter withBpmnProcessId(
        final String bpmnProcessId
    ) {

        return new InstanceFilter(businessKey, bpmnProcessId, processDefinitionKey, state, createdFrom, page, size);

    }

    /**
     * This filter, narrowed to one version of a process.
     *
     * @param processDefinitionKey The key of the process definition
     * @return A new filter
     */
    public InstanceFilter withProcessDefinitionKey(
        final Long processDefinitionKey
    ) {

        return new InstanceFilter(businessKey, bpmnProcessId, processDefinitionKey, state, createdFrom, page, size);

    }

    /**
     * This filter, narrowed to one state.
     *
     * @param state The state
     * @return A new filter
     */
    public InstanceFilter withState(
        final ProcessInstanceRef.State state
    ) {

        return new InstanceFilter(businessKey, bpmnProcessId, processDefinitionKey, state, createdFrom, page, size);

    }

    /**
     * This filter, narrowed to instances created at or after a moment.
     *
     * @param createdFrom The moment
     * @return A new filter
     */
    public InstanceFilter withCreatedFrom(
        final Instant createdFrom
    ) {

        return new InstanceFilter(businessKey, bpmnProcessId, processDefinitionKey, state, createdFrom, page, size);

    }

    /**
     * This filter, asking for another page.
     *
     * @param page The page, counted from 1
     * @return A new filter
     */
    public InstanceFilter withPage(
        final int page
    ) {

        return new InstanceFilter(businessKey, bpmnProcessId, processDefinitionKey, state, createdFrom, page, size);

    }

    /**
     * This filter, with another page size.
     *
     * @param size How many items a page holds per partition, 1 to {@value #MAXIMUM_SIZE}
     * @return A new filter
     */
    public InstanceFilter withSize(
        final int size
    ) {

        return new InstanceFilter(businessKey, bpmnProcessId, processDefinitionKey, state, createdFrom, page, size);

    }

}
