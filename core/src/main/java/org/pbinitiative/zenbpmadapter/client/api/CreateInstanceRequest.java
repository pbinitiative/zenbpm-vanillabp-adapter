/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.util.Map;

/**
 * What a start of a process instance sends (<code>POST /process-instances</code>). Start with
 * {@link #latestVersionOf(String)} or {@link #version(long)}; every <code>with</code> method answers
 * a new request.
 *
 * @param bpmnProcessId Start the latest version of this process; <code>null</code> where
 *            <code>processDefinitionKey</code> names the version
 * @param processDefinitionKey Start this version; <code>null</code> for the latest version of
 *            <code>bpmnProcessId</code>
 * @param businessKey The business key of the new instance, <code>null</code> for none
 * @param variables The variables of the new instance, <code>null</code> for none
 * @param historyTimeToLive How long the engine keeps the instance after it ended, in the engine's
 *            own form (<code>30d</code>, <code>1d8h</code>); <code>null</code> for the engine's
 *            default
 */
public record CreateInstanceRequest(
    String bpmnProcessId,
    Long processDefinitionKey,
    String businessKey,
    Map<String, Object> variables,
    String historyTimeToLive
) {

    /**
     * Refuses a request which names no process: the engine would answer it with 400.
     */
    public CreateInstanceRequest {

        if ((bpmnProcessId == null) && (processDefinitionKey == null)) {
            throw new IllegalArgumentException("A start names the process or the version of a process to start");
        }

    }

    /**
     * A start of the latest version of a process.
     *
     * @param bpmnProcessId The id of the process
     * @return The request
     */
    public static CreateInstanceRequest latestVersionOf(
        final String bpmnProcessId
    ) {

        return new CreateInstanceRequest(bpmnProcessId, null, null, null, null);

    }

    /**
     * A start of one version of a process.
     *
     * @param processDefinitionKey The key of the process definition
     * @return The request
     */
    public static CreateInstanceRequest version(
        final long processDefinitionKey
    ) {

        return new CreateInstanceRequest(null, processDefinitionKey, null, null, null);

    }

    /**
     * This request, with a business key.
     *
     * @param businessKey The business key
     * @return A new request
     */
    public CreateInstanceRequest withBusinessKey(
        final String businessKey
    ) {

        return new CreateInstanceRequest(bpmnProcessId, processDefinitionKey, businessKey, variables, historyTimeToLive);

    }

    /**
     * This request, with variables.
     *
     * @param variables The variables
     * @return A new request
     */
    public CreateInstanceRequest withVariables(
        final Map<String, Object> variables
    ) {

        return new CreateInstanceRequest(bpmnProcessId, processDefinitionKey, businessKey, variables, historyTimeToLive);

    }

    /**
     * This request, with a time to live of the finished instance.
     *
     * @param historyTimeToLive The time to live in the engine's form, e.g. <code>30d</code>
     * @return A new request
     */
    public CreateInstanceRequest withHistoryTimeToLive(
        final String historyTimeToLive
    ) {

        return new CreateInstanceRequest(bpmnProcessId, processDefinitionKey, businessKey, variables, historyTimeToLive);

    }

}
