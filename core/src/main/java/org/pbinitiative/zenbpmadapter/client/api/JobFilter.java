/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

/**
 * Which jobs a search asks for (<code>GET /jobs</code>). Every criterion left <code>null</code>
 * matches every job. Start with {@link #anyJob()} and narrow it down; every <code>with</code>
 * method answers a new filter.
 *
 * @param jobType Only jobs of this type
 * @param state Only jobs in this state
 * @param processInstanceKey Only jobs of this process instance
 * @param page The page to read, counted from 1
 * @param size How many items a page holds per partition of the engine, 1 to
 *            {@value #MAXIMUM_SIZE}
 */
public record JobFilter(
    String jobType,
    JobRef.State state,
    Long processInstanceKey,
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
    public JobFilter {

        PageRange.check(page, size, MAXIMUM_SIZE);

    }

    /**
     * Every job, the first page, {@value #MAXIMUM_SIZE} items per partition.
     *
     * @return The filter
     */
    public static JobFilter anyJob() {

        return new JobFilter(null, null, null, 1, MAXIMUM_SIZE);

    }

    /**
     * This filter, narrowed to one job type.
     *
     * @param jobType The job type
     * @return A new filter
     */
    public JobFilter withJobType(
        final String jobType
    ) {

        return new JobFilter(jobType, state, processInstanceKey, page, size);

    }

    /**
     * This filter, narrowed to one state.
     *
     * @param state The state
     * @return A new filter
     */
    public JobFilter withState(
        final JobRef.State state
    ) {

        return new JobFilter(jobType, state, processInstanceKey, page, size);

    }

    /**
     * This filter, narrowed to the jobs of one process instance.
     *
     * @param processInstanceKey The key of the process instance
     * @return A new filter
     */
    public JobFilter withProcessInstanceKey(
        final Long processInstanceKey
    ) {

        return new JobFilter(jobType, state, processInstanceKey, page, size);

    }

    /**
     * This filter, asking for another page.
     *
     * @param page The page, counted from 1
     * @return A new filter
     */
    public JobFilter withPage(
        final int page
    ) {

        return new JobFilter(jobType, state, processInstanceKey, page, size);

    }

    /**
     * This filter, with another page size.
     *
     * @param size How many items a page holds per partition, 1 to {@value #MAXIMUM_SIZE}
     * @return A new filter
     */
    public JobFilter withSize(
        final int size
    ) {

        return new JobFilter(jobType, state, processInstanceKey, page, size);

    }

}
