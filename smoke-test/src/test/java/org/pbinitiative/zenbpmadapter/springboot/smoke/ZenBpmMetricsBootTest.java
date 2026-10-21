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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.observability.MicrometerZenBpmMetrics;
import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.ClassUtils;

import io.micrometer.core.instrument.MeterRegistry;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The adapter's meters on Spring Boot, without an engine: where Micrometer is on the classpath, the
 * adapter's meter binder is a bean, every deployment service measures into it, and each adapter id
 * publishes its eleven meters under its own tag; where it is not, the application boots as it does
 * with it, minus the binder, and the deployment services measure nothing. The application starts
 * inside the test, so the output of its start is part of what the test captured.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmMetricsBootTest {

    private static final String[] TWO_ADAPTER_IDS = {
            "vanillabp.prioritized-adapters=zenbpm-new,zenbpm-old", "vanillabp.adapters.zenbpm-new.type=zenbpm", "vanillabp.adapters.zenbpm-new.rest-address=http://new-engine:8080", "vanillabp.adapters.zenbpm-old.type=zenbpm", "vanillabp.adapters.zenbpm-old.rest-address=http://old-engine:8080"
    };

    @Test
    @DisplayName("With Micrometer, every deployment service measures into the adapter's meter binder, which Spring Boot applies to its registry, and each of two adapter ids publishes the eleven meters there under its own tag")
    void withMicrometerEachAdapterIdPublishesItsMeters() {
        try (var context = run(null, TWO_ADAPTER_IDS)) {
            final var metrics = context.getBean(MicrometerZenBpmMetrics.class);
            // the registry Spring Boot's metrics build, which apply every meter binder bean to it
            final var registry = context.getBean(MeterRegistry.class);

            final var meterNamesByAdapterId = new TreeMap<String, List<String>>();
            registry
                    .getMeters()
                    .stream()
                    .filter(meter -> meter
                            .getId()
                            .getName()
                            .startsWith("vanillabp.zenbpm."))
                    .forEach(meter -> meterNamesByAdapterId
                            .computeIfAbsent(meter
                                    .getId()
                                    .getTag(ZenBpmMetrics.TAG_ADAPTER), adapterId -> new ArrayList<>())
                            .add(meter
                                    .getId()
                                    .getName()));
            meterNamesByAdapterId
                    .values()
                    .forEach(Collections::sort);
            assertAll(
                    () -> assertSame(metrics, deploymentServiceOf(context, "zenbpm-new").getMetrics()),
                    () -> assertSame(metrics, deploymentServiceOf(context, "zenbpm-old").getMetrics()),
                    () -> assertEquals(List.of("zenbpm-new", "zenbpm-old"), List.copyOf(meterNamesByAdapterId
                            .keySet())),
                    () -> assertEquals(meterNamesByAdapterId.get("zenbpm-new"), meterNamesByAdapterId.get(
                            "zenbpm-old"), "the two sets differ in the tag only"),
                    () -> assertEquals(11, meterNamesByAdapterId
                            .get("zenbpm-new")
                            .size(), meterNamesByAdapterId::toString));
        }
    }

    @Test
    @DisplayName("Without Micrometer the application boots unchanged: the same adapter ids, services and factories, the startup block, no meter binder, and deployment services which measure nothing")
    void withoutMicrometerTheApplicationBootsUnchanged(final CapturedOutput output) {
        final Map<String, Object> withMicrometer;
        try (var context = run(null, TWO_ADAPTER_IDS)) {
            withMicrometer = whatTheAdapterRegistered(context);
        }

        // hides Micrometer from the conditions only: ZenBpmWithoutMicrometerBootTest boots on a
        // classpath which lacks it
        try (var context = run(new FilteredClassLoader("io.micrometer."), TWO_ADAPTER_IDS)) {
            final var withoutMicrometer = whatTheAdapterRegistered(context);
            final var log = output.getAllOfThisTest();

            assertAll(
                    () -> assertFalse(ClassUtils.isPresent(MeterRegistry.class.getName(), context.getClassLoader()),
                            "the application must not see Micrometer"),
                    () -> assertEquals(withMicrometer, withoutMicrometer),
                    () -> assertEquals(0, context.getBeanNamesForType(ZenBpmMetrics.class).length),
                    () -> assertSame(ZenBpmMetrics.NONE, deploymentServiceOf(context, "zenbpm-new").getMetrics()),
                    () -> assertSame(ZenBpmMetrics.NONE, deploymentServiceOf(context, "zenbpm-old").getMetrics()),
                    () -> assertTrue(StartupBlock.isWritten(log), log));
        }
    }

    @Test
    @DisplayName("An application which brings its own ZenBpmMetrics gets every deployment service measuring into it, and the adapter adds no meter binder beside it")
    void anApplicationsOwnMetricsReplaceTheBinder() {
        final var own = new ZenBpmMetrics() {
        };
        final var application = new SpringApplicationBuilder(SmokeTestApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(context -> ((GenericApplicationContext) context).registerBean("ownMetrics",
                        ZenBpmMetrics.class, () -> own));
        try (var context = application.run(Stream
                .of(TWO_ADAPTER_IDS)
                .map("--%s"::formatted)
                .toArray(String[]::new))) {
            assertAll(
                    () -> assertEquals(0, context.getBeanNamesForType(MicrometerZenBpmMetrics.class).length),
                    () -> assertSame(own, deploymentServiceOf(context, "zenbpm-new").getMetrics()),
                    () -> assertSame(own, deploymentServiceOf(context, "zenbpm-old").getMetrics()));
        }
    }

    // the adapter's beans by name and what tells them apart, all of it but the meter binder
    private static Map<String, Object> whatTheAdapterRegistered(final ConfigurableApplicationContext context) {
        final var registered = new TreeMap<String, Object>();
        context
                .getBeansOfType(ZenBpmDeploymentService.class)
                .forEach((
                    name,
                    deploymentService) -> registered.put(name, deploymentService.getAdapterId()));
        context
                .getBeansOfType(ZenBpmProcessService.class)
                .forEach((
                    name,
                    processService) -> registered.put(name, processService.getAdapterId()));
        registered.put("factories", context
                .getBean(ZenBpmClientRegistry.class)
                .factories()
                .stream()
                .map(factory -> "%s configured=%s".formatted(factory.getAdapterId(), factory.isConfigured()))
                .toList());
        registered.put("configured", context
                .getBean(ZenBpmClientRegistry.class)
                .factories()
                .stream()
                .allMatch(ZenBpmClientFactory::isConfigured));
        return registered;
    }

    private static ZenBpmDeploymentService deploymentServiceOf(
        final ConfigurableApplicationContext context,
        final String adapterId
    ) {
        return context.getBean("ZenBpm_DeploymentService_%s".formatted(adapterId), ZenBpmDeploymentService.class);
    }

    private static ConfigurableApplicationContext run(
        final ClassLoader classLoader,
        final String... properties
    ) {
        final var application = new SpringApplicationBuilder(SmokeTestApplication.class).web(WebApplicationType.NONE);
        if (classLoader != null) {
            application.resourceLoader(new DefaultResourceLoader(classLoader));
        }
        return application.run(Stream
                .of(properties)
                .map("--%s"::formatted)
                .toArray(String[]::new));
    }

}
