/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.Approval;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.ApprovalRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.Endorsement;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.EndorsementRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.EndorsementWorkflow;
import org.pbinitiative.zenbpmadapter.springboot.it.usertask.UserTaskItApplication;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * The notifications of a user task reach the application once across a restart, against the
 * pinned engine: the restarted application has forgotten what its poller told, finds the user
 * tasks again, and the platform's record of deliveries answers every notification told before the
 * restart, while one the engine produced while the application was down is told, also for a user
 * task whose creation nobody was told of. A user task the end of its workflow took away is told
 * as cancelled once, by the report of the end, and neither by the looks nor after a restart, where
 * the platform keeps its records; where the end releases them, the next look tells it again; and a
 * cancellation the end could not report is told by a look.
 * <p>
 * The case boots {@link UserTaskItApplication} itself, twice, with <code>usertask-it.yaml</code>, on
 * one in-memory database which outlives both applications, as the database of an application
 * outlives its restart. The test does not use <code>@SpringBootTest</code>, because closing the
 * application is part of the case.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmUserTaskRestartIT {

    /**
     * How long a step may take. A look runs every second, so the limit only turns a notification
     * which never arrives into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private String database;

    private ZenBpmRestClient engine;

    private final List<ConfigurableApplicationContext> applications = new ArrayList<>();

    @BeforeEach
    void anEngineClientAndADatabaseOfThisCase() {
        database = "jdbc:h2:mem:usertask-restart-it-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID());
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        engine = new ZenBpmRestClient("usertask-restart-it", configuration);
    }

    @AfterEach
    void closeEveryApplication() {
        applications.forEach(ConfigurableApplicationContext::close);
        engine.close();
    }

    @Test
    @DisplayName("Across a restart each event of a user task reaches the application once: a creation and a cancellation told before the restart are answered from the platform's record, and a cancellation which happened while the application was down is told")
    void eachEventOfAUserTaskIsToldOnceAcrossARestart(final CapturedOutput output) throws Exception {
        final var waiting = "approval-%s".formatted(UUID.randomUUID());
        final var cancelledBefore = "approval-%s".formatted(UUID.randomUUID());
        final var cancelledWhileDown = "approval-%s".formatted(UUID.randomUUID());
        final var before = boot();
        start(before, waiting);
        final var cancelledBeforeInstance = start(before, cancelledBefore);
        final var cancelledWhileDownInstance = start(before, cancelledWhileDown);
        for (final var approvalId : List.of(waiting, cancelledBefore, cancelledWhileDown)) {
            eventually(() -> approvals(before)
                    .findById(approvalId)
                    .filter(approval -> approval.getCreations() > 0));
        }
        cancel(cancelledBeforeInstance);
        eventually(() -> approvals(before)
                .findById(cancelledBefore)
                .filter(approval -> approval.getCancellations() > 0));

        before.close();
        cancel(cancelledWhileDownInstance);
        final var logBefore = output
                .getAllOfThisTest()
                .length();
        final var after = boot();
        eventually(() -> approvals(after)
                .findById(cancelledWhileDown)
                .filter(approval -> approval.getCancellations() > 0));
        final var repeated = "Skipping the repeated delivery of task 'approve' (BPMN process 'Approving' of workflow module 'deploy-it', aggregate '%s')";
        eventually(() -> Optional
                .of(output
                        .getAllOfThisTest()
                        .substring(logBefore))
                .filter(log -> log.contains(repeated.formatted(waiting)) && log.contains(repeated.formatted(
                        cancelledBefore))));
        // three more looks, each of which would tell an event again
        LockSupport.parkNanos(Duration
                .ofMillis(3_500)
                .toNanos());

        assertAll(
                () -> assertEquals(List.of(1, 0), countsOf(after, waiting)),
                () -> assertEquals(List.of(1, 1), countsOf(after, cancelledBefore)),
                () -> assertEquals(List.of(1, 1), countsOf(after, cancelledWhileDown)));
    }

    @Test
    @DisplayName("A user task which came and went while nothing looked for it, here under an application whose looks were switched off, is told as cancelled only, although the platform holds no record of its creation")
    void aUserTaskNobodyWasToldOfIsToldAsCancelledOnly() throws Exception {
        final var approvalId = "approval-%s".formatted(UUID.randomUUID());
        final var before = boot("--vanillabp.adapters.zenbpm.user-task-poll-interval=PT0S");
        final var instance = start(before, approvalId);
        eventually(() -> engine
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(instance))
                .items()
                .stream()
                .filter(job -> "Approve".equals(job.elementId()))
                .findFirst());
        cancel(instance);
        before.close();

        final var after = boot();
        eventually(() -> approvals(after)
                .findById(approvalId)
                .filter(approval -> approval.getCancellations() > 0));
        LockSupport.parkNanos(Duration
                .ofMillis(3_500)
                .toNanos());

        final var deliveries = TaskDeliveryLogReader
                .of(after.getBean(DataSource.class))
                .deliveries()
                .stream()
                .filter(delivery -> approvalId.equals(delivery.aggregateId()))
                .toList();
        assertAll(
                () -> assertEquals(List.of(0, 1), countsOf(after, approvalId)),
                () -> assertEquals(1, deliveries.size(), deliveries::toString),
                () -> assertEquals("COMPLETED", deliveries
                        .getFirst()
                        .outcome()));
    }

    @Test
    @DisplayName("A user task the end of its workflow took away is told as cancelled once, by the report of the end, and neither by a look nor after a restart: the looks tell it under the id under which the core reported it, and the core answers them from its record")
    void aUserTaskTheEndTookAwayIsToldOnceAcrossARestart() throws Exception {
        final var endorsementId = "endorsement-%s".formatted(UUID.randomUUID());
        final var before = boot("--vanillabp.delivery.release-on-workflow-end=false");
        final var instance = endorseAndWithdraw(before, endorsementId);
        final var beforeTheRestart = endorsementCountsOf(before, endorsementId);
        before.close();
        final var after = boot("--vanillabp.delivery.release-on-workflow-end=false");
        // three looks of the restarted application, each of which would tell the cancellation again
        LockSupport.parkNanos(Duration
                .ofMillis(3_500)
                .toNanos());

        assertAll(
                () -> assertEquals(List.of(1, 1, 1), beforeTheRestart),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, engine
                        .instance(instance)
                        .orElseThrow()
                        .state()),
                () -> assertEquals(List.of(1, 1, 1), endorsementCountsOf(after, endorsementId)));
    }

    @Test
    @DisplayName("Where the end of a workflow releases the platform's records, a user task the end took away is told as cancelled twice, by the report of the end and by the next look, because no record is left to answer the look")
    void aUserTaskTheEndTookAwayIsToldTwiceWhereTheEndReleasesTheRecords() throws Exception {
        final var endorsementId = "endorsement-%s".formatted(UUID.randomUUID());
        final var application = boot("--vanillabp.delivery.release-on-workflow-end=true");

        endorseAndWithdraw(application, endorsementId);

        assertEquals(List.of(1, 2, 1), endorsementCountsOf(application, endorsementId));
    }

    @Test
    @DisplayName("A cancellation the core could not report when the workflow ended, because the method threw, is told by the next look, under the same id, so the application hears it once it succeeded")
    void aCancellationTheEndCouldNotReportIsToldByALook() throws Exception {
        final var endorsementId = "%s%s".formatted(EndorsementWorkflow.REFUSES_ITS_FIRST_CANCELLATION, UUID
                .randomUUID());
        final var application = boot("--vanillabp.delivery.release-on-workflow-end=false");

        endorseAndWithdraw(application, endorsementId);

        assertAll(
                () -> assertTrue(EndorsementWorkflow.refusedACancellationOf(endorsementId)),
                () -> assertEquals(List.of(1, 1, 1), endorsementCountsOf(application, endorsementId)));
    }

    private ConfigurableApplicationContext boot(final String... arguments) {
        final var context = new SpringApplicationBuilder(UserTaskItApplication.class)
                .properties(
                        "spring.config.name=usertask-it",
                        "spring.datasource.url=%s".formatted(database),
                        "vanillabp.adapters.zenbpm.rest-address=%s".formatted(ENGINE.restAddress()),
                        "vanillabp.adapters.zenbpm.grpc-address=%s".formatted(ENGINE.grpcAddress()))
                .run(arguments);
        applications.add(context);
        return context;
    }

    private long start(
        final ConfigurableApplicationContext application,
        final String approvalId
    ) {
        final ProcessService<Approval> processService = application
                .<ProcessService<Approval>>getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class,
                        Approval.class))
                .getObject();
        application
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> processService.startWorkflow(new Approval(approvalId)));
        return eventually(() -> engine
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(approvalId))
                .items()
                .stream()
                .findFirst())
                .key();
    }

    private long startEndorsing(
        final ConfigurableApplicationContext application,
        final String endorsementId
    ) {
        final ProcessService<Endorsement> processService = application
                .<ProcessService<Endorsement>>getBeanProvider(ResolvableType.forClassWithGenerics(
                        ProcessService.class, Endorsement.class))
                .getObject();
        application
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> processService.startWorkflow(new Endorsement(endorsementId)));
        return eventually(() -> engine
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(endorsementId))
                .items()
                .stream()
                .findFirst())
                .key();
    }

    // starts an endorsement, waits for the creation of its user task, lets a person withdraw the
    // request, whose terminate end event takes the user task away, waits for the end and for three
    // more looks, each of which would tell the cancellation, and answers the instance
    private long endorseAndWithdraw(
        final ConfigurableApplicationContext application,
        final String endorsementId
    ) {
        final var instance = startEndorsing(application, endorsementId);
        eventually(() -> endorsements(application)
                .findById(endorsementId)
                .filter(endorsement -> endorsement.getCreations() > 0));
        engine.completeJob(eventually(() -> engine
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(instance))
                .items()
                .stream()
                .filter(job -> "Withdraw".equals(job.elementId()))
                .findFirst())
                .key(), null);
        eventually(() -> endorsements(application)
                .findById(endorsementId)
                .filter(endorsement -> endorsement.getEnds() > 0));
        LockSupport.parkNanos(Duration
                .ofMillis(3_500)
                .toNanos());
        return instance;
    }

    // the creations and the cancellations the application was told of, and the ends
    private static List<Integer> endorsementCountsOf(
        final ConfigurableApplicationContext application,
        final String endorsementId
    ) {
        final var endorsement = endorsements(application)
                .findById(endorsementId)
                .orElseThrow();
        return List.of(endorsement.getCreations(), endorsement.getCancellations(), endorsement.getEnds());
    }

    private static EndorsementRepository endorsements(final ConfigurableApplicationContext application) {
        return application.getBean(EndorsementRepository.class);
    }

    // the creations and the cancellations the application was told of
    private static List<Integer> countsOf(
        final ConfigurableApplicationContext application,
        final String approvalId
    ) {
        final var approval = approvals(application)
                .findById(approvalId)
                .orElseThrow();
        return List.of(approval.getCreations(), approval.getCancellations());
    }

    private static ApprovalRepository approvals(final ConfigurableApplicationContext application) {
        return application.getBean(ApprovalRepository.class);
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
