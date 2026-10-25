/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import java.net.URI;
import java.time.LocalDate;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.pbinitiative.zenbpm.examples.employeeonboarding.Onboardings.NoWelcomeMeetingDue;
import org.pbinitiative.zenbpm.examples.employeeonboarding.Onboardings.UnknownOnboarding;
import org.pbinitiative.zenbpm.examples.employeeonboarding.model.Onboarding;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The REST API of the onboardings, which HR uses. It talks to {@link Onboardings} only.
 */
@Path("/onboardings")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ApiController {

    private final Onboardings onboardings;

    /**
     * Built by CDI.
     *
     * @param onboardings The business service
     */
    @Inject
    public ApiController(final Onboardings onboardings) {
        this.onboardings = onboardings;
    }

    /**
     * A new employee signed the contract.
     *
     * @param hire Who joins, as what, and when
     * @return The onboarding, started
     */
    @POST
    public Response contractSigned(final NewHire hire) {
        final var onboarding = onboardings.contractSigned(hire.employeeName(), hire.position(), hire.startDate());
        return Response
                .created(URI.create("/onboardings/%s".formatted(onboarding.getOnboardingId())))
                .entity(OnboardingView.of(onboarding))
                .build();
    }

    /**
     * Shows where an onboarding stands.
     *
     * @param onboardingId The id of the onboarding
     * @return The onboarding
     */
    @GET
    @Path("/{onboardingId}")
    public OnboardingView show(@PathParam("onboardingId") final String onboardingId) {
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
    @POST
    @Path("/{onboardingId}/welcome-meeting")
    public OnboardingView welcomeMeetingHeld(
        @PathParam("onboardingId") final String onboardingId,
        final WelcomeMeeting meeting
    ) {
        return OnboardingView.of(onboardings.welcomeMeetingHeld(onboardingId, meeting.notes()));
    }

    /**
     * An onboarding nobody started.
     *
     * @param exception Which onboarding
     * @return 404
     */
    @ServerExceptionMapper
    public Response unknown(final UnknownOnboarding exception) {
        return problem(Response.Status.NOT_FOUND, exception);
    }

    /**
     * A welcome meeting which is not due.
     *
     * @param exception Which onboarding
     * @return 409
     */
    @ServerExceptionMapper
    public Response notDue(final NoWelcomeMeetingDue exception) {
        return problem(Response.Status.CONFLICT, exception);
    }

    private static Response problem(
        final Response.Status status,
        final RuntimeException exception
    ) {
        return Response
                .status(status)
                .type("application/problem+json")
                .entity(new Problem(status.getReasonPhrase(), status.getStatusCode(), exception.getMessage()))
                .build();
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

    /**
     * What went wrong, in the fields of RFC 9457 which Spring Boot's twin of this example writes.
     *
     * @param title The reason phrase of the status
     * @param status The HTTP status
     * @param detail What went wrong
     */
    public record Problem(
        String title,
        int status,
        String detail
    ) {
    }

}
