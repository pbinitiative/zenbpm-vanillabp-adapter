/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.pbinitiative.zenbpmadapter.springboot.it.versions.Consignment;
import org.pbinitiative.zenbpmadapter.springboot.it.versions.storing.after.StoringAfterApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.versions.storing.before.StoringBeforeApplication;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * The startup check of the versions older than the one a start deployed, against the pinned engine
 * under <code>use-prefix</code>, as one application updated on its data. The version before the
 * change deploys <code>Storing</code> with the task <code>weigh</code> and starts a workflow, which
 * waits an hour at a timer. The version after the change deploys <code>Storing</code> with the task
 * <code>measure</code> in place of <code>weigh</code>, serves only that, and listens to the end of
 * the workflows, so its model carries end markers which the first version's lacks.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmOldProcessVersionsIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private String database;

    private ZenBpmRestClient engine;

    private final List<ConfigurableApplicationContext> boots = new ArrayList<>();

    @BeforeEach
    void anEngineClientAndADatabaseOfThisCase() {
        database = "jdbc:h2:mem:old-versions-it-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID());
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        engine = new ZenBpmRestClient("old-versions-it", configuration);
    }

    @AfterEach
    void closeEveryBoot() {
        boots.forEach(ConfigurableApplicationContext::close);
        engine.close();
    }

    @Test
    @DisplayName("The start after the change names the older version, the workflow which still runs on it, the task no method serves any more and what that workflow misses; fading the version out silences the missing method and names the workflow left behind instead")
    void theStartNamesTheOlderVersionItsWorkflowAndWhatIsMissing(final CapturedOutput output) {
        final var before = boot(StoringBeforeApplication.class, "storing/v1");
        startAWorkflowWhichWaits(before);
        before.close();
        final var beforeLog = output.getAllOfThisTest();

        boot(StoringAfterApplication.class, "storing/v2").close();
        final var afterLog = output
                .getAllOfThisTest()
                .substring(beforeLog.length());
        boot(StoringAfterApplication.class, "storing/v2", "vanillabp.adapters.zenbpm.outfaded-versions=1").close();
        final var fadedLog = output
                .getAllOfThisTest()
                .substring(beforeLog.length() + afterLog.length());

        final var versionOne = DeployedProcessVersionsCheck.A_VERSION_OF_A_PROCESS.formatted("1", "Storing");
        assertAll(
                () -> assertTrue(afterLog.contains(versionOne), afterLog),
                () -> assertTrue(afterLog.contains("1 workflow(s) %s".formatted(
                        DeployedProcessVersionsCheck.STILL_RUN_ON_THIS_VERSION)), afterLog),
                () -> assertTrue(afterLog.contains("'weigh'"), afterLog),
                () -> assertTrue(afterLog.contains(DeployedProcessVersionsCheck.SERVED_BY_NO_METHOD), afterLog),
                () -> assertTrue(
                        afterLog.contains("the end of such a workflow is not reported to a @WorkflowEnded method"),
                        afterLog),
                () -> assertFalse(fadedLog.contains(DeployedProcessVersionsCheck.SERVED_BY_NO_METHOD), fadedLog),
                () -> assertTrue(fadedLog.contains("1 workflow(s) %s".formatted(
                        DeployedProcessVersionsCheck.STILL_RUN_ON_AN_OUTFADED_VERSION.formatted("1"))), fadedLog));
    }

    private ConfigurableApplicationContext boot(
        final Class<?> application,
        final String models,
        final String... more
    ) {
        final var properties = new ArrayList<>(List.of(
                "spring.config.name=versions-it",
                "spring.datasource.url=%s".formatted(database),
                "vanillabp.adapters.zenbpm.resources-location=classpath*:versions-it/%s".formatted(models),
                "vanillabp.adapters.zenbpm.rest-address=%s".formatted(ENGINE.restAddress()),
                "vanillabp.adapters.zenbpm.grpc-address=%s".formatted(ENGINE.grpcAddress()),
                "vanillabp.adapters.zenbpm.client-id=old-versions-it-%s".formatted(UUID.randomUUID())));
        properties.addAll(List.of(more));
        final var context = new SpringApplicationBuilder(application)
                .properties(properties.toArray(String[]::new))
                .run();
        boots.add(context);
        return context;
    }

    // the timer of an hour keeps the workflow on the version it started on for the whole test
    private void startAWorkflowWhichWaits(final ConfigurableApplicationContext boot) {
        final var consignment = new Consignment("consignment-%s".formatted(UUID.randomUUID()));
        final ProcessService<Consignment> processService = boot
                .<ProcessService<Consignment>>getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class,
                        Consignment.class))
                .getObject();
        boot
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> processService.startWorkflow(consignment));
        eventually(() -> engine
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(consignment.getId()))
                .items()
                .stream()
                .findFirst());
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
