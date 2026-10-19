/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.usertask;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The workflow of a signature, whose user task <code>usertask-it.yaml</code> marks as
 * <code>implemented-externally</code>: something other than this application serves it, so no
 * method of this class does.
 */
@Service
@WorkflowService(workflowAggregateClass = Signature.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Signing"))
public class SignatureWorkflow {

    /**
     * Built by Spring.
     */
    public SignatureWorkflow() {
    }

}
