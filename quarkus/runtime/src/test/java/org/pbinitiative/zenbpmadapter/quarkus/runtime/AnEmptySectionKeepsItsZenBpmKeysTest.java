/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.yaml.YamlConfigSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A section of the <code>vanillabp</code> tree which a YAML file writes as <code>{}</code> keeps the
 * keys of the ZenBPM adapter which another source sets below it. On Quarkus nothing has to be done
 * for it: the YAML source of SmallRye Config turns <code>{}</code> into no property at all, and the
 * mapping finds the keys of a map by their names. This test holds that for each section the overlay
 * reads keys from: an adapter id, its section at a workflow module, a workflow and a task, and the
 * sections of a workflow module, a workflow and a task themselves. The Spring Boot twin of the same
 * name holds what that platform needs for it.
 * <p>
 * An environment variable is such a source as well, with one limit which is Quarkus's own: a
 * variable is written in capitals, so it cannot say how an id is spelt, and a section written as
 * <code>{}</code> does not tell it either.
 */
@ExtendWith(SuppressOutputExtension.class)
class AnEmptySectionKeepsItsZenBpmKeysTest {

    private static final String EVERY_SECTION_WRITTEN_EMPTY = """
            vanillabp:
              adapters:
                orders: {}
              workflow-modules:
                shop:
                  adapters:
                    orders: {}
                  workflows:
                    Order:
                      adapters:
                        orders: {}
                      tasks:
                        ship:
                          adapters:
                            orders: {}
                        pack: {}
                    Invoice: {}
                crm: {}
            """;

    private static final String MODULES = "vanillabp.workflow-modules.";

    private static final Map<String, String> KEYS_BELOW_THE_SECTIONS = Map.of(
            "vanillabp.adapters.orders.rest-address", "http://engine:8080",
            MODULES
                    + "shop.adapters.orders.retry-backoff",
            "PT2S",
            MODULES
                    + "shop.workflows.Order.adapters.orders.retry-backoff",
            "PT3S",
            MODULES
                    + "shop.workflows.Order.tasks.ship.adapters.orders.retry-backoff",
            "PT4S",
            MODULES
                    + "shop.workflows.Order.tasks.pack.adapters.orders.retry-backoff",
            "PT5S",
            MODULES
                    + "shop.workflows.Invoice.adapters.orders.retry-backoff",
            "PT6S",
            MODULES
                    + "crm.adapters.orders.retry-backoff",
            "PT7S");

    private static final int ORDINAL_OF_THE_KEYS = 300;

    private static final String MODULES_AS_A_VARIABLE = "VANILLABP_WORKFLOW_MODULES_";

    private static final Map<String, String> ENVIRONMENT_VARIABLES = Map.of(
            "VANILLABP_ADAPTERS_ORDERS_REST_ADDRESS", "http://engine:8080",
            MODULES_AS_A_VARIABLE
                    + "SHOP_ADAPTERS_ORDERS_RETRY_BACKOFF",
            "PT2S",
            MODULES_AS_A_VARIABLE
                    + "SHOP_WORKFLOWS_ORDER_TASKS_PACK_ADAPTERS_ORDERS_RETRY_BACKOFF",
            "PT5S",
            MODULES_AS_A_VARIABLE
                    + "CRM_ADAPTERS_ORDERS_RETRY_BACKOFF",
            "PT7S");

    @ParameterizedTest(name = "the YAML file at ordinal {0}, the keys at 300")
    @ValueSource(ints = {
            255, 400
    })
    @DisplayName("The keys below every section written as {} arrive, whichever source is stronger")
    void theKeysBelowEveryEmptySectionArrive(final int ordinalOfTheYamlFile) {
        final var overlay = new SmallRyeConfigBuilder()
                .withSources(
                        new YamlConfigSource("application.yaml", EVERY_SECTION_WRITTEN_EMPTY, ordinalOfTheYamlFile),
                        new PropertiesConfigSource(KEYS_BELOW_THE_SECTIONS, "another source", ORDINAL_OF_THE_KEYS))
                .withMapping(VanillaBpZenBpmProperties.class)
                .build()
                .getConfigMapping(VanillaBpZenBpmProperties.class);

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
    }

    @Test
    @DisplayName("Environment variables reach the keys below a section written as {} where its ids are in lower case")
    void environmentVariablesReachTheKeysBelowSectionsWithIdsInLowerCase() {
        final var overlay = new SmallRyeConfigBuilder()
                .withSources(
                        new YamlConfigSource("application.yaml", EVERY_SECTION_WRITTEN_EMPTY, 255),
                        new EnvConfigSource(ENVIRONMENT_VARIABLES, ORDINAL_OF_THE_KEYS))
                .withMapping(VanillaBpZenBpmProperties.class)
                .build()
                .getConfigMapping(VanillaBpZenBpmProperties.class);

        assertAll(
                () -> assertEquals("http://engine:8080", overlay
                        .configurationOf("orders")
                        .getRestAddress()),
                () -> assertEquals(Optional.of(Duration.ofSeconds(2)),
                        overlay.retryBackoffFor("shop", "Returns", "check", "orders")),
                () -> assertEquals(Optional.of(Duration.ofSeconds(7)),
                        overlay.retryBackoffFor("crm", "Lead", "call", "orders")),
                // the limit: 'Order: {}' is no property, so the variable names a workflow 'order'
                () -> assertEquals(Optional.of(Duration.ofSeconds(5)),
                        overlay.retryBackoffFor("shop", "order", "pack", "orders")),
                () -> assertEquals(Optional.of(Duration.ofSeconds(2)),
                        overlay.retryBackoffFor("shop", "Order", "pack", "orders")));
    }

}
