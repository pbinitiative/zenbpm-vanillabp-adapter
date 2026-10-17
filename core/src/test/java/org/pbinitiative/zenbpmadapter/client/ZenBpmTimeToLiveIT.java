/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the pinned engine does with the time to live a start sends, see decision 31 in the
 * repository's DECISIONS.md: it takes the whole seconds the adapter sends, it keeps an explicit
 * <code>0s</code> as a time to live of its own, which removes an ended instance at its next
 * clean-up, and it cuts a fraction of a second without a word, which is why the adapter refuses
 * one. The engine of the tests keeps an instance without a time to live forever.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmTimeToLiveIT {

    /**
     * How long an ended instance may wait for the engine's clean-up, which runs every thirty
     * seconds.
     */
    private static final Duration CLEAN_UP_TIMEOUT = Duration.ofSeconds(90);

    private static final String PROCESS_ID = "ttl-ends-at-once";

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static ZenBpmRestClient client;

    @BeforeAll
    static void deployAProcessWhichEndsAtOnce() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        client = new ZenBpmRestClient("time-to-live", configuration);
        client.deployBpmn("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                id="Definitions_TimeToLive" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="%s" isExecutable="true">
                    <bpmn:startEvent id="StartEvent">
                      <bpmn:outgoing>Flow_ToEnd</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:sequenceFlow id="Flow_ToEnd" sourceRef="StartEvent" targetRef="EndEvent" />
                    <bpmn:endEvent id="EndEvent">
                      <bpmn:incoming>Flow_ToEnd</bpmn:incoming>
                    </bpmn:endEvent>
                  </bpmn:process>
                </bpmn:definitions>
                """
                .formatted(PROCESS_ID)
                .getBytes(StandardCharsets.UTF_8));
    }

    @AfterAll
    static void closeTheClient() {
        client.close();
    }

    @Test
    @DisplayName("An ended instance started with 0s, or with half a second, is removed at the next clean-up; one started with whole seconds or without is kept")
    void anExplicitZeroRemovesTheEndedInstance() {
        final var withZero = start(ZenBpmTimeToLive.engineForm(Duration.ZERO));
        final var withHalfASecond = start("500ms");
        final var withADay = start(ZenBpmTimeToLive.engineForm(Duration.ofDays(1)));
        final var withNone = start(null);

        assertTrue(removedWithin(CLEAN_UP_TIMEOUT, withZero, withHalfASecond),
                "the instances started with 0s and 500ms are still there after %s"
                        .formatted(CLEAN_UP_TIMEOUT));
        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, client
                        .instance(withADay.key())
                        .orElseThrow()
                        .state()),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, client
                        .instance(withNone.key())
                        .orElseThrow()
                        .state()));
    }

    private static ProcessInstanceRef start(final String timeToLive) {
        final var started = client.createInstance(CreateInstanceRequest
                .latestVersionOf(PROCESS_ID)
                .withBusinessKey("order-%s".formatted(UUID.randomUUID()))
                .withHistoryTimeToLive(timeToLive));
        assertEquals(ProcessInstanceRef.State.COMPLETED, started.state());
        return started;
    }

    private static boolean removedWithin(
        final Duration timeout,
        final ProcessInstanceRef... instances
    ) {
        final var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var allRemoved = true;
            for (final var instance : instances) {
                allRemoved &= client
                        .instance(instance.key())
                        .equals(Optional.empty());
            }
            if (allRemoved) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (final InterruptedException e) {
                Thread
                        .currentThread()
                        .interrupt();
                return false;
            }
        }
        return false;
    }

}
