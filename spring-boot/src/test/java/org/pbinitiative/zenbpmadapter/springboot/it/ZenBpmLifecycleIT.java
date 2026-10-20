/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.Delivery;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.DeliveryRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.DeliveryWorkflow;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.Invoice;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.InvoiceRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.LifecycleItApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.Picking;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.PickingWorkflow;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.ReturnCase;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.ReturnCaseRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.ReturnWorkflow;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.Settlement;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.SettlementRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.Tick;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.TickRepository;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * How a booted Spring Boot application hears about the start of its workflows, against the pinned
 * engine: through the marker the adapter writes behind every start event. A workflow a timer
 * started gets the aggregate the application's <code>&#64;WorkflowStartedByBpms</code> method
 * built and named, and is found through the instance key of its start afterwards, which is what a
 * correlation needs, because the workflow has no business key. So does a workflow somebody started
 * over the engine's REST API without a business key. A workflow the application started itself
 * passes its marker without a second aggregate, and so does a second delivery of a marker whose
 * completion the engine did not take, because the id written before is read from the instance. And
 * a process called on an aggregate of its own starts without the caller's name, so its own method
 * builds its aggregate.
 * <p>
 * It hears the end of its workflows through the marker the adapter writes in front of every end
 * event: a workflow which reaches an end event is reported with that end event, after the tasks the
 * platform believes open in it were reported as cancelled, and the instance ends only after the
 * report committed. A branch which ends while another runs reports nothing, a workflow whose event
 * sub-process still runs is reported once that sub-process ended, a cancelled workflow reports
 * nothing, a report which threw is repeated by the engine, and a workflow whose aggregate the
 * application deleted ends all the same.
 * <p>
 * A task inside a multi-instance element is told the iteration it runs in: its item, its index
 * counted from 0 and the size of the collection, for an iteration of a parallel multi-instance
 * sub-process, of a sequential multi-instance task, and for a task of a process which an iteration
 * of a multi-instance call activity started.
 * <p>
 * The application {@link LifecycleItApplication} runs with <code>lifecycle-it.yaml</code>, which
 * gives the start marker of <code>Delivering</code> and the end marker of <code>Settling</code> a
 * job-timeout of two seconds, and the end marker of <code>Returning</code> a retry-backoff of one
 * second. The engine gives every job three attempts, where its default is one. It reaches the
 * engine's REST API through a {@link RefusingEngineProxy}, which refuses a completion where a case
 * asks for it; the job stream goes to the engine directly. Its process <code>Ticking</code> is
 * started once by its timer, two seconds after it was deployed; every other case starts workflows
 * of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = LifecycleItApplication.class, properties = "spring.config.name=lifecycle-it")
@DirtiesContext
class ZenBpmLifecycleIT {

