/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.springboot.it.deploy.DeployItApplication;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A booted Spring Boot application deploys its workflow module <code>deploy-it</code>, one BPMN and
 * one DMN file, to the pinned engine under <code>use-prefix</code> and starts its workflow
 * processing: the engine holds the process under the prefixed id and the decision under the prefixed
 * decision id, an unchanged start keeps version 1 and registers it, a changed file gets version 2,
 * the job type of the module's task reaches the engine, whose job reaches the adapter, and stopping
 * the application stops the module before the clients close.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmDeploymentIT {

    private static final String VERSION_1 = "classpath*:deploy-it/v1";

    private static final String VERSION_2 = "classpath*:deploy-it/v2";

    /**
     * How long a job may take to arrive over the job stream. The engine hands it out in
     * milliseconds, so the limit only turns a job which never comes into a failure.
     */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Test
    @DisplayName("A booted application deploys its BPMN and DMN file under the prefixed ids; a start with the same files keeps version 1, one with a changed file gets version 2")
    void aWorkflowModuleWithBpmnAndDmnDeploys(final CapturedOutput output) {
        final Optional<Integer> first;
        try (var context = run(VERSION_1)) {
            first = versionOfDeployIt(context);
        }
        final var firstLog = output.getAllOfThisTest();
        final Optional<Integer> unchanged;
        try (var context = run(VERSION_1)) {
            unchanged = versionOfDeployIt(context);
        }
        final var unchangedLog = output
                .getAllOfThisTest()
                .substring(firstLog.length());
        final Optional<Integer> changed;
        try (var context = run(VERSION_2)) {
            changed = versionOfDeployIt(context);
        }
        final var definitions = ENGINE.json("/v1/process-definitions?bpmnProcessId=deploy-it__DeployIt");
        final var decisions = ENGINE.json("/v1/dmn-resource-definitions?dmnResourceDefinitionId=deploy-it-rating");
        final var decision = ENGINE.json("/v1/dmn-resource-definitions/%s".formatted(decisions
                .path("items")
                .path(0)
                .path("key")
                .asText()));

        assertAll(
                () -> assertEquals(Optional.of(1), first),
                () -> assertEquals(Optional.of(1), unchanged),
                () -> assertEquals(Optional.of(2), changed),
                () -> assertTrue(firstLog.contains("DeployIt.bpmn as process 'deploy-it__DeployIt', version 1, "),
                        firstLog),
                () -> assertTrue(unchangedLog.contains(" (unchanged)"), unchangedLog),
                () -> assertEquals(2, definitions
                        .path("totalCount")
                        .asInt(), definitions::toPrettyString),
                () -> assertEquals(1, decisions
                        .path("totalCount")
                        .asInt(), decisions::toPrettyString),
                // the decision inside the deployed file carries the scoped id
                () -> assertTrue(decision
                        .path("dmnData")
                        .asText()
                        .contains("id=\"deploy-it__rating\""), decision::toPrettyString));
    }

    @Test
    @DisplayName("A started module subscribes its job type at the engine, whose job of a new instance arrives and, belonging to no shipment of the application, is failed; stopping the application stops the module before its clients close")
    void aStartedModuleSubscribesItsJobTypeAndStopsFirst(final CapturedOutput output) {
        final String warning = "(task 'ship') is about a workflow this application does not own";
        try (var context = run(VERSION_1)) {
            context
                    .getBean(ZenBpmClientRegistry.class)
                    .getFactory("zenbpm")
                    .rest()
                    .createInstance(
                            new CreateInstanceRequest("deploy-it__DeployIt", null, "shipment-1", Map.of(), null));
            final var deadline = Instant
                    .now()
                    .plus(DELIVERY_TIMEOUT);
            while (!output
                    .getAllOfThisTest()
                    .contains(warning) && Instant
                            .now()
                            .isBefore(deadline)) {
                LockSupport.parkNanos(Duration
                        .ofMillis(50)
                        .toNanos());
            }
        }
        final var log = output.getAllOfThisTest();

        assertAll(
                () -> assertTrue(log.contains(
                        "ZenBPM[zenbpm]: workflow module 'deploy-it' subscribed the job type(s) deploy-it__DeployIt__ship (job-timeout PT5M), each with at most 4 active job(s)"),
                        log),
                () -> assertTrue(log.contains(warning), log),
                () -> assertTrue(log.contains(
                        "ZenBPM[zenbpm]: workflow module 'deploy-it' unsubscribed the job type(s) deploy-it__DeployIt__ship"),
                        log),
                () -> assertTrue(log.contains("ZenBPM[zenbpm]: closing the REST client of the engine at "), log),
                () -> assertFalse(log.contains("still subscribe their job types"), log));
    }

    private static Optional<Integer> versionOfDeployIt(final ConfigurableApplicationContext context) {
        return context
                .getBean(ZenBpmClientRegistry.class)
                .getFactory("zenbpm")
                .deployedProcesses()
                .versionOf("deploy-it", "DeployIt");
    }

    // command-line arguments win over every other source of properties
    private static ConfigurableApplicationContext run(final String resourcesLocation) {
        return new SpringApplicationBuilder(DeployItApplication.class)
                .web(WebApplicationType.NONE)
                .run(Stream
                        .of("vanillabp.prioritized-adapters=zenbpm",
                                "vanillabp.adapters.zenbpm.rest-address=%s".formatted(ENGINE.restAddress()),
                                "vanillabp.adapters.zenbpm.grpc-address=%s".formatted(ENGINE.grpcAddress()),
                                "vanillabp.adapters.zenbpm.name-clash-avoidance=use-prefix",
                                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(resourcesLocation),
                                "vanillabp.outbox.housekeeping.zone=UTC")
                        .map("--%s"::formatted)
                        .toArray(String[]::new));
    }

}
