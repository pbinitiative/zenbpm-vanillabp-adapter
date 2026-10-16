/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.quarkus.runtime;

import java.util.List;

import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.runtime.support.AdapterCollaboratorsSupport;
import io.vanillabp.integration.spi.startup.StartupReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the {@link ZenBpmDeploymentService} of every configured adapter id of type
 * <code>zenbpm</code>, which the platform's deployment pipeline runs while the application starts.
 * <p>
 * The shape is that of {@link ZenBpmProcessServiceProducer}: ONE bean of type
 * <code>List&lt;AdapterDeploymentService&lt;Object, Object&gt;&gt;</code>, both type arguments
 * literally <code>Object</code> whatever the adapter's model and context classes are, because the
 * platform looks the bean up by that exact type and matches models through
 * <code>getModelType()</code> and <code>getProcessContextType()</code>. The producer is
 * <code>@Singleton</code>, because CDI cannot proxy a deployment service.
 */
@ApplicationScoped
public class ZenBpmDeploymentServiceProducer {

    /**
     * Built by CDI, which needs a constructor without parameters to proxy an application-scoped
     * bean.
     */
    public ZenBpmDeploymentServiceProducer() {
    }

    /**
     * Builds one deployment service per configured adapter id of type <code>zenbpm</code>.
     *
     * @param coreProperties The platform's properties, which name the adapter ids
     * @param registry Where each service gets the factory of its adapter id
     * @param workflowTaskRegistry The wiring half and the runtime half of the task SPI
     * @param scoping How identifiers are kept apart where two adapter ids share an engine
     * @param workflowAggregateSync Which values of an aggregate reach the engine
     * @param preCommitRegistrar Where phase one hooks its check into the caller's unit of work
     * @param workflowEndedInvoker What is called when a workflow ended, where the application asked
     *            to be told
     * @param bpmsInitiatedStartInvoker What is called when the engine starts a workflow on its own
     * @param startupReport Where a finding of the adapter goes while the application starts
     * @return One deployment service per configured adapter id of this type
     */
    @Produces
    @Singleton
    public List<AdapterDeploymentService<Object, Object>> zenBpmDeploymentServices(
        final MigrationAdapterProperties coreProperties,
        final ZenBpmClientRegistry registry,
        final WorkflowTaskRegistry workflowTaskRegistry,
        final NameClashAvoidanceSupport scoping,
        final WorkflowAggregateSync workflowAggregateSync,
        final PreCommitRegistrar preCommitRegistrar,
        @Any final Instance<WorkflowEndedInvoker> workflowEndedInvoker,
        @Any final Instance<BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker,
        final StartupReport startupReport
    ) {
        return coreProperties
                .adapterIdsOfType(ZenBpmAdapter.ADAPTER_TYPE)
                .stream()
                .<AdapterDeploymentService<Object, Object>>map(adapterId -> {
                    final var deploymentService = new ZenBpmDeploymentService(adapterId, registry.getFactory(
                            adapterId), AdapterCollaboratorsSupport.collaborators(adapterId, workflowTaskRegistry,
                                    workflowTaskRegistry, scoping, workflowAggregateSync, preCommitRegistrar,
                                    workflowEndedInvoker, bpmsInitiatedStartInvoker));
                    // the block both platform integrations write at the end of a start
                    deploymentService.setStartupReport(startupReport);
                    return asListElement(deploymentService);
                })
                .toList();
    }

    // the platform's list holds Object for both type parameters, see the class comment
    @SuppressWarnings({
            "unchecked", "rawtypes"
    })
    private static AdapterDeploymentService<Object, Object> asListElement(final ZenBpmDeploymentService deploymentService) {
        return (AdapterDeploymentService) deploymentService;
    }

}
