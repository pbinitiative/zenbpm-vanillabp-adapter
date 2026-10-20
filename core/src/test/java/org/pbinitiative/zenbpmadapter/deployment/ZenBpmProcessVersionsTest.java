/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the version catalog of an adapter id learns the versions of a process from an engine standing
 * in for ZenBPM: the list of the definitions under the process's scoped id, read with one request,
 * oldest first with their tags, and the version a deployment of this start ended up with, without a
 * request.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmProcessVersionsTest {

    private static final String ADAPTER_ID = "orders";

    // in the order of their keys, as the engine lists them, which need not be that of the versions
    private static final String THREE_VERSIONS = """
            {"items": [{"key": 9, "version": 1, "bpmnProcessId": "shop__Order"},
                       {"key": 7, "version": 3, "bpmnProcessId": "shop__Order", "versionTag": "release-3"},
                       {"key": 11, "version": 2, "bpmnProcessId": "shop__Order", "versionTag": "release-2"}],
             "totalCount": 3}""";

    private FakeEngine engine;

    private ZenBpmClientRegistry registry;

    private ZenBpmProcessVersions versions;

    @BeforeEach
    void startTheEngine() {
        engine = FakeEngine.started();
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        registry = new ZenBpmClientRegistry(List.of(new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")));
        versions = new ZenBpmProcessVersions(ADAPTER_ID, registry.getFactory(ADAPTER_ID), TestScoping.of(
                NameClashAvoidance.USE_PREFIX));
    }

    @AfterEach
    void stopTheEngine() {
        registry.close();
        engine.close();
    }

    @Test
    @DisplayName("Every version the engine holds under the scoped id of the process is read with one request, oldest first, each with its tag")
    void theVersionsAreReadWithOneRequestOldestFirst() {
        engine.answers("GET", "/v1/process-definitions", 200, THREE_VERSIONS);

        final var held = versions.deployedVersionsOf("shop", "Order");

        assertAll(
                () -> assertEquals(List.of(
                        DeployedProcessVersion.of("1", null),
                        DeployedProcessVersion.of("2", "release-2"),
                        DeployedProcessVersion.of("3", "release-3")), held),
                () -> assertEquals(1, engine
                        .requests()
                        .size()),
                () -> assertTrue(engine
                        .requests()
                        .getFirst()
                        .query()
                        .contains("bpmnProcessId=shop__Order"), () -> String.valueOf(engine.requests())),
                () -> assertTrue(engine
                        .requests()
                        .getFirst()
                        .query()
                        .contains("onlyLatest=false"), () -> String.valueOf(engine.requests())));
    }

    @Test
    @DisplayName("A version tag resolves to the version which carries it, and a tag no version carries to nothing")
    void aTagResolvesToTheVersionCarryingIt() {
        engine.answers("GET", "/v1/process-definitions", 200, THREE_VERSIONS);

        assertAll(
                () -> assertEquals(DeployedProcessVersion.of("2", "release-2"), versions.resolveVersion("shop", "Order",
                        "release-2")),
                () -> assertEquals(DeployedProcessVersion.of("1", null), versions.resolveVersion("shop", "Order", "1")),
                () -> assertNull(versions.resolveVersion("shop", "Order", "release-9")));
    }

    @Test
    @DisplayName("The version a deployment of this start ended up with resolves by its number and its tag without a request")
    void theDeployedVersionNeedsNoRequest() {
        versions.recordDeployed(
                new ZenBpmDeployedProcesses.DeployedProcess("shop", "Order", "shop__Order", 7, 3, "release-3", "<xml/>"));

        assertAll(
                () -> assertEquals(DeployedProcessVersion.of("3", "release-3"), versions.resolveVersion("shop", "Order",
                        "release-3")),
                () -> assertEquals(DeployedProcessVersion.of("3", "release-3"), versions.resolveVersion("shop", "Order",
                        "3")),
                () -> assertEquals(List.of(), engine.requests()));
    }

}
