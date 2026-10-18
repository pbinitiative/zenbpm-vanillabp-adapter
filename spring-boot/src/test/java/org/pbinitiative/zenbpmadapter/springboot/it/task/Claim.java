/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.task;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the outcomes other than a completion: a BPMN error, raised with the code
 * the claim names, and a task left open for its asynchronous completion, which the application
 * completes or cancels later. It shares which of the two the workflow runs, whether the claim is
 * refundable, which the path of the error boundary event decides on, and whether it was approved,
 * which the gateway behind the task left open decides on.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "TASK_IT_CLAIM")
public class Claim {

    /**
     * The scenario of a task left open; every other scenario raises a BPMN error.
     */
    public static final String PENDING = "pending";

    /**
     * The scenario of a BPMN error.
     */
    public static final String ERROR = "error";

    @Id
    private String id;

    @SyncWithBPMS
    private String scenario;

    @SyncWithBPMS
    private boolean refundable;

    @SyncWithBPMS
    private boolean approved;

    private String errorCode;

    private boolean inspected;

    private int approvalRequests;

    private String approvalTaskId;

    /**
     * For JPA.
     */
    protected Claim() {
    }

    /**
     * A new claim.
     *
     * @param id The id
     * @param scenario {@link #ERROR} or {@link #PENDING}
     * @param errorCode The code of the BPMN error the inspection raises
     */
    public Claim(
        final String id,
        final String scenario,
        final String errorCode
    ) {
        this.id = id;
        this.scenario = scenario;
        this.errorCode = errorCode;
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
     * Which outcome the workflow runs.
     *
     * @return {@link #ERROR} or {@link #PENDING}
     */
    public String getScenario() {
        return scenario;
    }

    /**
     * Whether the inspection found the claim refundable, which the path of the error boundary event
     * decides on.
     *
     * @return Whether it is refundable
     */
    public boolean isRefundable() {
        return refundable;
    }

    /**
     * Whether the application approved the claim, which the gateway behind the task left open
     * decides on.
     *
     * @return Whether it is approved
     */
    public boolean isApproved() {
        return approved;
    }

    /**
     * Approves the claim, before the application completes the task left open.
     */
    public void approve() {
        this.approved = true;
    }

    /**
     * The code of the BPMN error the inspection raises.
     *
     * @return The code
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Whether the inspection ran and its work was committed.
     *
     * @return Whether it ran
     */
    public boolean isInspected() {
        return inspected;
    }

    /**
     * How often the handler of the task left open ran.
     *
     * @return The number of runs
     */
    public int getApprovalRequests() {
        return approvalRequests;
    }

    /**
     * The task id the handler of the task left open was given.
     *
     * @return The id, <code>null</code> before it ran
     */
    public String getApprovalTaskId() {
        return approvalTaskId;
    }

    void inspect() {
        this.inspected = true;
        // what the gateway behind the error boundary event decides on: the workflow started with false
        this.refundable = true;
    }

    void requestApproval(final String taskId) {
        ++approvalRequests;
        this.approvalTaskId = taskId;
    }

}
