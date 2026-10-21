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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.TaskEvent;

/**
 * How the user-task poller of an adapter id finds the user tasks of a workflow module on an engine a
 * test stands in for, and has each creation and cancellation told to the application once per node:
 * the workflow module <code>shop</code> claims the process <code>Order</code>
 * (<code>shop__Order</code> at the engine), whose user task <code>Approve</code> has the task
 * definition <code>approve</code> (job type <code>shop__Order__approve</code>). Each look is run by
 * the test, so what it hands over is counted; one test lets the timing threads run them.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmUserTaskPollerTest {

    private static final String USER_TASK_TYPE = "shop__Order__approve";

    private static final String BILLING_TYPE = "billing__Invoice__check";

    private static final long ROOT = 100;

    private static final Pattern STATE = Pattern.compile("state=([a-z]+)");

    private static final Pattern PAGE = Pattern.compile("page=(\\d+)");

    private static final Pattern INSTANCE = Pattern.compile("processInstanceKey=(\\d+)");

    private final JobRunning jobs = new JobRunning(2);

    /**
     * What the engine lists, by <code>state/page</code>; a page not here is empty and the last.
     */
    private final Map<String, String> listed = new ConcurrentHashMap<>();

    /**
     * What the engine lists of the type of the module <code>billing</code>, as {@link #listed}.
     */
    private final Map<String, String> listedForBilling = new ConcurrentHashMap<>();

    /**
     * The jobs of an instance in every state, as pages, by the key of the instance: each listing
     * answers the next page of the queue, and the last one from then on.
     */
    private final Map<Long, Deque<String>> listedOfInstances = new ConcurrentHashMap<>();

    private ZenBpmUserTaskPoller poller;

    private ZenBpmJobHandler handler;

    private ZenBpmUserTaskNotifier notifier;

    @BeforeEach
    void listWhatTheTestSays() {
        answersTheListings();
    }

    @AfterEach
    void stopTheEngine() {
        jobs.close();
    }

    @Test
    @DisplayName("A user task listed active is told to the application as created once, with what the notification carries: the job key as task id and kind USER_TASK, the plain task definition, the element, the root as workflow, the aggregate, the version, the delivery id with the event and the job's variables; then the core looks at the other open tasks of the workflow")
    void aCreatedUserTaskIsNotifiedOnce() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        // the version this start deployed is the version the workflow runs on
        jobs.factory
                .deployedProcesses()
                .record(new ZenBpmDeployedProcesses.DeployedProcess("shop", "Order", "shop__Order", 4503599627370498L, 3, null, "<definitions/>"));
        listsActive(userTask(51, ROOT));
        startsPolling(Duration.ofHours(1));

        looksTwice();

        final var invocations = jobs.invoker.invocations();
        assertEquals(1, invocations.size(), invocations::toString);
        final var context = invocations
                .getFirst()
                .context();
        assertAll(
                () -> assertEquals("shop", invocations
                        .getFirst()
                        .workflowModuleId()),
                () -> assertEquals("Order", invocations
                        .getFirst()
                        .bpmnProcessId()),
                () -> assertEquals(TaskEvent.Event.CREATED, context.getTaskEvent()),
                () -> assertEquals(TaskKind.USER_TASK, context.getTaskKind()),
                () -> assertEquals("51", context.getTaskId()),
                () -> assertEquals("51", context.getActivationId()),
                () -> assertEquals("51-created", context.getDeliveryId()),
                () -> assertEquals("approve", context.getTaskDefinition()),
                () -> assertEquals("Approve", context.getBpmnElementId()),
                () -> assertEquals("100", context.getWorkflowId()),
                () -> assertEquals("order-1", context.getWorkflowAggregateId()),
                () -> assertEquals("order-1", context.getBusinessKey()),
                () -> assertEquals("3", context.getProcessVersion()),
                () -> assertEquals("orders", context.getAdapterId()),
                () -> assertEquals("rush", context.getTaskParameter("note")),
                () -> assertNull(context.getTaskParameter("missing")),
                () -> assertFalse(context.predatesDeployedVersion()),
                () -> assertEquals(1, jobs.invoker
                        .reports()
                        .size()),
                () -> assertTrue(jobs.invoker
                        .reports()
                        .getFirst()
                        .probe() instanceof ZenBpmOpenTaskProbe));
    }

    @Test
    @DisplayName("A user task seen active is told as cancelled once it is listed terminated, with a delivery id of its own, and once only")
    void aCancelledUserTaskIsNotifiedOnce() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        startsPolling(Duration.ofHours(1));
        looks();
        listsActive();
        listsTerminated(terminated(51, ROOT));

        looksTwice();

        assertEquals(List.of("51-created", "51"), deliveryIds());
        assertEquals(TaskEvent.Event.CANCELED, jobs.invoker
                .invocations()
                .getLast()
                .context()
                .getTaskEvent());
    }

    @Test
    @DisplayName("A terminated user task never seen active, as after a restart or where it came and went between two looks, is told as cancelled only, and a poller which starts afresh tells it again for the platform's record to answer")
    void aTerminatedUserTaskNeverSeenActiveIsNotifiedAsCancelledOnly() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsTerminated(terminated(51, ROOT));
        startsPolling(Duration.ofHours(1));
        looksTwice();
        final var beforeTheRestart = deliveryIds();

        startsPolling(Duration.ofHours(1));
        looks();

        assertEquals(List.of("51"), beforeTheRestart);
        assertEquals(List.of("51", "51"), deliveryIds());
    }

    @Test
    @DisplayName("A user task the end of its workflow took away is told as cancelled under its task id, the id under which the core reported it when the workflow ended, so the core answers the notification from its record where it reported it, and runs the method where it did not")
    void aUserTaskTheEndOfItsWorkflowTookAwayIsToldUnderItsTaskId() {
        jobs.holdsEnded(ROOT, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        startsPolling(Duration.ofHours(1));
        looks();
        listsActive();
        listsTerminated(terminated(51, ROOT));

        looksTwice();

        assertAll(
                () -> assertEquals(List.of("51-created", "51"), deliveryIds()),
                () -> assertEquals("51", jobs.invoker
                        .invocations()
                        .getLast()
                        .context()
                        .getTaskId()));
    }

    @Test
    @DisplayName("A user task told as cancelled is never told as created afterwards, although a node lagging behind lists its job active again")
    void aCancelledUserTaskIsNeverToldAsCreatedAfterwards() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsTerminated(terminated(51, ROOT));
        startsPolling(Duration.ofHours(1));
        looks();
        // a node lagging behind answers the next look
        listsTerminated();
        listsActive(userTask(51, ROOT));
        looks();
        listsActive();
        listsTerminated(terminated(51, ROOT));

        looks();

        assertEquals(List.of("51"), deliveryIds());
    }

    @Test
    @DisplayName("A notification which threw is not remembered, so the next look tells it again, and the one after it does not; the failure is said at WARN")
    void aNotificationWhichThrewIsRetriedAtTheNextLook(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        jobs.invoker.answers(context -> {
            throw new IllegalStateException("the task list is down");
        });
        startsPolling(Duration.ofHours(1));
        looks();
        jobs.invoker.answers(context -> WorkflowTaskOutcome.completionPending());

        looksTwice();

        assertEquals(List.of("51-created", "51-created"), deliveryIds());
        assertTrue(output
                .getOutOfThisTest()
                .contains(
                        "as CREATED threw; its transaction was rolled back, and a later look at the user tasks notifies it again: the next look first"),
                output::getOutOfThisTest);
        assertEquals(1, jobs.invoker
                .reports()
                .size());
    }

    @Test
    @DisplayName("A delivery done with is forgotten once two looks which listed every job in a row listed it no more, and not after one, because a listing by pages may pass over a job")
    void aDeliveryIsForgottenOnceTwoLooksListedItNoMore() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        startsPolling(Duration.ofHours(1));
        looks();
        listsActive();
        looks();
        listsActive(userTask(51, ROOT));
        looks();
        final var afterOneLookWithoutIt = deliveryIds();
        listsActive();
        looksTwice();
        listsActive(userTask(51, ROOT));

        looks();

        assertEquals(List.of("51-created"), afterOneLookWithoutIt);
        assertEquals(List.of("51-created", "51-created"), deliveryIds());
    }

    @Test
    @DisplayName("A look which ended without a free handler slot listed not every job, so it forgets nothing")
    void aLookWhichListedNotEveryJobForgetsNothing() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        startsPolling(Duration.ofHours(1));
        looks();
        final var release = new CountDownLatch(1);
        jobs.invoker.answers(context -> {
            await(release);
            return WorkflowTaskOutcome.completionPending();
        });
        listsActive(userTask(52, ROOT), userTask(53, ROOT), userTask(54, ROOT));
        poller.look();
        JobRunning.await(() -> jobs.invoker
                .invocations()
                .size() == 3, "both slots to run a notification");
        poller.look();
        release.countDown();
        jobs.awaitFreeSlots();
        listsActive(userTask(51, ROOT));

        looks();

        assertEquals(List.of("51-created", "52-created", "53-created"), deliveryIds()
                .stream()
                .sorted()
                .toList());
    }

    @Test
    @DisplayName("A look which lists more user tasks than any bound of the set hands none of them over a second time")
    void aLongListingIsHandedOverOnce() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        final var many = new ArrayList<String>();
        for (var key = 1000; key < 1300; ++key) {
            many.add(userTask(key, ROOT));
        }
        listed.put("active/1", page(many.size(), many));
        startsPolling(Duration.ofHours(1));
        for (var look = 0; (look < 1000) && (deliveryIds().size() < many.size()); ++look) {
            looks();
        }
        final var handedOverOnce = deliveryIds().size();

        looksTwice();

        assertEquals(many.size(), handedOverOnce);
        assertEquals(many.size(), deliveryIds().size());
    }

    @Test
    @DisplayName("Notifications which keep failing wait longer after each failure, so they cannot take every handler slot at every look and the others get theirs; the failure of each is said at WARN once")
    void notificationsWhichKeepFailingLeaveTheSlotsToTheOthers(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT), userTask(52, ROOT), userTask(53, ROOT));
        listsTerminated(terminated(54, ROOT));
        final var gate = new AtomicReference<CountDownLatch>();
        jobs.invoker.answers(context -> {
            if (Set
                    .of("51", "52")
                    .contains(context.getTaskId())) {
                // held until the look met the full slots, so the look ends at 53
                await(gate.get());
                throw new IllegalStateException("the task list is down");
            }
            return WorkflowTaskOutcome.completionPending();
        });
        startsPolling(Duration.ofHours(1));

        for (var look = 0; look < 3; ++look) {
            gate.set(new CountDownLatch(1));
            poller.look();
            gate
                    .get()
                    .countDown();
            jobs.awaitFreeSlots();
        }

        assertEquals(List.of("51-created", "51-created", "52-created", "52-created", "53-created", "54"),
                deliveryIds()
                        .stream()
                        .sorted()
                        .toList());
        assertEquals(2, countOf(output.getOutOfThisTest(), "as CREATED threw"), output::getOutOfThisTest);
    }

    @Test
    @DisplayName("A notification which keeps failing is handed over again at the latest after the longest wait")
    void aNotificationWhichKeepsFailingIsRetriedAfterTheLongestWaitAtTheLatest() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        jobs.invoker.answers(context -> {
            throw new IllegalStateException("the task list is down");
        });
        startsPolling(Duration.ofHours(1));

        for (var look = 0; look < 300; ++look) {
            looks();
        }

        // at the looks 1, 2, 4, 8, 16, 32, 64, and from then on every 64th: 128, 192, 256
        assertEquals(10, deliveryIds().size());
        assertEquals(64, ZenBpmUserTaskPoller.LONGEST_WAIT_IN_LOOKS);
    }

    @Test
    @DisplayName("A user task of a workflow module which starts after the first look is told to it once it started, although a look before found nobody to tell")
    void aUserTaskOfAModuleWhichStartsLaterIsToldOnceItStarted() {
        jobs.holds(300, "billing__Invoice", "default", null, "invoice-1", "\"id\": \"invoice-1\"");
        listsActive(userTask(61, 300));
        startsPolling(Duration.ofHours(1));
        looksTwice();
        final var beforeBillingStarted = deliveryIds();

        startsBillingSharingTheType();
        looksTwice();

        assertEquals(List.of(), beforeBillingStarted);
        assertEquals(List.of("billing/61-created"), modulesAndDeliveryIds());
    }

    @Test
    @DisplayName("A user task found to be nobody's while a workflow module starts which claims it is told to that module at the next look")
    void aUserTaskFoundToBeNobodysWhileItsModuleStartsIsToldAtTheNextLook() {
        final var reading = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        jobs.engine.answers("GET", "/v1/process-instances/300", exchange -> {
            reading.countDown();
            await(release);
            FakeEngine.respond(exchange, 200, """
                    {"key": 300, "processDefinitionKey": 4503599627370498, "bpmnProcessId": "billing__Invoice",
                     "version": 3, "businessKey": "invoice-1", "createdAt": "2026-10-17T14:30:25.123Z",
                     "state": "active", "processType": "default", "variables": {"id": "invoice-1"}}""");
        });
        listsActive(userTask(61, 300));
        startsPolling(Duration.ofHours(1));
        poller.look();
        await(reading);

        startsBillingSharingTheType();
        release.countDown();
        jobs.awaitFreeSlots();
        looks();

        assertEquals(List.of("billing/61-created"), modulesAndDeliveryIds());
    }

    @Test
    @DisplayName("PT0S looks for nothing: the module's user tasks are named as not looked for, and no job is listed")
    void aZeroIntervalLooksForNothing(final CapturedOutput output) {
        startsPolling(Duration.ZERO);

        poller.look();

        assertAll(
                () -> assertTrue(listings().isEmpty(), jobs.engine
                        .requests()::toString),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains("""
                                ZenBPM[orders]: workflow module 'shop' has user tasks of the job type(s) \
                                shop__Order__approve, which are not looked for, because \
                                vanillabp.adapters.orders.user-task-poll-interval is PT0S"""),
                        output::getOutOfThisTest));
    }

    @Test
    @DisplayName("The start of the looks names the module, the job types, the interval and what a look costs")
    void theStartOfTheLooksIsSaid(final CapturedOutput output) {
        startsPolling(Duration.ofSeconds(5));

        assertTrue(output
                .getOutOfThisTest()
                .contains(
                        """
                                ZenBPM[orders]: workflow module 'shop' has its user tasks of the job type(s) \
                                shop__Order__approve looked for every PT5S (vanillabp.adapters.orders.user-task-poll-interval): \
                                each look lists the active and the terminated jobs of each type, every page of them, so a look \
                                reads more the more terminated user tasks the engine keeps, until the history-time-to-live \
                                of their workflows passed, and a cancellation is told only while the engine still lists its \
                                job. A look starts one interval after the last one ended, so a creation or a cancellation \
                                reaches the application one interval and the time of a look late, and later where every \
                                handler slot is taken or the engine does not answer"""),
                output::getOutOfThisTest);
    }

    @Test
    @DisplayName("The timing threads run a look every interval once a module registered, and the looks end with the last module")
    void theLooksRunEveryIntervalUntilTheLastModuleLeaves() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        startsPolling(Duration.ofMillis(50));
        JobRunning.await(() -> listings().size() >= 4, "two looks");
        JobRunning.await(() -> deliveryIds().size() == 1, "the notification");

        poller.unregister("shop");
        // a look which ran at the moment of the stop may still list
        LockSupport.parkNanos(Duration
                .ofMillis(200)
                .toNanos());
        final var afterTheStop = listings().size();
        LockSupport.parkNanos(Duration
                .ofMillis(300)
                .toNanos());

        assertEquals(afterTheStop, listings().size());
        assertEquals(List.of("51-created"), deliveryIds());
    }

    @Test
    @DisplayName("A look reads every page of a state, and a job of another element type which shares the type is the job stream's and is not handed over")
    void aLookReadsEveryPageAndLeavesTheTasksToTheStream() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        final var firstPage = new ArrayList<String>();
        for (var key = 1000; key < 1099; ++key) {
            firstPage.add(job(key, ROOT, "SERVICE_TASK", "active"));
        }
        firstPage.add(userTask(51, ROOT));
        listed.put("active/1", page(101, firstPage));
        listed.put("active/2", page(101, List.of(userTask(52, ROOT))));
        startsPolling(Duration.ofHours(1));

        looks();

        assertEquals(List.of("51-created", "52-created"), deliveryIds().stream().sorted().toList());
        assertEquals(List.of("active/1", "active/2", "terminated/1"), listings());
    }

    @Test
    @DisplayName("A user task of a process this application does not claim, of the engine's default type which every application shares, is never told, and its instance is read once")
    void aUserTaskOfAProcessNobodyHereClaimsIsLeftAlone() {
        jobs.holds(300, "billing__Invoice", "default", null, "invoice-1", "\"id\": \"invoice-1\"");
        listsActive(userTask(61, 300));
        startsPolling(Duration.ofHours(1));
        // a cache which keeps nothing, so a second look at the job would read the instance again
        poller = new ZenBpmUserTaskPoller("orders", jobs.factory.rest(), jobs.factory
                .executor(), new ZenBpmInstanceCache(jobs.factory.rest(), 0), Duration.ofHours(1), jobs.metrics);
        poller.register(notifier);

        looksTwice();

        assertTrue(jobs.invoker
                .invocations()
                .isEmpty());
        assertEquals(1, readsOfInstance(300));
    }

    @Test
    @DisplayName("A user task no method serves, one marked implemented-externally which shares its type with a served one, is not told and costs no invocation")
    void aUserTaskNoMethodServesIsNotNotified() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        startsPolling(Duration.ofHours(1));
        jobs.invoker.servesUserTasks("Sign");

        looksTwice();

        assertTrue(jobs.invoker
                .invocations()
                .isEmpty());
        assertEquals(List.of("Order/approve", "Order/Approve"), jobs.invoker.handlerQuestions());
    }

    @Test
    @DisplayName("A user task served by its element id is told, although no method names its task definition")
    void aUserTaskServedByItsElementIdIsNotified() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        startsPolling(Duration.ofHours(1));
        jobs.invoker.servesUserTasks("Approve");

        looks();

        assertEquals(List.of("51-created"), deliveryIds());
    }

    @Test
    @DisplayName("A look the engine does not answer takes no user task for new or gone, is warned about once per outage, and the next look which is answered says so and tells what it finds; each look is counted as a poll, the failed ones included")
    void aFailedLookIsWarnedAboutOncePerOutage(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.engine.answers("GET", "/v1/jobs", 503, "{\"code\": \"UNAVAILABLE\", \"message\": \"no leader\"}");
        startsPolling(Duration.ofHours(1));
        looksTwice();
        final var warnings = countOf(output.getOutOfThisTest(), "did not answer the look at the user tasks");
        answersTheListings();
        listsActive(userTask(51, ROOT));

        looks();

        assertAll(
                () -> assertEquals(1, warnings),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains("the engine answers the looks at the user tasks again"), output::getOutOfThisTest),
                () -> assertEquals(List.of("51-created"), deliveryIds()),
                () -> assertEquals(3, jobs.counted(ZenBpmMetrics.USER_TASK_POLLS)));
    }

    @Test
    @DisplayName("A look which fails after the looks stopped, as one which runs into the shutdown of the clients, warns about nothing")
    void aFailedLookAfterTheStopIsNoOutage(final CapturedOutput output) throws InterruptedException {
        final var listing = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        jobs.engine.answers("GET", "/v1/jobs", exchange -> {
            listing.countDown();
            await(release);
            FakeEngine.respond(exchange, 503, "{\"code\": \"UNAVAILABLE\", \"message\": \"shutting down\"}");
        });
        startsPolling(Duration.ofHours(1));
        final var look = Thread
                .ofVirtual()
                .start(poller::look);
        await(listing);

        poller.unregister("shop");
        poller.unregister("shop");
        release.countDown();

        assertTrue(look.join(Duration.ofSeconds(10)));
        assertFalse(output
                .getOutOfThisTest()
                .contains("did not answer the look"), output::getOutOfThisTest);
    }

    @Test
    @DisplayName("An instance which cannot be read, or which a lagging node does not know, skips its user task only, which a later look hands over again")
    void anInstanceWhichCannotBeReadSkipsItsUserTaskOnly() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.engine.answers("GET", "/v1/process-instances/300", 503, "{\"code\": \"UNAVAILABLE\", \"message\": \"x\"}");
        jobs.engine.answers("GET", "/v1/process-instances/400", 404, "{\"code\": \"NOT_FOUND\", \"message\": \"x\"}");
        listsActive(userTask(51, ROOT), userTask(52, 300), userTask(53, 400));
        startsPolling(Duration.ofHours(1));
        // two slots: the first look hands over 51 and 52, the second 52 and 53
        looksTwice();
        final var beforeTheInstancesAreKnown = deliveryIds();
        jobs.holds(300, "default", null, "order-2", "\"id\": \"order-2\"");
        jobs.holds(400, "default", null, "order-3", "\"id\": \"order-3\"");

        // 52 was not told twice, so it waits two looks
        looksTwice();

        assertEquals(List.of("51-created"), beforeTheInstancesAreKnown);
        assertEquals(List.of("51-created", "52-created", "53-created"), deliveryIds()
                .stream()
                .sorted()
                .toList());
    }

    @Test
    @DisplayName("A user task in a sub-process names the root as its workflow, and the aggregate the root names where only the root carries it; one whose root a lagging node does not know, or which cannot be read, waits for a later look")
    void aUserTaskInASubProcessNamesTheRoot() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.holds(200, "subprocess", ROOT, null, "");
        jobs.holds(500, "subprocess", 501L, null, "");
        jobs.engine.answers("GET", "/v1/process-instances/501", 404, "{\"code\": \"NOT_FOUND\", \"message\": \"x\"}");
        jobs.holds(600, "subprocess", 601L, null, "");
        jobs.engine.answers("GET", "/v1/process-instances/601", 503, "{\"code\": \"UNAVAILABLE\", \"message\": \"x\"}");
        listsActive(userTask(51, 200), userTask(52, 500), userTask(53, 600));
        startsPolling(Duration.ofHours(1));

        looksTwice();
        final var beforeTheRootsAreKnown = deliveryIds();
        jobs.holds(501, "default", null, "order-2", "\"id\": \"order-2\"");
        jobs.holds(601, "default", null, "order-3", "\"id\": \"order-3\"");
        // 52 was not told twice, so it waits two looks
        looksTwice();

        assertEquals(List.of("51-created"), beforeTheRootsAreKnown);
        assertEquals(List.of("51-created", "52-created", "53-created"), deliveryIds()
                .stream()
                .sorted()
                .toList());
        final var invocations = jobs.invoker.invocations();
        assertAll(
                () -> assertEquals("100", invocations
                        .getFirst()
                        .context()
                        .getWorkflowId()),
                () -> assertEquals("order-1", invocations
                        .getFirst()
                        .context()
                        .getWorkflowAggregateId()));
    }

    @Test
    @DisplayName("A user task which is an iteration of a multi-instance user task is told its item, its index and the size of the collection, as a job is, and a listing which lacks the iteration is read again")
    void aUserTaskInAnIterationIsToldItsIteration() {
        final var variables = "\"id\": \"order-1\", \"parcels\": [\"a\", \"b\"]";
        jobs.holds(ROOT, "shop__Order", "default", null, "order-1", variables, List.of("Approve"));
        // the collection the engine evaluated when it entered the element, at the entry of the token
        jobs.engine.answers("GET", "/v1/process-instances/%d/history".formatted(ROOT), 200, """
                {"items": [{"key": 7, "processInstanceKey": %d, "createdAt": "2026-10-17T14:30:25.123Z",
                  "elementId": "Approve", "elementType": "USER_TASK", "inputVariables": {"parcel": ["a", "b"]}}],
                 "page": 1, "size": 100, "count": 1, "totalCount": 1}""".formatted(ROOT));
        // the body of the multi-instance user task, with a copy of the variables of the root
        jobs.holds(300, "multiInstance", ROOT, "order-1", variables);
        jobs.holdsTheModel(JobRunning.DEFINITION_KEY, """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="Definitions_Order" \
                targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="shop__Order" isExecutable="true">
                    <bpmn:userTask id="Approve">
                      <bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>
                        <zenbpm:loopCharacteristics inputCollection="=parcels" inputElement="parcel" />
                      </bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                  </bpmn:process>
                </bpmn:definitions>""");
        // a lagging node lists the iteration only at the second read, which the notification repeats
        listedOfInstances.put(300L,
                new ArrayDeque<>(List.of(page(1, List.of(userTaskOfParcel(51, "2026-10-19T10:00:00Z",
                        "a"))), page(2, List.of(userTaskOfParcel(52, "2026-10-19T10:00:01Z", "b"),
                                userTaskOfParcel(51,
                                        "2026-10-19T10:00:00Z", "a"))))));
        listsActive(userTask(52, 300));
        startsPolling(Duration.ofHours(1));

        looks();

        assertEquals(Map.of("Approve", new MultiInstanceValue("b", 1, 2)), jobs.invoker
                .invocations()
                .getFirst()
                .context()
                .getMultiInstances());
    }

    @Test
    @DisplayName("A user task whose instance names no aggregate was started past VanillaBP: it is not told, which a WARN says once")
    void aUserTaskWithoutAnAggregateIsWarnedAboutOnce(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, null, "");
        listsActive(userTask(51, ROOT));
        startsPolling(Duration.ofHours(1));

        looksTwice();

        assertTrue(jobs.invoker
                .invocations()
                .isEmpty());
        assertEquals(1, countOf(output.getOutOfThisTest(), "the instance names no workflow aggregate"),
                output::getOutOfThisTest);
    }

    @Test
    @DisplayName("A notification about a workflow this application does not own, and one whose method raised a BPMN error, are warned about and not told again")
    void aRefusedNotificationAndABpmnErrorAreNotRepeated(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.holds(300, "default", null, "order-2", "\"id\": \"order-2\"");
        listsActive(userTask(51, ROOT), userTask(52, 300));
        jobs.invoker.answers(context -> {
            if ("51".equals(context.getTaskId())) {
                throw new DeliveryOfAnUnknownWorkflowException("orders", "shop", "Order", "approve", "org.example.Order", "order-1", "100");
            }
            return WorkflowTaskOutcome.bpmnError("rejected", "Rejected");
        });
        startsPolling(Duration.ofHours(1));

        looksTwice();

        assertAll(
                () -> assertEquals(List.of("51-created", "52-created"), deliveryIds()
                        .stream()
                        .sorted()
                        .toList()),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains(
                                "is about a workflow this application does not own, so it is not notified as CREATED"),
                        output::getOutOfThisTest),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains(
                                "raised the BPMN error 'rejected', which a notification cannot throw into the workflow"),
                        output::getOutOfThisTest),
                () -> assertEquals(1, jobs.invoker
                        .reports()
                        .size()));
    }

    @Test
    @DisplayName("A look at the other open tasks which fails costs the notification nothing")
    void aFailedLookAtTheOtherTasksIsSwallowed() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        jobs.invoker.failsToReport(new IllegalStateException("the delivery log is down"));
        startsPolling(Duration.ofHours(1));

        looksTwice();

        assertEquals(List.of("51-created"), deliveryIds());
    }

    @Test
    @DisplayName("A user task whose notification still runs is not handed over a second time, and where every handler slot is taken, the look ends and the next one hands the rest over")
    void aLookWithoutAFreeSlotEnds() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        final var release = new CountDownLatch(1);
        jobs.invoker.answers(context -> {
            if (!"53".equals(context.getTaskId())) {
                await(release);
            }
            return WorkflowTaskOutcome.completionPending();
        });
        startsPolling(Duration.ofHours(1));
        poller.look();
        JobRunning.await(() -> jobs.invoker
                .invocations()
                .size() == 1, "the first notification to run");
        // one slot is free, and the notification of 51 runs in the other
        poller.look();
        listsActive(userTask(51, ROOT), userTask(52, ROOT), userTask(53, ROOT));
        final var listedBefore = listings().size();
        poller.look();
        final var listedByTheLookWithoutASlot = listings().subList(listedBefore, listings().size());
        JobRunning.await(() -> jobs.invoker
                .invocations()
                .size() == 2, "both slots to run a notification");

        release.countDown();
        jobs.awaitFreeSlots();
        looks();

        assertEquals(List.of("51-created", "52-created", "53-created"), deliveryIds());
        assertEquals(List.of("active/1"), listedByTheLookWithoutASlot);
    }

    @Test
    @DisplayName("A hand-over refused while the terminated jobs are listed ends the look as well, and the next look tells the cancellation")
    void aLookWithoutAFreeSlotForACancellationEnds() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT), userTask(52, ROOT));
        listsTerminated(terminated(54, ROOT));
        final var release = new CountDownLatch(1);
        jobs.invoker.answers(context -> {
            if (context.getTaskEvent() == TaskEvent.Event.CREATED) {
                await(release);
            }
            return WorkflowTaskOutcome.completionPending();
        });
        startsPolling(Duration.ofHours(1));
        poller.look();
        JobRunning.await(() -> jobs.invoker
                .invocations()
                .size() == 2, "both slots to run a notification");
        final var toldWhileTheSlotsWereTaken = deliveryIds();

        release.countDown();
        jobs.awaitFreeSlots();
        looks();

        assertEquals(List.of("51-created", "52-created"), toldWhileTheSlotsWereTaken
                .stream()
                .sorted()
                .toList());
        assertEquals(List.of("51-created", "52-created", "54"), deliveryIds()
                .stream()
                .sorted()
                .toList());
    }

    @Test
    @DisplayName("Two workflow modules share the looks of their adapter id: each user task goes to the module whose process it belongs to, and the looks go on for the one which stays when the other stops")
    void twoModulesShareTheLooks() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.holds(300, "billing__Invoice", "default", null, "invoice-1", "\"id\": \"invoice-1\"");
        listsActive(userTask(51, ROOT));
        listedForBilling.put("active/1", page(1, List.of(job(61, 300, "USER_TASK", "active").replace(USER_TASK_TYPE,
                BILLING_TYPE))));
        startsPolling(Duration.ofHours(1));
        final var billing = new ZenBpmProcessingContext("billing");
        billing.recordWiring(
                new WiredProcess("Invoice", "billing__Invoice", Set.of(), true, false, Map.of(), Map.of(), Map.of(
                        BILLING_TYPE, "check")));
        final var collaborators = TestCollaborators.withInvoker("orders", TestScoping.of(
                NameClashAvoidance.USE_PREFIX), jobs.invoker);
        final var billingHandler = new ZenBpmJobHandler("orders", jobs.factory, collaborators, billing, ZenBpmRetryBackoffResolver.NONE, jobs.metrics);
        poller.register(new ZenBpmUserTaskNotifier("orders", jobs.factory, collaborators, billing, billingHandler));
        jobs.invoker.servesUserTasks("approve", "check");
        looks();
        poller.unregister("shop");
        final var listedBefore = listings().size();
        listedForBilling.put("active/1", page(1, List.of(job(62, 300, "USER_TASK", "active").replace(USER_TASK_TYPE,
                BILLING_TYPE))));

        looks();

        assertEquals(List.of("billing/61-created", "billing/62-created", "shop/51-created"), jobs.invoker
                .invocations()
                .stream()
                .map(invocation -> "%s/%s".formatted(invocation.workflowModuleId(), invocation
                        .context()
                        .getDeliveryId()))
                .sorted()
                .toList());
        assertEquals(2, listings().size() - listedBefore);
    }

    @Test
    @DisplayName("A user task is not told while its workflow module shuts down, nor once the adapter shuts down, which is no outage of the engine and times no further look")
    void nothingIsToldWhileTheModuleOrTheAdapterShutsDown(final CapturedOutput output) throws InterruptedException {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        listsActive(userTask(51, ROOT));
        startsPolling(Duration.ofHours(1));
        handler.stopping();
        looks();
        final var executor = jobs.factory.executor();
        executor.shutdown();
        assertTrue(executor.awaitTermination(Duration.ofSeconds(10)));

        poller.look();
        poller.unregister("shop");
        poller.register(notifier);

        assertAll(
                () -> assertTrue(jobs.invoker
                        .invocations()
                        .isEmpty()),
                () -> assertFalse(output
                        .getOutOfThisTest()
                        .contains("did not answer the look"), output::getOutOfThisTest));
    }

    @Test
    @DisplayName("A module which starts while the look that met the stop of the last module still runs adds no second line of looks: no two looks ever run at once")
    void aStartDuringTheLastLookAddsNoSecondLineOfLooks() {
        final var looksAtOnce = new LooksAtOnce();
        final var firstLook = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        jobs.engine.answers("GET", "/v1/jobs", exchange -> looksAtOnce.count(() -> {
            firstLook.countDown();
            await(release);
            FakeEngine.respond(exchange, 200, page(0, List.of()));
        }));
        startsPolling(Duration.ofMillis(20));
        await(firstLook);

        poller.unregister("shop");
        poller.register(notifier);
        release.countDown();
        LockSupport.parkNanos(Duration
                .ofMillis(500)
                .toNanos());
        poller.unregister("shop");

        assertAll(
                () -> assertEquals(1, looksAtOnce.most()),
                () -> assertTrue(listings().size() > 4, () -> listings().toString()));
    }

    @Test
    @DisplayName("A look timed before the stop of the last module, whose cancellation came too late, does not start once a module started again, so there is one line of looks and not two")
    void aLookTimedBeforeAStopDoesNotStartAfterIt() {
        final var looksAtOnce = new LooksAtOnce();
        jobs.engine.answers("GET", "/v1/jobs", exchange -> looksAtOnce.count(() -> FakeEngine.respond(exchange,
                200, page(0, List.of()))));
        startsPolling(Duration.ofMillis(20));

        // the timing thread waits for the poller while the first look is due, and meanwhile the
        // module stops and starts again, which times a second look
        synchronized (poller) {
            LockSupport.parkNanos(Duration
                    .ofMillis(100)
                    .toNanos());
            poller.unregister("shop");
            poller.register(notifier);
            LockSupport.parkNanos(Duration
                    .ofMillis(100)
                    .toNanos());
        }
        LockSupport.parkNanos(Duration
                .ofMillis(400)
                .toNanos());
        poller.unregister("shop");

        assertAll(
                () -> assertEquals(1, looksAtOnce.most()),
                () -> assertTrue(listings().size() > 4, () -> listings().toString()));
    }

    @Test
    @DisplayName("A user task of a workflow which runs on a version older than the one this start deployed says so")
    void aUserTaskOfAnOlderVersionSaysSo() {
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
        jobs.factory
                .deployedProcesses()
                .record(new ZenBpmDeployedProcesses.DeployedProcess("shop", "Order", "shop__Order", 4503599627370499L, 4, null, "<definitions/>"));
        listsActive(userTask(51, ROOT));
        startsPolling(Duration.ofHours(1));

        looks();

        assertTrue(jobs.invoker
                .invocations()
                .getFirst()
                .context()
                .predatesDeployedVersion());
    }

    @Test
    @DisplayName("A module without user tasks to be told of starts no looks, and the delivery id of a creation and a cancellation carries the event")
    void aModuleWithoutUserTasksStartsNoLooks() {
        final var context = new ZenBpmProcessingContext("billing");
        context.recordWiring(new WiredProcess("Invoice", "billing__Invoice", Set
                .of("billing__Invoice__bill"), true, false, Map.of(), Map.of(), Map.of()));
        poller = new ZenBpmUserTaskPoller("orders", jobs.factory, jobs.metrics);
        poller.register(new ZenBpmUserTaskNotifier("orders", jobs.factory, TestCollaborators.withInvoker("orders",
                TestScoping.of(NameClashAvoidance.USE_PREFIX), jobs.invoker), context, jobs.handler));

        poller.look();

        assertTrue(listings().isEmpty());
        assertEquals("7-created", ZenBpmUserTaskNotifier.deliveryIdOf(7, TaskEvent.Event.CREATED));
        assertEquals("7", ZenBpmUserTaskNotifier.deliveryIdOf(7, TaskEvent.Event.CANCELED));
    }

    // a fresh poller with its own set, as after a restart, of the module 'shop' with the process
    // 'Order' and its user task 'approve'; every handler of the module shares the same drain
    private void startsPolling(final Duration interval) {
        jobs.invoker.servesUserTasks("approve");
        if (handler == null) {
            final var context = new ZenBpmProcessingContext("shop");
            context.recordWiring(new WiredProcess("Order", "shop__Order", Set.of(JobRunning.JOB_TYPE), true, false, Map
                    .of(), Map.of(), Map.of(USER_TASK_TYPE, "approve")));
            final var collaborators = TestCollaborators.withInvoker("orders", TestScoping.of(
                    NameClashAvoidance.USE_PREFIX), jobs.invoker);
            handler = new ZenBpmJobHandler("orders", jobs.factory, collaborators, context, ZenBpmRetryBackoffResolver.NONE, jobs.metrics);
            notifier = new ZenBpmUserTaskNotifier("orders", jobs.factory, collaborators, context, handler);
        }
        poller = new ZenBpmUserTaskPoller("orders", jobs.factory.rest(), jobs.factory.executor(), jobs.factory
                .instances(), interval, jobs.metrics);
        poller.register(notifier);
    }

    // the workflow module 'billing', whose process 'Invoice' has a user task of the type of 'shop',
    // as two modules share the engine's default type
    private void startsBillingSharingTheType() {
        final var billing = new ZenBpmProcessingContext("billing");
        billing.recordWiring(
                new WiredProcess("Invoice", "billing__Invoice", Set.of(), true, false, Map.of(), Map.of(), Map.of(
                        USER_TASK_TYPE, "check")));
        final var collaborators = TestCollaborators.withInvoker("orders", TestScoping.of(
                NameClashAvoidance.USE_PREFIX), jobs.invoker);
        final var billingHandler = new ZenBpmJobHandler("orders", jobs.factory, collaborators, billing, ZenBpmRetryBackoffResolver.NONE, jobs.metrics);
        jobs.invoker.servesUserTasks("approve", "check");
        poller.register(new ZenBpmUserTaskNotifier("orders", jobs.factory, collaborators, billing, billingHandler));
    }

    private void answersTheListings() {
        jobs.engine.answers("GET", "/v1/jobs", exchange -> {
            final var query = exchange
                    .getRequestURI()
                    .getRawQuery();
            final var instance = INSTANCE.matcher(query);
            if (instance.find()) {
                final var pages = listedOfInstances.getOrDefault(Long.valueOf(instance.group(1)), new ArrayDeque<>(
                        List.of(page(0, List.of()))));
                FakeEngine.respond(exchange, 200, pages.size() > 1 ? pages.poll() : pages.peek());
                return;
            }
            final var state = STATE.matcher(query);
            final var page = PAGE.matcher(query);
            assertTrue(state.find() && page.find(), query);
            final var listedOfTheType = query.contains("jobType=%s".formatted(BILLING_TYPE)) ? listedForBilling
                    : listed;
            FakeEngine.respond(exchange, 200, listedOfTheType.getOrDefault("%s/%s".formatted(state.group(1), page
                    .group(1)), page(0, List.of())));
        });
    }

    private void listsActive(final String... jobsListed) {
        listed.put("active/1", page(jobsListed.length, List.of(jobsListed)));
    }

    private void listsTerminated(final String... jobsListed) {
        listed.put("terminated/1", page(jobsListed.length, List.of(jobsListed)));
    }

    private void looks() {
        poller.look();
        jobs.awaitFreeSlots();
    }

    private void looksTwice() {
        looks();
        looks();
    }

    private List<String> deliveryIds() {
        return jobs.invoker
                .invocations()
                .stream()
                .map(TestInvoker.Invocation::context)
                .map(TaskInvocationContext::getDeliveryId)
                .toList();
    }

    private List<String> modulesAndDeliveryIds() {
        return jobs.invoker
                .invocations()
                .stream()
                .map(invocation -> "%s/%s".formatted(invocation.workflowModuleId(), invocation
                        .context()
                        .getDeliveryId()))
                .toList();
    }

    // each as state/page
    private List<String> listings() {
        return jobs.engine
                .requests()
                .stream()
                .filter(request -> "/v1/jobs".equals(request.path()))
                .map(request -> {
                    final var state = STATE.matcher(request.query());
                    final var page = PAGE.matcher(request.query());
                    return state.find() && page.find() ? "%s/%s".formatted(state.group(1), page.group(1)) : "?";
                })
                .toList();
    }

    private long readsOfInstance(final long key) {
        return jobs.engine
                .requests()
                .stream()
                .filter(request -> "/v1/process-instances/%d".formatted(key).equals(request.path()))
                .count();
    }

    private static int countOf(
        final String text,
        final String part
    ) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    /**
     * Counts the listings which run at the same time: the requests of one look follow each other,
     * so two at once are two looks at once. Each listing takes a moment, so two looks which start
     * together overlap.
     */
    private static final class LooksAtOnce {

        private final AtomicInteger now = new AtomicInteger();

        private final AtomicInteger most = new AtomicInteger();

        void count(final Listing listing) throws IOException {
            most.accumulateAndGet(now.incrementAndGet(), Math::max);
            try {
                LockSupport.parkNanos(Duration
                        .ofMillis(50)
                        .toNanos());
                listing.run();
            } finally {
                now.decrementAndGet();
            }
        }

        int most() {
            return most.get();
        }

    }

    /**
     * The answer to one listing.
     */
    @FunctionalInterface
    private interface Listing {

        void run() throws IOException;

    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new IllegalStateException(e);
        }
    }

    // one partition, as a one-node engine answers
    private static String page(
        final long totalCount,
        final List<String> items
    ) {
        return """
                {"partitions": [{"partition": 1, "totalCount": %d, "items": [%s]}], "totalCount": %d}"""
                .formatted(totalCount, String.join(",", items), totalCount);
    }

    // as the engine lists a user task's job: the variables of its scope at its creation, the
    // aggregate id among them
    private static String userTask(
        final long key,
        final long instanceKey
    ) {
        return job(key, instanceKey, "USER_TASK", "active");
    }

    // as the engine lists the job of an iteration of a multi-instance user task: with its item
    private static String userTaskOfParcel(
        final long key,
        final String createdAt,
        final String parcel
    ) {
        return """
                {"key": %d, "elementId": "Approve", "type": "%s", "elementType": "USER_TASK", "processInstanceKey": 300,
                 "elementInstanceKey": %d, "state": "active", "createdAt": "%s",
                 "inputVariables": {"parcel": "%s"}}""".formatted(key, USER_TASK_TYPE, key + 1_000_000, createdAt,
                parcel);
    }

    private static String terminated(
        final long key,
        final long instanceKey
    ) {
        return job(key, instanceKey, "USER_TASK", "terminated");
    }

    private static String job(
        final long key,
        final long instanceKey,
        final String elementType,
        final String state
    ) {
        return """
                {"key": %d, "elementId": "Approve", "type": "%s", "elementType": "%s", "processInstanceKey": %d,
                 "elementInstanceKey": %d, "state": "%s", "createdAt": "2026-10-19T10:00:00Z",
                 "inputVariables": {"id": "order-1", "note": "rush"}}""".formatted(key, USER_TASK_TYPE, elementType,
                instanceKey, key + 1_000_000, state);
    }

}
