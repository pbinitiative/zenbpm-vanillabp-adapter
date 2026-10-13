/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.DeployedResource;
import org.pbinitiative.zenbpmadapter.client.api.HistoryEntry;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.client.grpc.ZenBpmGrpc;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.JobStreamRequest;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.JobStreamResponse;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.StreamSubscriptionRequest;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.WaitingJob;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Every method of the REST client once against the pinned engine: the requests are the ones the
 * engine accepts, the answers read into the records the client promises, and each status the
 * client treats as an answer rather than a failure is the one the engine sends.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmRestClientContractIT {

    private static final String ADAPTER_ID = "contract";

    /**
     * How long a job may take to arrive over the job stream. The engine hands it out in
     * milliseconds, so the limit only turns a job which never comes into a failure.
     */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    /**
     * How long the engine may take to move an instance on after a command. It answers the command
     * once the instance moved, so this only bounds a test which waits for an effect.
     */
    private static final Duration EFFECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * A key carries the partition which holds it in bits 12 to 21. This is the oldest key partition
     * 1 can hand out, at the epoch of the engine's key generator, so no instance and no job has it.
     */
    private static final long KEY_NOBODY_HOLDS = 1L << 12;

    /**
     * A key of partition 0, which holds what every partition shares, like the process
     * definitions. No instance and no job is routed there.
     */
    private static final long KEY_OF_NO_PARTITION = 1;

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static ZenBpmRestClient client;

    private static DeployedResource task;

    private static DeployedResource decision;

    @BeforeAll
    static void deployTheFixtures() {

        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        client = new ZenBpmRestClient(ADAPTER_ID, configuration);
        task = client.deployBpmn(fixture("contract-task.bpmn"));
        client.deployBpmn(fixture("contract-parent.bpmn"));
        client.deployBpmn(fixture("contract-locked.bpmn"));
        decision = client.deployDmn(fixture("contract-decision.dmn"));

    }

    @AfterAll
    static void closeTheClient() {

        client.close();

    }

    @Test
    @DisplayName("The engine answers ready and names its commit")
    void theEngineAnswersReadyAndNamesItsCommit() {

        final var readiness = client.ready();
        final var status = client.status();

        assertTrue(readiness.ready(), readiness::toString);
        assertEquals(List.of(), readiness.reasons());
        assertTrue(EngineUnderTest
                .pinnedCommit()
                .startsWith(status.commit()), status::toString);
        assertFalse(status
                .version()
                .isBlank());
        assertEquals(1, status
                .nodes()
                .size(), status::toString);

    }

    @Test
    @DisplayName("A second identical deployment creates nothing and answers the same key")
    void aSecondIdenticalDeploymentCreatesNothing() {

        assertTrue(task.created());
        assertTrue(decision.created());

        assertEquals(new DeployedResource(task.key(), false), client.deployBpmn(fixture("contract-task.bpmn")));
        assertEquals(new DeployedResource(decision.key(), false), client.deployDmn(fixture("contract-decision.dmn")));

    }

    @Test
    @DisplayName("A deployed process is read by key, with its BPMN, and among the versions of its id")
    void aDeployedProcessIsRead() {

        final var definition = client.processDefinition(task.key());
        final var versions = client.processDefinitions("contract-task", false);

        assertAll(
                () -> assertEquals(task.key(), definition.key()),
                () -> assertEquals("contract-task", definition.bpmnProcessId()),
                () -> assertEquals(1, definition.version()),
                () -> assertNull(definition.versionTag()),
                () -> assertTrue(definition
                        .bpmnData()
                        .contains("Task_Work")),
                () -> assertEquals(List.of(task.key()), versions
                        .stream()
                        .map(version -> version.key())
                        .toList()),
                () -> assertNull(versions
                        .getFirst()
                        .bpmnData()),
                () -> assertEquals(List.of(), client.processDefinitions("contract-nobody-deployed", false)));

    }

    @Test
    @DisplayName("A definition the engine does not know is a 404")
    void anUnknownDefinitionIsA404() {

        final var failure = assertThrows(ZenBpmApiException.class, () -> client.processDefinition(1));

        assertAll(
                () -> assertEquals(404, failure.getStatus()),
                () -> assertEquals("GET", failure.getMethod()),
                () -> assertEquals("/v1/process-definitions/1", failure.getPath()));

    }

    @Test
    @DisplayName("A started instance is found by its business key, and its waiting job is active")
    void aStartedInstanceIsFoundAndItsJobIsActive() {

        final var businessKey = uniqueBusinessKey();

        final var started = start(businessKey);

        assertAll(
                () -> assertEquals(task.key(), started.definitionKey()),
                () -> assertEquals("contract-task", started.bpmnProcessId()),
                () -> assertEquals(1, started.version()),
                () -> assertEquals(businessKey, started.businessKey()),
                () -> assertEquals(ProcessInstanceRef.State.ACTIVE, started.state()),
                () -> assertEquals(ProcessInstanceRef.ProcessType.DEFAULT, started.processType()),
                () -> assertNull(started.parentKey()),
                () -> assertNotNull(started.createdAt()));
        final var found = client.instances(InstanceFilter
                .anyInstance()
                .withBusinessKey(businessKey)
                .withBpmnProcessId("contract-task")
                .withProcessDefinitionKey(task.key())
                .withState(ProcessInstanceRef.State.ACTIVE)
                .withCreatedFrom(started
                        .createdAt()
                        .minusSeconds(60))
                .withSize(1));
        assertEquals(1, found.totalCount());
        assertEquals(List.of(started.key()), found
                .items()
                .stream()
                .map(ProcessInstanceRef::key)
                .toList());
        final var job = waitingJobOf(started);
        assertAll(
                () -> assertEquals(JobRef.State.ACTIVE, job.state()),
                () -> assertEquals("contract-work", job.type()),
                () -> assertEquals("Task_Work", job.elementId()),
                () -> assertEquals("SERVICE_TASK", job.elementType()),
                () -> assertEquals(started.key(), job.instanceKey()),
                () -> assertNull(job.assignee()),
                () -> assertEquals(Map.of("orderId", businessKey), job.inputVariables()),
                () -> assertEquals(Optional.of(job), client.job(job.key())));
        assertTrue(client
                .processDefinitionStatistics(task.key())
                .elements()
                .get("Task_Work")
                .active() >= 1);

    }

    @Test
    @DisplayName("A whole number sent as a fraction comes back as a Long")
    void aWholeNumberComesBackAsALong() {

        final var started = client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-task")
                .withVariables(Map.of("orderId", uniqueBusinessKey(), "amount", 1000.0, "rate", 0.5)));

        final var variables = client
                .instance(started.key())
                .orElseThrow()
                .variables();

        assertEquals(1000L, variables.get("amount"));
        assertEquals(0.5, variables.get("rate"));

    }

    @Test
    @DisplayName("Patched variables are visible in the instance")
    void patchedVariablesAreVisible() {

        final var started = start(uniqueBusinessKey());

        client.patchVariables(started.key(), Map.of("note", "checked", "amount", 2));

        final var variables = client
                .instance(started.key())
                .orElseThrow()
                .variables();
        assertEquals("checked", variables.get("note"));
        assertEquals(2L, variables.get("amount"));

    }

    @Test
    @DisplayName("A job completed twice is answered normally, and a message moves the instance to its end")
    void aJobCompletedTwiceAndAMessage() {

        final var businessKey = uniqueBusinessKey();
        final var started = start(businessKey);
        final var job = waitingJobOf(started);

        client.completeJob(job.key(), Map.of("worked", true));
        client.completeJob(job.key(), Map.of("worked", true));
        client.publishMessage("contract-paid", businessKey, Map.of("paid", true));

        final var ended = eventually(() -> client
                .instance(started.key())
                .filter(instance -> instance.state() == ProcessInstanceRef.State.COMPLETED));
        assertEquals(true, ended
                .variables()
                .get("paid"));
        assertEquals(JobRef.State.COMPLETED, client
                .job(job.key())
                .orElseThrow()
                .state());
        final var history = client.history(started.key(), 1);
        assertTrue(history.last());
        assertTrue(history
                .items()
                .stream()
                .map(HistoryEntry::elementId)
                .toList()
                .containsAll(List.of("StartEvent", "Task_Work", "Catch_Paid", "EndEvent")), history::toString);
        assertTrue(history.totalCount() >= 4);

    }

    @Test
    @DisplayName("A message nothing waits for is a 404")
    void aMessageNothingWaitsForIsA404() {

        final var failure = assertThrows(ZenBpmApiException.class,
                () -> client.publishMessage("contract-paid", uniqueBusinessKey(), null));

        assertAll(
                () -> assertEquals(404, failure.getStatus()),
                () -> assertEquals("POST", failure.getMethod()),
                () -> assertEquals("/v1/messages", failure.getPath()));

    }

    @Test
    @DisplayName("A job failed with an error code nothing catches fails with an incident")
    void aJobFailedWithAnUncaughtErrorCode() {

        final var started = start(uniqueBusinessKey());
        final var job = waitingJobOf(started);

        client.failJob(job.key(), "contract-nobody-catches", Map.of("reason", "test"));

        assertEquals(JobRef.State.FAILED, eventually(() -> client
                .job(job.key())
                .filter(failed -> failed.state() == JobRef.State.FAILED)).state());

    }

    @Test
    @DisplayName("The child a call activity started is found with its parent")
    void theChildOfACallActivityIsFound() {

        final var parent = client.createInstance(CreateInstanceRequest.latestVersionOf("contract-parent"));

        final var children = client.children(parent.key());

        assertEquals(1, children.size(), children::toString);
        final var child = children.getFirst();
        assertAll(
                () -> assertEquals("contract-task", child.bpmnProcessId()),
                () -> assertEquals(ProcessInstanceRef.ProcessType.CALL_ACTIVITY, child.processType()),
                () -> assertEquals(parent.key(), child.parentKey()));

    }

    @Test
    @DisplayName("An instance and a job the engine does not know are empty")
    void unknownInstancesAndJobsAreEmpty() {

        assertEquals(Optional.empty(), client.instance(KEY_NOBODY_HOLDS));
        assertEquals(Optional.empty(), client.job(KEY_NOBODY_HOLDS));

    }

    @Test
    @DisplayName("A key of a partition the engine does not have is a 502, not a 404")
    void aKeyOfAnUnknownPartitionIsA502() {

        final var instance = assertThrows(ZenBpmApiException.class, () -> client.instance(KEY_OF_NO_PARTITION));
        final var job = assertThrows(ZenBpmApiException.class, () -> client.job(KEY_OF_NO_PARTITION));

        assertEquals(502, instance.getStatus());
        assertEquals(502, job.getStatus());

    }

    /**
     * The pinned engine does not serve its OpenAPI document. When an engine does, this test
     * fails, and the check of the copy against the engine's files can become a check against the
     * running engine, see decision 18 in the repository's DECISIONS.md.
     */
    @Test
    @DisplayName("The pinned engine does not serve its OpenAPI document")
    void theEngineDoesNotServeItsOpenApiDocument() {

        assertEquals(Optional.empty(), client.openApi());

    }

    @Test
    @DisplayName("The lock of a job delivered over the job stream is extended for its client only")
    void theLockOfADeliveredJobIsExtended() throws Exception {

        final var clientId = "contract-%s".formatted(UUID.randomUUID());
        final var channel = ManagedChannelBuilder
                .forTarget(ENGINE.grpcAddress())
                .usePlaintext()
                .build();
        final var deliveries = new LinkedBlockingQueue<JobStreamResponse>();
        try {
            final var requests = jobStream(channel, clientId, deliveries);
            requests.onNext(JobStreamRequest
                    .newBuilder()
                    .setSubscription(StreamSubscriptionRequest
                            .newBuilder()
                            .setJobType("contract-locked-work")
                            .setType(StreamSubscriptionRequest.Type.TYPE_SUBSCRIBE)
                            .setLockDurationMs(Duration
                                    .ofMinutes(1)
                                    .toMillis())
                            .setMaxActiveJobs(1))
                    .build());
            final var started = client.createInstance(CreateInstanceRequest.latestVersionOf("contract-locked"));
            final var delivered = deliveredJob(deliveries);
            assertEquals(started.key(), delivered.getInstanceKey());

            final var before = Instant.now();
            final var lockUntil = client.extendJobLock(delivered.getKey(), clientId, Duration.ofMinutes(10));

            assertTrue(lockUntil.isAfter(before.plus(Duration.ofMinutes(9))), lockUntil::toString);
            final var failure = assertThrows(ZenBpmApiException.class,
                    () -> client.extendJobLock(delivered.getKey(), "contract-another-client", Duration.ofMinutes(10)));
            assertEquals(409, failure.getStatus());
            assertEquals("/v1/jobs/%d/extend-lock".formatted(delivered.getKey()), failure.getPath());
            requests.onCompleted();
        } finally {
            channel
                    .shutdownNow()
                    .awaitTermination(10, TimeUnit.SECONDS);
        }

    }

    private static byte[] fixture(
        final String name
    ) {

        try (var resource = ZenBpmRestClientContractIT.class.getResourceAsStream("/contract/%s".formatted(name))) {
            return resource.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

    private static String uniqueBusinessKey() {

        return "order-%s".formatted(UUID.randomUUID());

    }

    private static ProcessInstanceRef start(
        final String businessKey
    ) {

        return client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-task")
                .withBusinessKey(businessKey)
                .withVariables(Map.of("orderId", businessKey)));

    }

    private static JobRef waitingJobOf(
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

    private static <T> T eventually(
        final Supplier<Optional<T>> read
    ) {

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

    private static StreamObserver<JobStreamRequest> jobStream(
        final io.grpc.Channel channel,
        final String clientId,
        final BlockingQueue<JobStreamResponse> deliveries
    ) {

        final var headers = new Metadata();
        headers.put(Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER), clientId);
        return ZenBpmGrpc
                .newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .jobStream(new StreamObserver<>() {

                    @Override
                    public void onNext(
                        final JobStreamResponse response
                    ) {

                        deliveries.add(response);

                    }

                    @Override
                    public void onError(
                        final Throwable failure
                    ) {
                    }

                    @Override
                    public void onCompleted() {
                    }

                });

    }

    private static WaitingJob deliveredJob(
        final BlockingQueue<JobStreamResponse> deliveries
    ) throws InterruptedException {

        final var deadline = System.nanoTime() + DELIVERY_TIMEOUT.toNanos();
        while (true) {
            final var response = deliveries.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (response == null) {
                throw new AssertionError("No job was delivered within %s".formatted(DELIVERY_TIMEOUT));
            }
            if (response.hasJob()) {
                return response.getJob();
            }
        }

    }

}
