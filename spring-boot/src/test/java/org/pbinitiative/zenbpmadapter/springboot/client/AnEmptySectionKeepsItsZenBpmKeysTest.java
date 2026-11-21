/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.springboot.ZenBpmAdapterConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;

/**
 * A section of the <code>vanillabp</code> tree which a YAML file writes as <code>{}</code> keeps the
 * keys of the ZenBPM adapter which another property source sets below it. Spring Boot reads the
 * empty map as an empty value at the section's own name, and a plain binding then never looks at
 * the keys below that name. This holds for each section the overlay reads keys from: an adapter id,
 * its section at a workflow module, a workflow and a task, and the sections of a workflow module, a
 * workflow and a task themselves. See {@link ZenBpmEmptySections}.
 */
@ExtendWith(SuppressOutputExtension.class)
class AnEmptySectionKeepsItsZenBpmKeysTest {

    private static final String SECTIONS = "empty-sections/sections.yaml";

    private static final String MODULES = "--vanillabp.workflow-modules.";

    private static final String[] COMMAND_LINE = {
            "--vanillabp.adapters.orders.type=zenbpm", "--vanillabp.adapters.orders.rest-address=http://engine:8080", MODULES
                    + "shop.adapters.orders.retry-backoff=PT2S", MODULES
                            + "shop.workflows.Order.adapters.orders.retry-backoff=PT3S", MODULES
                                    + "shop.workflows.Order.tasks.ship.adapters.orders.retry-backoff=PT4S", MODULES
                                            + "shop.workflows.Order.tasks.pack.adapters.orders.retry-backoff=PT5S", MODULES
                                                    + "shop.workflows.Invoice.adapters.orders.retry-backoff=PT6S", MODULES
                                                            + "crm.adapters.orders.retry-backoff=PT7S"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WorkflowModulesOfTheSections.class)
            .withConfiguration(AutoConfigurations.of(ZenBpmAdapterConfiguration.class,
                    SpringBootMigrationAdapterAutoConfiguration.class, ZenBpmClientAutoConfiguration.class));

    @Test
    @DisplayName("Spring Boot reads an empty YAML section as an empty value, and a plain binding loses the keys below it")
    void aPlainBindingLosesTheKeysBelowAnEmptySection() {
        // the cause: where Spring Boot stops doing this, ZenBpmEmptySections is not needed any more
        assertEquals("", String.valueOf(yamlFile().getProperty("vanillabp.adapters.orders")));
        final var environment = new StandardEnvironment();
        addSections(environment);

        final var plain = Binder
                .get(environment)
                .bind("vanillabp", VanillaBpZenBpmProperties.class)
                .orElseGet(VanillaBpZenBpmProperties::new);

        assertAll(
                () -> assertNull(plain
                        .configurationOf("orders")
                        .getRestAddress()),
                () -> assertEquals(Optional.empty(), plain.retryBackoffFor("shop", "Order", "ship", "orders")));
    }

    @Test
    @DisplayName("A booted application keeps the keys of the ZenBPM adapter below every section written as {}")
    void aBootedApplicationKeepsTheKeysBelowEveryEmptySection() {
        contextRunner
                .withInitializer(AnEmptySectionKeepsItsZenBpmKeysTest::addSectionsToTheContext)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    final var overlay = context.getBean(VanillaBpZenBpmProperties.class);

                    assertAll(
                            () -> assertEquals("http://engine:8080", overlay
                                    .configurationOf("orders")
                                    .getRestAddress()),
                            () -> assertEquals(Optional.of(Duration.ofSeconds(2)),
                                    overlay.retryBackoffFor("shop", "Returns", "check", "orders")),
                            () -> assertEquals(Optional.of(Duration.ofSeconds(3)),
                                    overlay.retryBackoffFor("shop", "Order", "bill", "orders")),
                            () -> assertEquals(Optional.of(Duration.ofSeconds(4)),
                                    overlay.retryBackoffFor("shop", "Order", "ship", "orders")),
                            () -> assertEquals(Optional.of(Duration.ofSeconds(5)),
                                    overlay.retryBackoffFor("shop", "Order", "pack", "orders")),
                            () -> assertEquals(Optional.of(Duration.ofSeconds(6)),
                                    overlay.retryBackoffFor("shop", "Invoice", "send", "orders")),
                            () -> assertEquals(Optional.of(Duration.ofSeconds(7)),
                                    overlay.retryBackoffFor("crm", "Lead", "call", "orders")));
                });
    }

    private static void addSectionsToTheContext(final ConfigurableApplicationContext context) {
        addSections(context.getEnvironment());
    }

    /** Puts the YAML file into the environment and the command line above it. */
    private static void addSections(final ConfigurableEnvironment environment) {
        environment
                .getPropertySources()
                .addFirst(yamlFile());
        environment
                .getPropertySources()
                .addFirst(new SimpleCommandLinePropertySource(COMMAND_LINE));
    }

    private static PropertySource<?> yamlFile() {
        try {
            return new YamlPropertySourceLoader()
                    .load(SECTIONS, new ClassPathResource(SECTIONS))
                    .getFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The workflow modules the sections name. Usually a scan of the classpath finds them, which the
     * binding under test does not depend on.
     */
    @Configuration(proxyBeanMethods = false)
    static class WorkflowModulesOfTheSections {

        @Bean
        WorkflowModules workflowModules() {
            return new WorkflowModules(List.of(workflowModule("shop"), workflowModule("crm")));
        }

        private static WorkflowModule workflowModule(final String id) {
            return WorkflowModule
                    .builder()
                    .id(id)
                    .sourceUri("file:/%s".formatted(id))
                    .build();
        }

    }

}
