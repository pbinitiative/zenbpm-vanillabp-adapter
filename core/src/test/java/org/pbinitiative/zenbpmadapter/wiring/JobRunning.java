/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ReceivedJob;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.WaitingJob;
import org.pbinitiative.zenbpmadapter.model.ZenBpmMarkerTasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Jobs run as the adapter runs them, against an engine a test stands in for: the clients of the
 * adapter id <code>orders</code> on a {@link FakeEngine}, the {@link TestInvoker} as the platform's
 * core, and the dispatcher with the handler of the workflow module <code>shop</code>, which claims
 * the process <code>Order</code> (<code>shop__Order</code> at the engine) with its task
 * <code>ship</code> (job type <code>shop__Order__ship</code>) under <code>use-prefix</code>. The job
 * stream is never opened.
 */
final class JobRunning implements AutoCloseable {

    static final String JOB_TYPE = "shop__Order__ship";

    /**
     * The job type of the start markers of <code>shop__Order</code>.
     */
    static final String START_MARKER_TYPE = "vanillabp__started__shop__Order";

    /**
     * The job type of the end markers of <code>shop__Order</code>, which stand in front of its end
     * events <code>Shipped</code>, which ends its branch, and <code>Withdrawn</code>, a terminate end
     * event.
     */
    static final String END_MARKER_TYPE = "vanillabp__ended__shop__Order";

    /**
     * The key of version 3 of <code>shop__Order</code>, the version of every instance the engine
     * holds unless a test says otherwise.
     */
    static final long DEFINITION_KEY = 4503599627370498L;

    static final Duration JOB_TIMEOUT = Duration.ofMinutes(5);

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    final FakeEngine engine = FakeEngine.started();

    final TestInvoker invoker = new TestInvoker();

    final TestStartInvoker starts = new TestStartInvoker();

    final TestEndInvoker ends = new TestEndInvoker();

    /**
     * The <code>retry-backoff</code> the configuration resolves, by
     * <code>module/process/task definition</code> as the handler asks for it, plain; a task which is
     * not here has none.
     */
    final Map<String, Duration> retryBackoffs = new ConcurrentHashMap<>();

    final ZenBpmClientFactory factory;

    final ZenBpmJobDispatcher dispatcher;

    /**
     * The handler of the workflow module <code>shop</code>, registered with the dispatcher.
     */
    final ZenBpmJobHandler handler;

    /**
     * The adapter id with one handler slot per worker thread.
     *
     * @param workerThreads How many handlers run at once
     */
    JobRunning(final int workerThreads) {
        this(workerThreads, configuration -> {
        });
    }

    /**
     * The adapter id with one handler slot per worker thread and further keys set.
     *
     * @param workerThreads How many handlers run at once
     * @param keys What sets the further keys of the adapter id
     */
    JobRunning(
        final int workerThreads,
        final Consumer<ZenBpmAdapterConfiguration> keys
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        configuration.setGrpcAddress("localhost:1");
        configuration.setWorkerThreads(String.valueOf(workerThreads));
        keys.accept(configuration);
        factory = new ZenBpmClientFactory("orders", configuration, () -> "shop");
        dispatcher = new ZenBpmJobDispatcher("orders", factory);
        handler = handlerOf("shop", NameClashAvoidance.USE_PREFIX, new WiredProcess("Order", "shop__Order", Set.of(
                JOB_TYPE), true, true, Map.of("Placed", BpmsStartTrigger.Kind.TIMER), Map.of("Shipped",
                        ZenBpmMarkerTasks.Ending.BRANCH, "Withdrawn", ZenBpmMarkerTasks.Ending.WORKFLOW), Map.of()));
        dispatcher.register(handler);
        holdsTheModel(DEFINITION_KEY, modelOfOrder("Shipped", "Withdrawn"));
    }

