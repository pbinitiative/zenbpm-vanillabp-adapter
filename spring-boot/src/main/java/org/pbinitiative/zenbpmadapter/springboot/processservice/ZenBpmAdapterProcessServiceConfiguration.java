/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.springboot.processservice;

import org.pbinitiative.zenbpmadapter.springboot.ZenBpmAdapterBeanRegistrar;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Wires the process service and the deployment service of the ZenBPM adapter: one bean of each per
 * configured adapter id of type <code>zenbpm</code>, registered by the imported
 * {@link ZenBpmAdapterBeanRegistrar}. Several ids of one type are the setup of a migration, for
 * example from one engine to another.
 */
@AutoConfiguration
@Import(ZenBpmAdapterBeanRegistrar.class)
public class ZenBpmAdapterProcessServiceConfiguration {

    /**
     * Built by Spring Boot while it reads the auto-configuration imports.
     */
    public ZenBpmAdapterProcessServiceConfiguration() {
    }

}
