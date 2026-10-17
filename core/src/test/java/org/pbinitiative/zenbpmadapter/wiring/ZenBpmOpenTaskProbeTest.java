/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;

import io.vanillabp.integration.adapter.spi.workflowtask.TaskExistence;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the probe of the open tasks of a process answers the platform's core about a task: gone where
 * the engine holds no such job or the job was completed or terminated, still there where it is
 * active or failed, and that it cannot say for everything else: an engine which could not be asked,
 * a job of another workflow, an id which is no key, and a task which is not served by a job of the
 * job stream, such as a user task. A job of a sub-process of the same workflow belongs to it.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmOpenTaskProbeTest {

    private static final long ROOT = 100;

    private static final long SUB_PROCESS = 200;

    private static final long OTHER_WORKFLOW = 300;

    private FakeEngine engine;

    private ZenBpmRestClient rest;

    private ZenBpmOpenTaskProbe probe;

    @BeforeEach
    void startTheEngine() {
        engine = FakeEngine.started();
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        rest = new ZenBpmRestClient("orders", configuration);
        probe = new ZenBpmOpenTaskProbe("orders", rest, new ZenBpmInstanceCache(rest, 100), Set.of("ship"));
        holds(ROOT, "default", null);
        holds(SUB_PROCESS, "subprocess", ROOT);
        holds(OTHER_WORKFLOW, "default", null);
    }

    @AfterEach
    void stopTheEngine() {
        rest.close();
        engine.close();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "active, STILL_THERE", "failed, STILL_THERE", "completed, GONE", "terminated, GONE"
    })
    @DisplayName("A job of the workflow is still there while it is active or failed, and gone once it was completed or terminated")
    void theStateOfTheJobDecides(
        final String state,
        final TaskExistence existence
    ) {
        engine.answers("GET", "/v1/jobs/42", 200, job(ROOT, state));

        assertEquals(existence, probe.stillExists("100", "42", "ship"));
    }

    @Test
    @DisplayName("A job the engine does not hold is gone")
    void aJobTheEngineDoesNotHoldIsGone() {
        engine.answers("GET", "/v1/jobs/42", 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no job\"}");

        assertEquals(TaskExistence.GONE, probe.stillExists("100", "42", "ship"));
    }

    @Test
    @DisplayName("A job of a sub-process belongs to the workflow of its root instance")
    void aJobOfASubProcessBelongsToTheWorkflow() {
        engine.answers("GET", "/v1/jobs/42", 200, job(SUB_PROCESS, "active"));

        assertEquals(TaskExistence.STILL_THERE, probe.stillExists("100", "42", "ship"));
    }

    @Test
    @DisplayName("An engine which could not be asked or refused the question, a job of another workflow, and a job whose instance is not known cannot say anything, and are never gone")
    void whatIsNoAnswerCannotSay() {
        engine.answers("GET", "/v1/jobs/42", 502, "{\"code\":\"BAD_GATEWAY\",\"message\":\"no leader\"}");
        engine.answers("GET", "/v1/jobs/43", 500, "{\"code\":\"TECHNICAL_ERROR\",\"message\":\"broken\"}");
        engine.answers("GET", "/v1/jobs/44", 200, job(OTHER_WORKFLOW, "completed"));
        engine.answers("GET", "/v1/jobs/45", 200, job(999, "completed"));
        engine.answers("GET", "/v1/process-instances/999", 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no\"}");

        assertEquals(List.of(TaskExistence.CANNOT_SAY, TaskExistence.CANNOT_SAY, TaskExistence.CANNOT_SAY,
                TaskExistence.CANNOT_SAY),
                List.of(probe.stillExists("100", "42", "ship"), probe.stillExists("100",
                        "43", "ship"), probe.stillExists("100", "44", "ship"), probe.stillExists("100", "45", "ship")));
    }

    @Test
    @DisplayName("A task not served by a job of the job stream, such as a user task, a task without a definition, and an id which is no key cannot say anything, and the engine is not asked")
    void whatIsNotAJobOfThisProcessCannotSay() {
        engine.answers("GET", "/v1/jobs/42", 200, job(ROOT, "terminated"));

        assertAll(
                () -> assertEquals(TaskExistence.CANNOT_SAY, probe.stillExists("100", "42", "approve")),
                () -> assertEquals(TaskExistence.CANNOT_SAY, probe.stillExists("100", "42", null)),
                () -> assertEquals(TaskExistence.CANNOT_SAY, probe.stillExists("100", "42")),
                () -> assertEquals(TaskExistence.CANNOT_SAY, probe.stillExists("100", "task-42", "ship")),
                () -> assertEquals(TaskExistence.CANNOT_SAY, probe.stillExists("workflow-100", "42", "ship")),
                () -> assertEquals(List.of(), engine
                        .requests()
                        .stream()
                        .filter(request -> request
                                .path()
                                .startsWith("/v1/jobs"))
                        .toList()));
    }

    private void holds(
        final long key,
        final String processType,
        final Long parentKey
    ) {
        engine.answers("GET", "/v1/process-instances/%d".formatted(key), 200, """
                {
                  "key": %d,
                  "processDefinitionKey": 4503599627370498,
                  "bpmnProcessId": "shop__Order",
                  "version": 3,
                  "createdAt": "2026-10-17T14:30:25.123Z",
                  "state": "active",
                  %s
                  "processType": "%s",
                  "variables": {}
                }
                """.formatted(key, parentKey == null ? "" : "\"parentProcessInstanceKey\": %d,".formatted(parentKey),
                processType));
    }

    private static String job(
        final long instanceKey,
        final String state
    ) {
        return """
                {"key": 42, "elementId": "Ship", "type": "shop__Order__ship", "elementType": "SERVICE_TASK",
                 "processInstanceKey": %d, "elementInstanceKey": 4503599627370599, "state": "%s",
                 "createdAt": "2026-10-17T10:00:00Z", "inputVariables": {}}""".formatted(instanceKey, state);
    }

}
