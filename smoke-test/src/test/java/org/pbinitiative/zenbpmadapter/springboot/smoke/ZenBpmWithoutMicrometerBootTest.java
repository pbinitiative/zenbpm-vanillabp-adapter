/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.util.ClassUtils;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application whose classpath lacks Micrometer, as one which never brought it: the build runs
 * this class in an execution of its own, which leaves Micrometer out. So a class of the adapter
 * which loads a Micrometer type outside the condition of its meters fails this boot, which a class
 * loader hiding Micrometer from the conditions would not show.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmWithoutMicrometerBootTest {

    @Test
    @DisplayName("An application without Micrometer on its classpath boots: both adapter ids get their services, no meter binder, deployment services which measure nothing, and the startup block")
    void anApplicationWithoutMicrometerBoots(final CapturedOutput output) {
        final var application = new SpringApplicationBuilder(SmokeTestApplication.class).web(WebApplicationType.NONE);
        try (var context = application.run("--vanillabp.prioritized-adapters=zenbpm-new,zenbpm-old",
                "--vanillabp.adapters.zenbpm-new.type=zenbpm",
                "--vanillabp.adapters.zenbpm-new.rest-address=http://new-engine:8080",
                "--vanillabp.adapters.zenbpm-old.type=zenbpm",
                "--vanillabp.adapters.zenbpm-old.rest-address=http://old-engine:8080")) {
            final var log = output.getAllOfThisTest();

            assertAll(
                    () -> assertFalse(ClassUtils.isPresent("io.micrometer.core.instrument.MeterRegistry", null),
                            "the classpath of this test must lack Micrometer"),
                    () -> assertEquals(2, context.getBeansOfType(ZenBpmProcessService.class).size()),
                    () -> assertEquals(0, context.getBeanNamesForType(ZenBpmMetrics.class).length),
                    () -> assertSame(ZenBpmMetrics.NONE, context
                            .getBean("ZenBpm_DeploymentService_zenbpm-new", ZenBpmDeploymentService.class)
                            .getMetrics()),
                    () -> assertSame(ZenBpmMetrics.NONE, context
                            .getBean("ZenBpm_DeploymentService_zenbpm-old", ZenBpmDeploymentService.class)
                            .getMetrics()),
                    () -> assertTrue(StartupBlock.isWritten(log), log));
        }
    }

}
