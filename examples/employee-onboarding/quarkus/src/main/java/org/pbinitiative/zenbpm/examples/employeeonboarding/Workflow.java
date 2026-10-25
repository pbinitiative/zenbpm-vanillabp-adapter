/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import org.pbinitiative.zenbpm.examples.employeeonboarding.model.Onboarding;

import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * What the application tells the process: a contract was signed, and the welcome meeting held. It
 * is the only class which knows {@link ProcessService}.
 */
@ApplicationScoped
@Transactional
public class Workflow {

    private final ProcessService<Onboarding> bpms;

    /**
     * Built by CDI.
     *
     * @param bpms The process service of the onboardings
     */
    @Inject
    public Workflow(final ProcessService<Onboarding> bpms) {
        this.bpms = bpms;
    }

    /**
     * A contract was signed. VanillaBP saves the onboarding and starts the process
     * <code>employee_onboarding</code>, the primary process of the workflow service; the called
     * process is never started on its own.
     *
     * @param onboarding The onboarding
     */
    public void contractSigned(final Onboarding onboarding) {
        bpms.startWorkflow(onboarding);
    }

    /**
     * HR held the welcome meeting, which completes the user task.
     *
     * @param onboarding The onboarding
     * @param taskId The id of the user task
     */
    public void welcomeMeetingHeld(
        final Onboarding onboarding,
        final String taskId
    ) {
        bpms.completeUserTask(onboarding, taskId);
    }

}
