/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim;

import org.pbinitiative.zenbpm.examples.insuranceclaim.model.Claim;

import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * What the application tells the process: a claim was reported, and an adjuster reviewed it. It is
 * the only class which knows {@link ProcessService}.
 */
@ApplicationScoped
@Transactional
public class Workflow {

    private final ProcessService<Claim> bpms;

    /**
     * Built by CDI.
     *
     * @param bpms The process service of the claims
     */
    @Inject
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
