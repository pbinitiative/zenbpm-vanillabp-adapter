/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.springboot.client;

import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmStartupValidation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.startup.StartupReport;

/**
 * Builds the {@link ZenBpmClientRegistry}, the one owner of every ZenBPM client of the application,
 * while the context starts, so the configuration of every adapter id is validated at startup and
 * not at its first use. {@link ZenBpmStartupValidation} does the work: it validates each adapter
 * id, reports what it found into the platform's startup report, ends the start where a
 * configuration cannot work, and builds a factory per adapter id. The registry is a bean, so
 * Spring closes it, and with it every client, when the application stops.
 * <p>
 * The adapter ids are the platform's: those its core properties name for type
 * <code>zenbpm</code>, the ids the core derives included, which are the ids
 * {@code ZenBpmAdapterBeanRegistrar} registers beans for. The overlay
 * {@link VanillaBpZenBpmProperties} is only asked about them.
 */
@AutoConfiguration(after = SpringBootMigrationAdapterAutoConfiguration.class)
@EnableConfigurationProperties(VanillaBpZenBpmProperties.class)
public class ZenBpmClientAutoConfiguration {

    /**
     * The property which names the application, the first part of a derived client id.
     */
    static final String APPLICATION_NAME_PROPERTY = "spring.application.name";

    /**
     * Built by Spring Boot while it reads the auto-configuration imports.
     */
    public ZenBpmClientAutoConfiguration() {
    }

    /**
     * Validates every adapter id of type <code>zenbpm</code> and builds the registry of their
     * factories.
     *
     * @param coreProperties The platform's validated properties, which name the adapter ids, their
     *            priorities and their deployment-failure policies
     * @param overlay The adapter's own view of the configuration tree
     * @param environment Where the application's name is read
     * @param startupReport The platform's startup report; without one, the findings go to the log
     * @return The registry, with a factory per adapter id
     */
    @Bean(destroyMethod = "close")
    public ZenBpmClientRegistry zenBpmClientRegistry(
        final MigrationAdapterProperties coreProperties,
        final VanillaBpZenBpmProperties overlay,
        final Environment environment,
        final ObjectProvider<StartupReport> startupReport
    ) {
        return ZenBpmStartupValidation.registryOf(coreProperties.adapterIdsOfType(ZenBpmAdapter.ADAPTER_TYPE),
                overlay::configurationOf, coreProperties::isFirstPriorityAnywhere, adapterId -> coreProperties
                        .getDeploymentFailureFor(adapterId) == DeploymentFailurePolicy.WARN,
                coreProperties.resolvedDeliveryRetention(),
                () -> environment
                        .getProperty(APPLICATION_NAME_PROPERTY),
                startupReport.getIfAvailable());
    }

}
