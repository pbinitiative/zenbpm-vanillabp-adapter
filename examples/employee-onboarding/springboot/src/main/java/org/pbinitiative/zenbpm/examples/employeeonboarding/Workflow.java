/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import org.pbinitiative.zenbpm.examples.employeeonboarding.model.Onboarding;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.process.ProcessService;

/**
 * What the application tells the process: a contract was signed, and the welcome meeting held. It
 * is the only class which knows {@link ProcessService}.
 */
@Component
@Transactional
public class Workflow {

    private final ProcessService<Onboarding> bpms;

    /**
     * Built by Spring.
     *
     * @param bpms The process service of the onboardings
     */
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
