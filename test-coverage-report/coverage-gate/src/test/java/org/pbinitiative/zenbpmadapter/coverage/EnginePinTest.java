/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The build reads the engine pin from {@code zenbpm.commit} and {@code zenbpm.image} of the
 * root POM only. Three places repeat it for people: the headers of the two copied engine files
 * and the table of the README. This test holds each of them to the POM, so moving the pin
 * cannot leave one of them naming an engine the tests no longer run.
 * <p>
 * It checks what the copies say they are, not their content. The content is checked by
 * {@code bin/check-engine-copies.sh} in the pull-request check, see decision 18 in the
 * repository's DECISIONS.md, because it needs the engine's files, which this build cannot
 * read offline.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EnginePinTest {

  private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

  private static final String COMMIT = System.getProperty("zenbpm.commit");

  private static final String IMAGE = System.getProperty("zenbpm.image");

  private static final Pattern ANY_COMMIT = Pattern.compile("\\b[0-9a-f]{40}\\b");

  private static final Pattern ANY_IMAGE = Pattern.compile("[^\\s`|(]+@sha256:[0-9a-f]{64}");

  @Test
  @DisplayName("The header of the copied REST contract names the pinned commit and image")
  public void theRestContractNamesThePin() throws IOException {

    assertNamesThePin("core/src/main/zenbpm/api.yaml", header("core/src/main/zenbpm/api.yaml", "#"));

  }

  @Test
  @DisplayName("The header of the copied proto names the pinned commit and image")
  public void theProtoNamesThePin() throws IOException {

    assertNamesThePin("core/src/main/proto/zenbpm.proto", header("core/src/main/proto/zenbpm.proto", "//"));

  }

  @Test
  @DisplayName("The README's section on the supported engine names the pinned commit and image")
  public void theReadmeNamesThePin() throws IOException {

    final var readme = Files.readString(ROOT.resolve("README.md"));
    final var start = readme.indexOf("## Supported engine version");
    assertTrue(start >= 0, "README.md has no section '## Supported engine version' any more.");
    final var end = readme.indexOf("\n## ", start + 1);
    final var section = readme.substring(start, (end < 0) ? readme.length() : end);

    assertNamesThePin("the section 'Supported engine version' of README.md", section);

  }

  /**
   * The comment lines a file starts with, which is where a copy says where it came from.
   */
  private static String header(
      final String file,
      final String commentMarker) throws IOException {

    final List<String> lines = Files.readAllLines(ROOT.resolve(file));
    return lines
        .stream()
        .takeWhile(line -> line.startsWith(commentMarker))
        .collect(Collectors.joining("\n"));

  }

  /**
   * Every commit and every image by digest the text names has to be the pin, and both have to
   * be named. So an old value left next to the new one fails as well as a missing one.
   */
  private static void assertNamesThePin(
      final String place,
      final String text) {

    final var commits = everyMatch(ANY_COMMIT, text);
    final var images = everyMatch(ANY_IMAGE, text);

    assertEquals(
        Set.of(COMMIT),
        commits,
        () -> ("%s names the commits %s, but the pin of the root POM is '%s' alone. Moving the pin "
            + "updates the README's table and the headers of both engine copies in the same "
            + "commit, see the README, section 'Supported engine version'.").formatted(place, commits, COMMIT));
    assertEquals(
        Set.of(IMAGE),
        images,
        () -> ("%s names the images %s, but the pin of the root POM is '%s' alone. Moving the pin "
            + "updates the README's table and the headers of both engine copies in the same "
            + "commit, see the README, section 'Supported engine version'.").formatted(place, images, IMAGE));

  }

  private static Set<String> everyMatch(
      final Pattern pattern,
      final String text) {

    return pattern
        .matcher(text)
        .results()
        .map(MatchResult::group)
        .collect(Collectors.toSet());

  }

}
