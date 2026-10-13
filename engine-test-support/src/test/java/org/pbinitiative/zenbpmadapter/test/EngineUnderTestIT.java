/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The engine every integration test of this repository meets is the pinned one, and it is
 * ready when a test starts.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
public class EngineUnderTestIT {

  @Container
  private static final EngineUnderTest engine = new EngineUnderTest();

  private static final HttpClient http = HttpClient.newHttpClient();

  @Test
  @DisplayName("The engine answers ready once the container is started")
  public void theEngineAnswersReady() throws Exception {

    final var response = get("/system/health/ready");

    assertEquals(200, response.statusCode(), response.body());

  }

  @Test
  @DisplayName("The container runs the pinned image, by digest")
  public void theContainerRunsThePinnedImage() {

    final var image = engine
        .getContainerInfo()
        .getConfig()
        .getImage();

    assertEquals(EngineUnderTest.image().asCanonicalNameString(), image);
    assertTrue(image.contains("@sha256:"), () -> "'%s' is not pinned by its digest".formatted(image));

  }

  /**
   * Compares commits and not versions: the version an engine built from {@code main} reports
   * is the last release's, the same for every build since, see decision 15 in the
   * repository's DECISIONS.md.
   */
  @Test
  @DisplayName("The engine reports the pinned commit")
  public void theEngineReportsThePinnedCommit() throws Exception {

    final var response = get("/system/status");
    final var reported = new ObjectMapper()
        .readTree(response.body())
        .path("git")
        .path("commitId")
        .asText();

    assertTrue(
        !reported.isEmpty() && EngineUnderTest
            .pinnedCommit()
            .startsWith(reported),
        () -> "The engine reports commit '%s', the pin is '%s'"
            .formatted(reported, EngineUnderTest.pinnedCommit()));

  }

  private static HttpResponse<String> get(
      final String path) throws IOException, InterruptedException {

    return http
        .send(
            HttpRequest
                .newBuilder(URI.create("%s%s".formatted(engine.restAddress(), path)))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

  }

}
