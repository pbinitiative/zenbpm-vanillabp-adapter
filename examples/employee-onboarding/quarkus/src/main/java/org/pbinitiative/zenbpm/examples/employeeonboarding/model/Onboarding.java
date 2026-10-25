/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding.model;

import java.time.LocalDate;

import org.hibernate.annotations.DynamicUpdate;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate: the onboarding of one new employee. It belongs to the process
 * <code>employee_onboarding</code> and to the process <code>workplace_provisioning</code> it calls:
 * a called process works on the aggregate of its caller.
 * <p>
 * Inside the called process two branches run at the same time, and each writes a column of its
 * own: the laptop's serial number and the badge's number. Both load the row, change their column
 * and save it, in two transactions nobody coordinates. Without <code>@DynamicUpdate</code>
 * Hibernate would write every column, and the transaction committing second would put back the
 * empty column of the first one. With it, each transaction writes only what it changed.
 * <p>
 * The model decides nothing on data, so the class is <code>@NoSyncWithBPMS</code> without a shared
 * member.
 */
@Entity
@Table(name = "ONBOARDING")
@DynamicUpdate
@NoSyncWithBPMS
public class Onboarding {

    /**
     * Where an onboarding stands.
     */
    public enum Status {
        /** The contract is signed; accounts and workplace are being prepared. */
        PREPARING,
        /** Everything is ready, and HR has to hold the welcome meeting. */
        WELCOME_MEETING_DUE,
        /** The employee is onboarded. */
        ONBOARDED
    }

    @Id
    private String onboardingId;

    private String employeeName;

    private String position;

    private LocalDate startDate;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String emailAddress;

    private String laptopSerialNumber;

    private String badgeNumber;

    private String welcomeMeetingTaskId;

    private String welcomeMeetingNotes;

    /**
     * For JPA.
     */
    protected Onboarding() {
    }

    /**
     * The onboarding of an employee whose contract was just signed.
     *
     * @param onboardingId The id of the onboarding
     * @param employeeName Who joins
     * @param position As what
     * @param startDate The first working day
     */
    public Onboarding(
        final String onboardingId,
        final String employeeName,
        final String position,
        final LocalDate startDate
    ) {
        this.onboardingId = onboardingId;
        this.employeeName = employeeName;
        this.position = position;
        this.startDate = startDate;
        this.status = Status.PREPARING;
    }

    /**
     * The id of the onboarding, which is the id of its workflow as well.
     *
     * @return The id
     */
    public String getOnboardingId() {
        return onboardingId;
    }

    /**
     * Who joins.
     *
     * @return The employee's name
     */
    public String getEmployeeName() {
        return employeeName;
    }

    /**
     * As what.
     *
     * @return The position
     */
    public String getPosition() {
        return position;
    }

    /**
     * The first working day.
     *
     * @return The date
     */
    public LocalDate getStartDate() {
        return startDate;
    }

    /**
     * Where the onboarding stands.
     *
     * @return The status
     */
    public Status getStatus() {
        return status;
    }

    /**
     * The e-mail address IT created.
     *
     * @return The address, or <code>null</code> before the accounts exist
     */
    public String getEmailAddress() {
        return emailAddress;
    }

    /**
     * The serial number of the laptop IT ordered.
     *
     * @return The number, or <code>null</code> before the order
     */
    public String getLaptopSerialNumber() {
        return laptopSerialNumber;
    }

    /**
     * The number of the badge facility management issued.
     *
     * @return The number, or <code>null</code> before it was issued
     */
    public String getBadgeNumber() {
        return badgeNumber;
    }

    /**
     * The open welcome meeting, the id the engine gave the user task.
     *
     * @return The task id, or <code>null</code> while none is open
     */
    public String getWelcomeMeetingTaskId() {
        return welcomeMeetingTaskId;
    }

    /**
     * What HR noted at the welcome meeting.
     *
     * @return The notes, or <code>null</code>
     */
    public String getWelcomeMeetingNotes() {
        return welcomeMeetingNotes;
    }

    /**
     * IT created the accounts.
     *
     * @param emailAddress The e-mail address
     */
    public void accountsCreated(final String emailAddress) {
        this.emailAddress = emailAddress;
    }

    /**
     * IT ordered the laptop.
     *
     * @param serialNumber Its serial number
     */
    public void laptopOrdered(final String serialNumber) {
        this.laptopSerialNumber = serialNumber;
    }

    /**
     * Facility management issued the badge.
     *
     * @param badgeNumber Its number
     */
    public void badgeIssued(final String badgeNumber) {
        this.badgeNumber = badgeNumber;
    }

    /**
     * HR has to hold the welcome meeting.
     *
     * @param taskId The id of the user task
     */
    public void welcomeMeetingDue(final String taskId) {
        this.welcomeMeetingTaskId = taskId;
        this.status = Status.WELCOME_MEETING_DUE;
    }

    /**
     * The welcome meeting is closed, held or taken away.
     */
    public void welcomeMeetingClosed() {
        this.welcomeMeetingTaskId = null;
    }

    /**
     * HR held the welcome meeting.
     *
     * @param notes What HR noted
     */
    public void welcomeMeetingHeld(final String notes) {
        this.welcomeMeetingNotes = notes;
        this.welcomeMeetingTaskId = null;
    }

    /**
     * The workflow ended: the employee is onboarded.
     */
    public void onboarded() {
        this.status = Status.ONBOARDED;
    }

}
