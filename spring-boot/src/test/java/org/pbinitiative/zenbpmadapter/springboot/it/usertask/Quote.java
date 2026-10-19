/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.usertask;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of a user task and a service task left open side by side: it counts the
 * cancellations the application was told of, of each.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "USER_TASK_IT_QUOTE")
public class Quote {

    @Id
    private String id;

    private String quotingTaskId;

    private String pricingTaskId;

    private int quotingCancellations;

    private int pricingCancellations;

    /**
     * For JPA.
     */
    protected Quote() {
    }

    /**
     * A new quote.
     *
     * @param id The id
     */
    public Quote(final String id) {
        this.id = id;
    }

    /**
     * The id.
     *
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * The task id of the user task, as the notification of its creation named it.
     *
     * @return The id, <code>null</code> before the notification
     */
    public String getQuotingTaskId() {
        return quotingTaskId;
    }

    /**
     * The task id of the service task left open.
     *
     * @return The id, <code>null</code> before its delivery
     */
    public String getPricingTaskId() {
        return pricingTaskId;
    }

    /**
     * How often the application was told that the user task was cancelled.
     *
     * @return The number of notifications
     */
    public int getQuotingCancellations() {
        return quotingCancellations;
    }

    /**
     * How often the application was told that the service task was cancelled.
     *
     * @return The number of notifications
     */
    public int getPricingCancellations() {
        return pricingCancellations;
    }

    void quotingCreated(final String taskId) {
        this.quotingTaskId = taskId;
    }

    void quotingCanceled() {
        ++quotingCancellations;
    }

    void pricingDelivered(final String taskId) {
        this.pricingTaskId = taskId;
    }

    void pricingCanceled() {
        ++pricingCancellations;
    }

}
