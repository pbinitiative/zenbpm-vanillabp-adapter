/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.TestStarts;
import org.pbinitiative.zenbpmadapter.TestWiring;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelWriter;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a whole start of an application asks an engine standing in for ZenBPM about one claimed
 * process: the open tasks the platform asks for before anything is deployed, the deployment with
 * the messages of every version the engine holds, and the questions the platform's check of the
 * older versions puts to the version catalog, in the order the platform asks them. Every model is
 * read once, the deployment's answer about its own definition aside; every number is counted by the
 * engine, from a search for one item; and the requests grow with the versions the engine holds,
 * never with the workflows which run on them, see decision 14 in the repository's DECISIONS.md.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmStartupQuestionCostTest {

    private static final String ADAPTER_ID = "orders";

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest(name = "{0} versions, {1} workflows on each")
    @CsvSource({
            "2, 1", "2, 1000000", "5, 1", "5, 1000000"
    })
    void aStartAsksAsManyQuestionsOnTheLastDayAsOnTheFirst(
        final int heldVersions,
        final long runningOnEach
    ) throws JsonProcessingException {
        final var model = deployed();
        try (var engine = FakeEngine.started()) {
            answerAsAnEngineHolding(engine, heldVersions, runningOnEach, model);
            final var configuration = new ZenBpmAdapterConfiguration();
            configuration.setRestAddress(engine.address());
            try (var registry = new ZenBpmClientRegistry(List
                    .of(new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")))) {
                final var collaborators = TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                        NameClashAvoidance.USE_PREFIX),
                        TestWiring
                                .claiming("Orders")
                                .wiring(),
                        TestStarts.everyProcessStartingAWorkflow());
                final var processService = new ZenBpmProcessService<Object>(ADAPTER_ID, registry.getFactory(
                        ADAPTER_ID), collaborators, (
                            module,
                            process) -> null);
                final var deploymentService = new ZenBpmDeploymentService(ADAPTER_ID, registry, collaborators);

                processService.openTaskCount("shop", "Orders");
                deploy(deploymentService);
                askAsThePlatformsCheckOfTheOlderVersions(deploymentService);
            }

            final var requests = engine
                    .requests()
                    .stream()
                    .map(request -> "%s %s".formatted(request.method(), request.path()))
                    .collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting()));
            final var expected = new TreeMap<String, Long>(Map.of(
                    "GET /system/health/ready", 1L,
                    "GET /system/status", 1L,
                    "POST /v1/process-definitions", 1L,
                    "GET /v1/process-definitions", 3L,
                    "GET /v1/process-instances", 2L * (heldVersions - 1)));
            IntStream
                    .rangeClosed(1, heldVersions)
                    .forEach(version -> {
                        expected.put("GET /v1/process-definitions/%d".formatted(key(version)),
                                version == heldVersions ? 2L
                                        : 1L);
                        expected.put("GET /v1/process-definitions/%d/statistics".formatted(key(version)), 1L);
                    });
            assertAll(
                    () -> assertEquals(expected, requests),
                    () -> assertTrue(engine
                            .requests()
                            .stream()
                            .filter(request -> "/v1/process-instances".equals(request.path()))
                            .allMatch(request -> request
                                    .query()
                                    .contains("size=1")),
                            engine.requests()::toString));
        }
    }

    // the order of DeployedProcessVersionsCheck#check for a version older than the deployed one
    private static void askAsThePlatformsCheckOfTheOlderVersions(final ZenBpmDeploymentService deploymentService) {
        final var catalog = deploymentService.processVersionCatalogOf("shop", "Orders");
        final var held = catalog.deployedVersionsOf("shop", "Orders");
        catalog.whatOlderVersionsMiss("shop", "Orders");
        held
                .subList(0, held.size() - 1)
                .forEach(version -> {
                    catalog.activeInstanceCountOf("shop", "Orders", version.version());
                    catalog.concurrentTokenElementsOfVersion("shop", "Orders", version.version());
                    catalog.identifiersOfVersion("shop", "Orders", version.version());
                    catalog.tasksOfVersion("shop", "Orders", version.version());
                });
    }

    private static void deploy(final ZenBpmDeploymentService deploymentService) {
        final var model = deploymentService
                .readBpmn("shop", "wiring.bpmn", new ByteArrayInputStream(fixture().getBytes(StandardCharsets.UTF_8)),
                        true)
                .getFirst()
                .getValue();
        final var context = deploymentService.prepareBpmn("shop", null, "wiring.bpmn", "Orders", model);
        deploymentService.wireBpmn("shop", "wiring.bpmn", "Orders", model, context);
        deploymentService.deployResources("shop", context);
    }

    // every version holds the model this adapter deploys, the newest one is the one the deployment
    // ends up with, and each version has the given number of workflows running
    private static void answerAsAnEngineHolding(
        final FakeEngine engine,
        final int heldVersions,
        final long runningOnEach,
        final String model
    ) throws JsonProcessingException {
        engine.answers("GET", "/system/health/ready", 200, "{\"status\":\"UP\",\"reasons\":[]}");
        engine.answers("GET", "/system/status", 200, """
                {"git":{"commitId":"bdd795a399b6"},"build":{"version":"1.2.0"},"nodes":{"node-1":{}}}""");
        engine.answers("POST", "/v1/process-definitions", 200, "{\"processDefinitionKey\":%d}".formatted(key(
                heldVersions)));
        engine.answers("GET", "/v1/process-definitions", 200, JSON.writeValueAsString(Map.of("items", IntStream
                .rangeClosed(1, heldVersions)
                .mapToObj(version -> Map.of("key", key(version), "version", version, "bpmnProcessId", "shop__Orders"))
                .toList(), "totalCount", heldVersions)));
        for (var version = 1; version <= heldVersions; ++version) {
            engine.answers("GET", "/v1/process-definitions/%d".formatted(key(version)), 200, JSON.writeValueAsString(
                    Map.of("key", key(version), "version", version, "bpmnProcessId", "shop__Orders", "bpmnData",
                            model)));
            engine.answers("GET", "/v1/process-definitions/%d/statistics".formatted(key(version)), 200, """
                    {"partitions": [{"partition": 1, "items": {"Charge": {"activeCount": %d, "incidentCount": 0}}}]}"""
                    .formatted(runningOnEach));
        }
        engine.answers("GET", "/v1/process-instances", 200, """
                {"partitions": [{"partition": 1, "totalCount": %d, "items": []}],
                 "page": 1, "size": 1, "count": 0, "totalCount": %d}""".formatted(runningOnEach, runningOnEach));
    }

    private static long key(final int version) {
        return 100L + version;
    }

    // the fixture as this adapter's pipeline deploys it under use-prefix
    private static String deployed() {
        try (var registry = new ZenBpmClientRegistry(List.of(ZenBpmClientFactory.unconfigured(ADAPTER_ID,
                new ZenBpmAdapterConfiguration(), "not configured")))) {
            final var deploymentService = new ZenBpmDeploymentService(ADAPTER_ID, registry, TestCollaborators.of(
                    ADAPTER_ID, TestScoping.of(NameClashAvoidance.USE_PREFIX), TestWiring
                            .claiming("Orders")
                            .wiring(),
                    TestStarts.everyProcessStartingAWorkflow()));
            final var model = deploymentService
                    .readBpmn("shop", "wiring.bpmn", new ByteArrayInputStream(fixture().getBytes(
                            StandardCharsets.UTF_8)), true)
                    .getFirst()
                    .getValue();
            final var context = deploymentService.prepareBpmn("shop", null, "wiring.bpmn", "Orders", model);
            deploymentService.wireBpmn("shop", "wiring.bpmn", "Orders", model, context);
            return new String(ZenBpmModelWriter.bytesOf(model), StandardCharsets.UTF_8);
        }
    }

    private static String fixture() {
        try (var file = ZenBpmStartupQuestionCostTest.class.getResourceAsStream("/models/wiring.bpmn")) {
            return new String(file.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
