/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process <code>Letters</code>, a copy of the Spring Boot message
 * fixture: a letter, whose scenario chooses the path of the workflow, whose status travels with
 * every message, and whose two reviewers are the correlation keys of the path of the reviews.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_LETTER")
public class E2eLetter {

    /**
     * The path which waits for the message <code>goAhead</code> at once.
     */
    public static final String AWAIT = "await";

    /**
     * The path which waits five seconds at a timer before it waits for <code>goAhead</code>.
     */
    public static final String DELAY = "delay";

    @Id
    private String id;

    @SyncWithBPMS
    private String scenario;

    @SyncWithBPMS
    private String status;

    @SyncWithBPMS
    private String firstReviewer;

    @SyncWithBPMS
    private String secondReviewer;

    /**
     * Built by JPA.
     */
    protected E2eLetter() {
    }

    /**
     * A new letter on a path, written and not sent yet; its reviewers are named after it.
     *
     * @param id The id, which becomes the business key of its workflow
     * @param scenario {@link #AWAIT} or {@link #DELAY}
     */
    public E2eLetter(
        final String id,
        final String scenario
    ) {
        this.id = id;
        this.scenario = scenario;
        this.status = "written";
        this.firstReviewer = "%s/ann".formatted(id);
        this.secondReviewer = "%s/bob".formatted(id);
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return The path the workflow takes
     */
    public String getScenario() {
        return scenario;
    }

    /**
     * @return Where the letter stands
     */
    public String getStatus() {
        return status;
    }

    /**
     * @return The key under which the workflow waits for the first review
     */
    public String getFirstReviewer() {
        return firstReviewer;
    }

    /**
     * @return The key under which the workflow waits for the second review
     */
    public String getSecondReviewer() {
        return secondReviewer;
    }

    /**
     * Says where the letter stands.
     *
     * @param status The status
     */
    public void setStatus(final String status) {
        this.status = status;
    }

}
