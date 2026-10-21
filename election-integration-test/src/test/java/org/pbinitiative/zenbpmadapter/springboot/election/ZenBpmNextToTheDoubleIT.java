/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.election;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.pbinitiative.zenbpmadapter.springboot.election.app.ElectionItApplication;
import org.pbinitiative.zenbpmadapter.springboot.election.app.Parcel;
import org.pbinitiative.zenbpmadapter.springboot.election.app.ParcelRepository;
import org.pbinitiative.zenbpmadapter.springboot.election.app.TheDouble;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * The adapter next to another BPMS, against the pinned engine: the adapter id <code>zenbpm</code>
 * first, and the platform's BPMS double, the adapter id <code>double</code>, second, as in the
 * migration from that BPMS to ZenBPM. The double holds the workflows a test tells {@link TheDouble}
 * it holds, and the engine holds those the application starts, because a start goes to the first
 * adapter.
 * <p>
 * The election walks the adapters in that order and stops at the first which answers that it holds
 * the workflow, so a workflow of the double reaches it only where the adapter asks the engine and
 * answers that it does not know it. The platform refuses, while the application starts, an
 * adapter which cannot ask its BPMS next to a second one, with a message naming
 * <code>vanillabp.election.guessing-adapters</code>; the adapter says it asks, so the application
 * starts without that message.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmNextToTheDoubleIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String MODULE = "election-it";

    private static final WorkflowScope PARCELING = WorkflowScope.of(MODULE, "Parceling");

    /**
     * The probes read the aggregate's id variable by the name of its id attribute, and that name
     * is all they ask of the persistence.
     */
    private static final AggregatePersistenceAware<Parcel> PARCELS = new AggregatePersistenceAware<>() {

        @Override
        public Class<Parcel> getAggregateClass() {
            return Parcel.class;
        }

        @Override
        public String getAggregateIdName() {
            return "id";
        }

    };

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private ZenBpmRestClient engine;

    private final List<ConfigurableApplicationContext> applications = new ArrayList<>();

    @BeforeEach
    void anEngineClient() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        engine = new ZenBpmRestClient("election-it", configuration);
    }

    @AfterEach
    void closeEveryApplication() {
        applications.forEach(ConfigurableApplicationContext::close);
        engine.close();
    }

    @Test
    @DisplayName("A workflow the double holds is not claimed by the adapter, which asks the engine and does not know it, so the election and the push of a changed aggregate reach the double; a workflow the application starts runs at the engine")
    void aWorkflowTheDoubleHoldsIsNotClaimed() {
        final var application = boot(false);
        final var theDouble = application.getBean(TheDouble.class);
        final var heldByTheDouble = "parcel-of-the-double-%s".formatted(UUID.randomUUID());
        final var parcel = application
                .getBean(TransactionTemplate.class)
                .execute(status -> application
                        .getBean(ParcelRepository.class)
                        .save(new Parcel(heldByTheDouble)));
        theDouble.holds(heldByTheDouble);
        final var startedHere = "parcel-of-the-engine-%s".formatted(UUID.randomUUID());
        application
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> parcels(application).startWorkflow(new Parcel(startedHere)));
        final var started = eventually(() -> instanceOf(startedHere));
        final var election = application.getBean(WorkflowElection.class);

        application
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> parcels(application).aggregateChanged(parcel));
        final var pushed = eventually(() -> Optional
                .of(theDouble.pushedAggregates())
                .filter(aggregates -> !aggregates.isEmpty()));

        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, probesOf(application).awarenessOfWorkflow(
                        PARCELING, PARCELS, heldByTheDouble)),
                () -> assertEquals("double", election.adapterIdOfWorkflow(MODULE, "Parceling", heldByTheDouble)),
                () -> assertEquals(List.of(heldByTheDouble), pushed),
                () -> assertEquals("election-it__Parceling", started.bpmnProcessId()),
                () -> assertEquals(WorkflowAwareness.ACTIVE, probesOf(application).awarenessOfWorkflow(PARCELING,
                        PARCELS, startedHere)),
                () -> assertEquals("zenbpm", election.adapterIdOfWorkflow(MODULE, "Parceling", startedHere)));
    }

    @Test
    @DisplayName("The adapter says it asks the engine whether it holds a workflow, so the application starts next to the double without a message about 'vanillabp.election.guessing-adapters'")
    void theAdapterLocatesWorkflowsSoNoGuessingMessageAppears(final CapturedOutput output) {
        final var application = boot(false);

        assertAll(
                () -> assertTrue(probesOf(application).canLocateWorkflows()),
                () -> assertTrue(application.isActive()),
                () -> assertFalse(output
                        .getAllOfThisTest()
                        .contains("guessing-adapters"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("Where the double guesses, the platform's check refuses the start in this setup and names the double alone, not the adapter")
    void aGuessingDoubleIsNamedAlone() {
        final var failure = assertThrows(Exception.class, () -> boot(true));

        final var messages = messagesOf(failure);
        assertAll(
                () -> assertTrue(
                        messages.contains("The adapter(s) [double] cannot ask their BPMS whether it holds a workflow"),
                        messages),
                () -> assertTrue(messages.contains("is served by 2 adapters ([zenbpm, double])"), messages),
                () -> assertTrue(messages.contains("guessing-adapters"), messages),
                // the platform lists every adapter which says it cannot ask, so a list which
                // starts with the first adapter would name the adapter too
                () -> assertFalse(messages.contains("The adapter(s) [zenbpm"), messages));
    }

    private ConfigurableApplicationContext boot(final boolean theDoubleGuesses) {
        final var application = new SpringApplicationBuilder(ElectionItApplication.class, TheDouble.class)
                .properties("spring.config.name=election-it",
                        "spring.datasource.url=jdbc:h2:mem:double-it-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID()),
                        "election-it.the-double-guesses=%s".formatted(theDoubleGuesses),
                        "vanillabp.prioritized-adapters=zenbpm,double",
                        "vanillabp.adapters.double.type=dummy",
                        "vanillabp.adapters.zenbpm.name-clash-avoidance=use-prefix",
                        "vanillabp.adapters.zenbpm.resources-location=classpath*:election-it",
                        "vanillabp.adapters.zenbpm.rest-address=%s".formatted(ENGINE.restAddress()),
                        "vanillabp.adapters.zenbpm.grpc-address=%s".formatted(ENGINE.grpcAddress()),
                        "vanillabp.adapters.zenbpm.client-id=double-it-%s".formatted(UUID.randomUUID()))
                .run();
        applications.add(application);
        return application;
    }

    private static ProcessService<Parcel> parcels(final ConfigurableApplicationContext application) {
        return application
                .<ProcessService<Parcel>>getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class,
                        Parcel.class))
                .getObject();
    }

    @SuppressWarnings("unchecked")
    private static MigratableProcessService<Parcel> probesOf(final ConfigurableApplicationContext application) {
        return application.getBean(ZenBpmProcessService.class);
    }

    private static String messagesOf(final Throwable failure) {
        final var messages = new StringBuilder();
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            messages
                    .append(cause.getMessage())
                    .append('\n');
        }
        return messages.toString();
    }

    private Optional<ProcessInstanceRef> instanceOf(final String businessKey) {
        return engine
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(businessKey))
                .items()
                .stream()
                .findFirst();
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
