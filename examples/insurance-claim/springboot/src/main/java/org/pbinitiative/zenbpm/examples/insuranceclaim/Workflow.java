/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim;

import org.pbinitiative.zenbpm.examples.insuranceclaim.model.Claim;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.process.ProcessService;

/**
 * What the application tells the process: a claim was reported, and an adjuster reviewed it. It is
 * the only class which knows {@link ProcessService}.
 */
@Component
@Transactional
public class Workflow {

    private final ProcessService<Claim> bpms;

    /**
     * Built by Spring.
     *
     * @param bpms The process service of the claims
     */
    public Workflow(final ProcessService<Claim> bpms) {
        this.bpms = bpms;
    }

    /**
     * A claim was reported. VanillaBP saves it and starts its workflow, whose first step is the
     * decision table.
     *
     * @param claim The claim
     */
    public void claimReported(final Claim claim) {
        bpms.startWorkflow(claim);
    }

    /**
     * An adjuster reviewed the claim, which completes the user task. The adapter sends the shared
     * members of the claim with the completion, so the gateway behind the review reads the answer.
     *
     * @param claim The claim
     * @param taskId The id of the review
     */
    public void claimReviewed(
        final Claim claim,
        final String taskId
    ) {
        bpms.completeUserTask(claim, taskId);
    }

}
