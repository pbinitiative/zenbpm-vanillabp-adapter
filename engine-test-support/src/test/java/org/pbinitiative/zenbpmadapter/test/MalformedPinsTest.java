/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The pin has to be a full commit and an image named by its digest. Any other form is refused
 * before an engine starts, and the message names the property of the root POM to correct.
 * No Docker is needed, because the check runs when the class is loaded.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MalformedPinsTest {

  /**
   * Loading the class checks the pin it was filtered with, so a malformed pin in the root POM
   * fails here first.
   */
  @Test
  @DisplayName("The pin of the root POM passes both checks")
  public void thePinOfTheRootPomIsAccepted() {

    assertEquals(40, EngineUnderTest
        .pinnedCommit()
        .length());
    assertTrue(EngineUnderTest
        .image()
        .asCanonicalNameString()
        .contains("@sha256:"));

  }

  @ParameterizedTest
  @ValueSource(strings = {
      "0123456789ab", "0123456789ABCDEF0123456789ABCDEF01234567", "0123456789abcdef0123456789abcdef01234567 ", "v1.8.0", "${zenbpm.commit}"
  })
  @DisplayName("A commit which is not 40 lower-case hexadecimal characters is refused")
  public void aCommitOfAnotherFormIsRefused(
      final String commit) {

    final var refusal = assertThrows(
        IllegalStateException.class,
        () -> EngineUnderTest.Pin.COMMIT.checked(commit));

    assertTrue(refusal
        .getMessage()
        .contains("'zenbpm.commit' of the root pom"), refusal.getMessage());

  }

  @ParameterizedTest
  @ValueSource(strings = {
      "ghcr.io/pbinitiative/zenbpm:dev", "ghcr.io/pbinitiative/zenbpm:dev@sha256:0000000000000000000000000000000000000000000000000000000000000000", "ghcr.io/pbinitiative/zenbpm@sha256:01234567", "ghcr.io/pbinitiative/zenbpm"
  })
  @DisplayName("An image which is not named by its digest alone is refused")
  public void anImageOfAnotherFormIsRefused(
      final String image) {

    final var refusal = assertThrows(
        IllegalStateException.class,
        () -> EngineUnderTest.Pin.IMAGE.checked(image));

    assertTrue(refusal
        .getMessage()
        .contains("'zenbpm.image' of the root pom"), refusal.getMessage());

  }

  @ParameterizedTest
  @ValueSource(strings = {
      "ghcr.io/pbinitiative/zenbpm@sha256:0000000000000000000000000000000000000000000000000000000000000000", "localhost:5000/zenbpm@sha256:0000000000000000000000000000000000000000000000000000000000000000"
  })
  @DisplayName("An image named by its digest is accepted, also from a registry with a port")
  public void anImageByDigestIsAccepted(
      final String image) {

    assertEquals(image, EngineUnderTest.Pin.IMAGE.checked(image));

  }

}
