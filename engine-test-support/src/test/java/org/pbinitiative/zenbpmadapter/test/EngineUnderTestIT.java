/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The engine every integration test of this repository meets is the pinned one, and it is
 * ready when a test starts.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class EngineUnderTestIT {

    /**
     * How long a request to the started engine may take. The engine answers these in
     * milliseconds, so the limit only turns an engine which stopped answering into a failure.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The engine reports its commit shortened. A report shorter than git's own shortest form
     * would mean that the format of {@code /system/status} changed, and so short a prefix fits
     * too many commits to prove anything.
     */
    private static final int SHORTEST_REPORTED_COMMIT = 7;

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static final HttpClient HTTP = HttpClient
            .newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    @AfterAll
    static void closeTheClient() {
        HTTP.close();
    }

    @Test
    @DisplayName("The engine answers ready once the container is started")
    void theEngineAnswersReady() throws Exception {
        final var response = get("/system/health/ready");

        assertEquals(200, response.statusCode(), response.body());
    }

    /**
     * Compares with {@code zenbpm.image} of the root POM, handed in by failsafe, and not with
     * what {@link EngineUnderTest} read: a resource filtered from the wrong property would
     * otherwise agree with itself.
     */
    @Test
    @DisplayName("The container runs the pinned image, by digest")
    void theContainerRunsThePinnedImage() {
        final var pinned = System.getProperty("zenbpm.image");
        assertNotNull(pinned, "Failsafe hands in 'zenbpm.image', so run this test through Maven ('mvn verify').");
        final var image = ENGINE
                .getContainerInfo()
                .getConfig()
                .getImage();

        assertEquals(DockerImageName
                .parse(pinned)
                .asCanonicalNameString(), image);
        assertTrue(image.contains("@sha256:"), () -> "'%s' is not pinned by its digest".formatted(image));
    }

    /**
     * Compares commits and not versions: the version an engine built from {@code main} reports
     * is the last release's, the same for every build since, see decision 15 in the
     * repository's DECISIONS.md.
     */
    @Test
    @DisplayName("The engine reports the pinned commit")
    void theEngineReportsThePinnedCommit() throws Exception {
        final var response = get("/system/status");
        final var reported = new ObjectMapper()
                .readTree(response.body())
                .path("git")
                .path("commitId")
                .asText();

        assertTrue(
                (reported.length() >= SHORTEST_REPORTED_COMMIT) && EngineUnderTest
                        .pinnedCommit()
                        .startsWith(reported),
                () -> "The engine reports commit '%s', the pin is '%s'. The report has to be a prefix of the pin, at least %d characters long."
                        .formatted(reported, EngineUnderTest.pinnedCommit(), SHORTEST_REPORTED_COMMIT));
    }

    private static HttpResponse<String> get(final String path) throws IOException, InterruptedException {
        return HTTP
                .send(
                        HttpRequest
                                .newBuilder(URI.create("%s%s".formatted(ENGINE.restAddress(), path)))
                                .timeout(REQUEST_TIMEOUT)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

}
