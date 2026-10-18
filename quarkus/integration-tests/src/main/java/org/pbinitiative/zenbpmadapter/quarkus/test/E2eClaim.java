/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process <code>TaskOutcomes</code>: a claim which is inspected, and
 * the inspection raises a BPMN error; or one whose approval the application completes later. It
 * shares only what the gateways of the model decide on.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_CLAIM")
public class E2eClaim {

    /**
     * The scenario of a claim whose approval stays open.
     */
    public static final String PENDING = "pending";

    /**
     * The scenario of a claim whose inspection raises a BPMN error.
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
     * Built by JPA.
     */
    protected E2eClaim() {
    }

    /**
     * A claim which is not saved yet.
     *
     * @param id The id, which becomes the business key of its workflow
     * @param scenario {@link #ERROR} or {@link #PENDING}
     * @param errorCode The code of the BPMN error the inspection raises
     */
    public E2eClaim(
        final String id,
        final String scenario,
        final String errorCode
    ) {
        this.id = id;
        this.scenario = scenario;
        this.errorCode = errorCode;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return {@link #ERROR} or {@link #PENDING}
     */
    public String getScenario() {
        return scenario;
    }

    /**
     * @return Whether the inspection found the claim refundable
     */
    public boolean isRefundable() {
        return refundable;
    }

    /**
     * @return Whether the application approved the claim
     */
    public boolean isApproved() {
        return approved;
    }

    /**
     * @return The code of the BPMN error the inspection raises
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * @return Whether the claim was inspected
     */
    public boolean isInspected() {
        return inspected;
    }

    /**
     * @return How often the handler of the approval asked for it, as committed
     */
    public int getApprovalRequests() {
        return approvalRequests;
    }

    /**
     * @return The task the approval completes, <code>null</code> before it was delivered
     */
    public String getApprovalTaskId() {
        return approvalTaskId;
    }

    /**
     * Approves the claim, before the application completes the approval.
     */
    public void approve() {
        approved = true;
    }

    void inspect() {
        inspected = true;
        refundable = true;
    }

    void requestApproval(final String taskId) {
        approvalTaskId = taskId;
        ++approvalRequests;
    }

}
