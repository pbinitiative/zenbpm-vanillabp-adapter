/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

/**
 * One version of a deployed process.
 *
 * @param key The process definition key
 * @param bpmnProcessId The id of the process in the BPMN file
 * @param version The version, counted per <code>bpmnProcessId</code> from 1
 * @param versionTag The <code>zenbpm:versionTag</code> of the process, <code>null</code> where it
 *            has none
 * @param bpmnData The BPMN file as the engine stores it; <code>null</code> in a list of
 *            definitions, which does not carry it
 */
public record ProcessDefinitionRef(
    long key,
    String bpmnProcessId,
    int version,
    String versionTag,
    String bpmnData
) {
}
