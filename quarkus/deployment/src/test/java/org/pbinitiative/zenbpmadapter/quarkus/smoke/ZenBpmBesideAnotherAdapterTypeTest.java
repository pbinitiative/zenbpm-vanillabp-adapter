/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * An adapter id of type <code>zenbpm</code> beside one of another type, the setup of a migration
 * from another BPMS to ZenBPM: the platform's BPMS double stands in for the other one. Both write
 * a key of their own under <code>vanillabp.adapters.</code>, and the start passes the check for
 * unknown keys, because each overlay knows its own keys. Each type has its list of services, and
 * the ZenBPM adapter holds nothing of the other id. That check sees keys, not ids: the mapping of
 * this adapter takes any adapter id, so a <code>rest-address</code> below the id of the other type
 * passes it too, and nobody reads it.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmBesideAnotherAdapterTypeTest {

    private static final String A_KEY_OF_THIS_ADAPTER_BELOW_THE_OTHER_ID = "vanillabp.adapters.dummy.rest-address";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withTheBpmsDouble()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm,dummy")
            .overrideConfigKey("vanillabp.adapters.zenbpm.type", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey("vanillabp.adapters.dummy.type", "dummy")
            .overrideConfigKey("vanillabp.adapters.dummy.test", "7")
            .overrideConfigKey(A_KEY_OF_THIS_ADAPTER_BELOW_THE_OTHER_ID, "http://localhost:8081")
            .assertLogRecords(records -> {
                final var log = SmokeApplication.logOf(records);
                assertAll(
                        () -> assertTrue(StartupBlock.isWritten(log), log),
                        () -> assertEquals("", StartupBlock.configuration(log), log));
            });

    @Inject
    @Any
    Instance<List<MigratableProcessService<Object>>> processServices;

    @Inject
    @Any
    Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("Each adapter type has a list of its own, and the ZenBPM adapter's holds only its adapter id")
    void eachAdapterTypeHasAListOfItsOwn() {
        assertAll(
                () -> assertEquals(List.of(List.of("dummy"), List.of("zenbpm")), adapterIdsByList(deploymentServices)),
                () -> assertEquals(List.of(List.of("dummy"), List.of("zenbpm")), adapterIdsByList(processServices)),
                () -> assertTrue(listOf(deploymentServices, "zenbpm")
                        .stream()
                        .allMatch(ZenBpmDeploymentService.class::isInstance)),
                () -> assertTrue(listOf(processServices, "zenbpm")
                        .stream()
                        .allMatch(ZenBpmProcessService.class::isInstance)));
    }

    @Test
    @DisplayName("The registry holds a factory for the ZenBPM adapter id only")
    void theRegistryHoldsOnlyItsOwnAdapterId() {
        assertEquals(List.of("zenbpm"), registry
                .factories()
                .stream()
                .map(ZenBpmClientFactory::getAdapterId)
                .toList());
    }

    @Test
    @DisplayName("A key of the ZenBPM adapter below the id of the other type is not refused, and nobody reads it")
    void aKeyOfThisAdapterBelowTheOtherTypesIdIsNotRefused() {
        // the application of this class started with the key set
        assertAll(
                () -> assertEquals("http://localhost:8081", ConfigProvider
                        .getConfig()
                        .getValue(A_KEY_OF_THIS_ADAPTER_BELOW_THE_OTHER_ID, String.class)),
                () -> assertEquals(List.of("zenbpm"), registry
                        .factories()
                        .stream()
                        .map(ZenBpmClientFactory::getAdapterId)
                        .toList()));
    }

    // the adapter ids of every list, one list per adapter type, sorted so the order is fixed
    private static List<List<String>> adapterIdsByList(final Instance<? extends List<?>> lists) {
        return lists
                .stream()
                .filter(Objects::nonNull)
                .map(list -> list
                        .stream()
                        .map(ZenBpmBesideAnotherAdapterTypeTest::adapterIdOf)
                        .toList())
                .sorted(Comparator.comparing(List::toString))
                .toList();
    }

    // the list of the platform's lists which holds the adapter id
    private static List<?> listOf(
        final Instance<? extends List<?>> lists,
        final String adapterId
    ) {
        return lists
                .stream()
                .filter(Objects::nonNull)
                .filter(list -> list
                        .stream()
                        .anyMatch(service -> adapterId.equals(adapterIdOf(service))))
                .findFirst()
                .orElseThrow();
    }

    private static String adapterIdOf(final Object service) {
        return service instanceof AdapterDeploymentService<?, ?> deploymentService
                ? deploymentService.getAdapterId()
                : ((MigratableProcessService<?>) service).getAdapterId();
    }

}
