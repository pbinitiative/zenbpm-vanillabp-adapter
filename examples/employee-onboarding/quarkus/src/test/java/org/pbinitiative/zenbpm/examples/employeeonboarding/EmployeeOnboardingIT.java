/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

/**
 * The walk-through of the README, through the REST API of the application, against a ZenBPM engine
 * of its own.
 */
@QuarkusTest
@QuarkusTestResource(ZenBpmEngine.class)
class EmployeeOnboardingIT {

    /**
     * How long a step may take. A user task reaches the application within the poll interval of
     * one second, so the limit only turns a step which never comes into a failure.
     */
    private static final Duration STEP = Duration.ofSeconds(60);

    /** How many people of HR report the same welcome meeting at once. */
    private static final int REPORTERS = 8;

    @Test
    void aNewEmployeeIsOnboardedOnceTheWelcomeMeetingIsHeld() {
        final var onboardingId = contractSigned();
        awaitStatus(onboardingId, "WELCOME_MEETING_DUE");

        welcomeMeeting(onboardingId).statusCode(200);

        awaitStatus(onboardingId, "ONBOARDED");
        show(onboardingId).body("emailAddress", equalTo("jane.doe@example.com"));
    }

    /**
     * The two branches of the called process write the same row at the same time. Without
     * <code>@DynamicUpdate</code> on the aggregate one of the two numbers is lost on every run.
     */
    @Test
    void theCalledProcessPreparesTheWorkplaceWithBothBranchesKept() {
        final var onboardingId = contractSigned();

        awaitStatus(onboardingId, "WELCOME_MEETING_DUE");
        show(onboardingId)
                .body("laptopSerialNumber", startsWith("NB-"))
                .body("badgeNumber", startsWith("BADGE-"));
    }

    @Test
    void aWelcomeMeetingWhichIsNotDueIsRefused() {
        final var onboardingId = contractSigned();
        awaitStatus(onboardingId, "WELCOME_MEETING_DUE");
        welcomeMeeting(onboardingId).statusCode(200);

        welcomeMeeting(onboardingId).statusCode(409);
        given()
                .get("/onboardings/{onboardingId}", "ONB-UNKNOWN")
                .then()
                .statusCode(404);
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
                            .extract()
                            .statusCode();
                }));
            }
            start.countDown();
        }

        final var statuses = answers
                .stream()
                .map(EmployeeOnboardingIT::status)
                .toList();
        assertThat(Collections.frequency(statuses, 200), equalTo(1));
        assertThat(Collections.frequency(statuses, 409), equalTo(REPORTERS - 1));
        awaitStatus(onboardingId, "ONBOARDED");
        show(onboardingId).body("welcomeMeetingNotes", equalTo(notesOf(statuses.indexOf(200))));
    }

    @Test
    void anIncompleteNewHireIsRefused() {
        contractSigned("""
                {"employeeName": " ", "position": "backend developer", "startDate": "2026-11-02"}
                """).statusCode(400);
        contractSigned("""
                {"employeeName": "Jane Doe", "startDate": "2026-11-02"}
                """).statusCode(400);
        contractSigned("""
                {"employeeName": "Jane Doe", "position": "backend developer"}
                """).statusCode(400);
    }

    private static String contractSigned() {
        return contractSigned("""
                {"employeeName": "Jane Doe", "position": "backend developer", "startDate": "2026-11-02"}
                """)
                .statusCode(201)
                .extract()
                .path("onboardingId");
    }

    private static ValidatableResponse contractSigned(final String hire) {
        return given()
                .contentType(ContentType.JSON)
                .body(hire)
                .post("/onboardings")
                .then();
    }

    private static ValidatableResponse welcomeMeeting(final String onboardingId) {
        return welcomeMeeting(onboardingId, "met the team, got the keys");
    }

    private static ValidatableResponse welcomeMeeting(
        final String onboardingId,
        final String notes
    ) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"notes": "%s"}
                        """.formatted(notes))
                .post("/onboardings/{onboardingId}/welcome-meeting", onboardingId)
                .then();
    }

    private static ValidatableResponse show(final String onboardingId) {
        return given()
                .get("/onboardings/{onboardingId}", onboardingId)
                .then()
                .statusCode(200);
    }

    /**
     * Reads one field instead of asserting its value, so a poll which still sees the old value logs
     * nothing: RestAssured logs the request and the response of every assertion which fails.
     */
    private static <T> T field(
        final String onboardingId,
        final String name
    ) {
        return show(onboardingId)
                .extract()
                .path(name);
    }

    private static void awaitStatus(
        final String onboardingId,
        final String status
    ) {
        await()
                .atMost(STEP)
                .until(() -> field(onboardingId, "status"), equalTo(status));
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
