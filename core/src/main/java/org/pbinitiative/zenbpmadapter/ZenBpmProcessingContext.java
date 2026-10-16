/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

/**
 * What the deployment pipeline of this adapter collects about one workflow module, from its first
 * BPMN file to the start of its workflow processing. It names the type the platform and the
 * extensions match the pipeline by; it holds nothing yet, because this version of the adapter
 * deploys nothing: its deployment service reads a BPMN file and refuses to prepare it.
 */
public final class ZenBpmProcessingContext {

    private ZenBpmProcessingContext() {
    }

}
