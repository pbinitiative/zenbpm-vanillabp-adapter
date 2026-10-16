/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.quarkus.runtime;

import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

/**
 * Builds the {@link ZenBpmClientRegistry} while the application starts, so the configuration of
 * every adapter id is validated at startup and not at the first use of the adapter. CDI builds a
 * bean only when something first asks for it.
 * <p>
 * The observer has the default priority, so it runs before the platform's deployment runner, whose
 * observer comes later on purpose. The runner asks for the deployment services, and so for the
 * registry, in the same start; the validation does not depend on that, because nothing in the
 * platform's contract promises when the runner asks.
 */
@ApplicationScoped
public class ZenBpmStartupObserver {

    /**
     * Built by CDI, which needs a constructor without parameters to proxy an application-scoped
     * bean.
     */
    public ZenBpmStartupObserver() {
    }

    void onStart(
        @Observes final StartupEvent event,
        final Instance<ZenBpmClientRegistry> registry
    ) {
        // resolving the instance runs the producer, and with it the validation
        registry.get();
    }

}
