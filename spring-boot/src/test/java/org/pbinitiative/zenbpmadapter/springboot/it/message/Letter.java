/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.message;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the messages: a letter, whose scenario chooses the path of the workflow,
 * whose status travels with every message, and whose two reviewers are the correlation keys of the
 * two reviews the workflow waits for at the same time.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "MESSAGE_IT_LETTER")
public class Letter {

    /**
     * The path which waits for the message <code>goAhead</code> at once.
     */
    public static final String AWAIT = "await";

    /**
     * The path which waits five seconds at a timer before it waits for <code>goAhead</code>.
     */
    public static final String DELAY = "delay";

    /**
     * The path which waits for two messages <code>reviewed</code>, each under the key of one
     * reviewer.
     */
    public static final String REVIEW = "review";

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
     * For JPA.
     */
    protected Letter() {
    }

    /**
     * A new letter on a path, written and not sent yet; its reviewers are named after it.
     *
     * @param id The id
     * @param scenario The path the workflow takes
     */
    public Letter(
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
     * The id.
     *
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * The path the workflow takes.
     *
     * @return The scenario
     */
    public String getScenario() {
        return scenario;
    }

    /**
     * Where the letter stands.
     *
     * @return The status
     */
    public String getStatus() {
        return status;
    }

    /**
     * Says where the letter stands.
     *
     * @param status The status
     */
    public void setStatus(final String status) {
        this.status = status;
    }

    /**
     * The key under which the workflow waits for the first review.
     *
     * @return The first reviewer
     */
    public String getFirstReviewer() {
        return firstReviewer;
    }

    /**
     * The key under which the workflow waits for the second review.
     *
     * @return The second reviewer
     */
    public String getSecondReviewer() {
        return secondReviewer;
    }

}
