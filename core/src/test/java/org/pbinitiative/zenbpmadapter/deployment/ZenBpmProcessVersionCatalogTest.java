/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.TestStarts;
import org.pbinitiative.zenbpmadapter.TestWiring;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.client.CollectedStartupReport;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmFindings;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelWriter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * What the version catalog of an adapter id answers about one version the engine holds, read from
 * its model as an engine standing in for ZenBPM hands it back. The held models are the ones this
 * adapter's own pipeline deploys, scoped under <code>use-prefix</code>, with markers and the
 * mappings of multi-instance bodies, so the answers are compared with what the wiring reports for
 * the model it deploys.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmProcessVersionCatalogTest {

    private static final String ADAPTER_ID = "orders";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TWO_VERSIONS = """
            {"items": [{"key": 9, "version": 1, "bpmnProcessId": "shop__Orders"},
                       {"key": 11, "version": 2, "bpmnProcessId": "shop__Orders"}], "totalCount": 2}""";

    private FakeEngine engine;

    private ZenBpmClientRegistry registry;

    private ZenBpmProcessVersions versions;

    private final CollectedStartupReport report = new CollectedStartupReport();

    @BeforeEach
    void startTheEngine() {
        engine = FakeEngine.started();
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        registry = new ZenBpmClientRegistry(List.of(new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")));
        versions = new ZenBpmProcessVersions(ADAPTER_ID, registry.getFactory(ADAPTER_ID), TestScoping.of(
                NameClashAvoidance.USE_PREFIX), new ZenBpmFindings(report));
        engine.answers("GET", "/v1/process-definitions", 200, TWO_VERSIONS);
    }

    @AfterEach
    void stopTheEngine() {
        registry.close();
        engine.close();
    }

    @Test
    @DisplayName("The tasks of a held version are the ones the wiring validates for the model it deploys, without the markers and with the task definitions as the module names them, and each names the multi-instance elements around it which hand no item to an iteration")
    void theTasksOfAHeldVersionAreThoseTheWiringValidates() throws JsonProcessingException {
        engine.answers("GET", "/v1/process-definitions/9", 200, definition(9, 1, deployed(TestWiring.claiming(
                "Orders"),
                TestStarts
                        .everyProcessStartingAWorkflow()
                        .listeningToTheEndOf("Orders"))));

        assertEquals(List.of(
                new BpmnTaskSpec("Charge", "charge", false, "Charge the card", List.of()),
                new BpmnTaskSpec("Notify", "notify", false, "Notify the customer", List.of()),
                new BpmnTaskSpec("Ship", "ship", false, "Ship the parcel", List.of("EveryParcel")),
                new BpmnTaskSpec("Pack", "pack", false, "Pack the line", List.of()),
                new BpmnTaskSpec("Approve", "user-task-type", true, "Approve the order", List.of())),
                versions.tasksOfVersion("shop", "Orders", "1"));
    }

    @Test
    @DisplayName("The start events, the elements which put a second token into a workflow and the plain identifiers of a held version are the ones the wiring and the deployment report for the model, read with one request for its model")
    void whatAHeldVersionDeclaresIsReadFromOneRead() throws JsonProcessingException {
        engine.answers("GET", "/v1/process-definitions/9", 200, definition(9, 1, deployed(TestWiring.claiming(
                "Orders"),
                TestStarts
                        .everyProcessStartingAWorkflow()
                        .listeningToTheEndOf("Orders"))));

        final var startEvents = versions.startEventsOfVersion("shop", "Orders", "1");
        final var concurrentTokenElements = versions.concurrentTokenElementsOfVersion("shop", "Orders", "1");
        final var identifiers = versions.identifiersOfVersion("shop", "Orders", "1");
        final var tasks = versions.tasksOfVersion("shop", "Orders", "1");

        assertAll(
                () -> assertEquals(List.of(
                        BpmsInitiatedStartSpec.of("Start", BpmsStartTrigger.Kind.NONE),
                        BpmsInitiatedStartSpec.of("Placed", BpmsStartTrigger.Kind.MESSAGE),
                        BpmsInitiatedStartSpec.of("EveryNight", BpmsStartTrigger.Kind.TIMER)), startEvents),
                () -> assertEquals(List.of("Fork", "EveryParcel"), concurrentTokenElements),
                () -> assertEquals(List.of(
                        new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "placed", null),
                        new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "paid", null),
                        new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "charge", "Orders"),
                        new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "notify", "Orders"),
                        new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "ship", "Orders"),
                        new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "pack", "Orders")), identifiers),
                () -> assertEquals(5, tasks.size()),
                () -> assertEquals(List.of("GET /v1/process-definitions", "GET /v1/process-definitions/9"),
                        requests()));
    }

    @Test
    @DisplayName("A version the engine does not hold, and a model the engine does not hand back, are answered with null, the second with a warning of the start about the infrastructure, naming the version and what cannot be told")
    void whatCannotBeReadIsNull() {
        engine.answers("GET", "/v1/process-definitions/9", 500, """
                {"code":"TECHNICAL_ERROR","message":"database is locked"}""");

        assertAll(
                () -> assertNull(versions.tasksOfVersion("shop", "Orders", "3")),
                () -> assertNull(versions.startEventsOfVersion("shop", "Orders", "3")),
                () -> assertNull(versions.tasksOfVersion("shop", "Orders", "1")),
                () -> assertNull(versions.startEventsOfVersion("shop", "Orders", "1")),
                () -> assertNull(versions.concurrentTokenElementsOfVersion("shop", "Orders", "1")),
                () -> assertNull(versions.identifiersOfVersion("shop", "Orders", "1")),
                () -> assertTrue(reportedAboutOrders(
                        "The model of version 1 of this BPMN process could not be read (GET /v1/process-definitions/9 was answered with status 500, TECHNICAL_ERROR: database is locked), so VanillaBP cannot tell whether this application still serves its tasks. Where the engine did not answer, check that it does; the next start reads it again."),
                        report::drawn),
                () -> assertTrue(reportedAboutOrders(
                        "The model of version 1 of this BPMN process could not be read (GET /v1/process-definitions/9 was answered with status 500, TECHNICAL_ERROR: database is locked), so VanillaBP cannot tell which start events it holds. Where the engine did not answer, check that it does; the next start reads it again."),
                        report::drawn),
                () -> assertTrue(reportedAboutOrders(
                        "The model of version 1 of this BPMN process could not be read (GET /v1/process-definitions/9 was answered with status 500, TECHNICAL_ERROR: database is locked), so VanillaBP cannot tell which of its elements put a second token into a workflow. Where the engine did not answer, check that it does; the next start reads it again."),
                        report::drawn),
                () -> assertTrue(reportedAboutOrders(
                        "The model of version 1 of this BPMN process could not be read (GET /v1/process-definitions/9 was answered with status 500, TECHNICAL_ERROR: database is locked), so VanillaBP cannot tell which names it still declares. Where the engine did not answer, check that it does; the next start reads it again."),
                        report::drawn));
    }

    @Test
    @DisplayName("The workflows on a version are the active and the failed instances of its definition, counted by the engine with two requests of one item each, whatever their number, each with the earliest creation time as its lower bound, which makes the engine search the index of the definition")
    void theWorkflowsOnAVersionAreCountedByTheEngine() {
        engine.answers("GET", "/v1/process-instances", exchange -> {
            final var total = exchange
                    .getRequestURI()
                    .getQuery()
                    .contains("state=failed") ? 34 : 1200;
            FakeEngine.respond(exchange, 200, """
                    {"partitions": [{"partition": 1, "totalCount": %d, "items": []}],
                     "page": 1, "size": 1, "count": 0, "totalCount": %d}""".formatted(total, total));
        });

        final var running = versions.activeInstanceCountOf("shop", "Orders", "1");

        final var instanceQueries = engine
                .requests()
                .stream()
                .filter(request -> "/v1/process-instances".equals(request.path()))
                .map(FakeEngine.Request::query)
                .toList();
        assertAll(
                () -> assertEquals(1234L, running),
                () -> assertEquals(2, instanceQueries.size(), instanceQueries::toString),
                () -> assertTrue(instanceQueries
                        .stream()
                        .allMatch(query -> query.contains("processDefinitionKey=9") && query.contains("size=1") && query
                                .contains("createdFrom=1970-01-01T00%3A00%3A00Z")),
                        instanceQueries::toString),
                () -> assertTrue(instanceQueries
                        .getFirst()
                        .contains("state=active"), instanceQueries::toString),
                () -> assertTrue(instanceQueries
                        .getLast()
                        .contains("state=failed"), instanceQueries::toString));
    }

    @Test
    @DisplayName("The workflows on a version the engine does not hold, or where the engine does not count, are not known, the second with a warning of the start about the infrastructure")
    void aCountTheEngineDoesNotGiveIsNull() {
        engine.answers("GET", "/v1/process-instances", 500, """
                {"code":"TECHNICAL_ERROR","message":"database is locked"}""");

        assertAll(
                () -> assertNull(versions.activeInstanceCountOf("shop", "Orders", "3")),
                () -> assertNull(versions.activeInstanceCountOf("shop", "Orders", "1")),
                () -> assertTrue(reportedAboutOrders(
                        "The engine did not say how many workflows run on version 1 of this BPMN process (GET /v1/process-instances was answered with status 500, TECHNICAL_ERROR: database is locked), so VanillaBP cannot tell whether any does. Check that the engine answers; the next start asks again."),
                        report::drawn));
    }

    @Test
    @DisplayName("What a workflow on an older version misses is what the deployed model carries and the older one lacks: the end markers, the start markers and the names mapped into a multi-instance body")
    void whatOlderVersionsMissIsReadFromTheModels() throws JsonProcessingException {
        recordDeployed(deployed(TestWiring
                .claiming("Orders")
                .withTaskParameters("ship", "tariff"),
                TestStarts
                        .everyProcessStartingAWorkflow()
                        .listeningToTheEndOf("Orders")));
        engine.answers("GET", "/v1/process-definitions/9", 200, definition(9, 1, deployed(TestWiring.claiming(
                "Orders"),
                TestStarts
                        .everyProcessStartingAWorkflow()
                        .called("Orders"))));

        assertEquals("""
                the end of such a workflow is not reported to a @WorkflowEnded method, their start was not \
                reported, so a workflow the engine started by a timer or a message has no workflow aggregate, and \
                no start row holds the instance key of any of them, and a task inside the multi-instance body of \
                'EveryParcel' does not get 'tariff'""", versions.whatOlderVersionsMiss("shop", "Orders"));
    }

    @Test
    @DisplayName("Only the older versions on which workflows run are compared, with the counts the platform asked right before, and the model of a version nobody runs on is not read")
    void onlyTheOlderVersionsWorkflowsRunOnAreCompared() throws JsonProcessingException {
        engine.answers("GET", "/v1/process-definitions", 200, """
                {"items": [{"key": 9, "version": 1, "bpmnProcessId": "shop__Orders"},
                           {"key": 10, "version": 2, "bpmnProcessId": "shop__Orders"},
                           {"key": 11, "version": 3, "bpmnProcessId": "shop__Orders"}], "totalCount": 3}""");
        final var withMarkers = deployed(TestWiring.claiming("Orders"), TestStarts
                .everyProcessStartingAWorkflow()
                .listeningToTheEndOf("Orders"));
        recordDeployed(withMarkers, 3);
        engine.answers("GET", "/v1/process-definitions/9", 200, definition(9, 1, deployed(TestWiring.claiming(
                "Orders"),
                TestStarts
                        .everyProcessStartingAWorkflow()
                        .called("Orders"))));
        engine.answers("GET", "/v1/process-definitions/10", 200, definition(10, 2, withMarkers));
        engine.answers("GET", "/v1/process-instances", exchange -> {
            final var total = exchange
                    .getRequestURI()
                    .getQuery()
                    .contains("processDefinitionKey=10&state=active") ? 5 : 0;
            FakeEngine.respond(exchange, 200, """
                    {"partitions": [{"partition": 1, "totalCount": %d, "items": []}],
                     "page": 1, "size": 1, "count": 0, "totalCount": %d}""".formatted(total, total));
        });
        versions.deployedVersionsOf("shop", "Orders");
        final var counts = List.of(versions.activeInstanceCountOf("shop", "Orders", "1"), versions
                .activeInstanceCountOf("shop", "Orders", "2"));
        final var asked = engine
                .requests()
                .size();

        final var missed = versions.whatOlderVersionsMiss("shop", "Orders");

        assertAll(
                () -> assertEquals(List.of(0L, 5L), counts),
                () -> assertNull(missed),
                () -> assertEquals(List.of("GET /v1/process-definitions/10"), requests().subList(asked, requests()
                        .size())));
    }

    @Test
    @DisplayName("Two things missed are named with 'and'")
    void twoThingsMissedAreNamedWithAnd() throws JsonProcessingException {
        recordDeployed(deployed(TestWiring.claiming("Orders"), TestStarts
                .everyProcessStartingAWorkflow()
                .listeningToTheEndOf("Orders")));
        engine.answers("GET", "/v1/process-definitions/9", 200, definition(9, 1, deployed(TestWiring.claiming(
                "Orders"),
                TestStarts
                        .everyProcessStartingAWorkflow()
                        .called("Orders"))));

        assertEquals("""
                the end of such a workflow is not reported to a @WorkflowEnded method and their start was not \
                reported, so a workflow the engine started by a timer or a message has no workflow aggregate, and \
                no start row holds the instance key of any of them""",
                versions.whatOlderVersionsMiss("shop", "Orders"));
    }

    @Test
    @DisplayName("Only what the deployed model has is named: no end marker where it has none, and no name mapped into a multi-instance body the older version does not have")
    void onlyWhatTheDeployedModelHasIsNamed() throws JsonProcessingException {
        recordDeployed(deployed(TestWiring
                .claiming("Orders")
                .withTaskParameters("ship", "tariff"), TestStarts.everyProcessStartingAWorkflow()));
        engine.answers("GET", "/v1/process-definitions/9", 200, definition(9, 1, deployed(TestWiring.claiming(
                "Orders"),
                TestStarts
                        .everyProcessStartingAWorkflow()
                        .called("Orders"))
                .replace("EveryParcel", "EveryBox")));

        assertEquals("""
                their start was not reported, so a workflow the engine started by a timer or a message has no \
                workflow aggregate, and no start row holds the instance key of any of them""", versions
                .whatOlderVersionsMiss("shop", "Orders"));
    }

    @Test
    @DisplayName("An older version which carries what the deployed one carries misses nothing")
    void anOlderVersionWhichLacksNothingMissesNothing() throws JsonProcessingException {
        final var withMarkers = deployed(TestWiring.claiming("Orders"), TestStarts
                .everyProcessStartingAWorkflow()
                .listeningToTheEndOf("Orders"));
        recordDeployed(withMarkers);
        engine.answers("GET", "/v1/process-definitions/9", 200, definition(9, 1, withMarkers));

        assertNull(versions.whatOlderVersionsMiss("shop", "Orders"));
    }

    @Test
    @DisplayName("Nothing is said about what older versions miss where this start deployed no model under the id, or where a model cannot be read, the second with a warning of the start about the infrastructure")
    void whatOlderVersionsMissIsNullWithoutAnAnswer() throws JsonProcessingException {
        recordDeployed(deployed(TestWiring.claiming("Orders"), TestStarts.everyProcessStartingAWorkflow()));
        engine.answers("GET", "/v1/process-definitions/9", 500, """
                {"code":"TECHNICAL_ERROR","message":"database is locked"}""");

        assertAll(
                () -> assertNull(versions.whatOlderVersionsMiss("shop", "Shipping")),
                () -> assertNull(versions.whatOlderVersionsMiss("shop", "Orders")),
                () -> assertTrue(reportedAboutOrders(
                        "The models of the versions of this BPMN process could not be read (GET /v1/process-definitions/9 was answered with status 500, TECHNICAL_ERROR: database is locked), so VanillaBP does not say what the workflows on older versions miss. Where the engine did not answer, check that it does; the next start reads them again."),
                        report::drawn));
    }

    // a warning reached the report, and the block the platform writes at the end of the start
    // shows it under its topic, below its scope
    private boolean reportedAboutOrders(final String message) {
        final var finding = aboutOrders(message);
        return report
                .findings()
                .contains(finding) && report.showsInTheBlock(finding);
    }

    private static CollectedStartupReport.Finding aboutOrders(final String message) {
        return new CollectedStartupReport.Finding("warn", StartupTopic.INFRASTRUCTURE, "process 'Orders' of workflow module 'shop', zenbpm adapter 'orders'", message);
    }

    private void recordDeployed(final String bpmn) {
        recordDeployed(bpmn, 2);
    }

    private void recordDeployed(
        final String bpmn,
        final int version
    ) {
        final var deployed = new ZenBpmDeployedProcesses.DeployedProcess("shop", "Orders", "shop__Orders", 11, version, null, bpmn);
        registry
                .getFactory(ADAPTER_ID)
                .deployedProcesses()
                .record(deployed);
        versions.recordDeployed(deployed);
    }

    private List<String> requests() {
        return engine
                .requests()
                .stream()
                .map(request -> "%s %s".formatted(request.method(), request.path()))
                .toList();
    }

    // the fixture as this adapter's pipeline deploys it under use-prefix, which is what the engine
    // hands back for its version
    private static String deployed(
        final TestWiring wiring,
        final TestStarts starts
    ) {
        try (var unconfigured = new ZenBpmClientRegistry(List.of(ZenBpmClientFactory.unconfigured(ADAPTER_ID,
                new ZenBpmAdapterConfiguration(), "not configured")))) {
            final var deploymentService = new ZenBpmDeploymentService(ADAPTER_ID, unconfigured, TestCollaborators.of(
                    ADAPTER_ID, TestScoping.of(NameClashAvoidance.USE_PREFIX), wiring.wiring(), starts));
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

    private static String definition(
        final long key,
        final int version,
        final String bpmn
    ) throws JsonProcessingException {
        return JSON.writeValueAsString(Map.of("key", key, "version", version, "bpmnProcessId", "shop__Orders",
                "bpmnData", bpmn));
    }

    private static String fixture() {
        try (var file = ZenBpmProcessVersionCatalogTest.class.getResourceAsStream("/models/wiring.bpmn")) {
            return new String(file.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
