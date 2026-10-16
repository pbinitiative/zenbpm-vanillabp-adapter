/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.quarkus.runtime;

import org.eclipse.microprofile.config.ConfigProvider;
import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmStartupValidation;

import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.spi.startup.StartupReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the {@link ZenBpmClientRegistry}, the one owner of every ZenBPM client of the
 * application. {@link ZenBpmStartupValidation} does the work, the same as on Spring Boot: it
 * validates each adapter id, reports what it found into the platform's startup report, ends the
 * start where a configuration cannot work, and builds a factory per adapter id.
 * {@link ZenBpmStartupObserver} makes this happen while the application starts and not at the
 * first use of the adapter. When the application stops, the
 * {@link #close(ZenBpmClientRegistry) disposer} closes the registry and with it every client.
 * <p>
 * The adapter ids are the platform's: those its core properties name for type
 * <code>zenbpm</code>, the ids the core derives included. The overlay
 * {@link VanillaBpZenBpmProperties} is only asked about them.
 */
@ApplicationScoped
public class ZenBpmClientProducer {

    /**
     * The property which names the application, the first part of a derived client id.
     */
    static final String APPLICATION_NAME_PROPERTY = "quarkus.application.name";

    /**
     * Built by CDI, which needs a constructor without parameters to proxy an application-scoped
     * bean.
     */
    public ZenBpmClientProducer() {
    }

    /**
     * Validates every adapter id of type <code>zenbpm</code> and builds the registry of their
     * factories.
     *
     * @param coreProperties The platform's validated properties, which name the adapter ids, their
     *            priorities and their deployment-failure policies
     * @param startupReport The platform's startup report, which collects the findings for the block
     *            at the end of the start
     * @return The registry, with a factory per adapter id
     */
    @Produces
    @Singleton
    public ZenBpmClientRegistry zenBpmClientRegistry(
        final MigrationAdapterProperties coreProperties,
        final StartupReport startupReport
    ) {
        final var config = ConfigProvider
                .getConfig()
                .unwrap(SmallRyeConfig.class);
        final var overlay = config.getConfigMapping(VanillaBpZenBpmProperties.class);
        return ZenBpmStartupValidation.registryOf(coreProperties.adapterIdsOfType(ZenBpmAdapter.ADAPTER_TYPE),
                overlay::configurationOf, coreProperties::isFirstPriorityAnywhere, adapterId -> coreProperties
                        .getDeploymentFailureFor(adapterId) == DeploymentFailurePolicy.WARN,
                () -> config
                        .getOptionalValue(APPLICATION_NAME_PROPERTY, String.class)
                        .orElse(null),
                startupReport);
    }

    /**
     * Closes every client of the registry when the application stops.
     *
     * @param registry The registry CDI disposes of
     */
    public void close(@Disposes final ZenBpmClientRegistry registry) {
        registry.close();
    }

}
