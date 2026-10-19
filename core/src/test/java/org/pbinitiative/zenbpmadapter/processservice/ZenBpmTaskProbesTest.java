/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The probes which ask for a task and for a user task, each finding a job of its own kind only, and
 * the count of the open tasks of a process. The engine is a fake which answers what each test tells
 * it to and writes down what it was asked, so every test also sees that a probe only reads. The
 * module is scoped with a prefix, so the scope's process is <code>shop__Order</code> at the engine.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmTaskProbesTest {

    private static final WorkflowScope SCOPE = WorkflowScope.of("shop", "Order");

    /**
     * A key of partition 1, the one partition the fake engine reports: a key carries its partition
     * in bits 12 to 21.
     */
    private static final long JOB = (1L << 52) | (1L << 12) | 600;

    private static final long INSTANCE = 4503599627370510L;

    private static final String JOB_PATH = "/v1/jobs/%d".formatted(JOB);

    private static final String INSTANCE_PATH = "/v1/process-instances/%d".formatted(INSTANCE);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FakeEngine engine = FakeEngine.started();

    private final ZenBpmClientFactory factory = configured(engine.address());

    private final ZenBpmProcessService<Object> processService = processService(factory);

    @AfterEach
    void stopTheEngine() {
        factory.close();
        engine.close();
    }

    @ParameterizedTest(name = "a job which is {0}")
    @ValueSource(strings = {
            "active", "failed", "completed", "terminated"
    })
    @DisplayName("A job of the scope is active while it waits or carries an incident and completed once it ended, for the probe of a task and for the probe of a user task, and only read")
    void aJobOfTheScopeAnswersByItsState(final String state) {
        engine.answers("GET", JOB_PATH, 200, job(state));
        engine.answers("GET", INSTANCE_PATH, 200, instance("shop__Order"));
        final var expected = List
                .of("active", "failed")
                .contains(state) ? WorkflowAwareness.ACTIVE : WorkflowAwareness.COMPLETED;

        final var task = processService.awarenessOfTask(SCOPE, "order-1", String.valueOf(JOB));
        engine.answers("GET", JOB_PATH, 200, userTaskJob(state));
        final var userTask = processService.awarenessOfUserTask(SCOPE, "order-1", String.valueOf(JOB));

        assertAll(
                () -> assertEquals(expected, task),
                () -> assertEquals(expected, userTask),
                () -> assertEquals(List.of("GET %s".formatted(JOB_PATH), "GET %s".formatted(INSTANCE_PATH),
                        "GET %s".formatted(JOB_PATH)), requests()));
    }

    @Test
    @DisplayName("A job of the other kind is unknown to a probe, whatever its state, without reading its instance: the job of a service task to the probe of a user task, and the job of a user task to the probe of a task")
    void aJobOfTheOtherKindIsUnknown() {
        engine.answers("GET", JOB_PATH, 200, job("active"));
        final var userTask = processService.awarenessOfUserTask(SCOPE, "order-1", String.valueOf(JOB));
        engine.answers("GET", JOB_PATH, 200, userTaskJob("completed"));
        final var task = processService.awarenessOfTask(SCOPE, "order-1", String.valueOf(JOB));

        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, userTask),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, task),
                () -> assertEquals(List.of("GET %s".formatted(JOB_PATH), "GET %s".formatted(JOB_PATH)), requests()));
    }

    @Test
    @DisplayName("A job of a process outside the scope is unknown")
    void aJobOutsideTheScopeIsUnknown() {
        engine.answers("GET", JOB_PATH, 200, job("active"));
        engine.answers("GET", INSTANCE_PATH, 200, instance("crm__Order"));
        final var task = processService.awarenessOfTask(SCOPE, "order-1", String.valueOf(JOB));
        engine.answers("GET", JOB_PATH, 200, userTaskJob("active"));
        final var userTask = processService.awarenessOfUserTask(SCOPE, "order-1", String.valueOf(JOB));

        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, task),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, userTask));
    }

    @Test
    @DisplayName("A job the engine does not know is unknown")
    void anUnknownJobIsUnknown() {
        engine.answers("GET", JOB_PATH, 404, """
                {"code":"NOT_FOUND","message":"job not found"}""");

        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService.awarenessOfTask(SCOPE,
                        "order-1", String.valueOf(JOB))),
                () -> assertEquals(List.of("GET %s".formatted(JOB_PATH)), requests()));
    }

    @ParameterizedTest(name = "task id {0}")
    @ValueSource(strings = {
            "Activity_Charge:2251799813685249", "0", "-4503599627370600", ""
    })
    @DisplayName("A task id which is no key of this engine is unknown, and nothing is asked")
    void aTaskIdWhichIsNoKeyIsUnknown(final String taskId) {
        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService.awarenessOfTask(SCOPE,
                        "order-1", taskId)),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService.awarenessOfUserTask(SCOPE,
                        "order-1", taskId)),
                () -> assertEquals(List.of(), requests()));
    }

    @Test
    @DisplayName("A 502 for a key of a partition the engine does not report is unknown, after one more read")
    void aKeyOfAPartitionTheEngineDoesNotHaveIsUnknown() {
        // partition 2, while the engine reports partition 1 only
        final var keyOfPartitionTwo = (1L << 52) | (2L << 12);
        engine.answers("GET", "/v1/jobs/%d".formatted(keyOfPartitionTwo), 502, """
                {"code":"CLUSTER_ERROR","message":"partition 2 not found"}""");
        engine.answers("GET", "/system/status", 200, status(1));

        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService.awarenessOfTask(SCOPE,
                        "order-1", String.valueOf(keyOfPartitionTwo))),
                () -> assertEquals(List.of("GET /v1/jobs/%d".formatted(keyOfPartitionTwo), "GET /system/status"),
                        requests()),
                () -> assertTrue(requests().size() <= SCOPE
                        .bpmnProcessIds()
                        .size() + 1, () -> requests().toString()));
    }

    @Test
    @DisplayName("Only a 502 makes the probe read the engine's partitions: any other failure for a key of a partition it does not have is unavailable")
    void onlyA502IsComparedWithThePartitions() {
        final var keyOfPartitionTwo = (1L << 52) | (2L << 12);
        engine.answers("GET", "/v1/jobs/%d".formatted(keyOfPartitionTwo), 503, null);
        engine.answers("GET", "/system/status", 200, status(1));

        assertAll(
                () -> assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, processService.awarenessOfTask(SCOPE,
                        "order-1", String.valueOf(keyOfPartitionTwo))),
                () -> assertEquals(List.of("GET /v1/jobs/%d".formatted(keyOfPartitionTwo)), requests()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    @DisplayName("Every other failure is unavailable, never unknown")
    void everyOtherFailureIsUnavailable(
        final String what,
        final FakeEngine.Handler jobAnswer,
        final FakeEngine.Handler userTaskJobAnswer,
        final FakeEngine.Handler instanceAnswer,
        final FakeEngine.Handler statusAnswer
    ) {
        engine.answers("GET", JOB_PATH, jobAnswer);
        engine.answers("GET", INSTANCE_PATH, instanceAnswer);
        engine.answers("GET", "/system/status", statusAnswer);
        final var task = processService.awarenessOfTask(SCOPE, "order-1", String.valueOf(JOB));
        engine.answers("GET", JOB_PATH, userTaskJobAnswer);
        final var userTask = processService.awarenessOfUserTask(SCOPE, "order-1", String.valueOf(JOB));

        assertAll(
                () -> assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, task),
                () -> assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, userTask),
                () -> assertTrue(engine
                        .requests()
                        .stream()
                        .allMatch(request -> "GET".equals(request.method())), () -> requests().toString()));
    }

    @Test
    @DisplayName("A task probe which fails is logged as the workflow probes log it: a WARN for the first failure after an answer")
    void aFailedTaskProbeIsLoggedOnce(final CapturedOutput output) {
        engine.answers("GET", JOB_PATH, 503, null);
        processService.awarenessOfTask(SCOPE, "order-1", String.valueOf(JOB));
        processService.awarenessOfUserTask(SCOPE, "order-1", String.valueOf(JOB));

        assertEquals(List.of(
                "ZenBPM[orders]: the engine could not be asked for task '4503599627375192', so the probe reports BPMS_UNAVAILABLE; check that the engine at vanillabp.adapters.orders.rest-address answers within vanillabp.adapters.orders.request-timeout; further failures are logged at DEBUG until the engine answers a probe again"),
                output
                        .getAllOfThisTest()
                        .lines()
                        .filter(line -> line.contains(" WARN "))
                        .map(line -> line.substring(line.indexOf("ZenBPM[")))
                        .toList());
    }

    @Test
    @DisplayName("The open tasks of a process are the active instances of its job elements in every version, each read with its own model, and no job page is fetched")
    void theOpenTasksOfEveryVersionAreCounted() throws JsonProcessingException {
        engine.answers("GET", "/v1/process-definitions", 200, versions(4503599627370402L, 4503599627370401L));
        engine.answers("GET", "/v1/process-definitions/4503599627370401", 200, definition(4503599627370401L, 1,
                model("Task_Work")));
        engine.answers("GET", "/v1/process-definitions/4503599627370401/statistics", 200, """
                {"partitions": [{"partition": 1, "items": {"Task_Work": {"activeCount": 2, "incidentCount": 1},
                  "UserTask_Check": {"activeCount": 1, "incidentCount": 0},
                  "Timer_Wait": {"activeCount": 5, "incidentCount": 0}}}]}""");
        engine.answers("GET", "/v1/process-definitions/4503599627370402/statistics", 200, """
                {"partitions": [{"partition": 1, "items": {"Task_Work_Again": {"activeCount": 1, "incidentCount": 0},
                  "Task_Work": {"activeCount": 7, "incidentCount": 0}}},
                 {"partition": 2, "items": {"UserTask_Check": {"activeCount": 2, "incidentCount": 0}}}]}""");
        engine.answers("GET", "/v1/process-definitions/4503599627370402", 200, definition(4503599627370402L, 2,
                model("Task_Work_Again")));

        final var open = processService.openTaskCount("shop", "Order");

        assertAll(
                () -> assertEquals(2 + 1 + 1 + 2, open),
                () -> assertEquals(List.of(
                        "GET /v1/process-definitions",
                        "GET /v1/process-definitions/4503599627370402",
                        "GET /v1/process-definitions/4503599627370402/statistics",
                        "GET /v1/process-definitions/4503599627370401",
                        "GET /v1/process-definitions/4503599627370401/statistics"), requests()),
                () -> assertTrue(engine
                        .requests()
                        .getFirst()
                        .query()
                        .contains("bpmnProcessId=shop__Order"),
                        () -> engine
                                .requests()
                                .toString()));
    }

    @Test
    @DisplayName("A process the engine holds no version of has no open task")
    void aProcessWithoutVersionsHasNoOpenTask() {
        engine.answers("GET", "/v1/process-definitions", 200, """
                {"items": [], "totalCount": 0}""");

        assertEquals(0L, processService.openTaskCount("shop", "Order"));
    }

    @Test
    @DisplayName("Where the engine does not answer, or the adapter id has no clients, the count is not known, and nothing is thrown")
    void aCountTheEngineDoesNotAnswerIsNotKnown(final CapturedOutput output) throws JsonProcessingException {
        engine.answers("GET", "/v1/process-definitions", 200, versions(4503599627370401L));
        engine.answers("GET", "/v1/process-definitions/4503599627370401", 200, definition(4503599627370401L, 1,
                model("Task_Work")));
        engine.answers("GET", "/v1/process-definitions/4503599627370401/statistics", 500, """
                {"code":"TECHNICAL_ERROR","message":"database is locked"}""");
        final var unconfigured = processService(ZenBpmClientFactory.unconfigured("orders",
                new ZenBpmAdapterConfiguration(), "not configured"));

        assertAll(
                () -> assertNull(processService.openTaskCount("shop", "Order")),
                () -> assertNull(unconfigured.openTaskCount("shop", "Order")),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .lines()
                        .noneMatch(line -> line.contains(" WARN ") || line.contains(" ERROR ")),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("An adapter id without clients refuses the task probes with the message of its validation and asks nothing")
    void anAdapterIdWithoutClientsRefusesTheTaskProbes() {
        final var unconfigured = processService(ZenBpmClientFactory.unconfigured("orders",
                new ZenBpmAdapterConfiguration(), "ZenBPM adapter 'orders' is not configured: set the key"));

        assertAll(Stream
                .<BiFunction<ZenBpmProcessService<Object>, String, WorkflowAwareness>>of(
                        (
                            probes,
                            taskId) -> probes.awarenessOfTask(SCOPE, "order-1", taskId),
                        (
                            probes,
                            taskId) -> probes.awarenessOfUserTask(SCOPE, "order-1", taskId))
                .map(probe -> () -> assertEquals("ZenBPM adapter 'orders' is not configured: set the key",
                        assertThrows(IllegalStateException.class, () -> probe.apply(unconfigured, String.valueOf(
                                JOB))).getMessage())));
        assertEquals(List.of(), requests());
    }

    // the job's answer for each probe: the job of a task, and the job of a user task, where a job
    // is found at all
    static Stream<Arguments> failures() {
        final var waiting = answer(200, job("active"));
        final var waitingUserTask = answer(200, userTaskJob("active"));
        final var found = answer(200, instance("shop__Order"));
        final var onePartition = answer(200, status(1));
        final var unavailable = answer(503, null);
        final var locked = answer(500, """
                {"code":"TECHNICAL_ERROR","message":"database is locked"}""");
        final var noLeader = answer(502, """
                {"code":"CLUSTER_ERROR","message":"no leader"}""");
        final var partitionNotFound = answer(502, """
                {"code":"CLUSTER_ERROR","message":"partition not found"}""");
        return Stream.of(
                Arguments.of("a 503 for the job", unavailable, unavailable, found, onePartition),
                Arguments.of("a 500 for the job", locked, locked, found, onePartition),
                Arguments.of("a 502 for a key of a partition the engine has", noLeader, noLeader, found,
                        onePartition),
                Arguments.of("a 502 for the job and no status", partitionNotFound, partitionNotFound, found,
                        unavailable),
                Arguments.of("a 502 for the job and a status without partitions", partitionNotFound,
                        partitionNotFound, found, answer(200, statusWith(""))),
                Arguments.of("a 502 for the job and a status whose partitions are null", partitionNotFound,
                        partitionNotFound, found, answer(200, statusWith(", \"partitions\": null"))),
                Arguments.of("a 502 for the job and a status naming no partition", partitionNotFound,
                        partitionNotFound, found, answer(200, statusWith(", \"partitions\": {}"))),
                Arguments.of("a job whose instance the engine does not know", waiting, waitingUserTask, answer(404, """
                        {"code":"NOT_FOUND","message":"process instance not found"}"""), onePartition),
                Arguments.of("a job whose instance cannot be read", waiting, waitingUserTask, unavailable,
                        onePartition));
    }

    private List<String> requests() {
        return engine
                .requests()
                .stream()
                .map(request -> "%s %s".formatted(request.method(), request.path()))
                .toList();
    }

    private static FakeEngine.Handler answer(
        final int status,
        final String body
    ) {
        return exchange -> FakeEngine.respond(exchange, status, body);
    }

    private static String job(final String state) {
        return """
                {"key": %d, "elementId": "Task_Work", "type": "work", "elementType": "SERVICE_TASK",
                 "processInstanceKey": %d, "elementInstanceKey": 4503599627370599, "state": "%s",
                 "createdAt": "2026-10-17T10:00:00Z", "inputVariables": {}}""".formatted(JOB, INSTANCE, state);
    }

    private static String userTaskJob(final String state) {
        return """
                {"key": %d, "elementId": "Approve", "type": "user-task-type", "elementType": "USER_TASK",
                 "processInstanceKey": %d, "elementInstanceKey": 4503599627370599, "state": "%s",
                 "createdAt": "2026-10-17T10:00:00Z", "inputVariables": {}}""".formatted(JOB, INSTANCE, state);
    }

    private static String instance(final String scopedProcessId) {
        return """
                {"key": %d, "processDefinitionKey": 4503599627370401, "bpmnProcessId": "%s", "version": 1,
                 "createdAt": "2026-10-17T10:00:00Z", "state": "active", "parentProcessInstanceKey": 4503599627370501,
                 "processType": "subprocess", "variables": {}}""".formatted(INSTANCE, scopedProcessId);
    }

    private static String status(final int partition) {
        return statusWith(", \"partitions\": {\"%d\": {\"id\": %d}}".formatted(partition, partition));
    }

    private static String statusWith(final String partitions) {
        return """
                {"git": {"commitId": "bdd795a399b6"}, "build": {"version": "v1.8.0"}, "nodes": {"zen-1": {}}%s}"""
                .formatted(partitions);
    }

    private static String versions(final long... keys) {
        final var items = new StringBuilder();
        for (var index = 0; index < keys.length; index++) {
            items
                    .append(index == 0 ? "" : ",")
                    .append("{\"key\": %d, \"version\": %d, \"bpmnProcessId\": \"shop__Order\"}".formatted(
                            keys[index], keys.length - index));
        }
        return "{\"items\": [%s], \"totalCount\": %d}".formatted(items, keys.length);
    }

    private static String definition(
        final long key,
        final int version,
        final String bpmn
    ) throws JsonProcessingException {
        return JSON.writeValueAsString(Map.of("key", key, "version", version, "bpmnProcessId", "shop__Order",
                "bpmnData", bpmn));
    }

    // a service task, a user task and a timer, which gives no worker a job
    private static String model(final String serviceTaskId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="Definitions" targetNamespace="test">
                  <bpmn:process id="shop__Order" isExecutable="true">
                    <bpmn:startEvent id="StartEvent" />
                    <bpmn:serviceTask id="%s">
                      <bpmn:extensionElements><zenbpm:taskDefinition type="work" /></bpmn:extensionElements>
                    </bpmn:serviceTask>
                    <bpmn:userTask id="UserTask_Check" />
                    <bpmn:intermediateCatchEvent id="Timer_Wait">
                      <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                    </bpmn:intermediateCatchEvent>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(serviceTaskId);
    }

    private static ZenBpmProcessService<Object> processService(final ZenBpmClientFactory factory) {
        return new ZenBpmProcessService<>("orders", factory, TestCollaborators.of("orders", TestScoping.of(
                NameClashAvoidance.USE_PREFIX)), (
                    module,
                    process) -> null);
    }

    private static ZenBpmClientFactory configured(final String restAddress) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        return new ZenBpmClientFactory("orders", configuration, () -> "shop");
    }

}
