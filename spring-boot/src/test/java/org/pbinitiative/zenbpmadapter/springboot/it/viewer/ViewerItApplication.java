/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.viewer;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application of <code>ZenBpmViewerApiIT</code>: {@link ShippingWorkflow} ships a
 * {@link Parcel} through a task, an embedded sub-process, a call activity of a process of its own
 * on the same aggregate and a multi-instance task. It scans this package only, so the application of
 * another integration test, in a package of its own, stays out of it.
 */
@SpringBootApplication
public class ViewerItApplication {

    /**
     * Built by Spring.
     */
    public ViewerItApplication() {
    }

}
