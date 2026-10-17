/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.HistoryEntry;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.springboot.it.task.Parcel;
import org.pbinitiative.zenbpmadapter.springboot.it.task.ParcelRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.task.TaskItApplication;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * The tasks of a booted Spring Boot application run against the pinned engine: a service task, a
 * send task, a business rule task served by a job and a service task inside an embedded
 * sub-process, each delivered to its <code>&#64;WorkflowTask</code> method and completed. A gateway
 * behind a task, and one behind the sub-process, decide on what the handler before them computed,
 * because the adapter writes the shared values into the engine before it completes the job. A
 * parameter the model maps into a task is bound, a workflow somebody started past VanillaBP with
 * nothing but a business key is served by that key, the job of a workflow whose aggregate this
 * application does not hold ends in an incident whose message is the platform's, and the
 * job-timeout of a task is resolved from the most specific level of the configuration.
 * <p>
 * The application runs the workflow module <code>deploy-it</code> under <code>use-prefix</code> with
 * the process <code>TaskMatrix</code> of <code>task-it/task-matrix.bpmn</code>, configured by
 * <code>task-it.yaml</code>. It is started once for the class, against an engine of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TaskItApplication.class, properties = "spring.config.name=task-it")
@DirtiesContext
class ZenBpmTaskProcessingIT {

    private static final String SCOPED_PROCESS_ID = "deploy-it__TaskMatrix";

