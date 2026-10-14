/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;

/**
 * What the integration tests against the pinned engine share: the models under
 * <code>/contract</code>, keys no instance and no job has, a start of <code>contract-task</code>
 * and a wait for what the engine shows a moment after a command.
 */
final class ContractFixtures {

    /**
     * A key carries the partition which holds it in bits 12 to 21. This is the oldest key partition
     * 1 can hand out, at the epoch of the engine's key generator, so no instance and no job has it.
     */
    static final long KEY_NOBODY_HOLDS = 1L << 12;

    /**
     * A key of partition 0, which holds what every partition shares, like the process
     * definitions. No instance and no job is routed there.
     */
    static final long KEY_OF_NO_PARTITION = 1;

    /**
     * How long the engine may take to move an instance on after a command. It answers the command
     * once the instance moved, so this only bounds a test which waits for an effect.
     */
    private static final Duration EFFECT_TIMEOUT = Duration.ofSeconds(10);

    private ContractFixtures() {
    }

    /**
     * A model or decision of <code>src/test/resources/contract</code>.
     *
     * @param name The file name
     * @return The file's bytes
     */
    static byte[] fixture(final String name) {
        try (var resource = ContractFixtures.class.getResourceAsStream("/contract/%s".formatted(name))) {
            return resource.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A model of one process with one service task between its start and its end. A test which
     * gives it a job type of its own receives no job of another test sharing the engine.
     *
     * @param processId The BPMN process id
     * @param jobType The job type of the service task
     * @return The model's bytes
     */
    static byte[] oneServiceTask(
        final String processId,
        final String jobType
    ) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="Definitions_%1$s" \
                targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="%1$s" isExecutable="true">
                    <bpmn:startEvent id="StartEvent">
                      <bpmn:outgoing>Flow_ToWork</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:sequenceFlow id="Flow_ToWork" sourceRef="StartEvent" targetRef="Task_Work" />
                    <bpmn:serviceTask id="Task_Work" name="Work">
                      <bpmn:extensionElements>
                        <zenbpm:taskDefinition type="%2$s" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_ToWork</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToEnd</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:sequenceFlow id="Flow_ToEnd" sourceRef="Task_Work" targetRef="EndEvent" />
                    <bpmn:endEvent id="EndEvent">
                      <bpmn:incoming>Flow_ToEnd</bpmn:incoming>
                    </bpmn:endEvent>
                  </bpmn:process>
                </bpmn:definitions>
                """
                .formatted(processId, jobType)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A business key no other test uses, so the tests sharing one engine never see each other's
     * instances.
     *
     * @return The business key
     */
    static String uniqueBusinessKey() {
        return "order-%s".formatted(UUID.randomUUID());
    }

    /**
     * Starts the latest version of <code>contract-task</code>, which waits in a job of type
     * <code>contract-work</code>.
     *
     * @param client The client of the engine the model is deployed to
     * @param businessKey The business key, also handed in as the variable <code>orderId</code>
     * @return The started instance
     */
    static ProcessInstanceRef start(
        final ZenBpmRestClient client,
        final String businessKey
    ) {
        return client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-task")
                .withBusinessKey(businessKey)
                .withVariables(Map.of("orderId", businessKey)));
    }

    /**
     * The job an instance of <code>contract-task</code> waits in, once the engine shows it.
     *
     * @param client The client of the engine the instance runs in
     * @param instance The instance
     * @return The waiting job
     */
    static JobRef waitingJobOf(
        final ZenBpmRestClient client,
        final ProcessInstanceRef instance
    ) {
        return eventually(() -> client
                .jobs(JobFilter
                        .anyJob()
                        .withJobType("contract-work")
                        .withState(JobRef.State.ACTIVE)
                        .withProcessInstanceKey(instance.key()))
                .items()
                .stream()
                .findFirst());
    }

    /**
     * Reads until the read finds something, for at most {@link #EFFECT_TIMEOUT}.
     *
     * @param <T> The type of what is read
     * @param read The read, empty while the effect is not there yet
     * @return What the read found
     */
    static <T> T eventually(final Supplier<Optional<T>> read) {
        final var deadline = System.nanoTime() + EFFECT_TIMEOUT.toNanos();
        while (true) {
            final var value = read.get();
            if (value.isPresent()) {
                return value.get();
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Nothing was read within %s".formatted(EFFECT_TIMEOUT));
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread
                        .currentThread()
                        .interrupt();
                throw new AssertionError(e);
            }
        }
    }

}
