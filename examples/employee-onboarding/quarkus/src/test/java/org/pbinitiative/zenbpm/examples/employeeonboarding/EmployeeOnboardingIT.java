/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;

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

    private static String contractSigned() {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"employeeName": "Jane Doe", "position": "backend developer", "startDate": "2026-11-02"}
                        """)
                .post("/onboardings")
                .then()
                .statusCode(201)
                .extract()
                .path("onboardingId");
    }

    private static ValidatableResponse welcomeMeeting(final String onboardingId) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"notes": "met the team, got the keys"}
                        """)
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
     * Reads one field without asserting anything, so the polls of a wait log nothing: RestAssured
     * logs the request and the response of every assertion which fails.
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

}
