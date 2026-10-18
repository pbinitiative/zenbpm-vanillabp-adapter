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
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.springboot.it.restart.Booking;
import org.pbinitiative.zenbpmadapter.springboot.it.restart.BookingRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.restart.RestartItApplication;
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
 * A task delivered before a restart and completed after it runs its handler once, against the
 * pinned engine: the application which received it leaves it open for its asynchronous
 * completion and is closed, the one booted after it on the same database receives the task again
 * and finds it answered by the platform's record, and the application completes it there, which
 * ends the workflow.
 * <p>
 * The case boots {@link RestartItApplication} itself, twice, with <code>restart-it.yaml</code>, on
 * one in-memory database which outlives both applications, as the database of an application
 * outlives its restart. The test does not use <code>@SpringBootTest</code>, because closing the
 * application is part of the case, and Spring's test context is a cache which expects to own that
 * lifecycle.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmRestartDeliveryIT {

    /**
     * How long the case waits for what the engine or an application does. Each step takes
     * milliseconds, so the limit only turns a step which never happens into a failure; it is
     * shorter than the <code>async-task-lock-renewal</code> of <code>restart-it.yaml</code>.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private String database;

    private ZenBpmRestClient engine;

    private final List<ConfigurableApplicationContext> applications = new ArrayList<>();

    @BeforeEach
    void anEngineClientAndADatabaseOfThisCase() {
        database = "jdbc:h2:mem:restart-it-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID());
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        engine = new ZenBpmRestClient("restart-it", configuration);
    }

    @AfterEach
    void closeEveryApplication() {
        applications.forEach(ConfigurableApplicationContext::close);
        engine.close();
    }

    @Test
    @DisplayName("A task delivered before a restart and completed after it is not run twice: the restarted application gets it again at once, the platform answers it from the record, and the completion ends the workflow")
    void aTaskDeliveredBeforeARestartAndCompletedAfterItRunsOnce(final CapturedOutput output) {
        final var bookingId = "booking-%s".formatted(UUID.randomUUID());
        final var before = boot();
        final var instance = start(before, bookingId);
        final var taskId = eventually(() -> bookings(before)
                .findById(bookingId)
                .map(Booking::getConfirmationTaskId), "the task to be delivered");
        eventually(() -> Optional
                .of(outcomesOf(before, bookingId))
                .filter(outcomes -> outcomes.equals(List.of("COMPLETION_PENDING"))), "the delivery to be recorded");
        final var deliveredBefore = deliveryTokenOf(taskId);

        before.close();
        final var stateWhileDown = ENGINE
                .json("/v1/jobs/%s".formatted(taskId))
                .path("state")
                .asText();
        final var logBefore = output
                .getAllOfThisTest()
                .length();
        final var after = boot();
        // the job stream of the closed application released the lock of the task it left open, so
        // the task comes back before its window of a minute
        eventually(() -> Optional
                .of(deliveryTokenOf(taskId))
                .filter(token -> token > deliveredBefore), "the restarted application to get the task again");
        final var skipped = "Skipping the repeated delivery of task 'await-confirmation' (BPMN process 'Restart' of workflow module 'deploy-it', aggregate '%s'): it was processed before, reporting the recorded outcome COMPLETION_PENDING again"
                .formatted(bookingId);
        eventually(() -> Optional
                .of(output
                        .getAllOfThisTest()
                        .substring(logBefore))
                .filter(log -> log.contains(skipped)), "the restarted application to answer it from the record");
        final ProcessService<Booking> processService = after
                .<ProcessService<Booking>>getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class,
                        Booking.class))
                .getObject();
        after
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> {
                    final var booking = bookings(after)
                            .findById(bookingId)
                            .orElseThrow();
                    booking.confirm();
                    processService.completeTask(booking, taskId);
                });
        final var ended = eventually(() -> engine
                .instance(instance)
                .filter(state -> state.state() != ProcessInstanceRef.State.ACTIVE), "the workflow to end");

        final var booking = bookings(after)
                .findById(bookingId)
                .orElseThrow();
        assertAll(
                () -> assertEquals("active", stateWhileDown),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals("completed", ENGINE
                        .json("/v1/jobs/%s".formatted(taskId))
                        .path("state")
                        .asText()),
                () -> assertEquals(1, booking.getConfirmationRequests()),
                () -> assertTrue(booking.isConfirmed()),
                () -> assertEquals(List.of("COMPLETION_PENDING"), outcomesOf(after, bookingId)));
    }

    private ConfigurableApplicationContext boot() {
        final var context = new SpringApplicationBuilder(RestartItApplication.class)
                .properties(
                        "spring.config.name=restart-it",
                        "spring.datasource.url=%s".formatted(database),
                        "vanillabp.adapters.zenbpm.rest-address=%s".formatted(ENGINE.restAddress()),
                        "vanillabp.adapters.zenbpm.grpc-address=%s".formatted(ENGINE.grpcAddress()))
                .run();
        applications.add(context);
        return context;
    }

    private long start(
        final ConfigurableApplicationContext application,
        final String bookingId
    ) {
        final ProcessService<Booking> processService = application
                .<ProcessService<Booking>>getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class,
                        Booking.class))
                .getObject();
        application
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> processService.startWorkflow(new Booking(bookingId)));
        return eventually(() -> engine
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(bookingId))
                .items()
                .stream()
                .findFirst(), "the workflow to start")
                .key();
    }

    private static BookingRepository bookings(final ConfigurableApplicationContext application) {
        return application.getBean(BookingRepository.class);
    }

    private static List<String> outcomesOf(
        final ConfigurableApplicationContext application,
        final String bookingId
    ) {
        return TaskDeliveryLogReader
                .of(application.getBean(DataSource.class))
                .deliveries()
                .stream()
                .filter(delivery -> bookingId.equals(delivery.aggregateId()))
                .map(TaskDeliveryLogReader.Delivery::outcome)
                .toList();
    }

    // raised by the engine with every delivery of the job
    private static long deliveryTokenOf(final String jobKey) {
        return ENGINE
                .json("/v1/jobs/%s".formatted(jobKey))
                .path("deliveryToken")
                .asLong();
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
                    .ofMillis(50)
                    .toNanos());
        }
    }

}
