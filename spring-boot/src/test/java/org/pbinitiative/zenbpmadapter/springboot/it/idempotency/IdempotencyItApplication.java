/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.idempotency;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application of <code>ZenBpmInboundIdempotencyIT</code>: the workflow {@link TicketWorkflow}
 * of the JPA aggregate {@link Ticket}, whose one task the test holds as long as its case needs. It
 * scans this package only, so the application of another integration test, in a package of its
 * own, stays out of it.
 */
@SpringBootApplication
public class IdempotencyItApplication {

    /**
     * Built by Spring.
     */
    public IdempotencyItApplication() {
    }

}
