/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.springboot;

import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.pbinitiative.zenbpmadapter.springboot.client.VanillaBpZenBpmProperties;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;
import io.vanillabp.integration.spi.startup.StartupReport;

/**
 * Registers the beans the ZenBPM adapter needs per adapter id: for EACH configured adapter id of
 * type <code>zenbpm</code> one {@link ZenBpmProcessService} and one {@link ZenBpmDeploymentService},
 * named after the id.
 * <p>
 * Each bean is a single element, never a bean of type {@code List<...>}: the platform collects the
 * element beans with {@code ObjectProvider.stream()}, which a list bean would break as soon as a
 * second adapter type is on the classpath.
 * <p>
 * A process service reads the <code>history-time-to-live</code> of a workflow from
 * {@link VanillaBpZenBpmProperties}, which resolves it from the workflow, the workflow module and
 * the adapter id.
 * <p>
 * The ids come from the configuration at runtime, so the beans are registered in code, through
 * {@link AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}, which reads the same ids the
 * registry of {@code ZenBpmClientAutoConfiguration} is built for. The suppliers are lazy: they
 * fetch the {@link ZenBpmClientRegistry} only when the bean is created.
 */
public class ZenBpmAdapterBeanRegistrar implements BeanRegistrar {

    /**
     * Built by Spring, which is given this registrar as a class.
     */
    public ZenBpmAdapterBeanRegistrar() {
    }

    @Override
    public void register(
        final BeanRegistry registry,
        final Environment environment
    ) {
        AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(environment, ZenBpmAdapter.ADAPTER_TYPE, adapterId -> {
            registry.registerBean("ZenBpm_ProcessService_%s".formatted(adapterId), ZenBpmProcessService.class,
                    spec -> spec.supplier(supplierContext -> {
                        final var overlay = supplierContext.bean(VanillaBpZenBpmProperties.class);
                        return new ZenBpmProcessService<>(adapterId, factoryOf(supplierContext,
                                adapterId), AdapterBeanRegistrarSupport.collaborators(supplierContext, adapterId), (
                                    workflowModuleId,
                                    bpmnProcessId) -> overlay.historyTimeToLiveFor(workflowModuleId, bpmnProcessId,
                                            adapterId));
                    }));

            registry.registerBean("ZenBpm_DeploymentService_%s".formatted(adapterId), ZenBpmDeploymentService.class,
                    spec -> spec.supplier(supplierContext -> {
                        final var deploymentService = new ZenBpmDeploymentService(adapterId, supplierContext
                                .bean(ZenBpmClientRegistry.class), AdapterBeanRegistrarSupport.collaborators(
                                        supplierContext, adapterId));
                        // the block both platform integrations write at the end of a start
                        deploymentService.setStartupReport(supplierContext
                                .beanProvider(StartupReport.class)
                                .getIfAvailable());
                        return deploymentService;
                    }));
        });
    }

    private static ZenBpmClientFactory factoryOf(
        final BeanRegistry.SupplierContext supplierContext,
        final String adapterId
    ) {
        return supplierContext
                .bean(ZenBpmClientRegistry.class)
                .getFactory(adapterId);
    }

}
