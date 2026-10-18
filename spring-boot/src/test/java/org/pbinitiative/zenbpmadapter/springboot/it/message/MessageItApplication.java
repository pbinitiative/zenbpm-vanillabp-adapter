/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.message;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application of <code>ZenBpmMessageIT</code>: the workflow {@link LetterWorkflow} of the JPA
 * aggregate {@link Letter}, which waits for messages only. It scans this package only, so the
 * application of another integration test, in a package of its own, stays out of it.
 */
@SpringBootApplication
public class MessageItApplication {

    /**
     * Built by Spring.
     */
    public MessageItApplication() {
    }

}
