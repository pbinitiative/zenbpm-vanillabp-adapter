/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import org.pbinitiative.zenbpm.examples.employeeonboarding.model.Onboarding;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * What the two processes tell the application. The workflow service names
 * <code>employee_onboarding</code> as its process and <code>workplace_provisioning</code>, which the
 * call activity calls, as a secondary one: both work on the same onboarding, and the tasks of the
 * called process are served here like those of the caller.
 */
@ApplicationScoped
@WorkflowService(
        workflowAggregateClass = Onboarding.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = "employee_onboarding"),
        secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "workplace_provisioning"))
public class WorkflowTaskHandler {

    private final Onboardings onboardings;

    /**
     * Built by CDI.
     *
     * @param onboardings The business service
     */
    @Inject
    public WorkflowTaskHandler(final Onboardings onboardings) {
        this.onboardings = onboardings;
    }

    /**
     * The service task "Create the accounts" of the onboarding.
     *
     * @param onboarding The onboarding
     */
    @WorkflowTask
    public void createAccounts(final Onboarding onboarding) {
        onboardings.createAccounts(onboarding);
    }

    /**
     * The service task "Order the laptop" of the called workplace provisioning.
     *
     * @param onboarding The onboarding
     */
    @WorkflowTask
    public void orderLaptop(final Onboarding onboarding) {
        onboardings.orderLaptop(onboarding);
    }

    /**
     * The service task "Issue the badge" of the called workplace provisioning, at the same time as
     * the laptop is ordered.
     *
     * @param onboarding The onboarding
     */
    @WorkflowTask
    public void issueBadge(final Onboarding onboarding) {
        onboardings.issueBadge(onboarding);
    }

    /**
     * The user task "Hold the welcome meeting" of HR.
     *
     * @param onboarding The onboarding
     * @param taskId The id of the user task
     * @param event Whether it was created or taken away
     */
    @WorkflowTask
    public void holdWelcomeMeeting(
        final Onboarding onboarding,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            onboardings.welcomeMeetingClosed(onboarding);
            return;
        }
        onboardings.welcomeMeetingDue(onboarding, taskId);
    }

    /**
     * The onboarding reached its end event. The adapter puts a marker task in front of the end
     * event, because the engine tells nobody that a workflow ended, and the marker calls this
     * method.
     *
     * @param onboarding The onboarding
     */
    @WorkflowEnded
    public void onboarded(final Onboarding onboarding) {
        onboardings.onboarded(onboarding);
    }

}
