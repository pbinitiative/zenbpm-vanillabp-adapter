/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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

import com.google.protobuf.ByteString;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;

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

    static final Duration JOB_TIMEOUT = Duration.ofMinutes(5);

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    final FakeEngine engine = FakeEngine.started();

    final TestInvoker invoker = new TestInvoker();

    final ZenBpmClientFactory factory;

    final ZenBpmJobDispatcher dispatcher;

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
        dispatcher.register(handlerOf("shop", NameClashAvoidance.USE_PREFIX,
                new WiredProcess("Order", "shop__Order", Set.of(JOB_TYPE), true, false)));
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
        return new ZenBpmJobHandler("orders", factory, TestCollaborators.withInvoker("orders", TestScoping.of(mode),
                invoker), context);
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
        engine.answers("GET", "/v1/process-instances/%d".formatted(key), 200, """
                {
                  "key": %d,
                  "processDefinitionKey": 4503599627370498,
                  "bpmnProcessId": "%s",
                  "version": 3,
                  %s
                  "createdAt": "2026-10-17T14:30:25.123Z",
                  "state": "active",
                  %s
                  "processType": "%s",
                  "variables": {%s}
                }
                """.formatted(key, scopedProcessId, businessKey == null ? "" : "\"businessKey\": \"%s\",".formatted(
                businessKey), parentKey == null ? "" : "\"parentProcessInstanceKey\": %d,".formatted(parentKey),
                processType, variables));
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

}
