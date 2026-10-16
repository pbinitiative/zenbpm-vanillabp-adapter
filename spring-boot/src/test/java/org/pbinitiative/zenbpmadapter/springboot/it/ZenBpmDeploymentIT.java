/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * one DMN file, to the pinned engine under <code>use-prefix</code>: the engine holds the process
 * under the prefixed id and the decision under the prefixed decision id, an unchanged start keeps
 * version 1 and registers it, and a changed file gets version 2.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmDeploymentIT {

    private static final String VERSION_1 = "classpath*:deploy-it/v1";

    private static final String VERSION_2 = "classpath*:deploy-it/v2";

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Test
    @DisplayName("A booted application deploys its BPMN and DMN file under the prefixed ids; a start with the same files keeps version 1, one with a changed file gets version 2")
    void aWorkflowModuleWithBpmnAndDmnDeploys(final CapturedOutput output) throws IOException, InterruptedException {
        final var first = startUntilTheProcessingStarts(VERSION_1);
        final var firstLog = output.getAllOfThisTest();
        startUntilTheProcessingStarts(VERSION_1);
        final var unchangedLog = output
                .getAllOfThisTest()
                .substring(firstLog.length());
        startUntilTheProcessingStarts(VERSION_2);
        final var changedLog = output
                .getAllOfThisTest()
                .substring(firstLog.length() + unchangedLog.length());
        final var definitions = engine("/v1/process-definitions?bpmnProcessId=deploy-it__DeployIt");
        final var decisions = engine("/v1/dmn-resource-definitions?dmnResourceDefinitionId=deploy-it-rating");

        assertAll(
                () -> assertEquals(
                        "ZenBPM adapter 'zenbpm': starting the workflow processing of workflow module 'deploy-it' is implemented in a later story",
                        first),
                () -> assertTrue(firstLog.contains("DeployIt.bpmn as process 'deploy-it__DeployIt', version 1, "),
                        firstLog),
                () -> assertTrue(unchangedLog.contains("DeployIt.bpmn as process 'deploy-it__DeployIt', version 1, "),
                        unchangedLog),
                () -> assertTrue(unchangedLog.contains(" (unchanged)"), unchangedLog),
                () -> assertTrue(changedLog.contains("DeployIt.bpmn as process 'deploy-it__DeployIt', version 2, "),
                        changedLog),
                () -> assertTrue(definitions.contains("\"totalCount\":2"), definitions),
                () -> assertTrue(decisions.contains("\"totalCount\":1"), decisions),
                () -> assertTrue(engine("/v1/dmn-resource-definitions/%s".formatted(keyIn(decisions))).contains(
                        "deploy-it__rating"), decisions));
    }

    // the start deploys the workflow module and ends where the processing would start, which is
    // not built yet
    private static String startUntilTheProcessingStarts(final String resourcesLocation) {
        Throwable failure = assertThrows(Exception.class, () -> run(resourcesLocation).close());
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure.getMessage();
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

    private static String engine(final String path) throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            return client
                    .send(HttpRequest
                            .newBuilder(URI.create("%s%s".formatted(ENGINE.restAddress(), path)))
                            .build(), HttpResponse.BodyHandlers.ofString())
                    .body();
        }
    }

    private static String keyIn(final String page) {
        final var start = page.indexOf("\"key\":") + "\"key\":".length();
        var end = start;
        while (Character.isDigit(page.charAt(end))) {
            ++end;
        }
        return page.substring(start, end);
    }

}
