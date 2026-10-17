/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.time.Duration;

import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;

/**
 * How long a job of a task stays locked to the node which received it: the most specific of four
 * levels which sets <code>job-timeout</code> wins, else the adapter's default
 * ({@value ZenBpmAdapterConfiguration#DEFAULT_JOB_TIMEOUT_ISO}). The platform modules implement it
 * on top of their view of the configuration:
 *
 * <pre>
 * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.tasks.&lt;task definition&gt;.adapters.&lt;id&gt;.job-timeout
 * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.adapters.&lt;id&gt;.job-timeout
 * vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.job-timeout
 * vanillabp.adapters.&lt;id&gt;.job-timeout
 * </pre>
 */
@FunctionalInterface
public interface ZenBpmJobTimeoutResolver {

    /**
     * A resolver which answers the default for every task, for a deployment service built without
     * a configuration.
     */
    ZenBpmJobTimeoutResolver DEFAULT = (
        workflowModuleId,
        bpmnProcessId,
        taskDefinition) -> ZenBpmAdapterConfiguration.DEFAULT_JOB_TIMEOUT;

    /**
     * How long a job of a task stays locked to the node which received it.
     *
     * @param workflowModuleId The workflow module of the task
     * @param bpmnProcessId The plain id of the process of the task
     * @param taskDefinition The plain task definition of the task
     * @return The most specific configured duration, else the default; never <code>null</code>
     */
    Duration jobTimeoutFor(
        String workflowModuleId,
        String bpmnProcessId,
        String taskDefinition
    );

}