    /**
     * How long a workflow may take to run through. Each task takes milliseconds, so the limit only
     * turns a workflow which never ends into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Autowired
    private ProcessService<Parcel> processService;

    @Autowired
    private ParcelRepository parcels;

    @Autowired
    private ZenBpmClientRegistry clients;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ZenBpmDeploymentService deploymentService;

    @Test
    @DisplayName("A service task, a send task, a business rule task served by a job and a service task in a sub-process are each delivered once and completed, and the workflow ends")
    void happyPathRunsEveryTaskKind() {
        final var parcelId = uniqueParcelId();

        final var ended = runThrough(parcelId);

        final var parcel = parcels
                .findById(parcelId)
                .orElseThrow();
        final var deliveries = deliveriesOf(parcelId);
        assertAll(
                () -> assertTrue(parcel.isWeighed()),
                () -> assertTrue(parcel.isCourierNotified()),
                () -> assertTrue(parcel.isRated()),
                () -> assertTrue(parcel.isPacked()),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(Set.of("Weigh", "NotifyCourier", "Rate", "Pack"), deliveries
                        .stream()
                        .map(TaskDeliveryLogReader.Delivery::bpmnElementId)
                        .collect(Collectors.toSet())),
                () -> assertEquals(4, deliveries.size(), deliveries::toString),
                () -> assertTrue(deliveries
                        .stream()
                        .allMatch(delivery -> "TASK".equals(delivery.taskKind()) && "1".equals(delivery
                                .processVersion()) && "COMPLETED".equals(delivery.outcome())),
                        deliveries::toString));
    }

    @Test
    @DisplayName("The gateway behind a task decides on what its handler computed: the workflow started with express false, the handler made the parcel express, and the courier is notified")
    void gatewayAfterTaskSeesTheNewValues() {
        final var parcelId = uniqueParcelId();

        final var ended = runThrough(parcelId);

        assertAll(
                () -> assertTrue(visited(ended).contains("NotifyCourier"), () -> visited(ended).toString()),
                () -> assertTrue(parcels
                        .findById(parcelId)
                        .orElseThrow()
                        .isCourierNotified()));
    }

    @Test
    @DisplayName("A task inside an embedded sub-process names the root instance as its workflow, and the gateway behind the sub-process decides on what its handler computed")
    void aTaskInASubProcessNamesTheRootAndTheGatewayBehindItSeesTheValues() {
        final var parcelId = uniqueParcelId();

        final var ended = runThrough(parcelId);

        final var pack = deliveriesOf(parcelId)
                .stream()
                .filter(delivery -> "Pack".equals(delivery.bpmnElementId()))
                .findFirst()
                .orElseThrow();
        final var packJob = rest()
                .jobs(JobFilter
                        .anyJob()
                        .withJobType("deploy-it__TaskMatrix__pack"))
                .items()
                .stream()
                .filter(job -> String
                        .valueOf(job.key())
                        .equals(pack.taskId()))
                .findFirst()
                .orElseThrow();
        assertAll(
                () -> assertEquals(String.valueOf(ended.key()), pack.workflowId()),
                () -> assertTrue(packJob.instanceKey() != ended.key(), packJob::toString),
                () -> assertTrue(visited(ended).contains("Packed"), () -> visited(ended).toString()));
    }

    @Test
    @DisplayName("A parameter the model maps into a task is bound to the method's @TaskParam")
    void aDeclaredTaskParameterIsBound() {
        final var parcelId = uniqueParcelId();

        runThrough(parcelId);

        assertEquals("standard", parcels
                .findById(parcelId)
                .orElseThrow()
                .getTariff());
    }

    @Test
    @DisplayName("A workflow somebody started past VanillaBP with nothing but a business key is served by the aggregate the key names")
    void theAggregateIdIsBoundFromTheBusinessKey() {
        final var parcelId = uniqueParcelId();
        transactionTemplate.executeWithoutResult(status -> parcels.save(new Parcel(parcelId)));

        final var started = rest().createInstance(CreateInstanceRequest
                .latestVersionOf(SCOPED_PROCESS_ID)
                .withBusinessKey(parcelId));
        final var ended = awaitTheEndOf(started.key());

        final var parcel = parcels
                .findById(parcelId)
                .orElseThrow();
        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertTrue(parcel.isWeighed()),
                () -> assertTrue(parcel.isPacked()),
                () -> assertEquals(parcelId, ended
                        .variables()
                        .get("id")));
    }

    @Test
    @DisplayName("The job of a workflow whose aggregate this application does not hold ends in an incident at once, whose message is the platform's")
    void aJobOfAWorkflowThisApplicationDoesNotOwnEndsInAnIncident() {
        final var strangerId = uniqueParcelId();

        final var started = rest().createInstance(CreateInstanceRequest
                .latestVersionOf(SCOPED_PROCESS_ID)
                .withBusinessKey(strangerId));
        final var incident = eventually(() -> incidentsOf(started.key()).findFirst());
        final var weigh = rest()
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(started.key()))
                .items()
                .getFirst();

        assertAll(
                () -> assertEquals("Weigh", incident
                        .get("elementId")
                        .asText()),
                () -> assertTrue(incident
                        .get("message")
                        .asText()
                        .contains("This application was given a task of a workflow it does not own."),
                        incident::toString),
                () -> assertTrue(incident
                        .get("message")
                        .asText()
                        .contains("'%s'".formatted(strangerId)), incident::toString),
                () -> assertEquals(JobRef.State.FAILED, weigh.state()),
                () -> assertEquals(List.of(), deliveriesOf(strangerId)));
    }

    @Test
    @DisplayName("The job-timeout of a task is resolved from the most specific level which sets it: the task's for the packing, the workflow module's for the weighing")
    void theJobTimeoutOfATaskComesFromItsMostSpecificLevel() {
        assertAll(
                () -> assertEquals(Duration.ofSeconds(42), deploymentService.jobTimeoutOf("deploy-it", "TaskMatrix",
                        "pack")),
                () -> assertEquals(Duration.ofMinutes(3), deploymentService.jobTimeoutOf("deploy-it", "TaskMatrix",
                        "weigh")));
    }

    // only the test knows the ports the container was given
    @DynamicPropertySource
    static void theEngineUnderTest(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", ENGINE::restAddress);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    // the parcel ids of every test are new, because all tests of the class share one engine
    private static String uniqueParcelId() {
        return "parcel-%s".formatted(UUID.randomUUID());
    }

    private ProcessInstanceRef runThrough(final String parcelId) {
        transactionTemplate.executeWithoutResult(status -> processService.startWorkflow(new Parcel(parcelId)));
        final var started = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(parcelId))
                .items()
                .stream()
                .findFirst());
        return awaitTheEndOf(started.key());
    }

    private ProcessInstanceRef awaitTheEndOf(final long key) {
        return eventually(() -> rest()
                .instance(key)
                .filter(instance -> instance.state() != ProcessInstanceRef.State.ACTIVE));
    }

    private List<String> visited(final ProcessInstanceRef instance) {
        final var visited = new ArrayList<String>();
        rest()
                .history(instance.key(), 1)
                .items()
                .stream()
                .map(HistoryEntry::elementId)
                .forEach(visited::add);
        rest()
                .children(instance.key())
                .forEach(child -> rest()
                        .history(child.key(), 1)
                        .items()
                        .forEach(entry -> visited.add(entry.elementId())));
        return visited;
    }

    private List<TaskDeliveryLogReader.Delivery> deliveriesOf(final String parcelId) {
        return TaskDeliveryLogReader
                .of(dataSource)
                .deliveries()
                .stream()
                .filter(delivery -> parcelId.equals(delivery.aggregateId()))
                .toList();
    }

    private Stream<JsonNode> incidentsOf(final long instanceKey) {
        try (var client = HttpClient.newHttpClient()) {
            final var answer = client.send(HttpRequest
                    .newBuilder(URI.create("%s/v1/process-instances/%d/incidents".formatted(ENGINE.restAddress(),
                            instanceKey)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, answer.statusCode(), answer::body);
            final var items = JsonMapper
                    .builder()
                    .build()
                    .readTree(answer.body())
                    .path("items");
            return StreamSupport.stream(items.spliterator(), false);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new IllegalStateException(e);
        }
    }

    private ZenBpmRestClient rest() {
        return clients
                .getFactory("zenbpm")
                .rest();
    }

    private static <T> T eventually(final Supplier<Optional<T>> read) {
        final var deadline = Instant
                .now()
                .plus(TIMEOUT);
        while (true) {
            final var found = read.get();
            if (found.isPresent()) {
                return found.get();
            }
            assertTrue(Instant
                    .now()
                    .isBefore(deadline), () -> "waited %s".formatted(TIMEOUT));
            LockSupport.parkNanos(Duration
                    .ofMillis(50)
                    .toNanos());
        }
    }

}
