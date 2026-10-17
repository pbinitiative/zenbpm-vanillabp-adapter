/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.pbinitiative.zenbpmadapter.springboot.it.task.Claim;
import org.pbinitiative.zenbpmadapter.springboot.it.task.ClaimRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.task.Parcel;
import org.pbinitiative.zenbpmadapter.springboot.it.task.ParcelRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.task.Reservation;
import org.pbinitiative.zenbpmadapter.springboot.it.task.ReservationRepository;
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
 * job-timeout of a task is resolved from the most specific level of the configuration. A BPMN error
 * is routed by the error boundary event which catches its code, or ends in an incident where none
 * does, and a task left open for its asynchronous completion is parked, and parked again whenever
 * the engine hands its job out once more. A task which a timer boundary event took away is reported
 * cancelled at the next delivery of its workflow.
 * <p>
 * The application runs the workflow module <code>deploy-it</code> under <code>use-prefix</code> with
 * the process <code>TaskMatrix</code> of <code>task-it/task-matrix.bpmn</code> and the process
 * <code>TaskOutcomes</code> of <code>task-it/task-outcomes.bpmn</code> and the process
 * <code>TaskTakenAway</code> of <code>task-it/task-taken-away.bpmn</code>, configured by
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
    private ProcessService<Claim> claimProcessService;

    @Autowired
    private ClaimRepository claims;

    @Autowired
    private ProcessService<Reservation> reservationProcessService;

    @Autowired
    private ReservationRepository reservations;

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

    @Test
    @DisplayName("A BPMN error reaches the engine with the code the deployed model carries: the error boundary event takes over, the gateway behind it decides on what the handler computed, and the handler's work stays committed")
    void aBpmnErrorRoutesTheBoundaryEvent() {
        final var claimId = uniqueClaimId();

        final var ended = awaitTheEndOf(start(new Claim(claimId, Claim.ERROR, "damaged")).key());

        final var claim = claims
                .findById(claimId)
                .orElseThrow();
        final var inspect = jobOf(ended.key(), "Inspect");
        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertTrue(visited(ended).containsAll(List.of("Damaged", "Refunded")), () -> visited(ended)
                        .toString()),
                () -> assertFalse(visited(ended).contains("Inspected"), () -> visited(ended).toString()),
                () -> assertEquals(JobRef.State.TERMINATED, inspect.state()),
                () -> assertTrue(claim.isInspected()),
                () -> assertTrue(claim.isRefundable()),
                () -> assertEquals(List.of("BPMN_ERROR"), deliveriesOf(claimId)
                        .stream()
                        .map(TaskDeliveryLogReader.Delivery::outcome)
                        .toList()));
    }

    @Test
    @DisplayName("A BPMN error whose code no event of the model catches ends in an incident on the task, which names the code as the engine knows it, and the handler's work stays committed")
    void anUnknownErrorCodeIsAnIncident() {
        final var claimId = uniqueClaimId();

        final var started = start(new Claim(claimId, Claim.ERROR, "lost"));
        final var incident = eventually(() -> incidentsOf(started.key()).findFirst());

        final var message = incident
                .get("message")
                .asText();
        assertAll(
                () -> assertEquals("Inspect", incident
                        .get("elementId")
                        .asText()),
                () -> assertEquals(
                        "BPMN error 'lost' of task 'inspect' (workflow aggregate '%s') is caught by no error boundary event and no error event sub-process, its code: deploy-it__lost"
                                .formatted(claimId),
                        message),
                () -> assertEquals(JobRef.State.FAILED, jobOf(started.key(), "Inspect").state()),
                () -> assertTrue(claims
                        .findById(claimId)
                        .orElseThrow()
                        .isInspected()));
    }

    @Test
    @DisplayName("A task left open for its asynchronous completion stays open: its handler runs once, and the engine hands its job out again every async-task-lock-renewal, which parks it again")
    void anAsynchronousTaskStaysOpen() {
        final var claimId = uniqueClaimId();

        final var started = start(new Claim(claimId, Claim.PENDING, null));
        final var taskId = eventually(() -> claims
                .findById(claimId)
                .map(Claim::getApprovalTaskId));
        final var firstDelivery = deliveryTokenOf(Long.parseLong(taskId));
        final var waitedFrom = Instant.now();
        // the window of task-it.yaml is two seconds, the job-timeout three minutes
        final var thirdDelivery = eventually(() -> Optional
                .of(deliveryTokenOf(Long.parseLong(taskId)))
                .filter(token -> token >= firstDelivery + 2));
        final var waited = Duration.between(waitedFrom, Instant.now());

        final var claim = claims
                .findById(claimId)
                .orElseThrow();
        assertAll(
                () -> assertTrue(waited.compareTo(Duration.ofSeconds(15)) < 0, waited::toString),
                () -> assertTrue(thirdDelivery >= firstDelivery + 2),
                () -> assertEquals(1, claim.getApprovalRequests()),
                () -> assertEquals(JobRef.State.ACTIVE, jobOf(started.key(), "AwaitApproval").state()),
                () -> assertEquals(ProcessInstanceRef.State.ACTIVE, rest()
                        .instance(started.key())
                        .orElseThrow()
                        .state()),
                () -> assertEquals(List.of("COMPLETION_PENDING"), deliveriesOf(claimId)
                        .stream()
                        .map(TaskDeliveryLogReader.Delivery::outcome)
                        .toList()));
    }

    @Test
    @DisplayName("A task an interrupting timer boundary event took away reaches the application as cancelled at the next delivery of its workflow, and only once")
    void aTaskTakenAwayByABoundaryEventIsReportedCanceled() {
        final var reservationId = "reservation-%s".formatted(UUID.randomUUID());
        transactionTemplate.executeWithoutResult(status -> reservationProcessService.startWorkflow(new Reservation(
                reservationId)));
        final var holdTaskId = eventually(() -> reservations
                .findById(reservationId)
                .map(Reservation::getHoldTaskId));
        final var checkTaskId = eventually(() -> reservations
                .findById(reservationId)
                .map(Reservation::getCheckTaskId));

        // the check comes back every two seconds, and the timer takes the hold away after two
        eventually(() -> reservations
                .findById(reservationId)
                .filter(reservation -> reservation.getHoldCancellations() > 0));
        final var deliveredBefore = deliveryTokenOf(Long.parseLong(checkTaskId));
        eventually(() -> Optional
                .of(deliveryTokenOf(Long.parseLong(checkTaskId)))
                .filter(token -> token >= deliveredBefore + 2));

        assertAll(
                () -> assertEquals(1, reservations
                        .findById(reservationId)
                        .orElseThrow()
                        .getHoldCancellations()),
                () -> assertEquals("terminated", engineJson("/v1/jobs/%s".formatted(holdTaskId))
                        .path("state")
                        .asText()),
                () -> assertEquals("active", engineJson("/v1/jobs/%s".formatted(checkTaskId))
                        .path("state")
                        .asText()));
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

    private static String uniqueClaimId() {
        return "claim-%s".formatted(UUID.randomUUID());
    }

    private ProcessInstanceRef start(final Claim claim) {
        transactionTemplate.executeWithoutResult(status -> claimProcessService.startWorkflow(claim));
        return eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(claim.getId()))
                .items()
                .stream()
                .findFirst());
    }

    private JobRef jobOf(
        final long instanceKey,
        final String elementId
    ) {
        return rest()
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(instanceKey))
                .items()
                .stream()
                .filter(job -> elementId.equals(job.elementId()))
                .findFirst()
                .orElseThrow();
    }

    // raised by the engine with every delivery of the job
    private static long deliveryTokenOf(final long jobKey) {
        return engineJson("/v1/jobs/%d".formatted(jobKey))
                .path("deliveryToken")
                .asLong();
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
        final var items = engineJson("/v1/process-instances/%d/incidents".formatted(instanceKey)).path("items");
        return StreamSupport.stream(items.spliterator(), false);
    }

    // what the engine answers, read past the adapter's client, which keeps only what it needs
    private static JsonNode engineJson(final String path) {
        try (var client = HttpClient.newHttpClient()) {
            final var answer = client.send(HttpRequest
                    .newBuilder(URI.create("%s%s".formatted(ENGINE.restAddress(), path)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, answer.statusCode(), answer::body);
            return JsonMapper
                    .builder()
                    .build()
                    .readTree(answer.body());
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
