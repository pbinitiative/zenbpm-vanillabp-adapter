/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The JSON the engine reads and writes, named as the schemas of the pinned copy of the engine's
 * <code>api.yaml</code> name it, with the fields the adapter uses. A field the schema requires is
 * required here too, so an answer without it, or with <code>null</code> in it, fails to decode
 * instead of reading as 0 or <code>null</code>. A whole number with a fraction fails as well.
 * These types never leave {@link ZenBpmRestClient}, which turns them into the records of the
 * <code>api</code> package.
 */
final class ZenBpmWire {

    private ZenBpmWire() {
    }

    record Error(
        String code,
        String message
    ) {
    }

    record Health(
        @JsonProperty(required = true) String status,
        List<String> reasons
    ) {
    }

    record SystemStatus(
        @JsonProperty(required = true) Git git,
        @JsonProperty(required = true) Build build,
        @JsonProperty(required = true) Map<String, Object> nodes,
        Map<String, Object> partitions
    ) {
    }

    record Git(@JsonProperty(required = true) String commitId) {
    }

    record Build(@JsonProperty(required = true) String version) {
    }

    record ProcessDefinitionCreated(@JsonProperty(required = true) long processDefinitionKey) {
    }

    record DmnResourceDefinitionCreated(@JsonProperty(required = true) long dmnResourceDefinitionKey) {
    }

    record ProcessDefinition(
        @JsonProperty(required = true) long key,
        @JsonProperty(required = true) int version,
        @JsonProperty(required = true) String bpmnProcessId,
        String versionTag,
        String bpmnData
    ) {
    }

    record ProcessDefinitionsPage(
        @JsonProperty(required = true) List<ProcessDefinition> items,
        @JsonProperty(required = true) long totalCount
    ) {
    }

    record ElementStatisticsPartitions(@JsonProperty(required = true) List<PartitionElementStatistics> partitions) {
    }

    record PartitionElementStatistics(@JsonProperty(required = true) Map<String, ElementStatisticCounts> items) {
    }

    record ElementStatisticCounts(
        @JsonProperty(required = true) long activeCount,
        @JsonProperty(required = true) long incidentCount
    ) {
    }

    record ProcessInstance(
        @JsonProperty(required = true) long key,
        @JsonProperty(required = true) long processDefinitionKey,
        String bpmnProcessId,
        @JsonProperty(required = true) int version,
        String versionTag,
        String businessKey,
        @JsonProperty(required = true) String createdAt,
        @JsonProperty(required = true) String state,
        Long parentProcessInstanceKey,
        @JsonProperty(required = true) String processType,
        @JsonProperty(required = true) Map<String, Object> variables
    ) {
    }

    record ProcessInstanceTokens(@JsonProperty(required = true) List<ElementInstance> activeElementInstances) {
    }

    record ElementInstance(@JsonProperty(required = true) String elementId) {
    }

    record ProcessInstancePage(
        @JsonProperty(required = true) List<PartitionProcessInstances> partitions,
        @JsonProperty(required = true) long totalCount
    ) {
    }

    record PartitionProcessInstances(
        @JsonProperty(required = true) long totalCount,
        @JsonProperty(required = true) List<ProcessInstance> items
    ) implements Partition<ProcessInstance> {
    }

    record Job(
        @JsonProperty(required = true) long key,
        @JsonProperty(required = true) String elementId,
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) String elementType,
        @JsonProperty(required = true) long processInstanceKey,
        @JsonProperty(required = true) long elementInstanceKey,
        @JsonProperty(required = true) String state,
        @JsonProperty(required = true) String createdAt,
        @JsonProperty(required = true) Map<String, Object> inputVariables,
        String assignee
    ) {
    }

    record JobPartitionPage(
        @JsonProperty(required = true) List<PartitionJobs> partitions,
        @JsonProperty(required = true) long totalCount
    ) {
    }

    record PartitionJobs(
        @JsonProperty(required = true) long totalCount,
        @JsonProperty(required = true) List<Job> items
    ) implements Partition<Job> {
    }

    record FlowElementHistory(
        @JsonProperty(required = true) long key,
        @JsonProperty(required = true) long processInstanceKey,
        @JsonProperty(required = true) String createdAt,
        String completedAt,
        @JsonProperty(required = true) String elementId,
        @JsonProperty(required = true) String elementType
    ) {
    }

    record FlowElementHistoryPage(
        List<FlowElementHistory> items,
        @JsonProperty(required = true) long totalCount
    ) {
    }

    record LockExtended(@JsonProperty(required = true) String lockUntil) {
    }

    record CreateProcessInstance(
        String bpmnProcessId,
        Long processDefinitionKey,
        String businessKey,
        Map<String, Object> variables,
        String historyTimeToLive
    ) {
    }

    record UpdateVariables(Map<String, Object> variables) {
    }

    record CompleteJob(Map<String, Object> variables) {
    }

    record FailJob(
        String errorCode,
        String message,
        Map<String, Object> variables,
        Integer retries,
        String retryBackoff,
        Long deliveryToken,
        String clientId
    ) {
    }

    record PublishMessage(
        String messageName,
        String correlationKey,
        Map<String, Object> variables
    ) {
    }

    record ExtendJobLock(
        String clientId,
        String lockDuration,
        Long deliveryToken
    ) {
    }

    /**
     * One partition's share of a search over the engine's partitions.
     */
    interface Partition<T> {

        long totalCount();

        List<T> items();

    }

}
