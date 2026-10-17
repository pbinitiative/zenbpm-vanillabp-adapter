/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.quarkus.runtime;

import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;
import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;

import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.runtime.support.AdapterCollaboratorsSupport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the {@link ZenBpmProcessService} of every configured adapter id of type
 * <code>zenbpm</code>. Several ids of one type are the setup of a migration, for example from one
 * engine to another.
 * <p>
 * A CDI producer cannot yield one bean per id when the ids are known only at runtime, so the adapter
 * produces ONE bean of type <code>List&lt;MigratableProcessService&lt;Object&gt;&gt;</code>, which
 * the platform flattens where it collects the process services. The type argument is literally
 * <code>Object</code>, because the platform looks the bean up by that exact type. The producer is
 * <code>@Singleton</code>, because a process service has no constructor without parameters and
 * so CDI cannot proxy it.
 * <p>
 * The adapter ids ALWAYS come from the platform's core properties. A process service reads the
 * <code>history-time-to-live</code> of a workflow from {@link VanillaBpZenBpmProperties}, which
 * resolves it from the workflow, the workflow module and the adapter id.
 */
@ApplicationScoped
public class ZenBpmProcessServiceProducer {

    /**
     * Built by CDI, which needs a constructor without parameters to proxy an application-scoped
     * bean.
     */
    public ZenBpmProcessServiceProducer() {
    }

    /**
     * Builds one process service per configured adapter id of type <code>zenbpm</code>.
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
     * @return One process service per configured adapter id of this type
     */
    @Produces
    @Singleton
    public List<MigratableProcessService<Object>> zenBpmProcessServices(
        final MigrationAdapterProperties coreProperties,
        final ZenBpmClientRegistry registry,
        final WorkflowTaskRegistry workflowTaskRegistry,
        final NameClashAvoidanceSupport scoping,
        final WorkflowAggregateSync workflowAggregateSync,
        final PreCommitRegistrar preCommitRegistrar,
        @Any final Instance<WorkflowEndedInvoker> workflowEndedInvoker,
        @Any final Instance<BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker
    ) {
        // read as the client producer reads it: a mapping of the configuration, never injected
        final var overlay = ConfigProvider
                .getConfig()
                .unwrap(SmallRyeConfig.class)
                .getConfigMapping(VanillaBpZenBpmProperties.class);
        return coreProperties
                .adapterIdsOfType(ZenBpmAdapter.ADAPTER_TYPE)
                .stream()
                .<MigratableProcessService<Object>>map(adapterId -> new ZenBpmProcessService<>(adapterId, registry
                        .getFactory(adapterId), AdapterCollaboratorsSupport.collaborators(adapterId,
                                workflowTaskRegistry, workflowTaskRegistry, scoping, workflowAggregateSync,
                                preCommitRegistrar, workflowEndedInvoker, bpmsInitiatedStartInvoker), (
                                    workflowModuleId,
                                    bpmnProcessId) -> overlay.historyTimeToLiveFor(workflowModuleId, bpmnProcessId,
                                            adapterId)))
                .toList();
    }

}
