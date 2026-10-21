/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.netty.util.Version;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Netty the Quarkus modules run the job stream on is the one the Quarkus platform manages, see
 * decision 61 in the repository's DECISIONS.md: Quarkus' native-image substitutions are written
 * against exactly that version, and the Spring Boot BOM the root POM imports first would lift it.
 */
@ExtendWith(SuppressOutputExtension.class)
class QuarkusNettyTest {

    private static final Pattern MANAGED_NETTY = Pattern
            .compile("<artifactId>netty-common</artifactId>\\s*<version>([^<]+)</version>");

    @Test
    @DisplayName("The Netty on the classpath is the pinned one, not the one of the Spring Boot BOM")
    void theNettyOnTheClasspathIsThePinnedOne() {
        final var running = Version
                .identify()
                .get("netty-common")
                .artifactVersion();

        assertEquals(System.getProperty("netty.version.quarkus"), running);
    }

    @Test
    @DisplayName("The pinned Netty is the one the Quarkus BOM manages")
    void thePinnedNettyIsTheOneOfTheQuarkusBom() throws IOException {
        final var bom = Files.readString(Path.of(System.getProperty("quarkus.bom")));
        final var managed = MANAGED_NETTY.matcher(bom);

        assertTrue(managed.find(), "the Quarkus BOM manages no netty-common");
        assertEquals(managed.group(1), System.getProperty("netty.version.quarkus"), """
                'netty.version.quarkus' in the root POM moves with 'quarkus.version': set it to the Netty \
                the Quarkus BOM manages.""");
    }

}
