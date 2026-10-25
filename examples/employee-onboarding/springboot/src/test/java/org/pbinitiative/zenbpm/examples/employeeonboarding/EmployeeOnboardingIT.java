/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;

/**
 * The walk-through of the README, through the REST API of the application, against a ZenBPM engine
 * of its own.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
// the application is closed after the tests, before the engine stops; otherwise it runs until the
// JVM ends and reports the engine gone
@DirtiesContext
class EmployeeOnboardingIT {

    /**
     * How long a step may take. A user task reaches the application within the poll interval of
     * one second, so the limit only turns a step which never comes into a failure.
     */
    private static final Duration STEP = Duration.ofSeconds(60);

    /** How many people of HR report the same welcome meeting at once. */
    private static final int REPORTERS = 8;

    @Container
    private static final GenericContainer<?> ENGINE = ZenBpmEngine.container();

    @Autowired
    private WebApplicationContext context;

    private MockMvcTester api;

    @BeforeEach
    void theApi() {
        api = MockMvcTester.from(context);
    }

    @Test
    void aNewEmployeeIsOnboardedOnceTheWelcomeMeetingIsHeld() {
        final var onboardingId = contractSigned();
        awaitStatus(onboardingId, "WELCOME_MEETING_DUE");

        assertThat(welcomeMeeting(onboardingId)).hasStatusOk();

        awaitStatus(onboardingId, "ONBOARDED");
        assertThat(show(onboardingId))
                .bodyJson()
                .extractingPath("$.emailAddress")
                .isEqualTo("jane.doe@example.com");
    }

    /**
     * The two branches of the called process write the same row at the same time. Without
     * <code>@DynamicUpdate</code> on the aggregate one of the two numbers is lost on every run.
     */
    @Test
    void theCalledProcessPreparesTheWorkplaceWithBothBranchesKept() {
        final var onboardingId = contractSigned();

        awaitStatus(onboardingId, "WELCOME_MEETING_DUE");
        assertThat(show(onboardingId))
                .bodyJson()
                .extractingPath("$.laptopSerialNumber")
                .asString()
                .startsWith("NB-");
        assertThat(show(onboardingId))
                .bodyJson()
                .extractingPath("$.badgeNumber")
                .asString()
                .startsWith("BADGE-");
    }

    @Test
    void aWelcomeMeetingWhichIsNotDueIsRefused() {
        final var onboardingId = contractSigned();
        awaitStatus(onboardingId, "WELCOME_MEETING_DUE");
        assertThat(welcomeMeeting(onboardingId)).hasStatusOk();

        assertThat(welcomeMeeting(onboardingId)).hasStatus(409);
        assertThat(show("ONB-UNKNOWN")).hasStatus(404);
    }

    @Test
    void aWelcomeMeetingReportedSeveralTimesAtOnceIsAcceptedOnce() {
        final var onboardingId = contractSigned();
        awaitStatus(onboardingId, "WELCOME_MEETING_DUE");
        final var start = new CountDownLatch(1);
        final var answers = new ArrayList<Future<Integer>>();
        try (var reporters = Executors.newFixedThreadPool(REPORTERS)) {
            for (var reporter = 0; reporter < REPORTERS; reporter++) {
                final var notes = notesOf(reporter);
                answers.add(reporters.submit(() -> {
                    start.await();
                    return welcomeMeeting(onboardingId, notes)
                            .getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
        }

        final var statuses = answers
                .stream()
                .map(EmployeeOnboardingIT::status)
                .toList();
        assertThat(Collections.frequency(statuses, 200)).isEqualTo(1);
        assertThat(Collections.frequency(statuses, 409)).isEqualTo(REPORTERS - 1);
        awaitStatus(onboardingId, "ONBOARDED");
        assertThat(show(onboardingId))
                .bodyJson()
                .extractingPath("$.welcomeMeetingNotes")
                .isEqualTo(notesOf(statuses.indexOf(200)));
    }

    @Test
    void anIncompleteNewHireIsRefused() {
        assertThat(contractSigned("""
                {"employeeName": " ", "position": "backend developer", "startDate": "2026-11-02"}
                """)).hasStatus(400);
        assertThat(contractSigned("""
                {"employeeName": "Jane Doe", "startDate": "2026-11-02"}
                """)).hasStatus(400);
        assertThat(contractSigned("""
                {"employeeName": "Jane Doe", "position": "backend developer"}
                """)).hasStatus(400);
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", () -> ZenBpmEngine.restAddress(ENGINE));
        registry.add("vanillabp.adapters.zenbpm.grpc-address", () -> ZenBpmEngine.grpcAddress(ENGINE));
    }

    private String contractSigned() {
        final var response = contractSigned("""
                {"employeeName": "Jane Doe", "position": "backend developer", "startDate": "2026-11-02"}
                """);
        assertThat(response).hasStatus(201);
        try {
            return JsonPath.read(response
                    .getResponse()
                    .getContentAsString(), "$.onboardingId");
        } catch (final UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcTestResult contractSigned(final String hire) {
        return api
                .post()
                .uri("/onboardings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(hire)
                .exchange();
    }

    private MvcTestResult welcomeMeeting(final String onboardingId) {
        return welcomeMeeting(onboardingId, "met the team, got the keys");
    }

    private MvcTestResult welcomeMeeting(
        final String onboardingId,
        final String notes
    ) {
        return api
                .post()
                .uri("/onboardings/{onboardingId}/welcome-meeting", onboardingId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"notes": "%s"}
                        """.formatted(notes))
                .exchange();
    }

    private MvcTestResult show(final String onboardingId) {
        return api
                .get()
                .uri("/onboardings/{onboardingId}", onboardingId)
                .exchange();
    }

    private void awaitStatus(
        final String onboardingId,
        final String status
    ) {
        await()
                .atMost(STEP)
                .untilAsserted(() -> assertThat(show(onboardingId))
                        .bodyJson()
                        .extractingPath("$.status")
                        .isEqualTo(status));
    }

    private static String notesOf(final int reporter) {
        return "noted by reporter %d".formatted(reporter);
    }

    private static int status(final Future<Integer> answer) {
        try {
            return answer.get();
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new IllegalStateException(e);
        }
    }

}
