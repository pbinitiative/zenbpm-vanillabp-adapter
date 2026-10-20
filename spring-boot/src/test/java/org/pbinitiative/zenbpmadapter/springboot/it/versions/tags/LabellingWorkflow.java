/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.versions.tags;

import org.pbinitiative.zenbpmadapter.springboot.it.versions.Consignment;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a consignment under the process id <code>Labelling</code>, whose task one method
 * serves on the version tagged <code>release-1</code>, another on the one tagged
 * <code>release-2</code>, and a third on a version tagged <code>release-9</code>, which nobody
 * deploys.
 */
@Service
@WorkflowService(workflowAggregateClass = Consignment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Labelling"))
public class LabellingWorkflow {

    /**
     * Built by Spring.
     */
    public LabellingWorkflow() {
    }

    /**
     * Labels a consignment of a workflow which runs on the version tagged <code>release-1</code>.
     *
     * @param consignment The consignment
     */
    @WorkflowTask(taskDefinition = "label", version = "release-1")
    public void labelOnReleaseOne(final Consignment consignment) {
        consignment.servedBy("release-1");
    }

    /**
     * Labels a consignment of a workflow which runs on the version tagged <code>release-2</code>.
     *
     * @param consignment The consignment
     */
    @WorkflowTask(taskDefinition = "label", version = "release-2")
    public void labelOnReleaseTwo(final Consignment consignment) {
        consignment.servedBy("release-2");
    }

    /**
     * Labels a consignment of a workflow which runs on a version tagged <code>release-9</code>, which
     * no model of the test carries.
     *
     * @param consignment The consignment
     */
    @WorkflowTask(taskDefinition = "label", version = "release-9")
    public void labelOnReleaseNine(final Consignment consignment) {
        consignment.servedBy("release-9");
    }

}