    /**
     * How long a case waits for what the engine or the application does. The timer fires two
     * seconds after the deployment, and every other step takes milliseconds or one outbox window of
     * two seconds, so the limit only turns a step which never happens into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // three attempts per job, so a report which threw is handed out again
    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest().withEnv("JOBS_DEFAULT_RETRIES", "3");

    private static final RefusingEngineProxy PROXY = new RefusingEngineProxy(ENGINE::restAddress);

    @Autowired
    private ProcessService<Tick> tickProcessService;

    @Autowired
    private ProcessService<Delivery> deliveryProcessService;

    @Autowired
    private TickRepository ticks;

    @Autowired
    private DeliveryRepository deliveries;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private DeliveryWorkflow deliveryWorkflow;

    @Autowired
    private ProcessService<ReturnCase> returnProcessService;

    @Autowired
    private ReturnCaseRepository returns;

    @Autowired
    private ReturnWorkflow returnWorkflow;

    @Autowired
    private ProcessService<Settlement> settlementProcessService;

    @Autowired
    private SettlementRepository settlements;

    @Autowired
    private ProcessService<Picking> pickingProcessService;

    @Autowired
    private PickingWorkflow pickingWorkflow;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ZenBpmClientRegistry clients;

    @AfterEach
    void startTheProxyAfresh() {
        PROXY.startAfresh();
        returnWorkflow.whileEnding(returnCase -> {
        });
    }

    @AfterAll
    static void stopTheProxy() {
        PROXY.close();
    }

    @Test
    @DisplayName("A workflow its timer started gets the aggregate the application's method built and named: the id variable holds it, the task behind the start ran on it, and a correlation on it reaches the workflow through the instance key of its start")
    void aTimerStartBuildsTheAggregate() {
        final var tick = eventually(() -> ticks
                .findAll()
                .stream()
                .filter(Tick::isTicked)
                .findFirst());
        final var instance = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBpmnProcessId("deploy-it__Ticking"))
                .items()
                .stream()
                .findFirst());

        transactionTemplate.executeWithoutResult(status -> tickProcessService.correlateMessage(ticks
                .findById(tick.getId())
                .orElseThrow(), "proceed"));
        final var ended = awaitTheEndOf(instance.key());

        assertAll(
                () -> assertEquals("TIMER", tick.getStartedBy()),
                () -> assertEquals("TwoSecondsAfterDeploying", tick.getStartEvent()),
                () -> assertNull(instance.businessKey()),
                () -> assertEquals(tick.getId(), ended
                        .variables()
                        .get("id")),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()));
    }

    @Test
    @DisplayName("A workflow somebody started over the engine's REST API without a business key gets the aggregate the application's method built, named in its id variable, and its task runs on it")
    void aWorkflowStartedPastVanillaBpGetsAnAggregate() {
        final var orderRef = uniqueOrderRef();

        final var started = startPastVanillaBp(orderRef);
        final var delivery = eventually(() -> deliveries
                .findById("delivery-%s".formatted(orderRef))
                .filter(Delivery::isDelivered));
        final var ended = awaitTheEndOf(started.key());

        assertAll(
                () -> assertNull(started.businessKey()),
                () -> assertEquals(orderRef, delivery.getOrderRef()),
                () -> assertEquals(1, Collections.frequency(deliveryWorkflow.builtFor(), orderRef)),
                () -> assertEquals(delivery.getId(), ended
                        .variables()
                        .get("id")),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()));
    }

    @Test
    @DisplayName("A start marker the engine hands out again after its id was written, because its completion did not reach the engine, finds the id in the instance: the method is not called a second time, and the instance keeps the first id")
    void aSecondDeliveryOfTheMarkerBuildsNothing() {
        // the timer's workflow completes a task once after the boot, which must not take the
        // refusal meant for the marker
        eventually(() -> ticks
                .findAll()
                .stream()
                .filter(Tick::isTicked)
                .findFirst());
        final var orderRef = uniqueOrderRef();

        // a 500 gives the completion up at once and leaves the job to its lock of two seconds,
        // after which the engine hands the marker out again with the variables it was created with
        PROXY.refuse(RefusingEngineProxy.COMPLETION, 500, 1);
        final var started = startPastVanillaBp(orderRef);
        final var delivery = eventually(() -> deliveries
                .findById("delivery-%s".formatted(orderRef))
                .filter(Delivery::isDelivered));
        final var ended = awaitTheEndOf(started.key());

        assertAll(
                () -> assertEquals(1, PROXY
                        .refused()
                        .size(),
                        () -> PROXY
                                .refused()
                                .toString()),
                () -> assertEquals(1, Collections.frequency(deliveryWorkflow.builtFor(), orderRef),
                        () -> deliveryWorkflow
                                .builtFor()
                                .toString()),
                () -> assertEquals(delivery.getId(), ended
                        .variables()
                        .get("id")),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()));
    }

    @Test
    @DisplayName("A workflow the application started itself passes its start marker without a second aggregate: the method is not called, and the instance keeps the id it was started with")
    void theApplicationsOwnStartBuildsNothing() {
        final var orderRef = uniqueOrderRef();
        final var deliveryId = "own-%s".formatted(orderRef);

        transactionTemplate.executeWithoutResult(status -> deliveryProcessService.startWorkflow(new Delivery(
                deliveryId, orderRef)));
        final var started = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(deliveryId))
                .items()
                .stream()
                .findFirst());
        final var delivery = eventually(() -> deliveries
                .findById(deliveryId)
                .filter(Delivery::isDelivered));
        final var ended = awaitTheEndOf(started.key());

        assertAll(
                () -> assertFalse(deliveryWorkflow
                        .builtFor()
                        .contains(orderRef)),
                () -> assertFalse(deliveries.existsById("delivery-%s".formatted(orderRef))),
                () -> assertEquals(deliveryId, delivery.getId()),
                () -> assertEquals(deliveryId, ended
                        .variables()
                        .get("id")),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()));
    }

    @Test
    @DisplayName("A process called on an aggregate of its own starts without the caller's business key and id, so its own method builds its aggregate, and the caller keeps its id")
    void aProcessCalledOnAnAggregateOfItsOwnBuildsItsOwn() {
        final var orderRef = uniqueOrderRef();
        final var deliveryId = "own-%s".formatted(orderRef);

        // started by the application, so the caller carries a business key and an id variable,
        // both of which the called instance would take over
        transactionTemplate.executeWithoutResult(status -> deliveryProcessService.startWorkflow(new Delivery(
                deliveryId, orderRef)));
        final var started = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(deliveryId))
                .items()
                .stream()
                .findFirst());
        final var invoice = eventually(() -> invoices
                .findById("invoice-%s".formatted(orderRef))
                .filter(Invoice::isIssued));
        final var ended = awaitTheEndOf(started.key());
        final var called = rest()
                .children(started.key())
                .stream()
                .filter(child -> child.processType() == ProcessInstanceRef.ProcessType.CALL_ACTIVITY)
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals("deploy-it__Invoicing", called.bpmnProcessId()),
                () -> assertTrue((called.businessKey() == null) || called
                        .businessKey()
                        .isEmpty(), called::toString),
                () -> assertEquals(invoice.getId(), called
                        .variables()
                        .get("id")),
                () -> assertEquals(deliveryId, ended
                        .variables()
                        .get("id")),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()));
    }

    @Test
    @DisplayName("A workflow which reaches an end event is reported with that end event: a refund at the terminate end event Refunded, a credit, once the inspection was completed, at the end event Credited")
    void theEndOfAWorkflowIsReportedWithItsEndEvent() {
        final var refunded = startAReturn();
        final var credited = startAReturn();
        theInspectionStarted(refunded.id());
        theInspectionStarted(credited.id());

        correlate(refunded.id(), "refund");
        completeTheInspection(credited.id());
        theTokensLeft(credited.key(), "Inspection", "vanillabp__ended__Inspected");
        correlate(credited.id(), "credit");

        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, awaitTheEndOf(refunded.key()).state()),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, awaitTheEndOf(credited.key()).state()),
                () -> assertEquals("COMPLETED at Refunded", eventsOf(refunded.id()).getLast()),
                () -> assertEquals(List.of("inspection started", "COMPLETED at Credited"), eventsOf(credited
                        .id())));
    }

    @Test
    @DisplayName("A branch which reaches its end event while another branch of the workflow waits reports nothing: its marker's job is completed, the workflow goes on, and the end the last branch reaches is reported once")
    void aBranchWhichEndsWhileAnotherRunsReportsNothing() {
        final var started = startAReturn();
        theInspectionStarted(started.id());

        completeTheInspection(started.id());
        final var inspected = eventually(() -> rest()
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(started.key())
                        .withJobType("vanillabp__ended__deploy-it__Returning")
                        .withState(JobRef.State.COMPLETED))
                .items()
                .stream()
                .findFirst());
        LockSupport.parkNanos(Duration
                .ofSeconds(1)
                .toNanos());
        final var whileTheOtherBranchWaits = eventsOf(started.id());
        final var stateWhileItWaits = rest()
                .instance(started.key())
                .orElseThrow()
                .state();
        correlate(started.id(), "credit");
        final var ended = awaitTheEndOf(started.key());

        assertAll(
                () -> assertEquals("vanillabp__ended__Inspected", inspected.elementId()),
                () -> assertEquals(List.of("inspection started"), whileTheOtherBranchWaits),
                () -> assertEquals(ProcessInstanceRef.State.ACTIVE, stateWhileItWaits),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(List.of("inspection started", "COMPLETED at Credited"), eventsOf(started.id())));
    }

    @Test
    @DisplayName("The end cancels the tasks still open: the inspection, a task waiting in a parallel branch inside an embedded sub-process, is reported cancelled before the @WorkflowEnded method runs, because both carry the root instance as their workflow, and the instance is still active while the method runs")
    void theEndCancelsTheTasksStillOpen() {
        final var started = startAReturn();
        theInspectionStarted(started.id());
        final var whileEnding = new CopyOnWriteArrayList<String>();
        returnWorkflow.whileEnding(returnCase -> whileEnding.add("%s %s".formatted(returnCase.getEvents(), rest()
                .instance(started.key())
                .orElseThrow()
                .state())));

        correlate(started.id(), "refund");
        final var ended = awaitTheEndOf(started.key());

        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(List.of("[inspection started, inspection cancelled] ACTIVE"), whileEnding),
                () -> assertEquals(List.of("inspection started", "inspection cancelled", "COMPLETED at Refunded"),
                        eventsOf(started.id())));
    }

    @Test
    @DisplayName("A workflow cancelled at the engine passes no end marker and reports nothing")
    void aCancelledWorkflowReportsNothing() throws Exception {
        final var started = startAReturn();
        theInspectionStarted(started.id());

        cancel(started.key());
        final var ended = awaitTheEndOf(started.key());
        LockSupport.parkNanos(Duration
                .ofSeconds(3)
                .toNanos());

        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.TERMINATED, ended.state()),
                () -> assertEquals(List.of("inspection started"), eventsOf(started.id())));
    }

    @Test
    @DisplayName("A report whose @WorkflowEnded method threw is repeated by the engine after the retry-backoff, and the end is reported once it went through")
    void aReportWhichThrewIsRepeated() {
        final var started = startAReturn();
        theInspectionStarted(started.id());
        final var attempts = new AtomicInteger();
        returnWorkflow.whileEnding(returnCase -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("the archive is down");
            }
        });

        correlate(started.id(), "refund");
        final var ended = awaitTheEndOf(started.key());

        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(2, attempts.get()),
                () -> assertEquals(List.of("inspection started", "inspection cancelled", "COMPLETED at Refunded"),
                        eventsOf(started.id())));
    }

    @Test
    @DisplayName("A workflow whose aggregate the application deleted ends all the same: the core skips the report, and the marker's job is completed")
    void aWorkflowWhoseAggregateIsGoneEnds(final CapturedOutput output) {
        final var started = startAReturn();
        theInspectionStarted(started.id());

        transactionTemplate.executeWithoutResult(status -> returns.deleteById(started.id()));
        // published past VanillaBP, which has no aggregate to correlate on any more; the message name is
        // scoped by the workflow module under use-prefix
        rest().publishMessage("deploy-it__refund", started.id(), Map.of());
        final var ended = awaitTheEndOf(started.key());

        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertFalse(returns.existsById(started.id())),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "The workflow aggregate '%s' of BPMN process 'Returning' (workflow module 'deploy-it') does not exist (any more)"
                                        .formatted(started.id())),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A workflow which reaches its end event while its non-interrupting event sub-process still runs is not reported until the sub-process ended, because it ends only then; then it is reported once, with that end event")
    void theEndWaitsForAnEventSubProcessStillRunning() {
        final var settlementId = "settlement-%s".formatted(UUID.randomUUID());
        transactionTemplate.executeWithoutResult(status -> settlementProcessService.startWorkflow(new Settlement(
                settlementId)));
        final var started = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(settlementId))
                .items()
                .stream()
                .findFirst());
        eventually(() -> rest()
                .children(started.key())
                .stream()
                .filter(child -> child.state() == ProcessInstanceRef.State.ACTIVE)
                .findFirst());

        transactionTemplate.executeWithoutResult(status -> settlementProcessService.correlateMessage(settlements
                .findById(settlementId)
                .orElseThrow(), "close"));
        eventually(() -> rest()
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(started.key())
                        .withJobType("vanillabp__ended__deploy-it__Settling")
                        .withState(JobRef.State.ACTIVE))
                .items()
                .stream()
                .findFirst());
        // longer than the marker's job-timeout of two seconds, so the engine handed it out again
        LockSupport.parkNanos(Duration
                .ofMillis(4_500)
                .toNanos());
        final var whileTheSubProcessRuns = settlements
                .findById(settlementId)
                .orElseThrow()
                .getEnds();
        final var stateWhileItRuns = rest()
                .instance(started.key())
                .orElseThrow()
                .state();
        transactionTemplate.executeWithoutResult(status -> settlementProcessService.correlateMessage(settlements
                .findById(settlementId)
                .orElseThrow(), "agree"));
        final var ended = awaitTheEndOf(started.key());

        assertAll(
                () -> assertEquals(List.of(), whileTheSubProcessRuns),
                () -> assertEquals(ProcessInstanceRef.State.ACTIVE, stateWhileItRuns),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(List.of("Closed"), settlements
                        .findById(settlementId)
                        .orElseThrow()
                        .getEnds()));
    }

    @Test
    @DisplayName("A task inside a parallel multi-instance sub-process over three parcels is told the item, the index counted from 0 and the total of its iteration")
    void theIterationIsReported() {
        final var picking = startAPicking();

        final var picked = eventually(() -> Optional
                .of(iterationsOf(pickingWorkflow.picked(), picking))
                .filter(iterations -> iterations.size() == 3));

        assertEquals(List.of("a:0/3", "b:1/3", "c:2/3"), picked
                .stream()
                .sorted()
                .toList());
    }

    @Test
    @DisplayName("The iterations of a sequential multi-instance task are told one after the other, each with the item, the index and the total of its own")
    void theIterationsOfASequentialTaskAreReportedOneAfterTheOther() {
        final var picking = startAPicking();

        final var weighed = eventually(() -> Optional
                .of(iterationsOf(pickingWorkflow.weighed(), picking))
                .filter(iterations -> iterations.size() == 3));

        assertEquals(List.of("a:0/3", "b:1/3", "c:2/3"), weighed);
    }

    @Test
    @DisplayName("A task of a called process is told the iteration of the multi-instance call activity which started it, and the workflow ends")
    void aCalledProcessIsToldTheIterationOfItsCaller() {
        final var picking = startAPicking();

        final var labelled = eventually(() -> Optional
                .of(iterationsOf(pickingWorkflow.labelled(), picking))
                .filter(iterations -> iterations.size() == 3));
        final var instance = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(picking))
                .items()
                .stream()
                .filter(started -> started.parentKey() == null)
                .findFirst());

        assertAll(
                () -> assertEquals(List.of("a:0/3", "b:1/3", "c:2/3"), labelled
                        .stream()
                        .sorted()
                        .toList()),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, awaitTheEndOf(instance.key()).state()));
    }

    @DynamicPropertySource
    static void theEngineBehindTheProxy(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", PROXY::address);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    private String startAPicking() {
        final var pickingId = "picking-%s".formatted(UUID.randomUUID());
        transactionTemplate.executeWithoutResult(
                status -> pickingProcessService.startWorkflow(new Picking(pickingId, List.of("a", "b", "c"))));
        return pickingId;
    }

    // the iterations told for one picking, as <item>:<index>/<total>
    private static List<String> iterationsOf(
        final List<String> told,
        final String pickingId
    ) {
        return told
                .stream()
                .filter(iteration -> iteration.startsWith("%s:".formatted(pickingId)))
                .map(iteration -> iteration.substring(pickingId.length() + 1))
                .toList();
    }

    private ProcessInstanceRef startPastVanillaBp(final String orderRef) {
        return rest().createInstance(CreateInstanceRequest
                .latestVersionOf("deploy-it__Delivering")
                .withVariables(Map.of("orderRef", orderRef)));
    }

    private StartedReturn startAReturn() {
        final var returnId = "return-%s".formatted(UUID.randomUUID());
        transactionTemplate.executeWithoutResult(status -> returnProcessService.startWorkflow(new ReturnCase(
                returnId)));
        return new StartedReturn(returnId, eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(returnId))
                .items()
                .stream()
                .findFirst())
                .key());
    }

    private void theInspectionStarted(final String returnId) {
        eventually(() -> returns
                .findById(returnId)
                .filter(returnCase -> returnCase.getInspection() != null));
    }

    private void completeTheInspection(final String returnId) {
        transactionTemplate.executeWithoutResult(status -> {
            final var returnCase = returns
                    .findById(returnId)
                    .orElseThrow();
            returnProcessService.completeTask(returnCase, returnCase.getInspection());
        });
    }

    private void correlate(
        final String returnId,
        final String messageName
    ) {
        transactionTemplate.executeWithoutResult(status -> returnProcessService.correlateMessage(returns
                .findById(returnId)
                .orElseThrow(), messageName));
    }

    // waits until no token of the instance stands at the elements any more
    private void theTokensLeft(
        final long key,
        final String... elementIds
    ) {
        eventually(() -> rest()
                .activeElementsOf(key)
                .filter(standing -> standing
                        .stream()
                        .noneMatch(List.of(elementIds)::contains)));
    }

    private List<String> eventsOf(final String returnId) {
        return returns
                .findById(returnId)
                .orElseThrow()
                .getEvents();
    }

    // the adapter's client has no call for it, because the adapter never cancels a workflow
    private static void cancel(final long key) throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            final var answer = client.send(HttpRequest
                    .newBuilder(URI.create("%s/v1/process-instances/%d/cancel".formatted(ENGINE.restAddress(), key)))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, answer.statusCode(), answer::body);
        }
    }

    private ProcessInstanceRef awaitTheEndOf(final long key) {
        return eventually(() -> rest()
                .instance(key)
                .filter(instance -> instance.state() != ProcessInstanceRef.State.ACTIVE));
    }

    private ZenBpmRestClient rest() {
        return clients
                .getFactory("zenbpm")
                .rest();
    }

    private static String uniqueOrderRef() {
        return "order-%s".formatted(UUID.randomUUID());
    }

    /**
     * A return the application started, with the key of its instance.
     */
    private record StartedReturn(
        String id,
        long key
    ) {
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