    /**
     * The handler of a workflow module claiming processes, which every job type of has the
     * default job-timeout.
     *
     * @param workflowModuleId The workflow module
     * @param mode The name-clash-avoidance mode of the module
     * @param processes The processes it claims
     * @return The handler, not registered
     */
    ZenBpmJobHandler handlerOf(
        final String workflowModuleId,
        final NameClashAvoidance mode,
        final WiredProcess... processes
    ) {
        final var context = new ZenBpmProcessingContext(workflowModuleId);
        for (final var process : processes) {
            context.recordWiring(process);
        }
        return new ZenBpmJobHandler("orders", factory, TestCollaborators.withInvokers("orders", TestScoping.of(mode),
                invoker, starts, ends), context, (
                    module,
                    process,
                    taskDefinition) -> Optional.ofNullable(retryBackoffs.get("%s/%s/%s".formatted(module, process,
                            taskDefinition))));
    }

    /**
     * Lets the engine hold an instance of <code>shop__Order</code>, version 3.
     *
     * @param key The instance key
     * @param processType <code>default</code>, <code>subprocess</code> or <code>callActivity</code>
     * @param parentKey The instance it is part of or was called from, <code>null</code> for none
     * @param businessKey The business key, <code>null</code> for none
     * @param variables The variables, written as JSON as they are, e.g.
     *            <code>"id": "order-1"</code>
     */
    void holds(
        final long key,
        final String processType,
        final Long parentKey,
        final String businessKey,
        final String variables
    ) {
        holds(key, "shop__Order", processType, parentKey, businessKey, variables);
    }

    /**
     * Lets the engine hold an instance of a process, version 3.
     *
     * @param key The instance key
     * @param scopedProcessId The process as the engine knows it
     * @param processType <code>default</code>, <code>subprocess</code> or <code>callActivity</code>
     * @param parentKey The instance it is part of or was called from, <code>null</code> for none
     * @param businessKey The business key, <code>null</code> for none
     * @param variables The variables, written as JSON as they are
     */
    void holds(
        final long key,
        final String scopedProcessId,
        final String processType,
        final Long parentKey,
        final String businessKey,
        final String variables
    ) {
        holds(key, scopedProcessId, processType, parentKey, businessKey, variables, List.of());
    }

    /**
     * Lets the engine hold an instance of a process, version 3, whose tokens stand at elements.
     *
     * @param key The instance key
     * @param scopedProcessId The process as the engine knows it
     * @param processType <code>default</code>, <code>subprocess</code> or <code>callActivity</code>
     * @param parentKey The instance it is part of or was called from, <code>null</code> for none
     * @param businessKey The business key, <code>null</code> for none
     * @param variables The variables, written as JSON as they are
     * @param standingAt The element of each token of the instance
     */
    void holds(
        final long key,
        final String scopedProcessId,
        final String processType,
        final Long parentKey,
        final String businessKey,
        final String variables,
        final List<String> standingAt
    ) {
        holds(key, scopedProcessId, processType, parentKey, businessKey, variables, standingAt, "active");
    }

    /**
     * Lets the engine hold a root instance of <code>shop__Order</code> of another version, whose
     * tokens stand at elements.
     *
     * @param key The instance key
     * @param definitionKey The key of the version
     * @param version The version
     * @param businessKey The business key, <code>null</code> for none
     * @param variables The variables, written as JSON as they are
     * @param standingAt The element of each token of the instance
     */
    void holdsOfVersion(
        final long key,
        final long definitionKey,
        final int version,
        final String businessKey,
        final String variables,
        final List<String> standingAt
    ) {
        holds(key, definitionKey, version, "shop__Order", "default", null, businessKey, variables, standingAt,
                "active");
    }

    /**
     * Lets the engine answer the model of a version of a process by its key.
     *
     * @param definitionKey The key of the version
     * @param bpmn The model as XML
     */
    void holdsTheModel(
        final long definitionKey,
        final String bpmn
    ) {
        engine.answers("GET", "/v1/process-definitions/%d".formatted(definitionKey), 200, """
                {"key": %d, "version": 3, "bpmnProcessId": "shop__Order", "bpmnData": %s}""".formatted(definitionKey,
                asJsonString(bpmn)));
    }

