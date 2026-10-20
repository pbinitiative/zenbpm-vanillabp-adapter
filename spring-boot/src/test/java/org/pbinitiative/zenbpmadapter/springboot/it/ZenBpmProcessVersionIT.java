/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.springboot.it.versions.Consignment;
import org.pbinitiative.zenbpmadapter.springboot.it.versions.ConsignmentRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.versions.numbers.NumbersApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.versions.tags.LabellingWorkflow;
import org.pbinitiative.zenbpmadapter.springboot.it.versions.tags.TagsApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.versions.unserved.UnservedApplication;
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
 * What <code>&#64;WorkflowTask(version = ...)</code> means on ZenBPM, against the pinned engine
 * under <code>use-prefix</code>. Each case boots one application twice, as one application updated
 * on its data, with two models of its process: a job carries the version of its definition, so a
 * version made of numbers needs nothing from the engine, and a version tag is read from the
 * engine's list of the definitions, which the version catalog of the adapter id answers.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmProcessVersionIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private String database;

    private final List<ConfigurableApplicationContext> boots = new ArrayList<>();

    @BeforeEach
    void aDatabaseOfThisCase() {
        database = "jdbc:h2:mem:versions-it-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID());
    }

    @AfterEach
    void closeEveryBoot() {
        boots.forEach(ConfigurableApplicationContext::close);
    }

    @Test
    @DisplayName("The version a workflow runs on decides which method serves its task: version 1 the method of version 1, version 2 the method of the versions after 1")
    void theVersionDecidesWhichMethodRuns() {
        final var first = boot(NumbersApplication.class, "numbers/v1");
        final var onVersionOne = start(first);
        final var servedOnVersionOne = servedBy(first, onVersionOne);
        final var firstVersion = versionOf(first, "Weighing");
        first.close();

        final var second = boot(NumbersApplication.class, "numbers/v2");
        final var onVersionTwo = start(second);

        assertAll(
                () -> assertEquals(Optional.of(1), firstVersion),
                () -> assertEquals(Optional.of(2), versionOf(second, "Weighing")),
                () -> assertEquals("version 1", servedOnVersionOne),
                () -> assertEquals("a later version", servedBy(second, onVersionTwo)));
    }

    @Test
    @DisplayName("A version specification naming a tag routes to the version the engine holds with that tag, and a tag no version carries is reported once at startup")
    void aTagResolves(final CapturedOutput output) {
        final var first = boot(TagsApplication.class, "tags/v1");
        final var onReleaseOne = start(first);
        final var servedOnReleaseOne = servedBy(first, onReleaseOne);
        first.close();
        final var firstBoot = output.getAllOfThisTest();

        final var second = boot(TagsApplication.class, "tags/v2");
        final var onReleaseTwo = start(second);
        final var servedOnReleaseTwo = servedBy(second, onReleaseTwo);
        final var secondBoot = output
                .getAllOfThisTest()
                .substring(firstBoot.length());

        assertAll(
                () -> assertEquals("release-1", servedOnReleaseOne),
                () -> assertEquals("release-2", servedOnReleaseTwo),
                () -> assertEquals(1, unknownTagFindingsIn(firstBoot), firstBoot),
                () -> assertEquals(1, unknownTagFindingsIn(secondBoot), secondBoot));
    }

    @Test
    @DisplayName("A model deployed again with the tag and the content of an older version gets that older version back, and a workflow started afterwards runs on the newest version")
    void aRolledBackTaggedModelGetsItsOldVersionBack() {
        boot(TagsApplication.class, "tags/v1").close();
        boot(TagsApplication.class, "tags/v2").close();

        final var rolledBack = boot(TagsApplication.class, "tags/v1");
        final var started = start(rolledBack);

        assertAll(
                () -> assertEquals(Optional.of(1), versionOf(rolledBack, "Labelling")),
                () -> assertEquals("release-2", servedBy(rolledBack, started)));
    }

    @Test
    @DisplayName("A model changed under a version tag another version of its process carries is refused by the engine, and the start ends naming the tag")
    void aTagAnotherVersionCarriesIsRefused() {
        boot(TagsApplication.class, "tags/v1").close();

        final var failure = assertThrows(RuntimeException.class, () -> boot(TagsApplication.class, "tags/reused"));

        final var message = messagesOf(failure);
        assertAll(
                () -> assertTrue(message.contains(
                        "ZenBPM refuses BPMN file 'labelling.bpmn' of workflow module 'deploy-it' (process 'Labelling')"),
                        message),
                () -> assertTrue(message.contains("\"release-1\""), message));
    }

    @Test
    @DisplayName("A task whose methods serve none of the version the engine gave the deployed model ends the start, naming the version and the method")
    void aDeployedVersionWithoutAMethodEndsTheStart() {
        final var failure = assertThrows(RuntimeException.class, () -> boot(UnservedApplication.class,
                "unserved"));

        final var message = messagesOf(failure);
        assertAll(
                () -> assertTrue(
                        message.contains(DeployedProcessVersionsCheck.ELEMENTS_SERVED_BY_NO_METHOD_IN_THIS_VERSION),
                        message),
                () -> assertTrue(message.contains("Version '1' of BPMN process 'Sealing'"), message),
                () -> assertTrue(message.contains("sealOnLaterVersions"), message));
    }

    private ConfigurableApplicationContext boot(
        final Class<?> application,
        final String models
    ) {
        final var context = new SpringApplicationBuilder(application)
                .properties(
                        "spring.config.name=versions-it",
                        "spring.datasource.url=%s".formatted(database),
                        "vanillabp.adapters.zenbpm.resources-location=classpath*:versions-it/%s".formatted(models),
                        "vanillabp.adapters.zenbpm.rest-address=%s".formatted(ENGINE.restAddress()),
                        "vanillabp.adapters.zenbpm.grpc-address=%s".formatted(ENGINE.grpcAddress()),
                        "vanillabp.adapters.zenbpm.client-id=versions-it-%s".formatted(UUID.randomUUID()))
                .run();
        boots.add(context);
        return context;
    }

    private static String start(final ConfigurableApplicationContext boot) {
        final var consignment = new Consignment("consignment-%s".formatted(UUID.randomUUID()));
        final ProcessService<Consignment> processService = boot
                .<ProcessService<Consignment>>getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class,
                        Consignment.class))
                .getObject();
        boot
                .getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> processService.startWorkflow(consignment));
        return consignment.getId();
    }

    private static String servedBy(
        final ConfigurableApplicationContext boot,
        final String consignmentId
    ) {
        final var consignments = boot.getBean(ConsignmentRepository.class);
        return eventually(() -> consignments
                .findById(consignmentId)
                .map(Consignment::getServedBy));
    }

    private static Optional<Integer> versionOf(
        final ConfigurableApplicationContext boot,
        final String bpmnProcessId
    ) {
        return boot
                .getBean(ZenBpmClientRegistry.class)
                .getFactory("zenbpm")
                .deployedProcesses()
                .versionOf("deploy-it", bpmnProcessId);
    }

    // the finding has no words the core publishes, so it is found by the specification and the
    // method it names, in the order the finding names them
    private static long unknownTagFindingsIn(final String log) {
        final var specificationOfTheMethod = "'release-9' of method '%s#labelOnReleaseNine'".formatted(
                LabellingWorkflow.class.getName());
        return log
                .lines()
                .filter(line -> line.contains(specificationOfTheMethod))
                .count();
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
