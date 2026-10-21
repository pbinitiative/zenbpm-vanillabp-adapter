/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.DeployedProcess;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskExistence;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the handler of a workflow module makes of a job: the invocation context it hands to the
 * platform's core, the update of the variables of every instance up to the workflow before the
 * completion, the failure without retries of a job whose instance names no aggregate or whose
 * workflow this application does not own, what at-least-once delivery leaves over, a BPMN error
 * sent with its scoped code after the values were written, a task left open parked by its window or,
 * overdue under the incident action, failed without retries; a handler which throws is failed at
 * the engine, which {@link ZenBpmJobFailureTest} holds. Once an outcome reached the engine, and only then, the core looks at the
 * other open tasks of the workflow with the probe of the job's process. Every job gives its handler
 * slot back.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmJobHandlerTest {

    private static final long ROOT = 100;

    private static final long SUB_PROCESS = 200;

    private static final long BODY = 300;

    private JobRunning jobs;

    @BeforeEach
    void startTheEngine() {
        jobs = new JobRunning(2);
    }

    @AfterEach
    void stopTheEngine() {
        jobs.close();
    }

    @Test
    @DisplayName("The context of a job in a sub-process names the plain task, the element, a task, the aggregate by its id variable and the business key, the root instance as the workflow, the job key as task, delivery and activation, the version and the adapter id, and the job's input variables")
    void theContextNamesWhatTheJobAndItsInstanceSay() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.holds(SUB_PROCESS, "subprocess", ROOT, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(SUB_PROCESS, ROOT);
        jobs.factory
                .deployedProcesses()
                .record(new DeployedProcess("shop", "Order", "shop__Order", 4503599627370499L, 4, null, "<bpmn/>"));

        jobs.runs(JobRunning.job(SUB_PROCESS));

        final var invocation = jobs.invoker
                .invocations()
                .getFirst();
        final var context = invocation.context();
        assertAll(
                () -> assertEquals("shop", invocation.workflowModuleId()),
                () -> assertEquals("Order", invocation.bpmnProcessId()),
                () -> assertEquals("ship", context.getTaskDefinition()),
                () -> assertEquals("Ship", context.getBpmnElementId()),
                () -> assertEquals(TaskKind.TASK, context.getTaskKind()),
                () -> assertEquals("order-1", context.getWorkflowAggregateId()),
                () -> assertEquals("order-1", context.getBusinessKey()),
                () -> assertEquals("100", context.getWorkflowId()),
                () -> assertEquals("42", context.getTaskId()),
                () -> assertEquals("42", context.getDeliveryId()),
                () -> assertEquals("42", context.getActivationId()),
                () -> assertEquals("3", context.getProcessVersion()),
                () -> assertEquals("orders", context.getAdapterId()),
                () -> assertEquals(12L, context.getTaskParameter("amount")),
                () -> assertEquals("gift", context.getTaskParameter("note")),
                () -> assertNull(context.getTaskParameter("missing")),
                () -> assertTrue(context.predatesDeployedVersion()),
                () -> assertFalse(context.runInCurrentTransaction()),
                () -> assertEquals(Map.of(), context.getMultiInstances()));
    }

    @Test
    @DisplayName("The iterations a job runs in are read only where the method asks for them, and once for all of its parameters, also where no body is kept")
    void theIterationsAreReadOnceWhereTheMethodAsksForThem() {
        // nothing kept, so only the delivery itself can spare the second parameter a read
        jobs.close();
        jobs = new JobRunning(2, configuration -> configuration.setInstanceCacheMaxEntries(0));
        final var variables = "\"id\": \"order-1\", \"parcels\": [\"a\", \"b\"]";
        jobs.holds(ROOT, "shop__Order", "default", null, "order-1", variables, List.of("Ship"));
        // the collection the engine evaluated when it entered the element, at the entry of the token
        jobs.engine.answers("GET", "/v1/process-instances/%d/history".formatted(ROOT), 200, """
                {"items": [{"key": 7, "processInstanceKey": %d, "createdAt": "2026-10-17T14:30:25.123Z",
                  "elementId": "Ship", "elementType": "SERVICE_TASK", "inputVariables": {"parcel": ["a", "b"]}}],
                 "page": 1, "size": 100, "count": 1, "totalCount": 1}""".formatted(ROOT));
        // the body of the multi-instance task 'Ship', with a copy of the variables of the root
        jobs.holds(BODY, "multiInstance", ROOT, "order-1", variables);
        jobs.takesTheCommands(BODY, ROOT);
        jobs.holdsTheModel(JobRunning.DEFINITION_KEY, """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="Definitions_Order" \
                targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="shop__Order" isExecutable="true">
                    <bpmn:serviceTask id="Ship">
                      <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="=parcels" inputElement="parcel" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:serviceTask>
                  </bpmn:process>
                </bpmn:definitions>""");
        jobs.engine.answers("GET", "/v1/jobs", 200, """
                {"partitions": [{"partition": 1, "totalCount": 2, "items": [
                  {"key": 42, "elementId": "Ship", "type": "shop__Order__ship", "elementType": "SERVICE_TASK",
                   "processInstanceKey": 300, "elementInstanceKey": 7, "state": "active",
                   "createdAt": "2026-10-17T14:30:26.123Z", "inputVariables": {"parcel": "b"}},
                  {"key": 41, "elementId": "Ship", "type": "shop__Order__ship", "elementType": "SERVICE_TASK",
                   "processInstanceKey": 300, "elementInstanceKey": 6, "state": "completed",
                   "createdAt": "2026-10-17T14:30:25.123Z", "inputVariables": {"parcel": "a"}}]}],
                 "totalCount": 2}""");
        final var told = new ArrayList<Map<String, MultiInstanceValue>>();
        jobs.invoker.answers(context -> {
            told.add(context.getMultiInstances());
            told.add(context.getMultiInstances());
            return WorkflowTaskOutcome.completed();
        });

        jobs.runs(JobRunning.job(BODY));
        final var listingsWhileAsked = listingsOfJobs();
        jobs.invoker.answers(context -> WorkflowTaskOutcome.completed());
        jobs.runs(JobRunning.job(BODY));

        assertAll(
                () -> assertEquals(List.of(Map.of("Ship", new MultiInstanceValue("b", 1, 2)), Map.of("Ship",
                        new MultiInstanceValue("b", 1, 2))), told),
                () -> assertEquals(1, listingsWhileAsked),
                () -> assertEquals(1, listingsOfJobs()));
    }

    @Test
    @DisplayName("A read of the iterations which fails fails the job as a method which threw, with the read's failure")
    void aReadOfTheIterationsWhichFailsFailsTheJob() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.holds(BODY, "multiInstance", ROOT, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(BODY, ROOT);
        jobs.engine.answers("GET", "/v1/process-definitions/%d".formatted(JobRunning.DEFINITION_KEY), 500, """
                {"code": "INTERNAL", "message": "the model cannot be read"}""");
        jobs.invoker.answers(context -> {
            context.getMultiInstances();
            return WorkflowTaskOutcome.completed();
        });

        jobs.runs(JobRunning.job(BODY));

        assertAll(
                () -> assertEquals(List.of("POST /v1/jobs/42/fail"), jobs.commands()),
                () -> assertTrue(jobs
                        .bodiesOf("POST", "/v1/jobs/42/fail")
                        .getFirst()
                        .contains("the model cannot be read"),
                        () -> jobs
                                .bodiesOf("POST", "/v1/jobs/42/fail")
                                .toString()));
    }

    @Test
    @DisplayName("A job of an instance of the version this start deployed, or of a process it did not deploy, does not predate the deployed version")
    void aJobOfTheDeployedVersionDoesNotPredateIt() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.job(ROOT));
        jobs.factory
                .deployedProcesses()
                .record(new DeployedProcess("shop", "Order", "shop__Order", 4503599627370499L, 3, null, "<bpmn/>"));
        jobs.runs(JobRunning.job(ROOT));

        assertAll(
                () -> assertFalse(jobs.invoker
                        .invocations()
                        .get(0)
                        .context()
                        .predatesDeployedVersion()),
                () -> assertFalse(jobs.invoker
                        .invocations()
                        .get(1)
                        .context()
                        .predatesDeployedVersion()));
    }

    @Test
    @DisplayName("A completed task writes the shared values and the id into every instance from the job's own up to the workflow, and then completes the job with them")
    void aCompletedTaskWritesTheValuesUpToTheWorkflowBeforeItCompletes() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.holds(SUB_PROCESS, "subprocess", ROOT, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(SUB_PROCESS, ROOT);
        jobs.invoker.shares(Map.of("shipped", true));

        jobs.runs(JobRunning.job(SUB_PROCESS));

        final Map<String, Object> variables = Map.of("variables", Map.of("shipped", true, "id", "order-1"));
        assertAll(
                () -> assertEquals(List.of(
                        "PATCH /v1/process-instances/200/variables",
                        "PATCH /v1/process-instances/100/variables",
                        "POST /v1/jobs/42/complete"),
                        jobs.commands()),
                () -> assertEquals(variables, json(jobs
                        .bodiesOf("PATCH", "/v1/process-instances/200/variables")
                        .getFirst())),
                () -> assertEquals(variables, json(jobs
                        .bodiesOf("PATCH", "/v1/process-instances/100/variables")
                        .getFirst())),
                () -> assertEquals(variables, json(jobs
                        .bodiesOf("POST", "/v1/jobs/42/complete")
                        .getFirst())),
                () -> assertEquals(List.of("shop/Order/order-1/FULL"), jobs.invoker.syncedReads()),
                () -> assertEquals(1, jobs.counted(ZenBpmMetrics.JOBS_COMPLETED)),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_FAILED)));
    }

    @Test
    @DisplayName("The aggregate is named by the id variable where the instance has it, the business key reported beside it, and else by the business key")
    void theAggregateIsNamedByTheIdVariableElseByTheBusinessKey() {
        jobs.holds(ROOT, "default", null, "other", "\"id\": \"order-1\"");
        jobs.holds(300, "default", null, "order-9", "");
        jobs.takesTheCommands(ROOT, 300);

        jobs.runs(JobRunning.job(ROOT));
        jobs.runs(JobRunning.job(300));

        final var byTheVariable = jobs.invoker
                .invocations()
                .get(0)
                .context();
        final var byTheBusinessKey = jobs.invoker
                .invocations()
                .get(1)
                .context();
        assertAll(
                () -> assertEquals("order-1", byTheVariable.getWorkflowAggregateId()),
                () -> assertEquals("other", byTheVariable.getBusinessKey()),
                () -> assertEquals("order-9", byTheBusinessKey.getWorkflowAggregateId()),
                () -> assertEquals("order-9", byTheBusinessKey.getBusinessKey()));
    }

    @Test
    @DisplayName("A sub-process whose instance names no aggregate has it read in the workflow's root instance")
    void anInstanceWithoutTheIdHasItReadInTheRoot() {
        jobs.holds(ROOT, "default", null, null, "\"id\": \"order-1\"");
        jobs.holds(SUB_PROCESS, "subprocess", ROOT, null, "");
        jobs.takesTheCommands(SUB_PROCESS, ROOT);

        jobs.runs(JobRunning.job(SUB_PROCESS));

        final var context = jobs.invoker
                .invocations()
                .getFirst()
                .context();
        assertAll(
                () -> assertEquals("order-1", context.getWorkflowAggregateId()),
                () -> assertNull(context.getBusinessKey()));
    }

    @Test
    @DisplayName("A job whose instance names no aggregate is failed at once without retries, naming the instance, the missing business key and the id variable, for its delivery and the stream's client, with a WARN")
    void aJobOfAnInstanceNamingNoAggregateIsFailedWithoutRetries(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, null, "");
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.job(ROOT));

        final var failure = json(jobs
                .bodiesOf("POST", "/v1/jobs/42/fail")
                .getFirst());
        assertAll(
                () -> assertEquals(List.of(), jobs.invoker.invocations()),
                () -> assertEquals(List.of("POST /v1/jobs/42/fail"), jobs.commands()),
                () -> assertEquals(0, failure.get("retries")),
                () -> assertEquals(7, failure.get("deliveryToken")),
                () -> assertEquals(jobs.factory.clientId(), failure.get("clientId")),
                () -> assertEquals("""
                        Process instance 100 of process 'shop__Order' names no workflow aggregate: it has no business \
                        key and no variable 'id'. An instance started through VanillaBP carries both, so this one was \
                        started past it. Set the variable 'id' to the id of the workflow aggregate, or the business \
                        key, and resolve the incident, or cancel the instance.""", failure.get("message")),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("job 42 (task 'ship' of workflow module 'shop') is failed without retries"),
                        output::getAllOfThisTest),
                () -> assertEquals(1, jobs.counted(ZenBpmMetrics.JOBS_FAILED)));
    }

    @Test
    @DisplayName("A job of a workflow this application does not own is failed at once without retries and with the core's message, for its delivery and the stream's client, with a WARN without stack trace")
    void aJobOfAWorkflowNobodyOwnsHereIsFailedWithTheCoresMessage(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        final var refusal = new DeliveryOfAnUnknownWorkflowException("orders", "shop", "Order", "ship", "org.example.Order", "order-1", "100");
        jobs.invoker.answers(context -> {
            throw refusal;
        });

        jobs.runs(JobRunning.job(ROOT));

        final var failure = json(jobs
                .bodiesOf("POST", "/v1/jobs/42/fail")
                .getFirst());
        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(List.of("POST /v1/jobs/42/fail"), jobs.commands()),
                () -> assertEquals(refusal.getMessage(), failure.get("message")),
                () -> assertEquals(0, failure.get("retries")),
                () -> assertEquals(7, failure.get("deliveryToken")),
                () -> assertEquals(jobs.factory.clientId(), failure.get("clientId")),
                () -> assertTrue(log.contains("job 42 (task 'ship') is about a workflow this application does not own"),
                        log),
                () -> assertFalse(log.contains(DeliveryOfAnUnknownWorkflowException.class.getName()), log),
                () -> assertEquals(1, jobs.counted(ZenBpmMetrics.JOBS_FAILED)),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_COMPLETED)));
    }

    @Test
    @DisplayName("A failure names no delivery token where the engine issued none")
    void aFailureNamesNoTokenTheEngineDidNotIssue() {
        jobs.holds(ROOT, "default", null, null, "");
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.job(42, ROOT, JobRunning.JOB_TYPE, Instant
                .now()
                .plus(JobRunning.JOB_TIMEOUT), 0));

        assertFalse(json(jobs
                .bodiesOf("POST", "/v1/jobs/42/fail")
                .getFirst()).containsKey("deliveryToken"));
    }

    @Test
    @DisplayName("A failure which finds the job gone is no failure of the handler")
    void aFailureWhichFindsTheJobGoneIsQuiet(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, null, "");
        jobs.engine.answers("POST", "/v1/jobs/42/fail", 409, "{\"code\":\"CONFLICT\",\"message\":\"superseded\"}");

        jobs.runs(JobRunning.job(ROOT));

        assertAll(
                () -> assertFalse(output
                        .getAllOfThisTest()
                        .contains("could not be answered"), output::getAllOfThisTest),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_FAILED), "the engine took no failure"));
    }

    @Test
    @DisplayName("A workflow which ended while the handler ran is not completed, and a WARN says so")
    void aWorkflowWhichEndedMeanwhileIsNotCompleted(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.engine.answers("PATCH", "/v1/process-instances/100/variables", 409,
                "{\"code\":\"CONFLICT\",\"message\":\"Can update variables only for process instances in active or failed state\"}");

        jobs.runs(JobRunning.job(ROOT));

        assertAll(
                () -> assertEquals(List.of("PATCH /v1/process-instances/100/variables"), jobs.commands()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "the workflow of job 42 (task 'ship', workflow aggregate 'order-1') ended or was cancelled while the handler ran"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("An update of the variables which finds no instance, as a node lagging behind answers, is sent again before the job is completed")
    void anUpdateWhichFindsNoInstanceIsSentAgain() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        final var notYet = new CountDownLatch(1);
        jobs.engine.answers("PATCH", "/v1/process-instances/100/variables", exchange -> {
            if (notYet.getCount() > 0) {
                notYet.countDown();
                FakeEngine.respond(exchange, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no such instance\"}");
                return;
            }
            FakeEngine.respond(exchange, 204, null);
        });

        jobs.runs(JobRunning.job(ROOT));

        assertEquals(List.of(
                "PATCH /v1/process-instances/100/variables",
                "PATCH /v1/process-instances/100/variables",
                "POST /v1/jobs/42/complete"),
                jobs.commands());
    }

    @Test
    @DisplayName("A job gone before its completion, completed by another delivery or ended with its workflow, is what at-least-once delivery leaves over: a WARN, nothing else")
    void aJobGoneBeforeItsCompletionIsTolerated(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.engine.answers("POST", "/v1/jobs/42/complete", 409, "{\"code\":\"CONFLICT\",\"message\":\"terminated\"}");

        jobs.runs(JobRunning.job(ROOT));

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertTrue(log.contains(
                        "job 42 (task 'ship', workflow aggregate 'order-1') was gone when it was to be completed"),
                        log),
                () -> assertFalse(log.contains("could not be answered"), log),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_COMPLETED), "the engine took no completion"));
    }

    @Test
    @DisplayName("A command the engine refuses for good leaves the job to its lock, with a WARN")
    void aCommandRefusedForGoodLeavesTheJobToItsLock(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.engine.answers("POST", "/v1/jobs/42/complete", 400, "{\"code\":\"BAD_REQUEST\",\"message\":\"no\"}");

        jobs.runs(JobRunning.job(ROOT));

        assertTrue(output
                .getAllOfThisTest()
                .contains("job 42 (task 'ship') could not be answered to the engine; it is left to its lock"),
                output::getAllOfThisTest);
    }

    @Test
    @DisplayName("An update of the variables, or a failure, which the engine refuses for good leaves the job to its lock, with a WARN")
    void anUpdateOrAFailureRefusedForGoodLeavesTheJobToItsLock(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.holds(300, "default", null, null, "");
        jobs.engine.answers("PATCH", "/v1/process-instances/100/variables", 400,
                "{\"code\":\"BAD_REQUEST\",\"message\":\"no\"}");
        jobs.engine.answers("POST", "/v1/jobs/43/fail", 400, "{\"code\":\"BAD_REQUEST\",\"message\":\"no\"}");

        jobs.runs(JobRunning.job(ROOT));
        jobs.runs(JobRunning.job(43, 300, JobRunning.JOB_TYPE, Instant
                .now()
                .plus(JobRunning.JOB_TIMEOUT), 7));

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(List.of("PATCH /v1/process-instances/100/variables", "POST /v1/jobs/43/fail"), jobs
                        .commands()),
                () -> assertTrue(log.contains("job 42 (task 'ship') could not be answered to the engine"), log),
                () -> assertTrue(log.contains("job 43 (task 'ship') could not be answered to the engine"), log));
    }

    @Test
    @DisplayName("A command which fails while the workflow module shuts down is not repeated, and the job is left to its lock with an INFO")
    void aCommandFailingWhileTheModuleShutsDownIsNotRepeated(final CapturedOutput output) throws InterruptedException {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.engine.answers("POST", "/v1/jobs/42/complete", 502,
                "{\"code\":\"CLUSTER_ERROR\",\"message\":\"no leader\"}");
        final var invoked = new CountDownLatch(1);
        final var stopped = new CountDownLatch(1);
        jobs.invoker.answers(context -> {
            invoked.countDown();
            awaitQuietly(stopped);
            return WorkflowTaskOutcome.completed();
        });

        jobs.dispatcher.accept(JobRunning.job(ROOT));
        assertTrue(invoked.await(10, TimeUnit.SECONDS));
        jobs.dispatcher.unregister("shop");
        stopped.countDown();
        jobs.awaitFreeSlots();

        assertAll(
                () -> assertEquals(1, jobs
                        .bodiesOf("POST", "/v1/jobs/42/complete")
                        .size()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("job 42 (task 'ship') could not be answered while workflow module 'shop' shuts down"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A BPMN error writes the shared values and the id into every instance up to the workflow, and then fails the job with the scoped code, the same values, a message for the incident, the delivery's token and the stream's client, without retries")
    void aBpmnErrorIsSentScopedWithTheValuesAfterTheyWereWritten(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.holds(SUB_PROCESS, "subprocess", ROOT, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(SUB_PROCESS, ROOT);
        jobs.engine.answers("GET", "/v1/jobs/42", 200, job42("terminated"));
        jobs.invoker.shares(Map.of("refundable", true));
        jobs.invoker.answers(context -> WorkflowTaskOutcome.bpmnError("out-of-stock", "Out of stock"));

        jobs.runs(JobRunning.job(SUB_PROCESS));

        final var failure = json(jobs
                .bodiesOf("POST", "/v1/jobs/42/fail")
                .getFirst());
        assertAll(
                () -> assertEquals(List.of(
                        "PATCH /v1/process-instances/200/variables",
                        "PATCH /v1/process-instances/100/variables",
                        "POST /v1/jobs/42/fail"),
                        jobs.commands()),
                () -> assertEquals("shop__out-of-stock", failure.get("errorCode")),
                () -> assertEquals(Map.of("refundable", true, "id", "order-1"), failure.get("variables")),
                () -> assertEquals(7, failure.get("deliveryToken")),
                () -> assertEquals(jobs.factory.clientId(), failure.get("clientId")),
                () -> assertEquals(
                        "BPMN error 'Out of stock' of task 'ship' (workflow aggregate 'order-1') is caught by no error boundary event and no error event sub-process, its code",
                        failure.get("message")),
                () -> assertFalse(failure.containsKey("retries"), failure::toString),
                () -> assertFalse(output
                        .getAllOfThisTest()
                        .contains("so the engine raised an incident"), output::getAllOfThisTest),
                () -> assertEquals(1, jobs.counted(ZenBpmMetrics.JOBS_COMPLETED),
                        "a BPMN error is an outcome the handler chose"),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_FAILED)));
    }

    @Test
    @DisplayName("A BPMN error no event of the model catches, which the engine answers with an incident, is named in a WARN")
    void aBpmnErrorNothingCatchesIsNamed(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.engine.answers("GET", "/v1/jobs/42", 200, job42("failed"));
        jobs.invoker.answers(context -> WorkflowTaskOutcome.bpmnError("lost", "lost"));

        jobs.runs(JobRunning.job(ROOT));

        assertTrue(output
                .getAllOfThisTest()
                .contains(
                        "no error boundary event and no error event sub-process catches the BPMN error 'lost' of job 42 (task 'ship', workflow aggregate 'order-1'), whose code is 'shop__lost' at the engine, so the engine raised an incident on the task"),
                output::getAllOfThisTest);
    }

    @Test
    @DisplayName("Where the job cannot be read after its BPMN error, nothing more is said, and the delivery is done")
    void aBpmnErrorWhoseJobCannotBeReadIsDone(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.engine.answers("GET", "/v1/jobs/42", 502, "{\"code\":\"BAD_GATEWAY\",\"message\":\"no leader\"}");
        jobs.invoker.answers(context -> WorkflowTaskOutcome.bpmnError("lost", "lost"));

        jobs.runs(JobRunning.job(ROOT));

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(List.of("PATCH /v1/process-instances/100/variables", "POST /v1/jobs/42/fail"), jobs
                        .commands()),
                () -> assertFalse(log.contains("raised an incident"), log),
                () -> assertFalse(log.contains("could not be answered"), log));
    }

    @Test
    @DisplayName("A BPMN error which finds the job gone, and one whose workflow ended while the handler ran, are what at-least-once delivery leaves over: a WARN each")
    void aBpmnErrorOfAGoneJobOrAnEndedWorkflowIsWarned(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.engine.answers("POST", "/v1/jobs/42/fail", 409, "{\"code\":\"CONFLICT\",\"message\":\"superseded\"}");
        jobs.invoker.answers(context -> WorkflowTaskOutcome.bpmnError("out-of-stock", "Out of stock"));

        jobs.runs(JobRunning.job(ROOT));
        jobs.engine.answers("PATCH", "/v1/process-instances/100/variables", 409,
                "{\"code\":\"CONFLICT\",\"message\":\"Can update variables only for process instances in active or failed state\"}");
        jobs.runs(JobRunning.job(ROOT));

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(List.of(
                        "PATCH /v1/process-instances/100/variables",
                        "POST /v1/jobs/42/fail",
                        "PATCH /v1/process-instances/100/variables"),
                        jobs.commands()),
                () -> assertTrue(log.contains(
                        "job 42 (task 'ship', workflow aggregate 'order-1') was gone when its BPMN error 'out-of-stock' was to be sent"),
                        log),
                () -> assertTrue(log.contains(
                        "ended or was cancelled while the handler ran, so the job is not failed with its BPMN error"),
                        log),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_COMPLETED),
                        "the engine took neither BPMN error"));
    }

    @Test
    @DisplayName("A BPMN error the engine refuses for another reason leaves the job to its lock, with a WARN, and the job is not read")
    void aRefusedBpmnErrorLeavesTheJobToItsLock(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.engine.answers("POST", "/v1/jobs/42/fail", 400, "{\"code\":\"BAD_REQUEST\",\"message\":\"no\"}");
        jobs.invoker.answers(context -> WorkflowTaskOutcome.bpmnError("out-of-stock", "Out of stock"));

        jobs.runs(JobRunning.job(ROOT));

        assertAll(
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("job 42 (task 'ship') could not be answered to the engine"),
                        output::getAllOfThisTest),
                () -> assertEquals(List.of(), jobs.bodiesOf("GET", "/v1/jobs/42")));
    }

    @Test
    @DisplayName("A task left open for its asynchronous completion is parked:its lock is extended once by async-task-lock-renewal, for the stream's client and its own delivery, and nothing else is sent")
    void aTaskLeftOpenIsParkedForTheWindow() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.engine.answers("POST", "/v1/jobs/42/extend-lock", 200, "{\"lockUntil\":\"%s\"}".formatted(Instant
                .now()
                .plus(Duration.ofHours(1))));
        jobs.invoker.answers(context -> WorkflowTaskOutcome.completionPending());

        jobs.runs(JobRunning.job(ROOT));
        jobs.invoker.answers(context -> WorkflowTaskOutcome.completionPending(Duration.ofDays(8), true));
        jobs.runs(JobRunning.job(ROOT));

        final var parking = "{\"clientId\":\"%s\",\"lockDuration\":\"PT3600S\",\"deliveryToken\":7}".formatted(
                jobs.factory.clientId());
        assertAll(
                () -> assertEquals(List.of("POST /v1/jobs/42/extend-lock", "POST /v1/jobs/42/extend-lock"), jobs
                        .commands()),
                () -> assertEquals(List.of(parking, parking), jobs.bodiesOf("POST", "/v1/jobs/42/extend-lock")),
                () -> assertEquals(List.of(), jobs.invoker.syncedReads()),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_COMPLETED),
                        "a task left open is completed later"),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_FAILED)));
    }

    @Test
    @DisplayName("A task left open whose lock is lost or whose job is gone when it is to be parked is no failure of the handler")
    void aTaskLeftOpenWhoseLockIsLostIsQuiet(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.engine.answers("POST", "/v1/jobs/42/extend-lock", 409,
                "{\"code\":\"CONFLICT\",\"message\":\"lock not held\"}");
        jobs.invoker.answers(context -> WorkflowTaskOutcome.completionPending());

        jobs.runs(JobRunning.job(ROOT));
        jobs.engine.answers("POST", "/v1/jobs/42/extend-lock", 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no job\"}");
        jobs.runs(JobRunning.job(ROOT));

        assertFalse(output
                .getAllOfThisTest()
                .contains("could not be answered"), output::getAllOfThisTest);
    }

    @Test
    @DisplayName("A parking the engine refuses otherwise leaves the job to its lock, with a WARN")
    void aRefusedParkingLeavesTheJobToItsLock(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.engine.answers("POST", "/v1/jobs/42/extend-lock", 400, "{\"code\":\"BAD_REQUEST\",\"message\":\"no\"}");
        jobs.invoker.answers(context -> WorkflowTaskOutcome.completionPending());

        jobs.runs(JobRunning.job(ROOT));

        assertTrue(output
                .getAllOfThisTest()
                .contains("job 42 (task 'ship') could not be answered to the engine"), output::getAllOfThisTest);
    }

    @Test
    @DisplayName("With async-task-max-age-action 'incident', a task left open longer than the maximum age is failed without retries, whatever retries the job has left, with a message naming the aggregate, the age and the keys, for its delivery and the stream's client, and is not parked")
    void anOverdueTaskWithTheIncidentActionIsFailedWithoutRetries(final CapturedOutput output) {
        try (var incidents = new JobRunning(2, keys -> keys.setAsyncTaskMaxAgeAction("incident"))) {
            incidents.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
            incidents.takesTheCommands(ROOT);
            incidents.engine.answers("POST", "/v1/jobs/42/extend-lock", 200, "{\"lockUntil\":\"%s\"}".formatted(
                    Instant
                            .now()
                            .plus(Duration.ofHours(1))));
            incidents.invoker.answers(context -> WorkflowTaskOutcome.completionPending(Duration.ofDays(8), false));
            incidents.runs(JobRunning.job(ROOT));
            incidents.invoker.answers(context -> WorkflowTaskOutcome.completionPending(Duration.ofDays(8), true));

            incidents.runs(JobRunning.job(ROOT));

            final var failure = json(incidents
                    .bodiesOf("POST", "/v1/jobs/42/fail")
                    .getFirst());
            assertAll(
                    () -> assertEquals(List.of("POST /v1/jobs/42/extend-lock", "POST /v1/jobs/42/fail"), incidents
                            .commands()),
                    () -> assertEquals(0, failure.get("retries")),
                    () -> assertFalse(failure.containsKey("errorCode"), failure::toString),
                    () -> assertEquals(7, failure.get("deliveryToken")),
                    () -> assertEquals(incidents.factory.clientId(), failure.get("clientId")),
                    () -> assertEquals(
                            """
                                    Task 'ship' of workflow aggregate 'order-1' (process 'Order' of workflow module 'shop') has \
                                    waited for its asynchronous completion for PT192H, longer than vanillabp.delivery.max-task-age \
                                    allows. The application owes it a ProcessService#completeTask or #cancelTask, which the \
                                    engine takes only once this incident is resolved. Set \
                                    vanillabp.adapters.orders.async-task-max-age-action back to 'report', or raise the maximum \
                                    age where such a wait is legitimate, then resolve this incident: the next delivery leaves the \
                                    task open again, and the application can complete or cancel it.""",
                            failure.get(
                                    "message")),
                    () -> assertTrue(output
                            .getAllOfThisTest()
                            .contains("job 42 is failed without retries, so an incident shows it: Task 'ship'"),
                            output::getAllOfThisTest),
                    () -> assertEquals(1, incidents.counted(ZenBpmMetrics.JOBS_FAILED)),
                    () -> assertEquals(0, incidents.counted(ZenBpmMetrics.JOBS_COMPLETED)));
        }
    }

    @Test
    @DisplayName("After a completion, a BPMN error and a parked task the core looks at the other open tasks of the workflow, with the context of the delivery and the probe of its process, which asks the engine about a task the process serves by a job and about nothing else")
    void theOtherOpenTasksAreLookedAtOnceTheOutcomeReachedTheEngine() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.engine.answers("GET", "/v1/jobs/42", 200, job42("terminated"));
        jobs.engine.answers("POST", "/v1/jobs/42/extend-lock", 200, "{\"lockUntil\":\"%s\"}".formatted(Instant
                .now()
                .plus(Duration.ofHours(1))));

        jobs.runs(JobRunning.job(ROOT));
        jobs.invoker.answers(context -> WorkflowTaskOutcome.bpmnError("out-of-stock", "Out of stock"));
        jobs.runs(JobRunning.job(ROOT));
        jobs.invoker.answers(context -> WorkflowTaskOutcome.completionPending());
        jobs.runs(JobRunning.job(ROOT));

        final var reports = jobs.invoker.reports();
        final var probe = reports
                .getFirst()
                .probe();
        assertAll(
                () -> assertEquals(3, reports.size()),
                () -> assertTrue(reports
                        .stream()
                        .allMatch(report -> "shop".equals(report.workflowModuleId()) && "Order".equals(report
                                .bpmnProcessId()) && "42".equals(
                                        report
                                                .wakeUp()
                                                .getTaskId()) && "100".equals(
                                                        report
                                                                .wakeUp()
                                                                .getWorkflowId())),
                        reports::toString),
                () -> assertEquals(TaskExistence.GONE, probe.stillExists("100", "42", "ship")),
                () -> assertEquals(TaskExistence.CANNOT_SAY, probe.stillExists("100", "42", "approve")));
    }

    @Test
    @DisplayName("Nothing is looked at after a delivery the handler refused, nor after an outcome which did not reach the engine")
    void nothingIsLookedAtWhereNoOutcomeReachedTheEngine() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.holds(SUB_PROCESS, "default", null, null, "");
        jobs.engine.answers("POST", "/v1/jobs/42/fail", 204, null);
        jobs.engine.answers("POST", "/v1/jobs/42/complete", 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no job\"}");
        jobs.engine.answers("POST", "/v1/jobs/42/extend-lock", 409,
                "{\"code\":\"CONFLICT\",\"message\":\"lock not held\"}");
        jobs.engine.answers("PATCH", "/v1/process-instances/100/variables", 204, null);

        // a job of an instance which names no aggregate, a workflow nobody owns here, a thrown
        // handler, a gone job at the completion, a lost lock at the parking
        jobs.runs(JobRunning.job(SUB_PROCESS));
        jobs.invoker.answers(context -> {
            throw new DeliveryOfAnUnknownWorkflowException("orders", "shop", "Order", "ship", "org.example.Order", "order-1", "100");
        });
        jobs.runs(JobRunning.job(ROOT));
        jobs.invoker.answers(context -> {
            throw new IllegalStateException("the warehouse is closed");
        });
        jobs.runs(JobRunning.job(ROOT));
        jobs.invoker.answers(context -> WorkflowTaskOutcome.completed());
        jobs.runs(JobRunning.job(ROOT));
        jobs.invoker.answers(context -> WorkflowTaskOutcome.completionPending());
        jobs.runs(JobRunning.job(ROOT));

        assertEquals(List.of(), jobs.invoker.reports());
    }

    @Test
    @DisplayName("A look at the other open tasks which fails costs the delivery nothing")
    void aFailingLookCostsTheDeliveryNothing(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.invoker.failsToReport(new IllegalStateException("no connection to the delivery log"));

        jobs.runs(JobRunning.job(ROOT));

        assertAll(
                () -> assertEquals(1, jobs.invoker
                        .reports()
                        .size()),
                () -> assertEquals(List.of("PATCH /v1/process-instances/100/variables", "POST /v1/jobs/42/complete"),
                        jobs.commands()),
                () -> assertFalse(output
                        .getAllOfThisTest()
                        .contains("could not be answered"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A job whose instance's way up to the workflow the engine node asked does not know is left to its lock, after the walk was repeated")
    void aJobWhoseWayUpIsNotKnownIsLeftToItsLock(final CapturedOutput output) {
        jobs.holds(SUB_PROCESS, "subprocess", ROOT, "order-1", "\"id\": \"order-1\"");
        jobs.engine.answers("GET", "/v1/process-instances/100", 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no\"}");

        jobs.runs(JobRunning.job(SUB_PROCESS));

        assertAll(
                () -> assertEquals(List.of(), jobs.invoker.invocations()),
                () -> assertEquals(5, jobs
                        .bodiesOf("GET", "/v1/process-instances/100")
                        .size()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("an instance above instance 200 of job 42 (task 'ship') is not known"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A handler which started before the module stopped and threw during the stop is not failed at the engine, so its job keeps its retries")
    void aHandlerWhichThrowsWhileTheModuleStopsIsNotFailed(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.invoker.answers(context -> {
            jobs.dispatcher.unregister("shop");
            throw new IllegalStateException("the connection pool was closed");
        });

        jobs.runs(JobRunning.job(ROOT, 3, 1));

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(List.of(), jobs.commands()),
                () -> assertTrue(log.contains(
                        "the handler of job 42 (task 'ship', workflow aggregate 'order-1') threw while workflow module 'shop' shuts down, so the job is not failed and keeps its retries"),
                        log),
                () -> assertTrue(log.contains("java.lang.IllegalStateException: the connection pool was closed"), log));
    }

    @Test
    @DisplayName("An outcome a handler reached while its module stops is still reported: the work is committed")
    void anOutcomeReachedWhileTheModuleStopsIsReported() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.invoker.answers(context -> {
            jobs.dispatcher.unregister("shop");
            return WorkflowTaskOutcome.completed();
        });

        jobs.runs(JobRunning.job(ROOT));

        assertEquals(List.of("PATCH /v1/process-instances/100/variables", "POST /v1/jobs/42/complete"), jobs
                .commands());
    }

    @Test
    @DisplayName("A job handed to the handler after its module began to stop is not started and is left to its lock")
    void aJobWhichArrivesWhileTheModuleStopsIsNotStarted(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.handler.stopping();

        jobs.runs(JobRunning.job(ROOT));

        assertAll(
                () -> assertEquals(List.of(), jobs.invoker.invocations()),
                () -> assertEquals(List.of(), jobs.commands()),
                () -> assertEquals(List.of(), jobs.handler
                        .drain()
                        .running()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "job 42 (task 'ship') arrived while workflow module 'shop' shuts down, so it is not started; the closing job stream releases its lock"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A handler is in the module's drain while it runs, with its task and aggregate, and leaves it when it returns")
    void aRunningHandlerIsInTheDrain() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.takesTheCommands(ROOT);
        final var whileRunning = new AtomicReference<List<ZenBpmDrain.Running>>();
        jobs.invoker.answers(context -> {
            whileRunning.set(jobs.handler
                    .drain()
                    .running());
            return WorkflowTaskOutcome.completed();
        });

        jobs.runs(JobRunning.job(ROOT));

        final var running = whileRunning
                .get()
                .getFirst();
        assertAll(
                () -> assertEquals(42, running.jobKey()),
                () -> assertEquals("ship", running.taskDefinition()),
                () -> assertEquals("order-1", running.aggregateId()),
                () -> assertEquals(List.of(), jobs.handler
                        .drain()
                        .running()));
    }

    // job 42 of the module's type in instance 100, as the engine answers it
    private long listingsOfJobs() {
        return jobs.engine
                .requests()
                .stream()
                .filter(request -> "/v1/jobs".equals(request.path()))
                .count();
    }

    private static String job42(final String state) {
        return """
                {"key": 42, "elementId": "Ship", "type": "shop__Order__ship", "elementType": "SERVICE_TASK",
                 "processInstanceKey": 100, "elementInstanceKey": 4503599627370599, "state": "%s",
                 "createdAt": "2026-10-17T10:00:00Z", "inputVariables": {}}""".formatted(state);
    }

    private static Map<String, Object> json(final String body) {
        try {
            return JsonMapper
                    .builder()
                    .build()
                    .readValue(body, new TypeReference<Map<String, Object>>() {
                    });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
        }
    }

}
