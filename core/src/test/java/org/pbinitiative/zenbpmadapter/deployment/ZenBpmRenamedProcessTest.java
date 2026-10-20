/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The versions of a process id a workflow module declares without bringing a model for it, such as
 * the old id of a renamed process, as the platform asks the deployment service for them once the
 * module is deployed: the catalog every claimed process is registered with, which asks an engine
 * standing in for ZenBPM by the id scoped as the deployment of that id scoped it.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmRenamedProcessTest {

    private static final String ADAPTER_ID = "orders";

    private static final ObjectMapper JSON = new ObjectMapper();

    // the old model as the engine holds it, deployed under use-prefix with its start marker, with a
    // task another runtime serves, which names no task definition
    private static final String SHIPPING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="shipping">
              <bpmn:message id="Message_Shipped" name="shop__shipped" />
              <bpmn:message id="Message_Unnamed" />
              <bpmn:error id="Error_Lost" errorCode="shop__lost" />
              <bpmn:escalation id="Escalation_Late" escalationCode="shop__late" />
              <bpmn:process id="shop__Shipping" isExecutable="true">
                <bpmn:startEvent id="Start" />
                <bpmn:serviceTask id="vanillabp__started__Start">
                  <bpmn:extensionElements>
                    <zenbpm:taskDefinition type="vanillabp__started__shop__Shipping" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:serviceTask id="Ship" name="Ship the shipment">
                  <bpmn:extensionElements>
                    <zenbpm:taskDefinition type="shop__Shipping__ship" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:sendTask id="Notify" name="Notify the customer" />
              </bpmn:process>
            </bpmn:definitions>
            """;

    private FakeEngine engine;

    private ZenBpmClientRegistry registry;

    @BeforeEach
    void startTheEngine() {
        engine = FakeEngine.started();
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        registry = new ZenBpmClientRegistry(List.of(new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")));
    }

    @AfterEach
    void stopTheEngine() {
        registry.close();
        engine.close();
    }

    @Test
    @DisplayName("A declared process id gets the catalog of the claimed processes, which lists the versions under the scoped old id and reads the tasks and the plain identifiers of each from its model; what older versions miss is not said, because this start deployed nothing under the id")
    void theCatalogOfADeclaredIdIsTheCatalogOfTheClaimedProcesses() throws JsonProcessingException {
        engine.answers("GET", "/v1/process-definitions", exchange -> FakeEngine.respond(exchange, 200, exchange
                .getRequestURI()
                .getQuery()
                .contains("bpmnProcessId=shop__Shipping") ? """
                        {"items": [{"key": 21, "version": 1, "bpmnProcessId": "shop__Shipping"}], "totalCount": 1}"""
                        : """
                                {"items": [], "totalCount": 0}"""));
        engine.answers("GET", "/v1/process-definitions/21", 200, JSON.writeValueAsString(Map.of("key", 21, "version",
                1, "bpmnProcessId", "shop__Shipping", "bpmnData", SHIPPING)));
        final var wiring = TestWiring.claiming("Dispatch");
        final var deploymentService = new ZenBpmDeploymentService(ADAPTER_ID, registry, TestCollaborators.of(ADAPTER_ID,
                TestScoping.of(NameClashAvoidance.USE_PREFIX), wiring.wiring(), TestStarts
                        .everyProcessStartingAWorkflow()));

        final var declared = deploymentService.processVersionCatalogOf("shop", "Shipping");

        assertAll(
                () -> assertSame(declared, deploymentService.processVersionCatalogOf("shop", "Dispatch")),
                () -> assertEquals(List.of(DeployedProcessVersion.of("1", null)), declared.deployedVersionsOf("shop",
                        "Shipping")),
                () -> assertEquals(List.of(
                        new BpmnTaskSpec("Ship", "ship", false, "Ship the shipment", List.of()),
                        new BpmnTaskSpec("Notify", null, false, "Notify the customer", List.of())),
                        declared.tasksOfVersion("shop", "Shipping", "1")),
                () -> assertEquals(List.of(
                        new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "shipped", null),
                        new ModelIdentifier(ScopedIdentifierKind.ERROR_CODE, "lost", null),
                        new ModelIdentifier(ScopedIdentifierKind.ESCALATION_CODE, "late", null),
                        new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "ship", "Shipping")),
                        declared.identifiersOfVersion("shop", "Shipping", "1")),
                () -> assertNull(declared.whatOlderVersionsMiss("shop", "Shipping")));
    }

}
