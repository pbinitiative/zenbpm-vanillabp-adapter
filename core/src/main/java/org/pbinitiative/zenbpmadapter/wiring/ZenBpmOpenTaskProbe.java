/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.util.Set;

import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;

import io.vanillabp.integration.adapter.spi.workflowtask.OpenTaskProbe;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskExistence;
import lombok.extern.slf4j.Slf4j;

/**
 * Tells the platform's core whether the engine still holds a task the core believes is open, for
 * the tasks of one process of a workflow module. The engine tells nobody about a job it took away:
 * an interrupting boundary event, a terminate end event or an instance cancelled over its API end a
 * job without a word. So after every delivery whose outcome reached the engine, the core asks this
 * probe about the other tasks it still believes open in the same workflow, and reports the ones
 * which are gone as <code>CANCELED</code>.
 * <p>
 * One question is one read of the job (<code>GET /v1/jobs/{key}</code>), which changes nothing at
 * the engine, and, for an instance the instance cache does not hold yet, of the instances up to its
 * workflow:
 * <ul>
 * <li>a job the engine does not hold, or one which was completed or terminated, is
 * {@link TaskExistence#GONE};</li>
 * <li>an active job is {@link TaskExistence#STILL_THERE}, and so is a failed one, which waits for
 * its incident to be resolved;</li>
 * <li>everything else is {@link TaskExistence#CANNOT_SAY}: an engine which could not be asked or
 * answered something else, an id which is no key, a job of another workflow, and a task this
 * probe is not asked about. A failure is never read as gone, because the core would report every
 * open task of a workflow as cancelled at the engine's next hiccup.</li>
 * </ul>
 * Only a task served by a job of the job stream is asked about: a task definition the process
 * subscribes as a job type. A user task, and anything else, is {@link TaskExistence#CANNOT_SAY},
 * because its cancellation reaches the application another way, and a task reported twice is
 * worse than one reported late.
 * <p>
 * A read is answered by a node of the engine which may lag behind its partition's leader, so a job
 * created a moment before it is asked about may be answered as gone. The core asks only about a
 * task delivered before, so the job was there for at least as long as that delivery took.
 */
@Slf4j
public final class ZenBpmOpenTaskProbe implements OpenTaskProbe {

    private final String adapterId;

    private final ZenBpmRestClient rest;

    private final ZenBpmInstanceCache instances;

    private final Set<String> jobTaskDefinitions;

    /**
     * The probe of the tasks of one process. Contacts nothing.
     *
     * @param adapterId The adapter id, for the messages
     * @param rest The REST client of the adapter id
     * @param instances The instance cache of the adapter id, which names the workflow of an
     *            instance
     * @param jobTaskDefinitions The task definitions, as the application knows them, which the
     *            process serves through jobs of the job stream
     */
    public ZenBpmOpenTaskProbe(
        final String adapterId,
        final ZenBpmRestClient rest,
        final ZenBpmInstanceCache instances,
        final Set<String> jobTaskDefinitions
    ) {
        this.adapterId = adapterId;
        this.rest = rest;
        this.instances = instances;
        this.jobTaskDefinitions = Set.copyOf(jobTaskDefinitions);
    }

    /**
     * A question without a task definition, which the core does not ask: without it the task may
     * be a user task.
     *
     * @return {@link TaskExistence#CANNOT_SAY}
     */
    @Override
    public TaskExistence stillExists(
        final String workflowId,
        final String taskId
    ) {
        return stillExists(workflowId, taskId, null);
    }

    @Override
    public TaskExistence stillExists(
        final String workflowId,
        final String taskId,
        final String taskDefinition
    ) {
        if ((taskDefinition == null) || !jobTaskDefinitions.contains(taskDefinition)) {
            return TaskExistence.CANNOT_SAY;
        }
        final long workflowKey;
        final long jobKey;
        try {
            workflowKey = Long.parseLong(workflowId);
            jobKey = Long.parseLong(taskId);
        } catch (final NumberFormatException e) {
            return TaskExistence.CANNOT_SAY;
        }
        try {
            return rest
                    .job(jobKey)
                    .map(job -> existenceOf(job, workflowKey))
                    .orElse(TaskExistence.GONE);
        } catch (final RuntimeException e) {
            log.debug("ZenBPM[{}]: the engine did not say whether it still holds job {} (task '{}'): {}", adapterId,
                    jobKey, taskDefinition, e.getMessage());
            return TaskExistence.CANNOT_SAY;
        }
    }

    private TaskExistence existenceOf(
        final JobRef job,
        final long workflowKey
    ) {
        // a key of this engine belongs to one workflow, and an answer about another one would be
        // about a task the core did not mean
        final var workflowOfTheJob = instances.workflowIdOf(job.instanceKey());
        if (workflowOfTheJob.isEmpty() || (workflowOfTheJob.getAsLong() != workflowKey)) {
            return TaskExistence.CANNOT_SAY;
        }
        return switch (job.state()) {
            case ACTIVE, FAILED -> TaskExistence.STILL_THERE;
            case COMPLETED, TERMINATED -> TaskExistence.GONE;
        };
    }

}
