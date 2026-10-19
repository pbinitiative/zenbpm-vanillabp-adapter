/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.api.HistoryEntry;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.Approval;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.ApprovalRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.Offer;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.OfferRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.Quote;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.QuoteRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.Review;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.ReviewRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.Signature;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.UserTaskItApplication;
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
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.TaskNotFoundException;

/**
 * The user tasks of a booted Spring Boot application against the pinned engine: the user-task
 * poller looks for them every second, as <code>usertask-it.yaml</code> configures it, and tells the
 * application once that a user task was created and once that it was cancelled, whether the
 * instance was cancelled over the engine's API or an interrupting boundary event took the user task
 * away while a service task of the same workflow waited; the cancellation of a user task wakes its
 * workflow up, so the service task left open in a cancelled instance is told as cancelled as well;
 * the job stream never takes a user task, and a user task the application marks
 * <code>implemented-externally</code> is never looked for. The application completes a user task,
 * after which the gateway behind it decides on the values the aggregate shares, or cancels it with a
 * BPMN error, which the error boundary event of the user task catches and which the application is
 * then told of as a cancellation, once; a user task which ends before the commit is not found, and
 * the commands of a task find no user task.
 * <p>
 * The application runs the workflow module <code>deploy-it</code> under <code>use-prefix</code> with
 * the processes <code>Approving</code> of <code>usertask-it/approvals.bpmn</code>,
 * <code>Offering</code> of <code>usertask-it/offers.bpmn</code>, <code>Quoting</code> of
 * <code>usertask-it/quotes.bpmn</code>, <code>Reviewing</code> of
 * <code>usertask-it/reviews.bpmn</code> and <code>Signing</code> of
 * <code>usertask-it/signatures.bpmn</code>. It is started once for the class, against an engine of
 * its own.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = UserTaskItApplication.class, properties = "spring.config.name=usertask-it")
@DirtiesContext
class ZenBpmUserTaskIT {

    /**
     * How long a step may take. A look runs every second, so the limit only turns a notification
     * which never arrives into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * How long a test watches for a second notification after the first: three looks.
     */
    private static final Duration THREE_LOOKS = Duration.ofMillis(3_500);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Autowired
    private ProcessService<Approval> approvalProcessService;

    @Autowired
    private ApprovalRepository approvals;

    @Autowired
    private ProcessService<Offer> offerProcessService;

    @Autowired
    private OfferRepository offers;

    @Autowired
    private ProcessService<Quote> quoteProcessService;

    @Autowired
    private QuoteRepository quotes;

    @Autowired
    private ProcessService<Review> reviewProcessService;

    @Autowired
    private ReviewRepository reviews;

    @Autowired
    private ProcessService<Signature> signatureProcessService;

    @Autowired
    private ZenBpmClientRegistry clients;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("A created user task is told to the application once, as a user task with its job key as the task id, and the job stream never hands its job out")
    void userTaskCreatedIsNotifiedOnce() {
        final var approvalId = "approval-%s".formatted(UUID.randomUUID());
        startApproval(approvalId);
        final var taskId = eventually(() -> approvals
                .findById(approvalId)
                .map(Approval::getApprovalTaskId));

        watchThreeLooks();

        final var job = ENGINE.json("/v1/jobs/%s".formatted(taskId));
        final var deliveries = deliveriesOf(approvalId);
        assertAll(
                () -> assertEquals(1, approvals
                        .findById(approvalId)
                        .orElseThrow()
                        .getCreations()),
                () -> assertEquals("USER_TASK", job
                        .path("elementType")
                        .asText()),
                () -> assertEquals("deploy-it__Approving__approve", job
                        .path("type")
                        .asText()),
                () -> assertEquals(0, job
                        .path("deliveryToken")
                        .asLong(), job::toString),
                () -> assertEquals(1, deliveries.size(), deliveries::toString),
                () -> assertEquals("USER_TASK", deliveries
                        .getFirst()
                        .taskKind()),
                () -> assertEquals("Approve", deliveries
                        .getFirst()
                        .bpmnElementId()),
                () -> assertEquals("approve", deliveries
                        .getFirst()
                        .taskDefinition()),
                () -> assertEquals(taskId, deliveries
                        .getFirst()
                        .taskId()),
                () -> assertEquals("COMPLETION_PENDING", deliveries
                        .getFirst()
                        .outcome()));
    }

    @Test
    @DisplayName("A user task whose instance is cancelled over the engine's API is told to the application as cancelled, once, and its record is closed")
    void userTaskCanceledOnInstanceCancellation() throws IOException, InterruptedException {
        final var approvalId = "approval-%s".formatted(UUID.randomUUID());
        final var instance = startApproval(approvalId);
        final var taskId = eventually(() -> approvals
                .findById(approvalId)
                .map(Approval::getApprovalTaskId));

        cancel(instance.key());
        eventually(() -> approvals
                .findById(approvalId)
                .filter(approval -> approval.getCancellations() > 0));
        watchThreeLooks();

        final var approval = approvals
                .findById(approvalId)
                .orElseThrow();
        final var deliveries = deliveriesOf(approvalId);
        assertAll(
                () -> assertEquals(1, approval.getCreations()),
                () -> assertEquals(1, approval.getCancellations()),
                () -> assertEquals("terminated", ENGINE
                        .json("/v1/jobs/%s".formatted(taskId))
                        .path("state")
                        .asText()),
                () -> assertEquals(2, deliveries.size(), deliveries::toString),
                () -> assertTrue(deliveries
                        .stream()
                        .allMatch(TaskDeliveryLogReader.Delivery::taskWasClosed), deliveries::toString));
    }

    @Test
    @DisplayName("A user task an interrupting timer boundary event takes away while a service task of the same workflow waits is told as cancelled once, also once the service task was delivered and the platform looked at the other open tasks of the workflow")
    void aUserTaskTakenAwayIsCanceledOnce() {
        final var offerId = "offer-%s".formatted(UUID.randomUUID());
        transactionTemplate.executeWithoutResult(status -> offerProcessService.startWorkflow(new Offer(offerId)));
        final var taskId = eventually(() -> offers
                .findById(offerId)
                .map(Offer::getAcceptanceTaskId));

        // the timer takes the user task away after two seconds, the service task comes after five
        eventually(() -> offers
                .findById(offerId)
                .filter(Offer::isArchived)
                .filter(offer -> offer.getCancellations() > 0));
        watchThreeLooks();

        final var offer = offers
                .findById(offerId)
                .orElseThrow();
        assertAll(
                () -> assertEquals(1, offer.getCreations()),
                () -> assertEquals(1, offer.getCancellations()),
                () -> assertEquals("terminated", ENGINE
                        .json("/v1/jobs/%s".formatted(taskId))
                        .path("state")
                        .asText()),
                () -> assertEquals(3, deliveriesOf(offerId).size(), () -> deliveriesOf(offerId).toString()));
    }

    @Test
    @DisplayName("The cancellation of a user task wakes its workflow up: where the instance is cancelled over the engine's API, the service task its application left open is told as cancelled as well, once, through the platform's look at the other open tasks")
    void theCancellationOfAUserTaskTellsTheOpenServiceTaskOfItsWorkflow() throws IOException, InterruptedException {
        final var quoteId = "quote-%s".formatted(UUID.randomUUID());
        transactionTemplate.executeWithoutResult(status -> quoteProcessService.startWorkflow(new Quote(quoteId)));
        eventually(() -> quotes
                .findById(quoteId)
                .filter(quote -> (quote.getQuotingTaskId() != null) && (quote.getPricingTaskId() != null)));
        final var instance = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(quoteId))
                .items()
                .stream()
                .findFirst());

        cancel(instance.key());
        eventually(() -> quotes
                .findById(quoteId)
                .filter(quote -> (quote.getQuotingCancellations() > 0) && (quote.getPricingCancellations() > 0)));
        watchThreeLooks();

        final var quote = quotes
                .findById(quoteId)
                .orElseThrow();
        assertAll(
                () -> assertEquals(1, quote.getQuotingCancellations()),
                () -> assertEquals(1, quote.getPricingCancellations()),
                () -> assertEquals("terminated", ENGINE
                        .json("/v1/jobs/%s".formatted(quote.getPricingTaskId()))
                        .path("state")
                        .asText()));
    }

    @Test
    @DisplayName("A user task the application marks implemented-externally is never looked for: its type is not among those the start names, and nothing is told about it")
    void aMarkedUserTaskIsNotPolled(final CapturedOutput output) {
        final var signatureId = "signature-%s".formatted(UUID.randomUUID());
        transactionTemplate.executeWithoutResult(status -> signatureProcessService.startWorkflow(new Signature(
                signatureId)));
        final var instance = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(signatureId))
                .items()
                .stream()
                .findFirst());
        final var signing = eventually(() -> rest()
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(instance.key()))
                .items()
                .stream()
                .filter(job -> "Sign".equals(job.elementId()))
                .findFirst());

        watchThreeLooks();

        final var startLine = output
                .getAll()
                .lines()
                .filter(line -> line.contains("has its user tasks of the job type(s)"))
                .toList();
        assertAll(
                () -> assertEquals(JobRef.State.ACTIVE, signing.state()),
                () -> assertEquals("deploy-it__Signing__sign", signing.type()),
                () -> assertTrue(deliveriesOf(signatureId).isEmpty()),
                () -> assertEquals(1, startLine.size(), startLine::toString),
                () -> assertTrue(startLine
                        .getFirst()
                        .contains("deploy-it__Approving__approve"), startLine::toString),
                () -> assertTrue(startLine
                        .getFirst()
                        .contains("deploy-it__Offering__accept"), startLine::toString),
                () -> assertFalse(startLine
                        .getFirst()
                        .contains("sign"), startLine::toString));
    }

    @Test
    @DisplayName("A user task the application completes continues its workflow: the gateway behind it decides on what the application changed before the completion, the record of the user task is closed, and nothing is told as cancelled")
    void completeUserTaskContinuesWithSharedValues() {
        final var reviewId = "review-%s".formatted(UUID.randomUUID());
        final var started = startReview(reviewId);
        final var taskId = reviewTaskOf(reviewId);

        transactionTemplate.executeWithoutResult(status -> {
            final var review = reviews
                    .findById(reviewId)
                    .orElseThrow();
            review.accept();
            reviewProcessService.completeUserTask(review, taskId);
        });
        final var ended = awaitTheEndOf(started.key());
        watchThreeLooks();

        final var review = reviews
                .findById(reviewId)
                .orElseThrow();
        final var deliveries = deliveriesOf(reviewId);
        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertTrue(visited(ended).containsAll(List.of("Decision", "Accepted")), () -> visited(ended)
                        .toString()),
                () -> assertFalse(visited(ended).contains("Rejected"), () -> visited(ended).toString()),
                () -> assertEquals("completed", ENGINE
                        .json("/v1/jobs/%s".formatted(taskId))
                        .path("state")
                        .asText()),
                () -> assertEquals(1, review.getCreations()),
                () -> assertEquals(0, review.getCancellations()),
                () -> assertEquals(1, deliveries.size(), deliveries::toString),
                () -> assertTrue(deliveries
                        .getFirst()
                        .taskWasClosed(), deliveries::toString));
    }

    @Test
    @DisplayName("A user task the application cancels with a BPMN error is caught by the error boundary event of the user task, and the application is then told once that the user task was cancelled, as Camunda 7 tells it")
    void cancelUserTaskRoutesTheBoundaryEvent() {
        final var reviewId = "review-%s".formatted(UUID.randomUUID());
        final var started = startReview(reviewId);
        final var taskId = reviewTaskOf(reviewId);

        transactionTemplate.executeWithoutResult(status -> reviewProcessService.cancelUserTask(reviews
                .findById(reviewId)
                .orElseThrow(), taskId, "withdrawn"));
        final var ended = awaitTheEndOf(started.key());
        eventually(() -> reviews
                .findById(reviewId)
                .filter(review -> review.getCancellations() > 0));
        watchThreeLooks();

        final var review = reviews
                .findById(reviewId)
                .orElseThrow();
        final var deliveries = deliveriesOf(reviewId);
        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertTrue(visited(ended).containsAll(List.of("Withdrawn", "ReviewWithdrawn")), () -> visited(
                        ended).toString()),
                () -> assertFalse(visited(ended).contains("Decision"), () -> visited(ended).toString()),
                () -> assertEquals("terminated", ENGINE
                        .json("/v1/jobs/%s".formatted(taskId))
                        .path("state")
                        .asText()),
                () -> assertEquals(1, review.getCreations()),
                () -> assertEquals(1, review.getCancellations()),
                () -> assertEquals(2, deliveries.size(), deliveries::toString),
                () -> assertTrue(deliveries
                        .stream()
                        .allMatch(TaskDeliveryLogReader.Delivery::taskWasClosed), deliveries::toString));
    }

    @Test
    @DisplayName("Phase two of a user task which ended meanwhile is a WARN and nothing else, so its outbox entry is done, and a completed user task is never told as cancelled")
    void completingAGoneUserTaskIsAWarnedNoOp(final CapturedOutput output) {
        final var reviewId = "review-%s".formatted(UUID.randomUUID());
        final var started = startReview(reviewId);
        final var taskId = reviewTaskOf(reviewId);

        // both checks pass, as the user task waits at the commit, so the second phase two finds it
        // ended
        transactionTemplate.executeWithoutResult(status -> {
            final var review = reviews
                    .findById(reviewId)
                    .orElseThrow();
            reviewProcessService.completeUserTask(review, taskId);
            reviewProcessService.cancelUserTask(review, taskId, "withdrawn");
        });
        final var ended = awaitTheEndOf(started.key());
        final var warned = eventually(() -> output
                .getOutOfThisTest()
                .lines()
                .filter(line -> line.contains("Skipped phase two of") && line
                        .contains("canceling user task '%s'".formatted(
                                taskId)) && line.contains("it has ended already; the entry is consumed"))
                .findFirst());
        watchThreeLooks();

        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertTrue(visited(ended).contains("Rejected"), () -> visited(ended).toString()),
                () -> assertTrue(warned.contains("WARN"), warned),
                () -> assertEquals(0, reviews
                        .findById(reviewId)
                        .orElseThrow()
                        .getCancellations()));
    }

    @Test
    @DisplayName("The check of a user task's completion runs right before the commit: a user task which ends between the call and the commit is not found, and the transaction is rolled back with the platform's TaskNotFoundException")
    void completingAUserTaskWhichIsGoneThrowsTaskNotFound() {
        final var reviewId = "review-%s".formatted(UUID.randomUUID());
        final var started = startReview(reviewId);
        final var taskId = reviewTaskOf(reviewId);

        final var refused = assertThrows(TaskNotFoundException.class, () -> transactionTemplate
                .executeWithoutResult(status -> {
                    final var review = reviews
                            .findById(reviewId)
                            .orElseThrow();
                    review.accept();
                    reviewProcessService.completeUserTask(review, taskId);
                    // somebody else completes the user task after the call and before the commit
                    rest().completeJob(Long.parseLong(taskId), Map.of());
                }));
        final var ended = awaitTheEndOf(started.key());

        assertAll(
                () -> assertTrue(refused
                        .getMessage()
                        .contains("user task '%s' is gone, the engine holds it as completed".formatted(taskId)),
                        refused::getMessage),
                () -> assertFalse(reviews
                        .findById(reviewId)
                        .orElseThrow()
                        .isAccepted()),
                () -> assertTrue(visited(ended).contains("Rejected"), () -> visited(ended).toString()));
    }

    @Test
    @DisplayName("The commands of a task find no user task: completing a user task with completeTask throws the platform's TaskNotFoundException, which names the method for a user task, and the user task still waits")
    void theCommandsOfATaskFindNoUserTask() {
        final var reviewId = "review-%s".formatted(UUID.randomUUID());
        startReview(reviewId);
        final var taskId = reviewTaskOf(reviewId);

        final var refused = assertThrows(TaskNotFoundException.class, () -> transactionTemplate
                .executeWithoutResult(status -> reviewProcessService.completeTask(reviews
                        .findById(reviewId)
                        .orElseThrow(), taskId)));

        assertAll(
                () -> assertTrue(refused
                        .getMessage()
                        .contains("is the id of a user task"), refused::getMessage),
                () -> assertTrue(refused
                        .getMessage()
                        .contains("completeUserTask"), refused::getMessage),
                () -> assertEquals("active", ENGINE
                        .json("/v1/jobs/%s".formatted(taskId))
                        .path("state")
                        .asText()));
    }

    // only the test knows the ports the container was given
    @DynamicPropertySource
    static void theEngineUnderTest(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", ENGINE::restAddress);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    private ProcessInstanceRef startApproval(final String approvalId) {
        transactionTemplate.executeWithoutResult(status -> approvalProcessService.startWorkflow(new Approval(
                approvalId)));
        return eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(approvalId))
                .items()
                .stream()
                .findFirst());
    }

    private ProcessInstanceRef startReview(final String reviewId) {
        transactionTemplate.executeWithoutResult(status -> reviewProcessService.startWorkflow(new Review(reviewId)));
        return eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(reviewId))
                .items()
                .stream()
                .findFirst());
    }

    private String reviewTaskOf(final String reviewId) {
        return eventually(() -> reviews
                .findById(reviewId)
                .map(Review::getReviewTaskId));
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
        return visited;
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

    private List<TaskDeliveryLogReader.Delivery> deliveriesOf(final String aggregateId) {
        return TaskDeliveryLogReader
                .of(dataSource)
                .deliveries()
                .stream()
                .filter(delivery -> aggregateId.equals(delivery.aggregateId()))
                .toList();
    }

    private org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient rest() {
        return clients
                .getFactory("zenbpm")
                .rest();
    }

    // long enough for three looks, each of which would hand a notification over again
    private static void watchThreeLooks() {
        LockSupport.parkNanos(THREE_LOOKS.toNanos());
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
                    .ofMillis(100)
                    .toNanos());
        }
    }

}
