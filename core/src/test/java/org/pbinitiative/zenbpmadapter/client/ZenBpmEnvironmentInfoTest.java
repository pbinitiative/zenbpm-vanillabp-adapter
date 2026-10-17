/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pbinitiative.zenbpmadapter.client.CollectedStartupReport.Finding;
import org.pbinitiative.zenbpmadapter.client.api.EngineStatus;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;

import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the adapter says about the engine it reached, and when a commit it was not tested with is
 * a finding of the startup report.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmEnvironmentInfoTest {

    private static final String ADAPTER_ID = "orders";

    /**
     * The engine reports its commit shortened to twelve characters.
     */
    private static final String TESTED_SHORT_COMMIT = EngineUnderTest
            .pinnedCommit()
            .substring(0, 12);

    private FakeEngine engine;

    private CollectedStartupReport report;

    @BeforeEach
    void startTheEngine() {
        engine = FakeEngine.started();
        report = new CollectedStartupReport();
    }

    @AfterEach
    void stopTheEngine() {
        engine.close();
    }

    @Test
    @DisplayName("The commit the adapter was tested with is the pinned engine's")
    void theTestedCommitIsThePin() {
        assertEquals(EngineUnderTest.pinnedCommit(), ZenBpmEnvironmentInfo.testedCommit());
    }

    @Test
    @DisplayName("One line names the addresses, the client id, the engine's version and commit, and the commit tested with")
    void oneLineNamesTheEnvironment(final CapturedOutput output) {
        engine.answers("GET", "/system/status", 200, status(TESTED_SHORT_COMMIT));
        final var configuration = configuration();
        configuration.setGrpcAddress("127.0.0.1:9191");
        configuration.setClientId("node-7");

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")) {
            ZenBpmEnvironmentInfo.logOnce(factory, report);

            assertAll(
                    () -> assertTrue(output
                            .getAllOfThisTest()
                            .contains(
                                    "INFO  o.p.z.client.ZenBpmEnvironmentInfo - ZenBPM[orders]: engine at %s, job stream at 127.0.0.1:9191 with client id 'node-7': version 1.2.0, commit %s, node(s) node-1, node-2; this adapter was tested with engine commit %s"
                                            .formatted(engine.address(), TESTED_SHORT_COMMIT, EngineUnderTest
                                                    .pinnedCommit())),
                            output.getAllOfThisTest()),
                    () -> assertEquals(List.of(), report.findings()),
                    () -> assertEquals(Optional.of(new EngineStatus("1.2.0", TESTED_SHORT_COMMIT, List.of("node-1",
                            "node-2"), List.of())), factory.engineStatusAtStart()));
        }
    }

    @Test
    @DisplayName("Another commit is one warning of topic INFRASTRUCTURE, scoped to the adapter id, which the text does not repeat")
    void anotherCommitIsOneWarning(final CapturedOutput output) {
        engine.answers("GET", "/system/status", 200, status("0123456789ab"));

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration(), () -> "shop")) {
            ZenBpmEnvironmentInfo.logOnce(factory, report);

            assertAll(
                    () -> assertEquals(List.of(
                            new Finding("warn", StartupTopic.INFRASTRUCTURE, "zenbpm adapter 'orders'", "The engine reports commit 0123456789ab (version 1.2.0), but this adapter was tested with engine commit %s, and an engine is supported only where it was tested. Run the engine image this adapter's release names, or an adapter release tested with this engine."
                                    .formatted(EngineUnderTest.pinnedCommit()))),
                            report.findings()),
                    () -> assertFalse(report
                            .findings()
                            .getFirst()
                            .message()
                            .contains(ADAPTER_ID)),
                    () -> assertTrue(output
                            .getAllOfThisTest()
                            .contains("commit 0123456789ab"), output.getAllOfThisTest()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0, 6
    })
    @DisplayName("A beginning of the tested commit too short to tell commits apart is not taken for it")
    void aShortCommitIsNoMatch(final int length) {
        engine.answers("GET", "/system/status", 200, status(TESTED_SHORT_COMMIT.substring(0, length)));

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration(), () -> "shop")) {
            ZenBpmEnvironmentInfo.logOnce(factory, report);

            assertEquals(1, report
                    .findings()
                    .size());
        }
    }

    @Test
    @DisplayName("The first seven characters of the tested commit, git's shortest form, are taken for it")
    void sevenCharactersAreAMatch() {
        engine.answers("GET", "/system/status", 200, status(TESTED_SHORT_COMMIT.substring(0, 7)));

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration(), () -> "shop")) {
            ZenBpmEnvironmentInfo.logOnce(factory, report);

            assertEquals(List.of(), report.findings());
        }
    }

    @Test
    @DisplayName("A commit of upper-case digits matches the tested one")
    void theCommitIsComparedIgnoringCase() {
        engine.answers("GET", "/system/status", 200, status(TESTED_SHORT_COMMIT.toUpperCase()));

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration(), () -> "shop")) {
            ZenBpmEnvironmentInfo.logOnce(factory, report);

            assertEquals(List.of(), report.findings());
        }
    }

    @Test
    @DisplayName("The engine is asked once per adapter id")
    void theEngineIsAskedOnce() {
        engine.answers("GET", "/system/status", 200, status("0123456789ab"));

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration(), () -> "shop")) {
            ZenBpmEnvironmentInfo.logOnce(factory, report);
            ZenBpmEnvironmentInfo.logOnce(factory, report);

            assertAll(
                    () -> assertEquals(1, engine
                            .requests()
                            .size()),
                    () -> assertEquals(1, report
                            .findings()
                            .size()));
        }
    }

    @Test
    @DisplayName("An engine which does not say what it runs is logged, and nothing is reported")
    void anUnansweredStatusIsLoggedOnly(final CapturedOutput output) throws IOException {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(closedPortAddress());

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")) {
            ZenBpmEnvironmentInfo.logOnce(factory, report);

            assertAll(
                    () -> assertEquals(List.of(), report.findings()),
                    () -> assertEquals(Optional.empty(), factory.engineStatusAtStart()),
                    () -> assertTrue(output
                            .getAllOfThisTest()
                            .contains(
                                    "WARN  o.p.z.client.ZenBpmEnvironmentInfo - ZenBPM[orders]: the engine at %s did not say which version it runs, so whether it is the one this adapter was tested with (commit %s) is unknown: GET /system/status got no answer"
                                            .formatted(configuration
                                                    .getRestAddress(), EngineUnderTest.pinnedCommit())),
                            output.getAllOfThisTest()));
        }
    }

    @Test
    @DisplayName("An adapter id without clients asks nothing and reports nothing")
    void anAdapterIdWithoutClientsIsSkipped() {
        final var factory = ZenBpmClientFactory.unconfigured(ADAPTER_ID, new ZenBpmAdapterConfiguration(),
                "configure me");

        ZenBpmEnvironmentInfo.logOnce(factory, report);

        assertAll(
                () -> assertEquals(List.of(), report.findings()),
                () -> assertEquals(Optional.empty(), factory.engineStatusAtStart()));
    }

    private ZenBpmAdapterConfiguration configuration() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        return configuration;
    }

    private static String status(final String commit) {
        return """
                {"git":{"branch":"main","commitId":"%s"},"build":{"version":"1.2.0","time":"2026-10-13"},\
                "health":{"ok":true,"reasons":[]},"nodes":{"node-2":{"id":"node-2"},"node-1":{"id":"node-1"}}}"""
                .formatted(commit);
    }

    private static String closedPortAddress() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:%d".formatted(socket.getLocalPort());
        }
    }

}