    /**
     * A model of <code>shop__Order</code> whose start splits into two branches, each with an end
     * marker in front of its end event: one which ends its branch, and a terminate end event.
     *
     * @param branchEnd The end event which ends its branch
     * @param terminateEnd The terminate end event
     * @return The model as XML
     */
    static String modelOfOrder(
        final String branchEnd,
        final String terminateEnd
    ) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="Definitions_Order" \
                targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="shop__Order" isExecutable="true">
                    <bpmn:startEvent id="Placed"><bpmn:outgoing>toSplit</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:parallelGateway id="Split">
                      <bpmn:incoming>toSplit</bpmn:incoming>
                      <bpmn:outgoing>toBranchMarker</bpmn:outgoing>
                      <bpmn:outgoing>toTerminateMarker</bpmn:outgoing>
                    </bpmn:parallelGateway>
                    <bpmn:serviceTask id="vanillabp__ended__%1$s">
                      <bpmn:extensionElements><zenbpm:taskDefinition type="vanillabp__ended__shop__Order" />\
                </bpmn:extensionElements>
                      <bpmn:incoming>toBranchMarker</bpmn:incoming>
                      <bpmn:outgoing>toBranchEnd</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="%1$s"><bpmn:incoming>toBranchEnd</bpmn:incoming></bpmn:endEvent>
                    <bpmn:serviceTask id="vanillabp__ended__%2$s">
                      <bpmn:extensionElements><zenbpm:taskDefinition type="vanillabp__ended__shop__Order" />\
                </bpmn:extensionElements>
                      <bpmn:incoming>toTerminateMarker</bpmn:incoming>
                      <bpmn:outgoing>toTerminateEnd</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="%2$s">
                      <bpmn:incoming>toTerminateEnd</bpmn:incoming>
                      <bpmn:terminateEventDefinition />
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="toSplit" sourceRef="Placed" targetRef="Split" />
                    <bpmn:sequenceFlow id="toBranchMarker" sourceRef="Split" targetRef="vanillabp__ended__%1$s" />
                    <bpmn:sequenceFlow id="toBranchEnd" sourceRef="vanillabp__ended__%1$s" targetRef="%1$s" />
                    <bpmn:sequenceFlow id="toTerminateMarker" sourceRef="Split" targetRef="vanillabp__ended__%2$s" />
                    <bpmn:sequenceFlow id="toTerminateEnd" sourceRef="vanillabp__ended__%2$s" targetRef="%2$s" />
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(branchEnd, terminateEnd);
    }

    /**
     * Lets the engine hold a root instance of <code>shop__Order</code>, version 3, which completed,
     * so no token of it stands anywhere.
     *
     * @param key The instance key
     * @param businessKey The business key, <code>null</code> for none
     * @param variables The variables, written as JSON as they are
     */
    void holdsEnded(
        final long key,
        final String businessKey,
        final String variables
    ) {
        holds(key, "shop__Order", "default", null, businessKey, variables, List.of(), "completed");
    }

    /**
     * Lets the engine answer the child instances of an instance, each of <code>shop__Order</code>
     * as an event sub-process starts it.
     *
     * @param key The instance key
     * @param states The state of each child, e.g. <code>active</code>
     */
    void hasChildren(
        final long key,
        final String... states
    ) {
        final var children = new ArrayList<String>();
        for (var index = 0; index < states.length; ++index) {
            children.add("""
                    {"key": %d, "processDefinitionKey": 4503599627370498, "bpmnProcessId": "shop__Order", \
                    "version": 3, "createdAt": "2026-10-17T14:30:25.123Z", "state": "%s", \
                    "parentProcessInstanceKey": %d, "processType": "subprocess", "variables": {}}"""
                    .formatted(key + index + 1, states[index], key));
        }
        engine.answers("GET", "/v1/process-instances/%d/child-processes".formatted(key), 200, """
                {"partitions": [{"partition": 1, "totalCount": %d, "items": [%s]}], "page": 1, "size": 100, \
                "count": %d, "totalCount": %d}""".formatted(children.size(), String.join(",", children), children
                .size(), children.size()));
    }

    /**
     * Lets the engine take the commands about job 42 and the variable updates of the given
     * instances.
     *
     * @param instanceKeys The instances
     */
    void takesTheCommands(final long... instanceKeys) {
        for (final var key : instanceKeys) {
            engine.answers("PATCH", "/v1/process-instances/%d/variables".formatted(key), 204, null);
        }
        engine.answers("POST", "/v1/jobs/42/complete", 201, null);
        engine.answers("POST", "/v1/jobs/42/fail", 204, null);
    }

    /**
     * Job 42 of the module's type in an instance, delivered just now with a full lock and delivery
     * token 7, for the element <code>Ship</code>, with the input variables <code>amount</code> (12)
     * and <code>note</code>.
     *
     * @param instanceKey The instance of the job
     * @return The job
     */
    static ReceivedJob job(final long instanceKey) {
        return job(42, instanceKey, JOB_TYPE, Instant
                .now()
                .plus(JOB_TIMEOUT), 7);
    }

    /**
     * A job delivered by an engine whose clock agrees with the application's: received
     * {@link #JOB_TIMEOUT} before its lock lapses.
     *
     * @param key The job key
     * @param instanceKey The instance of the job
     * @param jobType The job type
     * @param lockUntil When its lock lapses
     * @param deliveryToken The delivery token, <code>0</code> for none
     * @return The job
     */
    static ReceivedJob job(
        final long key,
        final long instanceKey,
        final String jobType,
        final Instant lockUntil,
        final long deliveryToken
    ) {
        return new ReceivedJob(WaitingJob
                .newBuilder()
                .setKey(key)
                .setInstanceKey(instanceKey)
                .setType(jobType)
                .setElementId("Ship")
                .setLockUntil(lockUntil.toEpochMilli())
                .setDeliveryToken(deliveryToken)
                .setInputVariables(ByteString.copyFromUtf8("{\"amount\": 12, \"note\": \"gift\"}"))
                .build(), lockUntil.minus(JOB_TIMEOUT));
    }

    /**
     * Job 42 of a start marker of <code>shop__Order</code> in an instance, delivered just now with a
     * full lock, delivery token 7 and one retry, as the first attempt.
     *
     * @param instanceKey The instance of the job
     * @param elementId The marker's id, e.g. <code>vanillabp__started__Placed</code>
     * @param inputVariables The job's variables as JSON
     * @return The job
     */
    static ReceivedJob startMarkerJob(
        final long instanceKey,
        final String elementId,
        final String inputVariables
    ) {
        final var lockUntil = Instant
                .now()
                .plus(JOB_TIMEOUT);
        return new ReceivedJob(WaitingJob
                .newBuilder()
                .setKey(42)
                .setInstanceKey(instanceKey)
                .setType(START_MARKER_TYPE)
                .setElementId(elementId)
                .setLockUntil(lockUntil.toEpochMilli())
                .setDeliveryToken(7)
                .setRetries(1)
                .setAttempt(1)
                .setInputVariables(ByteString.copyFromUtf8(inputVariables))
                .build(), lockUntil.minus(JOB_TIMEOUT));
    }

    /**
     * Job 42 of an end marker of <code>shop__Order</code> in an instance, delivered just now with a
     * full lock, delivery token 7 and one retry, as the first attempt.
     *
     * @param instanceKey The instance of the job
     * @param elementId The marker's id, e.g. <code>vanillabp__ended__Shipped</code>
     * @return The job
     */
    static ReceivedJob endMarkerJob(
        final long instanceKey,
        final String elementId
    ) {
        final var lockUntil = Instant
                .now()
                .plus(JOB_TIMEOUT);
        return new ReceivedJob(WaitingJob
                .newBuilder()
                .setKey(42)
                .setInstanceKey(instanceKey)
                .setType(END_MARKER_TYPE)
                .setElementId(elementId)
                .setLockUntil(lockUntil.toEpochMilli())
                .setDeliveryToken(7)
                .setRetries(1)
                .setAttempt(1)
                .setInputVariables(ByteString.copyFromUtf8("{\"id\": \"order-1\"}"))
                .build(), lockUntil.minus(JOB_TIMEOUT));
    }

    /**
     * Job 42 of the module's type in an instance, as {@link #job(long)} delivers it, with the
     * retries the engine says it has left and the attempt this delivery is.
     *
     * @param instanceKey The instance of the job
     * @param retries The retries the job has before this attempt fails
     * @param attempt The attempt, 1 for the first delivery
     * @return The job
     */
    static ReceivedJob job(
        final long instanceKey,
        final int retries,
        final int attempt
    ) {
        final var plain = job(instanceKey);
        return new ReceivedJob(plain
                .job()
                .toBuilder()
                .setRetries(retries)
                .setAttempt(attempt)
                .build(), plain.receivedAt());
    }

    /**
     * Hands a job to the dispatcher as the job stream does, and waits until every handler slot is
     * free again: on every path a handler gives its slot back.
     *
     * @param received The job
     */
    void runs(final ReceivedJob received) {
        dispatcher.accept(received);
        awaitFreeSlots();
    }

    /**
     * Waits until no handler runs.
     */
    void awaitFreeSlots() {
        await(() -> factory
                .executor()
                .getBusySlots() == 0, "every handler slot to be free");
    }

    /**
     * The requests of the engine other than the reads of instances, as <code>METHOD path</code>.
     *
     * @return The commands, in the order received
     */
    List<String> commands() {
        return engine
                .requests()
                .stream()
                .filter(request -> !"GET".equals(request.method()))
                .map(request -> "%s %s".formatted(request.method(), request.path()))
                .toList();
    }

    /**
     * The bodies of the requests of a method and path, in the order received.
     *
     * @param method The method
     * @param path The path
     * @return The bodies
     */
    List<String> bodiesOf(
        final String method,
        final String path
    ) {
        return engine
                .requests()
                .stream()
                .filter(request -> method.equals(request.method()) && path.equals(request.path()))
                .map(FakeEngine.Request::body)
                .collect(Collectors.toList());
    }

    /**
     * Waits for a condition.
     *
     * @param condition The condition
     * @param what What is waited for, for the failure
     */
    static void await(
        final BooleanSupplier condition,
        final String what
    ) {
        final var deadline = Instant
                .now()
                .plus(PATIENCE);
        while (!condition.getAsBoolean()) {
            assertTrue(Instant
                    .now()
                    .isBefore(deadline), () -> "waited %s for %s".formatted(PATIENCE, what));
            LockSupport.parkNanos(Duration
                    .ofMillis(10)
                    .toNanos());
        }
    }

    @Override
    public void close() {
        factory.close();
        engine.close();
    }

    private static String asJsonString(final String text) {
        try {
            return new ObjectMapper().writeValueAsString(text);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void holds(
        final long key,
        final String scopedProcessId,
        final String processType,
        final Long parentKey,
        final String businessKey,
        final String variables,
        final List<String> standingAt,
        final String state
    ) {
        holds(key, DEFINITION_KEY, 3, scopedProcessId, processType, parentKey, businessKey, variables, standingAt,
                state);
    }

    private void holds(
        final long key,
        final long definitionKey,
        final int version,
        final String scopedProcessId,
        final String processType,
        final Long parentKey,
        final String businessKey,
        final String variables,
        final List<String> standingAt,
        final String state
    ) {
        final var tokens = standingAt
                .stream()
                .map(elementId -> """
                        {"elementInstanceKey": 7, "createdAt": "2026-10-17T14:30:25.123Z", "state": "TokenStateWaiting", \
                        "elementId": "%s"}"""
                        .formatted(elementId))
                .collect(Collectors.joining(","));
        engine.answers("GET", "/v1/process-instances/%d".formatted(key), 200,
                """
                        {
                          "key": %d,
                          "processDefinitionKey": %d,
                          "bpmnProcessId": "%s",
                          "version": %d,
                          %s
                          "createdAt": "2026-10-17T14:30:25.123Z",
                          "state": "%s",
                          %s
                          "processType": "%s",
                          "variables": {%s},
                          "activeElementInstances": [%s]
                        }
                        """.formatted(key, definitionKey, scopedProcessId, version,
                        businessKey == null ? "" : "\"businessKey\": \"%s\",".formatted(
                                businessKey),
                        state, parentKey == null ? ""
                                : "\"parentProcessInstanceKey\": %d,".formatted(
                                        parentKey),
                        processType, variables, tokens));
    }

}
