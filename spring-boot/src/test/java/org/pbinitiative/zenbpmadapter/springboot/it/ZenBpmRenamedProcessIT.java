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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.springboot.it.renamed.Shipment;
import org.pbinitiative.zenbpmadapter.springboot.it.renamed.ShipmentRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.renamed.after.RenamedAfterApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.renamed.before.RenamedBeforeApplication;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * A BPMN process renamed between two versions of an application, against the pinned engine under
 * <code>use-prefix</code>, where a job type carries the id of the process it was deployed with. The
 * version before the rename deploys <code>Shipping</code> and starts a workflow of it, which waits
 * at a timer and stops before its task is due. The version after the rename deploys
 * <code>Dispatch</code> instead and declares <code>Shipping</code> beside it, with the method of
 * the task <code>ship</code> serving both. That version subscribes the job type the old model
 * carries, and the workflow started before the rename finishes.
 * <p>
 * Both versions boot on one named in-memory database, as one application updated on its data, one
 * after the other.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmRenamedProcessIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private String database;

    private ZenBpmRestClient engine;

    private final List<ConfigurableApplicationContext> versions = new ArrayList<>();

    @BeforeEach
    void anEngineClientAndADatabaseOfThisCase() {
        database = "jdbc:h2:mem:renamed-it-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID());
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        engine = new ZenBpmRestClient("renamed-it", configuration);
    }

    @AfterEach
    void closeEveryVersion() {
        versions.forEach(ConfigurableApplicationContext::close);
        engine.close();
    }

    @Test
    @DisplayName("A workflow started on a process before the application renamed it finishes after the rename, served by the job type the old model carries, with the variables of its scope as the task's parameters; a declared id the engine holds no model of is a finding the start of the processing reports after the block")
    void aWorkflowOfARenamedProcessFinishesAfterTheRename(final CapturedOutput output) {
        final var shipmentId = "shipment-%s".formatted(UUID.randomUUID());
        final var before = boot(RenamedBeforeApplication.class, "before");
        final var started = start(before, new Shipment(shipmentId, "Lisbon"));
        // closed before the timer of eight seconds is due, so the version before the rename never
        // receives the job
        before.close();

        final var after = boot(RenamedAfterApplication.class, "after");
        final var ended = eventually(() -> engine
                .instance(started)
                .filter(instance -> instance.state() != ProcessInstanceRef.State.ACTIVE));

        final var shipment = after
                .getBean(ShipmentRepository.class)
                .findById(shipmentId)
                .orElseThrow();
        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals("deploy-it__Shipping", ended.bpmnProcessId()),
                () -> assertEquals("after", shipment.getShippedBy()),
                () -> assertEquals("Lisbon", shipment.getShippedTo()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("""
                                workflow module 'deploy-it' declares BPMN process 'Shipping' without deploying a model \
                                under it, and the engine holds it as 'deploy-it__Shipping': its workflows are served \
                                by the job types [deploy-it__Shipping__ship] subscribed for it"""),
                        output::getAllOfThisTest),
                // Spring Boot starts the processing after the block at the end of the start, so the
                // platform's report writes what the adapter finds there into the log on its own
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("""
                                StartupFindings - process 'Parcels' of workflow module 'deploy-it', zenbpm adapter \
                                'zenbpm': This BPMN process is declared without a model deployed under it, and not \
                                everything the methods serving it name reaches its workflows, which run at the engine \
                                under the id 'deploy-it__Parcels'. Not served: the task definition(s) [ship], which no \
                                model held under that id names."""),
                        output::getAllOfThisTest));
    }

    private ConfigurableApplicationContext boot(
        final Class<?> application,
        final String version
    ) {
        final var context = new SpringApplicationBuilder(application)
                .properties(
                        "spring.config.name=renamed-it",
                        "spring.datasource.url=%s".formatted(database),
                        "vanillabp.adapters.zenbpm.resources-location=classpath*:renamed-it/%s".formatted(version),
                        "vanillabp.adapters.zenbpm.rest-address=%s".formatted(ENGINE.restAddress()),
                        "vanillabp.adapters.zenbpm.grpc-address=%s".formatted(ENGINE.grpcAddress()),
                        "vanillabp.adapters.zenbpm.client-id=renamed-it-%s-%s".formatted(version, UUID.randomUUID()))
                .run();
        versions.add(context);
        return context;
    }

    private long start(
        final ConfigurableApplicationContext version,
        final Shipment shipment
    ) {
        final ProcessService<Shipment> processService = version
                .<ProcessService<Shipment>>getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class,
                        Shipment.class))
                .getObject();
        version
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> processService.startWorkflow(shipment));
        return eventually(() -> engine
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(shipment.getId()))
                .items()
                .stream()
                .findFirst())
                .key();
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
