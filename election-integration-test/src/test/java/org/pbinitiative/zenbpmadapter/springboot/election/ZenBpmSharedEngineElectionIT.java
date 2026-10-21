/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.election;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.pbinitiative.zenbpmadapter.springboot.election.app.ElectionItApplication;
import org.pbinitiative.zenbpmadapter.springboot.election.app.Parcel;
import org.pbinitiative.zenbpmadapter.springboot.election.app.ParcelRepository;
import org.pbinitiative.zenbpmadapter.springboot.election.app.Shipment;
import org.pbinitiative.zenbpmadapter.springboot.election.app.ShipmentRepository;
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
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * The migration of a workflow module from <code>name-clash-avoidance: none</code> to
 * <code>use-prefix</code> on one engine, with two adapter ids of type <code>zenbpm</code> on the
 * same <code>rest-address</code>: the setup the shared-engine boot tests start, run against the
 * pinned engine.
 * <p>
 * An application served by the old id alone, <code>zenbpm-old</code> under <code>none</code>,
 * starts two shipments and closes. Then the application of the migration boots on the same
 * database, with the new id <code>zenbpm-new</code> under <code>use-prefix</code> first and the old
 * one second. On one engine every key answers for both ids, so the only thing which keeps a probe
 * from claiming the workflow of the other id is the scope it is asked about: under
 * <code>none</code> the engine holds the process <code>Shipping</code>, under
 * <code>use-prefix</code> <code>election-it__Shipping</code>. An adapter answers the awareness
 * probes only for the workflows and tasks of its own scope, so the walk of the election reaches the
 * adapter which holds the workflow.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmSharedEngineElectionIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String MODULE = "election-it";

    private static final WorkflowScope SHIPPING = WorkflowScope.of(MODULE, "Shipping");

    private static final String NEW_ID = "zenbpm-new";

    private static final String OLD_ID = "zenbpm-old";

    /**
     * The probes read the aggregate's id variable by the name of its id attribute, and that name
     * is all they ask of the persistence.
     */
    private static final AggregatePersistenceAware<Shipment> SHIPMENTS = new AggregatePersistenceAware<>() {

        @Override
        public Class<Shipment> getAggregateClass() {
            return Shipment.class;
        }

        @Override
        public String getAggregateIdName() {
            return "id";
        }

    };

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    /**
     * The shipment the old id started, which the application of the migration completes.
     */
    private static final String COMPLETED_UNDER_THE_OLD_ID = "shipment-completed-%s".formatted(UUID.randomUUID());

    /**
     * The shipment the old id started, whose user task stays open for the probes.
     */
    private static final String PROBED_UNDER_THE_OLD_ID = "shipment-probed-%s".formatted(UUID.randomUUID());

    private static ZenBpmRestClient engine;

    private static ConfigurableApplicationContext migration;

    @BeforeAll
    static void theOldIdStartedTwoShipmentsAndTheMigrationBoots() {
        final var database = "jdbc:h2:mem:election-it-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID());
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        engine = new ZenBpmRestClient("election-it", configuration);

        final var properties = new ArrayList<>(List.of("vanillabp.prioritized-adapters=%s".formatted(OLD_ID)));
        properties.addAll(adapterId(OLD_ID, "none"));
        try (var beforeTheMigration = boot(database, properties)) {
            for (final var shipmentId : List.of(COMPLETED_UNDER_THE_OLD_ID, PROBED_UNDER_THE_OLD_ID)) {
                startShipment(beforeTheMigration, shipmentId);
                confirmationTaskOf(beforeTheMigration, shipmentId);
            }
        }

        final var both = new ArrayList<>(List.of("vanillabp.prioritized-adapters=%s,%s".formatted(NEW_ID, OLD_ID)));
        both.addAll(adapterId(NEW_ID, "use-prefix"));
        both.addAll(adapterId(OLD_ID, "none"));
        migration = boot(database, both);
    }

    @AfterAll
    static void closeTheMigrationAndTheClient() {
        if (migration != null) {
            migration.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @DisplayName("A workflow the old id started before the migration is completed through the old id: the application completes its user task, and the old id runs its service task, under the process id of 'none'")
    void aWorkflowStartedUnderTheOldIdIsCompletedByTheOldId() {
        final var taskId = confirmationTaskOf(migration, COMPLETED_UNDER_THE_OLD_ID);

        migration
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> shipments(migration).completeUserTask(migration
                        .getBean(ShipmentRepository.class)
                        .findById(COMPLETED_UNDER_THE_OLD_ID)
                        .orElseThrow(), taskId));
        final var ended = eventually(() -> instanceOf(COMPLETED_UNDER_THE_OLD_ID)
                .filter(instance -> instance.state() != ProcessInstanceRef.State.ACTIVE));

        final var deliveries = deliveriesOf(COMPLETED_UNDER_THE_OLD_ID);
        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals("Shipping", ended.bpmnProcessId()),
                () -> assertTrue(migration
                        .getBean(ShipmentRepository.class)
                        .findById(COMPLETED_UNDER_THE_OLD_ID)
                        .orElseThrow()
                        .isShipped()),
                // the service task became due only after the migration booted, so the old id
                // ran it in the application of the migration
                () -> assertTrue(deliveries
                        .stream()
                        .anyMatch(delivery -> "ship".equals(delivery.taskDefinition())), deliveries::toString),
                () -> assertTrue(deliveries
                        .stream()
                        .allMatch(delivery -> OLD_ID.equals(delivery.adapterId())), deliveries::toString));
    }

    @Test
    @DisplayName("A workflow started during the migration starts under the new id, under the process id of 'use-prefix', and the new id runs its tasks")
    void aNewWorkflowStartsUnderTheNewId() {
        final var shipmentId = "shipment-new-%s".formatted(UUID.randomUUID());
        startShipment(migration, shipmentId);
        final var taskId = confirmationTaskOf(migration, shipmentId);
        final var started = instanceOf(shipmentId).orElseThrow();

        migration
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> shipments(migration).completeUserTask(migration
                        .getBean(ShipmentRepository.class)
                        .findById(shipmentId)
                        .orElseThrow(), taskId));
        final var ended = eventually(() -> instanceOf(shipmentId)
                .filter(instance -> instance.state() != ProcessInstanceRef.State.ACTIVE));

        final var deliveries = deliveriesOf(shipmentId);
        final var starts = TaskDeliveryLogReader
                .of(migration.getBean(DataSource.class))
                .workflowStartsOfAggregate(shipmentId);
        assertAll(
                () -> assertEquals("election-it__Shipping", started.bpmnProcessId()),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(List.of(NEW_ID), starts
                        .stream()
                        .map(TaskDeliveryLogReader.Delivery::adapterId)
                        .toList()),
                () -> assertEquals(List.of("confirm", "ship"), deliveries
                        .stream()
                        .map(TaskDeliveryLogReader.Delivery::taskDefinition)
                        .distinct()
                        .sorted()
                        .toList(), deliveries::toString),
                () -> assertTrue(deliveries
                        .stream()
                        .allMatch(delivery -> NEW_ID.equals(delivery.adapterId())), deliveries::toString));
    }

    @Test
    @DisplayName("Asked about a user task of the other id, whose key the shared engine knows, each id answers UNKNOWN_TO_BPMS, and the id holding it ACTIVE")
    void aTaskProbeOfTheWrongIdAnswersUnknown() {
        final var shipmentId = "shipment-probed-new-%s".formatted(UUID.randomUUID());
        startShipment(migration, shipmentId);
        final var newTask = confirmationTaskOf(migration, shipmentId);
        final var oldTask = confirmationTaskOf(migration, PROBED_UNDER_THE_OLD_ID);

        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, probesOf(NEW_ID).awarenessOfUserTask(SHIPPING,
                        PROBED_UNDER_THE_OLD_ID, oldTask)),
                () -> assertEquals(WorkflowAwareness.ACTIVE, probesOf(OLD_ID).awarenessOfUserTask(SHIPPING,
                        PROBED_UNDER_THE_OLD_ID, oldTask)),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, probesOf(OLD_ID).awarenessOfUserTask(SHIPPING,
                        shipmentId, newTask)),
                () -> assertEquals(WorkflowAwareness.ACTIVE, probesOf(NEW_ID).awarenessOfUserTask(SHIPPING,
                        shipmentId, newTask)));
    }

    @Test
    @DisplayName("Asked about a workflow of the other id, by its aggregate and by its key on the shared engine, each id answers UNKNOWN_TO_BPMS, and the id holding it ACTIVE")
    void aWorkflowProbeOfTheWrongIdAnswersUnknown() {
        final var shipmentId = "shipment-located-new-%s".formatted(UUID.randomUUID());
        startShipment(migration, shipmentId);
        final var newKey = String.valueOf(instanceOf(shipmentId)
                .orElseThrow()
                .key());
        final var oldKey = String.valueOf(instanceOf(PROBED_UNDER_THE_OLD_ID)
                .orElseThrow()
                .key());

        // the probe leaves whatever a key does not lead to to the search, so an UNKNOWN_TO_BPMS by
        // the key of the other id shows the scope at work only where the engine resolves that key
        assertAll(
                () -> assertEquals(Optional.of("Shipping"), runningProcessOf(oldKey)),
                () -> assertEquals(Optional.of("election-it__Shipping"), runningProcessOf(newKey)),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, probesOf(NEW_ID).awarenessOfWorkflow(SHIPPING,
                        SHIPMENTS, PROBED_UNDER_THE_OLD_ID)),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, probesOf(NEW_ID).awarenessOfWorkflow(SHIPPING,
                        SHIPMENTS, PROBED_UNDER_THE_OLD_ID, oldKey)),
                () -> assertEquals(WorkflowAwareness.ACTIVE, probesOf(OLD_ID).awarenessOfWorkflow(SHIPPING, SHIPMENTS,
                        PROBED_UNDER_THE_OLD_ID, oldKey)),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, probesOf(OLD_ID).awarenessOfWorkflow(SHIPPING,
                        SHIPMENTS, shipmentId)),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, probesOf(OLD_ID).awarenessOfWorkflow(SHIPPING,
                        SHIPMENTS, shipmentId, newKey)),
                () -> assertEquals(WorkflowAwareness.ACTIVE, probesOf(NEW_ID).awarenessOfWorkflow(SHIPPING, SHIPMENTS,
                        shipmentId, newKey)));
    }

    @Test
    @DisplayName("The platform's election finds a workflow of the old id it holds no row and no hint of past the new id, which answers it does not know it, and a workflow started during the migration under the new id")
    void theElectionReachesTheOldIdPastTheNewOne() throws IOException {
        // the adapter writes a start marker into every model it deploys, and the delivery of that
        // marker writes the row which names the adapter id. A workflow started on the plain model
        // under the process id of the old id is one the application never heard start, as one
        // another client of the engine started
        final var unrecorded = "parcel-unrecorded-%s".formatted(UUID.randomUUID());
        migration
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> migration
                        .getBean(ParcelRepository.class)
                        .save(new Parcel(unrecorded)));
        try (var plainModel = ZenBpmSharedEngineElectionIT.class.getResourceAsStream("/election-it/parceling.bpmn")) {
            final var definition = engine.deployBpmn(plainModel.readAllBytes());
            engine.createInstance(CreateInstanceRequest
                    .version(definition.key())
                    .withBusinessKey(unrecorded)
                    .withVariables(Map.of("id", unrecorded)));
        }
        eventually(() -> instanceOf(unrecorded));
        final var started = "parcel-new-%s".formatted(UUID.randomUUID());
        startParcel(migration, started);
        final var election = migration.getBean(WorkflowElection.class);

        assertAll(
                () -> assertEquals(OLD_ID, election.adapterIdOfWorkflow(MODULE, "Parceling", unrecorded)),
                () -> assertEquals(List.of(), TaskDeliveryLogReader
                        .of(migration.getBean(DataSource.class))
                        .deliveries()
                        .stream()
                        .filter(delivery -> unrecorded.equals(delivery.aggregateId()))
                        .toList()),
                () -> assertEquals(List.of(), TaskDeliveryLogReader
                        .of(migration.getBean(DataSource.class))
                        .workflowStartsOfAggregate(unrecorded)),
                () -> assertEquals(NEW_ID, election.adapterIdOfWorkflow(MODULE, "Parceling", started)),
                () -> assertEquals("election-it__Parceling", instanceOf(started)
                        .orElseThrow()
                        .bpmnProcessId()));
    }

    private static List<String> adapterId(
        final String adapterId,
        final String nameClashAvoidance
    ) {
        return Stream
                .of("type=zenbpm",
                        "name-clash-avoidance=%s".formatted(nameClashAvoidance),
                        "resources-location=classpath*:election-it",
                        "user-task-poll-interval=PT1S",
                        "rest-address=%s".formatted(ENGINE.restAddress()),
                        "grpc-address=%s".formatted(ENGINE.grpcAddress()),
                        "client-id=%s-%s".formatted(adapterId, UUID.randomUUID()))
                .map(property -> "vanillabp.adapters.%s.%s".formatted(adapterId, property))
                .toList();
    }

    private static ConfigurableApplicationContext boot(
        final String database,
        final List<String> properties
    ) {
        final var all = new ArrayList<>(List.of("spring.config.name=election-it", "spring.datasource.url=%s"
                .formatted(database)));
        all.addAll(properties);
        return new SpringApplicationBuilder(ElectionItApplication.class)
                .properties(all.toArray(String[]::new))
                .run();
    }

    private static void startShipment(
        final ConfigurableApplicationContext application,
        final String shipmentId
    ) {
        application
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> shipments(application).startWorkflow(new Shipment(shipmentId)));
        eventually(() -> instanceOf(shipmentId));
    }

    private static void startParcel(
        final ConfigurableApplicationContext application,
        final String parcelId
    ) {
        final ProcessService<Parcel> parcels = application
                .<ProcessService<Parcel>>getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class,
                        Parcel.class))
                .getObject();
        application
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> parcels.startWorkflow(new Parcel(parcelId)));
        eventually(() -> instanceOf(parcelId));
    }

    private static ProcessService<Shipment> shipments(final ConfigurableApplicationContext application) {
        return application
                .<ProcessService<Shipment>>getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class,
                        Shipment.class))
                .getObject();
    }

    // the user task the application was told of, so the id it names is the one it completes with
    private static String confirmationTaskOf(
        final ConfigurableApplicationContext application,
        final String shipmentId
    ) {
        return eventually(() -> application
                .getBean(ShipmentRepository.class)
                .findById(shipmentId)
                .map(Shipment::getConfirmationTaskId));
    }

    @SuppressWarnings("unchecked")
    private static MigratableProcessService<Shipment> probesOf(final String adapterId) {
        return migration
                .getBeansOfType(ZenBpmProcessService.class)
                .values()
                .stream()
                .filter(adapter -> adapterId.equals(adapter.getAdapterId()))
                .findFirst()
                .orElseThrow();
    }

    private static List<TaskDeliveryLogReader.Delivery> deliveriesOf(final String aggregateId) {
        return TaskDeliveryLogReader
                .of(migration.getBean(DataSource.class))
                .deliveries()
                .stream()
                .filter(delivery -> aggregateId.equals(delivery.aggregateId()))
                .toList();
    }

    private static Optional<ProcessInstanceRef> instanceOf(final String businessKey) {
        return engine
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(businessKey))
                .items()
                .stream()
                .findFirst();
    }

    private static Optional<String> runningProcessOf(final String instanceKey) {
        return engine
                .instance(Long.parseLong(instanceKey))
                .filter(instance -> instance.state() == ProcessInstanceRef.State.ACTIVE)
                .map(ProcessInstanceRef::bpmnProcessId);
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
