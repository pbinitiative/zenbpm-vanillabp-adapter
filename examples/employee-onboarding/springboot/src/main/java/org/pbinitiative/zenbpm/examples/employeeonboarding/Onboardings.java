/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.pbinitiative.zenbpm.examples.employeeonboarding.model.Onboarding;
import org.pbinitiative.zenbpm.examples.employeeonboarding.model.OnboardingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What HR, IT and facility management do when somebody joins, said without a word about
 * processes.
 */
@Service
public class Onboardings {

    private static final Logger LOG = LoggerFactory.getLogger(Onboardings.class);

    private final OnboardingRepository onboardings;

    private final Workflow workflow;

    private final ItDepartment itDepartment;

    private final FacilityManagement facilityManagement;

    /**
     * Built by Spring.
     *
     * @param onboardings The onboardings
     * @param workflow What this class tells the process
     * @param itDepartment Who creates the accounts and orders the laptop
     * @param facilityManagement Who issues the badge
     */
    public Onboardings(
        final OnboardingRepository onboardings,
        final Workflow workflow,
        final ItDepartment itDepartment,
        final FacilityManagement facilityManagement
    ) {
        this.onboardings = onboardings;
        this.workflow = workflow;
        this.itDepartment = itDepartment;
        this.facilityManagement = facilityManagement;
    }

    /**
     * A new employee signed the contract.
     *
     * @param employeeName Who joins
     * @param position As what
     * @param startDate The first working day
     * @return The onboarding, started
     */
    @Transactional
    public Onboarding contractSigned(
        final String employeeName,
        final String position,
        final LocalDate startDate
    ) {
        if (employeeName == null || employeeName.isBlank()) {
            throw new InvalidNewHire("The name of the new employee is missing");
        }
        if (position == null || position.isBlank()) {
            throw new InvalidNewHire("The position of the new employee is missing");
        }
        if (startDate == null) {
            throw new InvalidNewHire("The first working day of the new employee is missing");
        }
        final var onboarding = new Onboarding("ONB-%s".formatted(UUID
                .randomUUID()
                .toString()
                .substring(0, 8)), employeeName, position, startDate);
        workflow.contractSigned(onboarding);
        LOG.info("Onboarding {} of {} as {} starts, first day {}", onboarding.getOnboardingId(), employeeName,
                position, startDate);
        return onboarding;
    }

    /**
     * IT creates the accounts.
     *
     * @param onboarding The onboarding
     */
    public void createAccounts(final Onboarding onboarding) {
        onboarding.accountsCreated(itDepartment.createAccounts(onboarding.getEmployeeName(), onboarding
                .getPosition()));
    }

    /**
     * IT orders the laptop.
     *
     * @param onboarding The onboarding
     */
    public void orderLaptop(final Onboarding onboarding) {
        onboarding.laptopOrdered(itDepartment.orderLaptop(onboarding.getOnboardingId()));
    }

    /**
     * Facility management issues the badge.
     *
     * @param onboarding The onboarding
     */
    public void issueBadge(final Onboarding onboarding) {
        onboarding.badgeIssued(facilityManagement.issueBadge(onboarding.getOnboardingId(), onboarding
                .getEmployeeName()));
    }

    /**
     * The workplace is ready, so HR has to hold the welcome meeting on the first day.
     *
     * @param onboarding The onboarding
     * @param taskId The id of the user task
     */
    public void welcomeMeetingDue(
        final Onboarding onboarding,
        final String taskId
    ) {
        onboarding.welcomeMeetingDue(taskId);
        LOG.info("Onboarding {}: HR holds the welcome meeting with {} on {}", onboarding.getOnboardingId(),
                onboarding.getEmployeeName(), onboarding.getStartDate());
    }

    /**
     * The welcome meeting was taken away without being held.
     *
     * @param onboarding The onboarding
     */
    public void welcomeMeetingClosed(final Onboarding onboarding) {
        onboarding.welcomeMeetingClosed();
    }

    /**
     * HR held the welcome meeting. The onboarding is locked while it is read. So a second report
     * of the same meeting waits until the first commits, and then finds no meeting due. Without the
     * lock, both would read the open task and both would answer it.
     *
     * @param onboardingId The id of the onboarding
     * @param notes What HR noted
     * @return The onboarding
     */
    @Transactional
    public Onboarding welcomeMeetingHeld(
        final String onboardingId,
        final String notes
    ) {
        final var onboarding = onboardings
                .findLockedByOnboardingId(onboardingId)
                .orElseThrow(() -> new UnknownOnboarding(onboardingId));
        final var taskId = onboarding.getWelcomeMeetingTaskId();
        if (taskId == null) {
            throw new NoWelcomeMeetingDue(onboardingId);
        }
        onboarding.welcomeMeetingHeld(notes);
        workflow.welcomeMeetingHeld(onboarding, taskId);
        return onboarding;
    }

    /**
     * The workflow ended: everything is done.
     *
     * @param onboarding The onboarding
     */
    public void onboarded(final Onboarding onboarding) {
        onboarding.onboarded();
        LOG.info("Onboarding {}: welcome aboard, {}", onboarding.getOnboardingId(), onboarding.getEmployeeName());
    }

    /**
     * An onboarding as it is stored right now.
     *
     * @param onboardingId The id of the onboarding
     * @return The onboarding, if there is one
     */
    @Transactional(readOnly = true)
    public Optional<Onboarding> find(final String onboardingId) {
        return onboardings.findById(onboardingId);
    }

    /**
     * A new hire which cannot be onboarded as it is reported.
     */
    public static class InvalidNewHire extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InvalidNewHire(final String message) {
            super(message);
        }

    }

    /**
     * There is no onboarding of this id.
     */
    public static class UnknownOnboarding extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnknownOnboarding(final String onboardingId) {
            super("There is no onboarding %s".formatted(onboardingId));
        }

    }

    /**
     * The welcome meeting is not due: the workplace is not ready yet, or the meeting was held.
     */
    public static class NoWelcomeMeetingDue extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NoWelcomeMeetingDue(final String onboardingId) {
            super("Onboarding %s has no welcome meeting due".formatted(onboardingId));
        }

    }

}
