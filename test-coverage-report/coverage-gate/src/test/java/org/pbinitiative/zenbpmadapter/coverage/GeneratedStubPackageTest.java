/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The coverage reports leave out the whole package of the generated gRPC stubs, see the
 * JaCoCo configuration of the root POM. A class written by hand into that package would be
 * left out as well, and the gate would never see whether it is tested. So no module may hold
 * sources of its own in that package; the stubs themselves are generated into
 * {@code target}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class GeneratedStubPackageTest {

  private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

  private static final Path STUB_PACKAGE = Path.of("org", "pbinitiative", "zenbpmadapter", "client", "grpc");

  @Test
  @DisplayName("No module writes sources into the package of the generated stubs")
  public void noModuleWritesSourcesIntoTheStubPackage() throws IOException {

    try (Stream<Path> files = Files.walk(ROOT)) {
      final var offenders = files
          .filter(path -> !path.toString().contains("/target/"))
          .filter(path -> path
              .getParent()
              .endsWith(STUB_PACKAGE))
          .filter(path -> path
              .getParent()
              .toString()
              .contains("/src/main/"))
          .map(ROOT::relativize)
          .toList();

      assertTrue(
          offenders.isEmpty(),
          () -> ("%s sit in '%s', which holds the generated gRPC stubs only and is left out of "
              + "every coverage report. Move them to 'org.pbinitiative.zenbpmadapter.client'.")
              .formatted(offenders, STUB_PACKAGE.toString().replace('/', '.')));
    }

  }

}
