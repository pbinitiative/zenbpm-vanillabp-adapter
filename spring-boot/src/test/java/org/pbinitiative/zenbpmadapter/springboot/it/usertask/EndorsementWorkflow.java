/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.usertask;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a user task which waits for a person while a parallel user task, which something
 * else serves, may withdraw the request at a terminate end event, which takes the first user task
 * away. It counts the notifications of the first user task and the ends of the workflow. The
 * method throws at the first cancellation of an endorsement whose id starts with
 * {@link #REFUSES_ITS_FIRST_CANCELLATION}, as a method fails for a moment.
 */
@Service
@WorkflowService(workflowAggregateClass = Endorsement.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Endorsing"))
public class EndorsementWorkflow {

    /**
     * The start of the id of an endorsement whose first cancellation the method refuses.
     */
    public static final String REFUSES_ITS_FIRST_CANCELLATION = "refusing-";

    // outside the aggregate, whose change the refusal rolls back
    private static final Set<String> REFUSED = ConcurrentHashMap.newKeySet();

    /**
     * Built by Spring.
     */
    public EndorsementWorkflow() {
    }

    /**
     * Counts that the user task was created or cancelled.
     *
     * @param endorsement The endorsement
     * @param taskId The user task
     * @param event What happened to it
     */
    @WorkflowTask(taskDefinition = "endorse")
    public void endorse(
        final Endorsement endorsement,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            if (endorsement
                    .getId()
                    .startsWith(REFUSES_ITS_FIRST_CANCELLATION) && REFUSED.add(endorsement.getId())) {
                throw new IllegalStateException("the first cancellation of %s is refused".formatted(endorsement
                        .getId()));
            }
            endorsement.canceled();
            return;
        }
        endorsement.created();
    }

    /**
     * Whether the method refused a cancellation of the endorsement.
     *
     * @param endorsementId The endorsement
     * @return <code>true</code> once it threw at its first cancellation
     */
    public static boolean refusedACancellationOf(final String endorsementId) {
        return REFUSED.contains(endorsementId);
    }

    /**
     * Counts that the workflow ended.
     *
     * @param endorsement The endorsement
     */
    @WorkflowEnded
    public void ended(final Endorsement endorsement) {
        endorsement.ended();
    }

}
