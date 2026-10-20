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
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.KEY_NOBODY_HOLDS;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.KEY_OF_NO_PARTITION;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.eventually;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.fixture;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.start;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.uniqueBusinessKey;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.waitingJobOf;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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
import org.pbinitiative.zenbpmadapter.client.api.Page;
import org.pbinitiative.zenbpmadapter.client.api.ProcessDefinitionRef;
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
        client.deployBpmn(fixture("contract-received.bpmn"));
        client.deployBpmn(fixture("contract-started.bpmn"));
        client.deployBpmn(fixture("contract-start-fails.bpmn"));
        client.deployBpmn(fixture("contract-start-mapped.bpmn"));
        client.deployBpmn(fixture("contract-iterations.bpmn"));
        client.deployBpmn(fixture("contract-multi-instance.bpmn"));
        client.deployBpmn(fixture("contract-multi-instance-call.bpmn"));
        decision = client.deployDmn(fixture("contract-decision.dmn"));
    }

    @AfterAll
    static void closeTheClient() {
        client.close();
    }

    @Test
    @DisplayName("The engine answers ready and names its commit, its node and its partition")
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
        assertEquals(List.of(1), status.partitions(), status::toString);
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
    @DisplayName("Of two versions of a process, both are listed in key order, and the latest one alone")
    void ofTwoVersionsTheLatestIsListedAlone() {
        final var first = client.deployBpmn(fixture("contract-versions.bpmn"));
        final var second = client.deployBpmn(new String(fixture("contract-versions.bpmn"), StandardCharsets.UTF_8)
                .replace("Version 1", "Version 2")
                .getBytes(StandardCharsets.UTF_8));

        assertTrue(second.created());
        assertEquals(List.of(first.key(), second.key()), client
                .processDefinitions("contract-versions", false)
                .stream()
                .map(ProcessDefinitionRef::key)
                .toList());
        final var latest = client.processDefinitions("contract-versions", true);
        assertEquals(List.of(second.key()), latest
                .stream()
                .map(ProcessDefinitionRef::key)
                .toList());
        assertEquals(2, latest
                .getFirst()
                .version());
    }

    @Test
    @DisplayName("A search of one instance a page reads the engine's totals, and its last page says so")
    void aSearchIsPaged() {
        final var definition = client.deployBpmn(fixture("contract-paged.bpmn"));
        final var started = List.of(
                client.createInstance(CreateInstanceRequest.version(definition.key())),
                client.createInstance(CreateInstanceRequest.version(definition.key())));
        final var filter = InstanceFilter
                .anyInstance()
                .withProcessDefinitionKey(definition.key())
                .withSize(1);

        final var first = client.instances(filter);
        final var second = client.instances(filter.withPage(2));

        assertEquals(List.of(2L, 2L), List.of(first.totalCount(), second.totalCount()));
        assertEquals(List.of(false, true), List.of(first.last(), second.last()));
        assertEquals(started
                .stream()
                .map(ProcessInstanceRef::key)
                .sorted()
                .toList(),
                Stream
                        .of(first, second)
                        .flatMap(page -> page
                                .items()
                                .stream())
                        .map(ProcessInstanceRef::key)
                        .toList());
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

        final var started = start(client, businessKey);

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
        final var job = waitingJobOf(client, started);
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

    /**
     * The engine reads every number as a float of 64 bits, which holds a whole number exactly up
     * to 2^53 only. When an engine keeps larger ones exact, this test fails, and the Javadoc of
     * {@link org.pbinitiative.zenbpmadapter.client.api.Variables} has to say so.
     */
    @Test
    @DisplayName("A whole number beyond 2^53 comes back rounded by the engine, from 10^21 on as a Double")
    void aWholeNumberBeyond2To53ComesBackRounded() {
        final var started = client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-task")
                .withVariables(Map.of(
                        "orderId", uniqueBusinessKey(),
                        "id", (1L << 53) + 1,
                        "beyondALong", new BigInteger("12345678901234567890"),
                        "beyond10To21", BigInteger.TEN.pow(22))));

        final var variables = client
                .instance(started.key())
                .orElseThrow()
                .variables();

        assertAll(
                () -> assertEquals(1L << 53, variables.get("id")),
                () -> assertEquals(new BigInteger("12345678901234567000"), variables.get("beyondALong")),
                () -> assertEquals(1e22, variables.get("beyond10To21")));
    }

    @Test
    @DisplayName("Patched variables are visible in the instance")
    void patchedVariablesAreVisible() {
        final var started = start(client, uniqueBusinessKey());

        client.patchVariables(started.key(), Map.of("note", "checked", "amount", 2));

        final var variables = client
                .instance(started.key())
                .orElseThrow()
                .variables();
        assertEquals("checked", variables.get("note"));
        assertEquals(2L, variables.get("amount"));
    }

    @Test
    @DisplayName("A job keeps the variables it was created with: a variable patched into its instance afterwards is the instance's, not the job's")
    void aJobKeepsTheVariablesItWasCreatedWith() {
        final var started = start(client, uniqueBusinessKey());
        final var job = waitingJobOf(client, started);

        client.patchVariables(started.key(), Map.of("note", "pushed"));

        assertAll(
                () -> assertEquals("pushed", client
                        .instance(started.key())
                        .orElseThrow()
                        .variables()
                        .get("note")),
                () -> assertEquals(job.inputVariables(), client
                        .job(job.key())
                        .orElseThrow()
                        .inputVariables()),
                () -> assertFalse(job
                        .inputVariables()
                        .containsKey("note"), job::toString));
    }

    @Test
    @DisplayName("A job completed twice is answered normally, and a message moves the instance to its end")
    void aJobCompletedTwiceAndAMessage() {
        final var businessKey = uniqueBusinessKey();
        final var started = start(client, businessKey);
        final var job = waitingJobOf(client, started);

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
    @DisplayName("A message which reached the waiting element is answered 404 all the same where the workflow fails right behind it on a process the engine does not hold")
    void aMessageWhichWasReceivedIsA404WhereTheWorkflowFailsBehindIt() {
        final var businessKey = uniqueBusinessKey();
        final var started = client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-received")
                .withBusinessKey(businessKey)
                .withVariables(Map.of("orderId", businessKey)));

        final var failure = assertThrows(ZenBpmApiException.class, () -> client.publishMessage("contract-taken",
                businessKey, Map.of()));

        // a 404 of a message nothing waits for would leave the instance waiting, never failed
        final var failed = eventually(() -> client
                .instance(started.key())
                .filter(instance -> instance.state() == ProcessInstanceRef.State.FAILED));
        final var history = client.history(failed.key(), 1);
        assertAll(
                () -> assertEquals(404, failure.getStatus()),
                () -> assertTrue(ZenBpmErrors.nobodyWaits(failure)),
                () -> assertTrue(history
                        .items()
                        .stream()
                        .anyMatch(entry -> "Wait_Taken".equals(entry.elementId()) && entry.completedAt() != null),
                        history::toString));
    }

    @Test
    @DisplayName("An iteration of a multi-instance sub-process which waited reads only its own variables: its gateway does not see a variable its workflow carried from the start; a write of an instance which ended is answered 409, also below a root which runs")
    void anIterationWhichWaitedReadsOnlyItsOwnVariables() {
        final var started = client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-iterations")
                .withBusinessKey(uniqueBusinessKey())
                .withVariables(Map.of("approved", true)));
        final var body = eventually(() -> client
                .children(started.key())
                .stream()
                .findFirst());

        final var iterations = eventually(() -> {
            final var ended = client
                    .children(body.key())
                    .stream()
                    .filter(iteration -> iteration.state() == ProcessInstanceRef.State.COMPLETED)
                    .toList();
            return ended.size() == 2 ? Optional.of(ended) : Optional.empty();
        });
        final var waitsLong = eventually(() -> client
                .instance(started.key())
                .filter(instance -> instance.state() == ProcessInstanceRef.State.ACTIVE)
                .filter(instance -> client
                        .children(started.key())
                        .stream()
                        .allMatch(child -> child.state() == ProcessInstanceRef.State.COMPLETED)));
        final var write = assertThrows(ZenBpmApiException.class, () -> client.patchVariables(iterations
                .getFirst()
                .key(), Map.of("approved", false)));

        assertAll(
                () -> assertEquals(Boolean.TRUE, waitsLong
                        .variables()
                        .get("approved")),
                () -> assertTrue(iterations
                        .stream()
                        .allMatch(iteration -> client
                                .history(iteration.key(), 1)
                                .items()
                                .stream()
                                .anyMatch(entry -> "Iteration_Held".equals(entry.elementId()))),
                        iterations::toString),
                () -> assertEquals(409, write.getStatus()),
                () -> assertTrue(ZenBpmErrors.variablesFoundTheInstanceEnded(write)));
    }

    @Test
    @DisplayName("An activation of a multi-instance element is a body under its instance which holds a copy of the instance's variables; the iterations of a sub-process are child instances of the body and those of a task are jobs of the body, each with its item, created in the order of the items, and a sequential element's iteration which ended stays listed")
    void theIterationsOfAMultiInstanceElementAreKeptInTheOrderOfTheirItems() {
        final var started = client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-multi-instance")
                .withBusinessKey(uniqueBusinessKey())
                .withVariables(Map.of("items", List.of("x", "y", "z"))));
        final var eachBody = eventually(() -> client
                .children(started.key())
                .stream()
                .findFirst());
        final var eachIterations = eventually(() -> Optional
                .of(inTheOrderCreated(client.children(eachBody.key())))
                .filter(children -> children.size() == 3));
        final var eachJobs = eachIterations
                .stream()
                .map(iteration -> eventually(() -> client
                        .jobsOf(iteration.key())
                        .stream()
                        .findFirst()))
                .toList();

        eachJobs.forEach(job -> client.completeJob(job.key(), null));
        final var oneByOneBody = eventually(() -> client
                .children(started.key())
                .stream()
                .filter(child -> child.key() != eachBody.key())
                .findFirst());
        final var first = eventually(() -> client
                .jobsOf(oneByOneBody.key())
                .stream()
                .findFirst());
        client.completeJob(first.key(), null);
        final var oneByOne = eventually(() -> Optional
                .of(client
                        .jobsOf(oneByOneBody.key())
                        .stream()
                        .sorted(Comparator
                                .comparing(JobRef::createdAt)
                                .thenComparing(JobRef::key))
                        .toList())
                .filter(jobs -> jobs.size() == 2));

        assertAll(
                () -> assertEquals(ProcessInstanceRef.ProcessType.MULTI_INSTANCE, eachBody.processType()),
                () -> assertEquals(List.of("x", "y", "z"), eachBody
                        .variables()
                        .get("items")),
                () -> assertEquals(List.of(ProcessInstanceRef.ProcessType.SUBPROCESS,
                        ProcessInstanceRef.ProcessType.SUBPROCESS, ProcessInstanceRef.ProcessType.SUBPROCESS),
                        eachIterations
                                .stream()
                                .map(ProcessInstanceRef::processType)
                                .toList()),
                () -> assertEquals(List.of("x", "y", "z"), eachIterations
                        .stream()
                        .map(iteration -> iteration
                                .variables()
                                .get("item"))
                        .toList()),
                () -> assertEquals(List.of("x", "y", "z"), eachJobs
                        .stream()
                        .map(job -> job
                                .inputVariables()
                                .get("item"))
                        .toList()),
                () -> assertEquals(ProcessInstanceRef.ProcessType.MULTI_INSTANCE, oneByOneBody.processType()),
                () -> assertEquals(List.of(), client.children(oneByOneBody.key())),
                () -> assertEquals(List.of(JobRef.State.COMPLETED, JobRef.State.ACTIVE), oneByOne
                        .stream()
                        .map(JobRef::state)
                        .toList()),
                () -> assertEquals(List.of("x", "y"), oneByOne
                        .stream()
                        .map(job -> job
                                .inputVariables()
                                .get("item"))
                        .toList()));
    }

    @Test
    @DisplayName("The collection an activation of a multi-instance element iterates over is kept in the history of the instance the element stands in, at the entry of the token waiting at the element, under the name of the input element, and a change of the body's copy does not change it; the body of a multi-instance call activity shows its tokens at the call activity")
    void theCollectionOfAnActivationIsKeptInTheHistory() {
        final var started = client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-multi-instance")
                .withBusinessKey(uniqueBusinessKey())
                .withVariables(Map.of("items", List.of("x", "y", "z"))));
        final var body = eventually(() -> client
                .children(started.key())
                .stream()
                .findFirst());
        final var tokens = eventually(() -> client
                .tokensAt(started.key(), "Each")
                .filter(keys -> !keys.isEmpty()));
        client.patchVariables(body.key(), Map.of("items", List.of("x")));
        final var entries = new ArrayList<HistoryEntry>();
        var page = 0;
        Page<HistoryEntry> read;
        do {
            read = client.history(started.key(), ++page);
            read
                    .items()
                    .stream()
                    .filter(entry -> tokens.contains(entry.key()))
                    .forEach(entries::add);
        } while (!read.last());
        final var caller = client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-multi-instance-call")
                .withBusinessKey(uniqueBusinessKey())
                .withVariables(Map.of("items", List.of("p", "q"))));
        final var callBody = eventually(() -> client
                .children(caller.key())
                .stream()
                .findFirst());
        final var callsWaitingAt = eventually(() -> client
                .activeElementsOf(callBody.key())
                .filter(elements -> elements.size() == 2));

        assertAll(
                () -> assertEquals(1, tokens.size()),
                () -> assertEquals(List.of("Each"), entries
                        .stream()
                        .map(HistoryEntry::elementId)
                        .toList()),
                () -> assertEquals(List.of("x", "y", "z"), entries
                        .getFirst()
                        .inputVariables()
                        .get("item")),
                () -> assertEquals(List.of("x"), client
                        .instance(body.key())
                        .orElseThrow()
                        .variables()
                        .get("items")),
                () -> assertEquals(List.of("CallEach", "CallEach"), callsWaitingAt));
    }

    @Test
    @DisplayName("A start message without a key starts the newest version, is answered without the instance it started, and the instance and the job of its first task carry the message's variables; a search of the process's running instances reads them")
    void aStartMessageIsAnsweredWithoutTheInstanceItStarted() {
        final var aggregateId = uniqueBusinessKey();

        client.publishMessage("contract-started", null, Map.of("orderId", aggregateId, "amount", 1000));

        final var started = eventually(() -> runningInstanceOf("contract-started", aggregateId));
        final var job = eventually(() -> client
                .jobs(JobFilter
                        .anyJob()
                        .withJobType("contract-started-marker")
                        .withProcessInstanceKey(started.key()))
                .items()
                .stream()
                .findFirst());
        assertAll(
                () -> assertNull(started.businessKey()),
                () -> assertEquals(1000L, started
                        .variables()
                        .get("amount")),
                () -> assertEquals(aggregateId, job
                        .inputVariables()
                        .get("orderId")));
    }

    @Test
    @DisplayName("A message start event which maps outputs starts its instance with the mapped variables only, and one whose source the message does not carry maps it to nothing and starts the instance all the same")
    void aMessageStartWhichMapsOutputsHandsOnTheMappedVariablesOnly() {
        final var aggregateId = uniqueBusinessKey();
        final var amountWithoutTheId = ThreadLocalRandom
                .current()
                .nextLong(1_000_000, 1_000_000_000);

        client.publishMessage("contract-start-mapped", null, Map.of("orderId", aggregateId, "amount", 1000, "note",
                "not mapped"));
        client.publishMessage("contract-start-mapped", null, Map.of("amount", amountWithoutTheId));

        final var withTheId = eventually(() -> runningInstanceOf("contract-start-mapped", aggregateId));
        final var withoutTheId = eventually(() -> client
                .instances(InstanceFilter
                        .anyInstance()
                        .withBpmnProcessId("contract-start-mapped")
                        .withState(ProcessInstanceRef.State.ACTIVE))
                .items()
                .stream()
                .filter(instance -> Long
                        .valueOf(amountWithoutTheId)
                        .equals(instance
                                .variables()
                                .get("amount")))
                .findFirst());
        assertAll(
                () -> assertEquals(1000L, withTheId
                        .variables()
                        .get("amount")),
                () -> assertFalse(withTheId
                        .variables()
                        .containsKey("note"), withTheId::toString),
                () -> assertNull(withoutTheId
                        .variables()
                        .get("orderId"), withoutTheId::toString));
    }

    @Test
    @DisplayName("A start message nothing starts with is a 404, and so is one whose instance the engine created and failed right behind the start event on a process it does not hold")
    void aStartMessageIsA404AlsoWhereItsInstanceFailsBehindTheStart() {
        final var aggregateId = uniqueBusinessKey();

        final var nothingStarts = assertThrows(ZenBpmApiException.class, () -> client.publishMessage(
                "contract-never-started", null, Map.of("orderId", aggregateId)));
        final var failsBehind = assertThrows(ZenBpmApiException.class, () -> client.publishMessage(
                "contract-start-fails", null, Map.of("orderId", aggregateId)));

        final var failed = eventually(() -> client
                .instances(InstanceFilter
                        .anyInstance()
                        .withBpmnProcessId("contract-start-fails")
                        .withState(ProcessInstanceRef.State.FAILED))
                .items()
                .stream()
                .filter(instance -> aggregateId.equals(instance
                        .variables()
                        .get("orderId")))
                .findFirst());
        assertAll(
                () -> assertEquals(404, nothingStarts.getStatus()),
                () -> assertTrue(ZenBpmErrors.nobodyWaits(nothingStarts)),
                () -> assertEquals(404, failsBehind.getStatus()),
                () -> assertTrue(ZenBpmErrors.nobodyWaits(failsBehind)),
                () -> assertEquals("contract-start-fails", failed.bpmnProcessId()));
    }

    @Test
    @DisplayName("Start messages of one name published at the same moment lose starts: the engine answers some of them 201 and starts nothing for them, and others 404")
    void startMessagesOfOneNamePublishedAtOnceLoseStarts() throws Exception {
        // the race needs requests which meet inside the engine, so a round which happens to send
        // them one after the other proves nothing and the next round is sent
        var lostOnA201 = false;
        var answered404 = false;
        for (var round = 0; (round < 5) && !(lostOnA201 && answered404); ++round) {
            final var answered = new ArrayList<String>();
            try (var publishers = Executors.newFixedThreadPool(40)) {
                final var publications = new ArrayList<Future<String>>();
                for (var publication = 0; publication < 40; ++publication) {
                    final var aggregateId = uniqueBusinessKey();
                    publications.add(publishers.submit(() -> {
                        try {
                            client.publishMessage("contract-started", null, Map.of("orderId", aggregateId));
                            return aggregateId;
                        } catch (final ZenBpmApiException refusal) {
                            return "%d".formatted(refusal.getStatus());
                        }
                    }));
                }
                for (final var publication : publications) {
                    answered.add(publication.get(30, TimeUnit.SECONDS));
                }
            }
            Thread.sleep(500);
            answered404 |= answered.contains("404");
            lostOnA201 |= answered
                    .stream()
                    .filter(answer -> answer.startsWith("order-"))
                    .anyMatch(aggregateId -> runningInstanceOf("contract-started", aggregateId).isEmpty());
        }

        final var lost = lostOnA201;
        final var refused = answered404;
        assertAll(
                () -> assertTrue(lost,
                        "five rounds of 40 start messages at once lost no start answered 201: has the engine fixed the race?"),
                () -> assertTrue(refused, "five rounds of 40 start messages at once were never answered 404"));
    }

    @Test
    @DisplayName("A job failed with an error code nothing catches fails with an incident")
    void aJobFailedWithAnUncaughtErrorCode() {
        final var started = start(client, uniqueBusinessKey());
        final var job = waitingJobOf(client, started);

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
    @DisplayName("The tokens of an instance stand at the element they wait at and move on with the instance, the one of a call activity at the call activity and not in the called instance, and an instance the engine does not know is empty")
    void theTokensOfAnInstanceStandWhereTheyWait() {
        final var waiting = start(client, uniqueBusinessKey());
        final var parent = client.createInstance(CreateInstanceRequest.latestVersionOf("contract-parent"));
        final var movedOn = start(client, uniqueBusinessKey());
        client.completeJob(waitingJobOf(client, movedOn).key(), Map.of());

        assertAll(
                () -> assertEquals(List.of("Task_Work"), eventually(() -> client
                        .activeElementsOf(waiting.key())
                        .filter(elements -> !elements.isEmpty()))),
                () -> assertEquals(List.of("Call_Task"), eventually(() -> client
                        .activeElementsOf(parent.key())
                        .filter(elements -> !elements.isEmpty()))),
                () -> assertEquals(List.of("Catch_Paid"), eventually(() -> client
                        .activeElementsOf(movedOn.key())
                        .filter(elements -> elements.contains("Catch_Paid")))),
                () -> assertEquals(Optional.empty(), client.activeElementsOf(KEY_NOBODY_HOLDS)));
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
            // a fraction of a second, which the engine refuses unless the client rounds it away
            final var lockUntil = client.extendJobLock(delivered.getKey(), clientId, Duration
                    .ofMinutes(10)
                    .plusMillis(500));

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

    // the instance of a process a start message started for an aggregate, found by its variable as
    // the scan finds it
    private static Optional<ProcessInstanceRef> runningInstanceOf(
        final String bpmnProcessId,
        final String aggregateId
    ) {
        for (var page = 1;; ++page) {
            final var read = client.instances(InstanceFilter
                    .anyInstance()
                    .withBpmnProcessId(bpmnProcessId)
                    .withState(ProcessInstanceRef.State.ACTIVE)
                    .withPage(page));
            final var found = read
                    .items()
                    .stream()
                    .filter(instance -> aggregateId.equals(instance
                            .variables()
                            .get("orderId")))
                    .findFirst();
            if (found.isPresent() || read.last()) {
                return found;
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
                    public void onNext(final JobStreamResponse response) {
                        deliveries.add(response);
                    }

                    @Override
                    public void onError(final Throwable failure) {
                    }

                    @Override
                    public void onCompleted() {
                    }

                });
    }

    private static WaitingJob deliveredJob(final BlockingQueue<JobStreamResponse> deliveries) throws InterruptedException {
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

    private static List<ProcessInstanceRef> inTheOrderCreated(final List<ProcessInstanceRef> instances) {
        return instances
                .stream()
                .sorted(Comparator
                        .comparing(ProcessInstanceRef::createdAt)
                        .thenComparing(ProcessInstanceRef::key))
                .toList();
    }

}
