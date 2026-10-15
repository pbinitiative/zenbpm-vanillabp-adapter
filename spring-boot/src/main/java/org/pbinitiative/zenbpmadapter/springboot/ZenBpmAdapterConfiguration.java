/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.springboot;

import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

/**
 * Announces the ZenBPM adapter to the VanillaBP Spring Boot integration. The platform validates the
 * configured adapter types, and this class must be built before that, which is why it says
 * {@code before = SpringBootMigrationAdapterAutoConfiguration.class}.
 * <p>
 * It declares nothing else: Spring builds it very early while the context starts, and other beans
 * would make that harder.
 */
@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)
public class ZenBpmAdapterConfiguration extends AdapterConfigurationBase {

    /**
     * Built by Spring Boot while it reads the auto-configuration imports.
     */
    public ZenBpmAdapterConfiguration() {
    }

    @Override
    public String getAdapterType() {
        return ZenBpmAdapter.ADAPTER_TYPE;
    }

}
