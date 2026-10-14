/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.ValidationResult;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The execution model of a ZenBPM adapter instance: what <code>worker-threads</code> resolves to,
 * and what an unusable value says.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmExecutionModelTest {

    private static final String ADAPTER_ID = "orders";

    @Test
    @DisplayName("Nothing configured means four platform threads")
    void defaultIsFourPlatformThreads() {
        final var model = ZenBpmExecutionModel.resolve(ADAPTER_ID, null, null);

        assertFalse(model.virtual(), "the default does not use virtual threads");
        assertEquals(4, model.slots());
        assertEquals(model, ZenBpmExecutionModel.resolve(ADAPTER_ID, "  ", null), "a blank value is no value");
        assertEquals("4 platform threads", model.describe());
    }

    @Test
    @DisplayName("A number configures platform threads")
    void numberConfiguresPlatformThreads() {
        final var model = ZenBpmExecutionModel.resolve(ADAPTER_ID, " 12 ", null);

        assertFalse(model.virtual());
        assertEquals(12, model.slots());
    }

    @Test
    @DisplayName("'virtual' takes the bound the platform mode would have used")
    void virtualDefaultsToThePlatformBound() {
        final var model = ZenBpmExecutionModel.resolve(ADAPTER_ID, "virtual", null);

        assertTrue(model.virtual());
        assertEquals(ZenBpmExecutionModel.DEFAULT_SLOTS, model.slots(),
                "switching the mode changes how threads are made, not how much runs at once");
    }

    @Test
    @DisplayName("'virtual' with a bound of its own")
    void virtualWithConfiguredBound() {
        final var model = ZenBpmExecutionModel.resolve(ADAPTER_ID, "VIRTUAL", 16);

        assertTrue(model.virtual());
        assertEquals(16, model.slots());
        assertEquals("virtual threads, bounded at 16", model.describe());
    }

    @Test
    @DisplayName("Zero threads end the boot naming the property and what to size it against")
    void zeroThreadsFailsGuiding() {
        final var exception = assertThrows(IllegalStateException.class, () -> ZenBpmExecutionModel
                .resolve(ADAPTER_ID, "0", null));

        assertTrue(exception
                .getMessage()
                .contains("'vanillabp.adapters.orders.worker-threads: 0'"), exception.getMessage());
        assertTrue(exception
                .getMessage()
                .contains("connection pool"), exception.getMessage());
    }

    @Test
    @DisplayName("A negative number ends the boot the same way")
    void negativeThreadsFailsGuiding() {
        final var exception = assertThrows(IllegalStateException.class, () -> ZenBpmExecutionModel
                .resolve(ADAPTER_ID, "-4", null));

        assertTrue(exception
                .getMessage()
                .contains("'vanillabp.adapters.orders.worker-threads: -4'"), exception.getMessage());
    }

    @Test
    @DisplayName("An unparseable value names both accepted forms")
    void unparseableValueNamesBothForms() {
        final var exception = assertThrows(IllegalStateException.class, () -> ZenBpmExecutionModel
                .resolve(ADAPTER_ID, "many", null));

        assertTrue(exception
                .getMessage()
                .contains("vanillabp.adapters.orders.worker-threads: 4 "), exception.getMessage());
        assertTrue(exception
                .getMessage()
                .contains("vanillabp.adapters.orders.worker-threads: virtual "), exception.getMessage());
        assertTrue(exception
                .getMessage()
                .contains("'vanillabp.adapters.orders.worker-threads-bound'"), exception.getMessage());
    }

    @Test
    @DisplayName("A number too large for an int is called too large, not a word")
    void aNumberTooLargeIsCalledTooLarge() {
        final var exception = assertThrows(IllegalStateException.class, () -> ZenBpmExecutionModel
                .resolve(ADAPTER_ID, "99999999999", null));

        assertTrue(exception
                .getMessage()
                .contains("'vanillabp.adapters.orders.worker-threads: 99999999999', which is too large a number"),
                exception.getMessage());
    }

    @Test
    @DisplayName("A bound without the virtual mode ends the boot rather than being ignored")
    void boundWithoutVirtualFails() {
        final var exception = assertThrows(IllegalStateException.class, () -> ZenBpmExecutionModel
                .resolve(ADAPTER_ID, "4", 8));

        assertTrue(exception
                .getMessage()
                .contains("configures 'vanillabp.adapters.orders.worker-threads-bound'"), exception.getMessage());
        assertTrue(exception
                .getMessage()
                .contains("set 'vanillabp.adapters.orders.worker-threads: virtual'"), exception.getMessage());
        assertTrue(ZenBpmExecutionModel
                .resolve(ADAPTER_ID, "virtual", 8)
                .virtual(), "the way out the message names works");
    }

    @Test
    @DisplayName("A bound beside the default platform threads names the default")
    void boundBesideTheDefaultNamesTheDefault() {
        final var exception = assertThrows(IllegalStateException.class, () -> ZenBpmExecutionModel
                .resolve(ADAPTER_ID, null, 8));

        assertTrue(exception
                .getMessage()
                .contains("its 'vanillabp.adapters.orders.worker-threads' is '4'"), exception.getMessage());
    }

    @Test
    @DisplayName("A bound below one ends the boot naming the property")
    void boundBelowOneFails() {
        final var exception = assertThrows(IllegalStateException.class, () -> ZenBpmExecutionModel
                .resolve(ADAPTER_ID, "virtual", 0));

        assertTrue(exception
                .getMessage()
                .contains("'vanillabp.adapters.orders.worker-threads-bound: 0'"), exception.getMessage());
    }

    @Test
    @DisplayName("A configuration hands on the bound as written, so a bound beside platform threads is refused")
    void aConfigurationHandsOnTheBoundAsWritten() {
        final var configuration = new ZenBpmAdapterConfiguration();
        assertEquals(ZenBpmExecutionModel.DEFAULT, ZenBpmExecutionModel.resolve(ADAPTER_ID, configuration));

        configuration.setWorkerThreads("virtual");
        assertEquals(new ZenBpmExecutionModel(true, 4), ZenBpmExecutionModel.resolve(ADAPTER_ID, configuration));

        configuration.setWorkerThreads("8");
        configuration.setWorkerThreadsBound(8);
        assertEquals(8, configuration.workerThreadsBoundInUse(), "the bound in use would hide the defect");
        assertThrows(IllegalStateException.class, () -> ZenBpmExecutionModel.resolve(ADAPTER_ID, configuration));
    }

    @ParameterizedTest(name = "worker-threads: {0}, worker-threads-bound: {1}")
    @MethodSource("workerThreadValues")
    @DisplayName("The model refuses exactly the values the startup validation refuses")
    void theModelRefusesWhatTheValidationRefuses(
        final String workerThreads,
        final Integer workerThreadsBound
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress("http://localhost:8080");
        configuration.setWorkerThreads(workerThreads);
        configuration.setWorkerThreadsBound(workerThreadsBound);
        final var validation = configuration.validate(ADAPTER_ID, false, false);

        final var complete = validation.kind() == ValidationResult.Kind.COMPLETE;
        try {
            final var model = ZenBpmExecutionModel.resolve(ADAPTER_ID, configuration);
            assertTrue(complete, "resolved to %s, but the validation said: %s".formatted(model, validation
                    .message()));
            assertEquals(configuration.workerThreadsBoundInUse(), model.slots(),
                    "the model and the configuration agree on the bound");
        } catch (final IllegalStateException e) {
            assertFalse(complete, "refused, but the validation found it complete: %s".formatted(e.getMessage()));
            assertTrue(validation.endsTheBoot(), "a value which cannot work ends the boot");
        }
    }

    static Stream<Arguments> workerThreadValues() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of("1", null),
                Arguments.of("12", null),
                Arguments.of("0", null),
                Arguments.of("-1", null),
                Arguments.of("many", null),
                Arguments.of("99999999999", null),
                Arguments.of("4", 4),
                Arguments.of(null, 2),
                Arguments.of("virtual", null),
                Arguments.of("Virtual", 1),
                Arguments.of("virtual", 0),
                Arguments.of("virtual", -3));
    }

}
