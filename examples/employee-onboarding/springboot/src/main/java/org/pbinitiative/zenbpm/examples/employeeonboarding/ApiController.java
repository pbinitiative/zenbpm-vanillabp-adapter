/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import java.time.LocalDate;

import org.pbinitiative.zenbpm.examples.employeeonboarding.Onboardings.InvalidNewHire;
import org.pbinitiative.zenbpm.examples.employeeonboarding.Onboardings.NoWelcomeMeetingDue;
import org.pbinitiative.zenbpm.examples.employeeonboarding.Onboardings.UnknownOnboarding;
import org.pbinitiative.zenbpm.examples.employeeonboarding.model.Onboarding;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The REST API of the onboardings, which HR uses. It talks to {@link Onboardings} only.
 */
@RestController
@RequestMapping("/onboardings")
public class ApiController {

    private final Onboardings onboardings;

    /**
     * Built by Spring.
     *
     * @param onboardings The business service
     */
    public ApiController(final Onboardings onboardings) {
        this.onboardings = onboardings;
    }

    /**
     * A new employee signed the contract.
     *
     * @param hire Who joins, as what, and when
     * @return The onboarding, started
     */
    @PostMapping
    public ResponseEntity<OnboardingView> contractSigned(@RequestBody final NewHire hire) {
        final var onboarding = onboardings.contractSigned(hire.employeeName(), hire.position(), hire.startDate());
        return ResponseEntity
                .created(UriComponentsBuilder
                        .fromPath("/onboardings/{onboardingId}")
                        .buildAndExpand(onboarding.getOnboardingId())
                        .encode()
                        .toUri())
                .body(OnboardingView.of(onboarding));
    }

    /**
     * Shows where an onboarding stands.
     *
     * @param onboardingId The id of the onboarding
     * @return The onboarding
     */
    @GetMapping("/{onboardingId}")
    public OnboardingView show(@PathVariable final String onboardingId) {
        return onboardings
                .find(onboardingId)
                .map(OnboardingView::of)
                .orElseThrow(() -> new UnknownOnboarding(onboardingId));
    }

    /**
     * HR held the welcome meeting.
     *
     * @param onboardingId The id of the onboarding
     * @param meeting What HR noted
     * @return The onboarding
     */
    @PostMapping("/{onboardingId}/welcome-meeting")
    public OnboardingView welcomeMeetingHeld(
        @PathVariable final String onboardingId,
        @RequestBody final WelcomeMeeting meeting
    ) {
        return OnboardingView.of(onboardings.welcomeMeetingHeld(onboardingId, meeting.notes()));
    }

    /**
     * A new hire reported without what the onboarding needs.
     *
     * @param exception What is missing
     * @return 400
     */
    @ExceptionHandler
    public ProblemDetail invalid(final InvalidNewHire exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * An onboarding nobody started.
     *
     * @param exception Which onboarding
     * @return 404
     */
    @ExceptionHandler
    public ProblemDetail unknown(final UnknownOnboarding exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * A welcome meeting which is not due.
     *
     * @param exception Which onboarding
     * @return 409
     */
    @ExceptionHandler
    public ProblemDetail notDue(final NoWelcomeMeetingDue exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    /**
     * A new employee.
     *
     * @param employeeName Who joins
     * @param position As what
     * @param startDate The first working day
     */
    public record NewHire(
        String employeeName,
        String position,
        LocalDate startDate
    ) {
    }

    /**
     * What HR noted at the welcome meeting.
     *
     * @param notes The notes
     */
    public record WelcomeMeeting(String notes) {
    }

    /**
     * An onboarding as the API shows it.
     *
     * @param onboardingId The id
     * @param employeeName Who joins
     * @param position As what
     * @param startDate The first working day
     * @param status Where the onboarding stands
     * @param emailAddress The e-mail address, once created
     * @param laptopSerialNumber The laptop, once ordered
     * @param badgeNumber The badge, once issued
     * @param welcomeMeetingNotes What HR noted at the welcome meeting
     */
    public record OnboardingView(
        String onboardingId,
        String employeeName,
        String position,
        LocalDate startDate,
        Onboarding.Status status,
        String emailAddress,
        String laptopSerialNumber,
        String badgeNumber,
        String welcomeMeetingNotes
    ) {

        static OnboardingView of(final Onboarding onboarding) {
            return new OnboardingView(onboarding.getOnboardingId(), onboarding.getEmployeeName(), onboarding
                    .getPosition(), onboarding.getStartDate(), onboarding.getStatus(), onboarding
                            .getEmailAddress(), onboarding.getLaptopSerialNumber(), onboarding
                                    .getBadgeNumber(), onboarding
                                            .getWelcomeMeetingNotes());
        }

    }

}
