/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.time.Duration;
import java.util.Optional;

/**
 * How long the engine waits before it hands the job of a handler which threw out again: the most
 * specific of four levels which sets <code>retry-backoff</code> wins. The platform modules
 * implement it on top of their view of the configuration:
 *
 * <pre>
 * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.tasks.&lt;task definition&gt;.adapters.&lt;id&gt;.retry-backoff
 * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.adapters.&lt;id&gt;.retry-backoff
 * vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.retry-backoff
 * vanillabp.adapters.&lt;id&gt;.retry-backoff
 * </pre>
 *
 * Where no level sets it, the failure carries no backoff, and the engine applies the
 * <code>retryBackoff</code> of the task's model, else its own default: a configured value would
 * override what the modeller wrote, so the adapter has no default of its own. Unlike
 * <code>job-timeout</code>, the backoff travels with each failure, so two tasks of one job type may
 * have different ones.
 */
@FunctionalInterface
public interface ZenBpmRetryBackoffResolver {

    /**
     * A resolver which answers no backoff for every task, for a deployment service built without a
     * configuration.
     */
    ZenBpmRetryBackoffResolver NONE = (
        workflowModuleId,
        bpmnProcessId,
        taskDefinition) -> Optional.empty();

    /**
     * How long the engine waits before it hands the job of a task out again after its handler
     * threw.
     *
     * @param workflowModuleId The workflow module of the task
     * @param bpmnProcessId The plain id of the process of the task
     * @param taskDefinition The plain task definition of the task
     * @return The most specific configured duration; empty where no level sets one
     */
    Optional<Duration> retryBackoffFor(
        String workflowModuleId,
        String bpmnProcessId,
        String taskDefinition
    );

}
