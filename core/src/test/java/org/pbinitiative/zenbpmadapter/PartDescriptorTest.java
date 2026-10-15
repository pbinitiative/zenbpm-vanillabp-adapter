/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.spi.parts.PartKind;
import io.vanillabp.integration.spi.parts.VanillaBpParts;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The version descriptor the build fills into the core jar, which the platform reads at every
 * start to tell whether this adapter and the platform integration belong together.
 */
@ExtendWith(SuppressOutputExtension.class)
class PartDescriptorTest {

    /**
     * The platform finds the descriptor by the adapter type, letter for letter.
     */
    private static final String DESCRIPTOR = "/%s".formatted(VanillaBpParts.descriptorOf(PartKind.ADAPTER,
            ZenBpmAdapter.ADAPTER_TYPE));

    @Test
    @DisplayName("The platform finds nothing to say about the descriptor of this build")
    void thePlatformFindsNothingToSayAboutThisBuild() throws IOException {
        assertEquals(Optional.empty(),
                VanillaBpParts.judge(PartKind.ADAPTER, ZenBpmAdapter.ADAPTER_TYPE, descriptor()));
    }

    @Test
    @DisplayName("The descriptor names the artifact an application changes, and the platform version it was built against")
    void theDescriptorNamesTheArtifactAndThePlatform() throws IOException {
        final var descriptor = descriptor();
        assertAll(
                () -> assertEquals("org.pbinitiative.zenbpm:zenbpm-vanillabp-adapter",
                        descriptor.getProperty(VanillaBpParts.PART_ARTIFACT_KEY)),
                () -> assertEquals(VanillaBpParts.platformVersion(),
                        descriptor.getProperty(VanillaBpParts.PLATFORM_VERSION_KEY)));
    }

    private static Properties descriptor() throws IOException {
        try (var stream = PartDescriptorTest.class.getResourceAsStream(DESCRIPTOR)) {
            assertNotNull(stream, "%s is missing from the core jar".formatted(DESCRIPTOR));
            final var descriptor = new Properties();
            descriptor.load(stream);
            return descriptor;
        }
    }
}
