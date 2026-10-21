/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.springboot.it.message.MessageItApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.message.Postcard;
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
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * A booted Spring Boot application under <code>message-start-lookup: scan</code>, against the
 * pinned engine: the start warns about the lookup, its bound and what it cannot do, and a start by a
 * message which is sent again after its answer was lost, before the start marker wrote the start
 * row, publishes nothing a second time, because the probe asked before the start is sent again
 * finds the first workflow among the instances of the process by its id variable.
 * <p>
 * The application is the one of {@link ZenBpmMessageIT}, {@link MessageItApplication}, with the key
 * set to <code>scan</code> and two seconds of <code>vanillabp.outbox.attempt-frequency</code>, so a
 * failed start is sent again soon. The adapter reaches the engine through a proxy, which loses the
 * answer to the first publication and refuses every read of one instance while the case runs, so
 * the start marker cannot report the workflow and no start row exists.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = MessageItApplication.class, properties = {
        "spring.config.name=message-it", "vanillabp.adapters.zenbpm.message-start-lookup=scan", "vanillabp.outbox.attempt-frequency=PT2S"
})
@DirtiesContext
class ZenBpmMessageStartScanIT {

    /**
     * How long the case waits for the start to be sent again and skipped: a failed start is sent
     * again after <code>attempt-frequency</code>, so the limit only turns a start which is never sent
     * again into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static final RefusingEngineProxy PROXY = new RefusingEngineProxy(ENGINE::restAddress);

    @Autowired
    private ProcessService<Postcard> processService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @AfterAll
    static void stopTheProxy() {
        PROXY.close();
    }

    @Test
    @DisplayName("Under message-start-lookup scan, a start by a message sent again after its answer was lost, before its start marker wrote the start row, publishes nothing a second time: the probe finds the first workflow by its id variable, and the start warned about the lookup")
    void scanFindsTheWorkflowOfARedispatchedStart(final CapturedOutput output) {
        final var postcardId = "postcard-%s".formatted(UUID.randomUUID());
        PROXY.refuse(RefusingEngineProxy.INSTANCE_READ, 502, RefusingEngineProxy.ALWAYS);
        PROXY.loseTheAnswer(RefusingEngineProxy.PUBLICATION, 502, 1);

        transactionTemplate.executeWithoutResult(status -> processService.startWorkflowByMessage(new Postcard(
                postcardId), "postcardPosted"));
        final var dispatched = eventually(() -> startsOf(postcardId)
                .stream()
                .filter(PhaseTwoOutboxReader.Entry::wasDispatched)
                .findFirst(), "the start to be sent again and skipped");
        final var log = output.getAllOfThisTest();
        final var startRows = TaskDeliveryLogReader
                .of(dataSource)
                .workflowStartsOfAggregate(postcardId);
        final var publications = PROXY
                .received()
                .stream()
                .filter(request -> request.equals("POST /v1/messages"))
                .count();
        PROXY.refuseNothing();

        assertAll(
                () -> assertEquals(1, publications),
                () -> assertEquals(1, instancesOf(postcardId)),
                () -> assertEquals(List.of(), startRows),
                () -> assertTrue(dispatched.attempts() >= 2, dispatched::toString),
                () -> assertTrue(log.contains(
                        "Skipped re-dispatched phase two of starting the workflow by message 'postcardPosted' of aggregate '%s' (BPMN process 'Postcards' of workflow module 'deploy-it'): adapter 'zenbpm' already knows the workflow (ACTIVE)"
                                .formatted(postcardId)),
                        log),
                () -> assertTrue(configurationTopicOf(output.getAll())
                        .contains(
                                """
                                        workflow module 'deploy-it', zenbpm adapter 'zenbpm'
                                              vanillabp.adapters.zenbpm.message-start-lookup is 'scan': where the start row does not know a workflow of the processes 'Postcards', a probe reads their instances to find it by its id variable, at most 1000 (vanillabp.adapters.zenbpm.message-start-lookup-max-instances), and answers BPMS_UNAVAILABLE where it would have to read more."""),
                        output::getAll));
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", PROXY::address);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    private List<PhaseTwoOutboxReader.Entry> startsOf(final String postcardId) {
        return PhaseTwoOutboxReader
                .of(dataSource)
                .entries()
                .stream()
                .filter(entry -> postcardId.equals(entry.aggregateId()))
                .filter(entry -> "START_WORKFLOW_BY_MESSAGE".equals(entry.operation()))
                .toList();
    }

    // the instances of the process whose id variable names the postcard, read past the adapter
    private static long instancesOf(final String postcardId) {
        var found = 0L;
        for (final var partition : ENGINE.json("/v1/process-instances?bpmnProcessId=deploy-it__Postcards&size=100")
                .path("partitions")) {
            for (final var instance : partition.path("items")) {
                if (postcardId.equals(instance
                        .path("variables")
                        .path("id")
                        .asText())) {
                    ++found;
                }
            }
        }
        return found;
    }

    private static <T> T eventually(
        final Supplier<Optional<T>> read,
        final String what
    ) {
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
                    .isBefore(deadline), () -> "waited %s for %s".formatted(TIMEOUT, what));
            LockSupport.parkNanos(Duration
                    .ofMillis(100)
                    .toNanos());
        }
    }

    // what the block the platform writes at the end of the start holds under the heading
    // CONFIGURATION: the lines up to the next heading or the ruler which closes the block
    private static String configurationTopicOf(final String log) {
        final var heading = Pattern.compile("^CONFIGURATION \\(\\d+\\)$");
        final var endOfTheTopic = Pattern.compile("^([A-Z][A-Z ]+ \\(\\d+\\)|-{20,})$");
        final var inTopic = new boolean[1];
        return log
                .lines()
                .filter(line -> {
                    if (heading
                            .matcher(line)
                            .matches()) {
                        inTopic[0] = true;
                        return false;
                    }
                    if (inTopic[0] && endOfTheTopic
                            .matcher(line)
                            .matches()) {
                        inTopic[0] = false;
                    }
                    return inTopic[0];
                })
                .collect(Collectors.joining("\n"));
    }

}
