/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.UnsupportedEncodingException;
import java.time.Duration;

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

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", () -> ZenBpmEngine.restAddress(ENGINE));
        registry.add("vanillabp.adapters.zenbpm.grpc-address", () -> ZenBpmEngine.grpcAddress(ENGINE));
    }

    private String contractSigned() {
        final var result = api
                .post()
                .uri("/onboardings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"employeeName": "Jane Doe", "position": "backend developer", "startDate": "2026-11-02"}
                        """)
                .exchange();
        assertThat(result).hasStatus(201);
        try {
            return JsonPath.read(result
                    .getResponse()
                    .getContentAsString(), "$.onboardingId");
        } catch (final UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcTestResult welcomeMeeting(final String onboardingId) {
        return api
                .post()
                .uri("/onboardings/{onboardingId}/welcome-meeting", onboardingId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"notes": "met the team, got the keys"}
                        """)
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

}
