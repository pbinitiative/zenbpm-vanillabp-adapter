/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * root POM only. Four places repeat it for people: the headers of the two copied engine files,
 * the table of the README and the wiki's page {@code Home}. This test holds each of them to the
 * POM, so moving the pin cannot leave one of them naming an engine the tests no longer run, and
 * it holds {@code bin/zenbpm-engine.sh}, which starts the engine for the blueprints, to reading
 * the pin from the POM.
 * <p>
 * It checks what the copies say they are, not their content. The content is checked by
 * {@code bin/check-engine-copies.sh} in the pull-request check, see decision 18 in the
 * repository's DECISIONS.md, because it needs the engine's files, which this build cannot
 * read offline.
 */
@ExtendWith(SuppressOutputExtension.class)
class EnginePinTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final String COMMIT = System.getProperty("zenbpm.commit");

    private static final String IMAGE = System.getProperty("zenbpm.image");

    private static final Pattern ANY_COMMIT = Pattern.compile("\\b[0-9a-f]{40}\\b");

    private static final Pattern ANY_IMAGE = Pattern.compile("[^\\s`|(]+@sha256:[0-9a-f]{64}");

    /** The image a <code>docker run</code> starts: the last word of the command. */
    private static final Pattern IMAGE_OF_A_COMMAND = Pattern.compile("(?m)^docker run [^\\n]*[ \\t](\\S+)[ \\t]*$");

    @Test
    @DisplayName("The header of the copied REST contract names the pinned commit and image")
    void theRestContractNamesThePin() throws IOException {
        assertNamesThePin("core/src/main/zenbpm/api.yaml", header("core/src/main/zenbpm/api.yaml", "#"));
    }

    @Test
    @DisplayName("The header of the copied proto names the pinned commit and image")
    void theProtoNamesThePin() throws IOException {
        assertNamesThePin("core/src/main/proto/zenbpm.proto", header("core/src/main/proto/zenbpm.proto", "//"));
    }

    @Test
    @DisplayName("The README's section on the supported engine names the pinned commit and image")
    void theReadmeNamesThePin() throws IOException {
        final var readme = Files.readString(ROOT.resolve("README.md"));
        final var start = readme.indexOf("## Supported engine version");
        assertTrue(start >= 0, "README.md has no section '## Supported engine version' any more.");
        final var end = readme.indexOf("\n## ", start + 1);
        final var section = readme.substring(start, (end < 0) ? readme.length() : end);

        assertNamesThePin("the section 'Supported engine version' of README.md", section);
    }

    @Test
    @DisplayName("The wiki's page Home names the pinned commit and image, and no page of the wiki names another engine")
    void theWikiNamesThePin() throws IOException {
        final var home = Files.readString(ROOT.resolve("wiki/Home.md"));
        assertNamesThePin("wiki/Home.md", home);
        // a command which starts an image by a tag names no digest, which the check above would miss
        assertEquals(List.of(IMAGE), IMAGE_OF_A_COMMAND
                .matcher(home)
                .results()
                .map(command -> command.group(1))
                .toList(), "every 'docker run' of wiki/Home.md has to start the pinned image");
        try (var pages = Files.list(ROOT.resolve("wiki"))) {
            for (final var page : pages.toList()) {
                final var text = Files.readString(page);
                final var commits = everyMatch(ANY_COMMIT, text);
                final var images = everyMatch(ANY_IMAGE, text);

                assertTrue(Set
                        .of(COMMIT)
                        .containsAll(commits) && Set
                                .of(IMAGE)
                                .containsAll(images),
                        () -> "%s names the commits %s and the images %s, but the pin is '%s' and '%s'."
                                .formatted(ROOT.relativize(page), commits, images, COMMIT, IMAGE));
            }
        }
    }

    @Test
    @DisplayName("The engine script of the blueprints starts the pinned image, read from the root POM and not written a second time")
    void theBlueprintsEngineIsThePin() throws IOException, InterruptedException {
        final var script = ROOT.resolve("bin/zenbpm-engine.sh");
        final var process = new ProcessBuilder("bash", script.toString(), "image")
                .redirectErrorStream(true)
                .start();
        final var printed = new String(process
                .getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8).strip();

        assertEquals(0, process.waitFor(), printed);
        assertEquals(IMAGE, printed, "bin/zenbpm-engine.sh has to start the image the root POM pins");
        final var text = Files.readString(script);
        assertEquals(Set.of(), everyMatch(ANY_IMAGE, text), """
                bin/zenbpm-engine.sh names an image of its own; it reads the pin from 'zenbpm.image' of the \
                root POM, so moving the pin cannot leave the blueprints on another engine.""");
        assertTrue(text.contains("docker run ") && text.contains("\"$1\" >/dev/null") && text.contains(
                "started \"${pinned}\""), "the engine script starts what 'image' prints, as 'pinned'");
    }

    /**
     * The comment lines a file starts with, which is where a copy says where it came from.
     */
    private static String header(
        final String file,
        final String commentMarker
    ) throws IOException {
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
        final String text
    ) {
        final var commits = everyMatch(ANY_COMMIT, text);
        final var images = everyMatch(ANY_IMAGE, text);

        assertEquals(
                Set.of(COMMIT),
                commits,
                () -> ("%s names the commits %s, but the pin of the root POM is '%s' alone. Moving the pin "
                        + "updates the README's table, the wiki's page Home and the headers of both engine copies in the same "
                        + "commit, see the README, section 'Supported engine version'.")
                        .formatted(place, commits, COMMIT));
        assertEquals(
                Set.of(IMAGE),
                images,
                () -> ("%s names the images %s, but the pin of the root POM is '%s' alone. Moving the pin "
                        + "updates the README's table, the wiki's page Home and the headers of both engine copies in the same "
                        + "commit, see the README, section 'Supported engine version'.")
                        .formatted(place, images, IMAGE));
    }

    private static Set<String> everyMatch(
        final Pattern pattern,
        final String text
    ) {
        return pattern
                .matcher(text)
                .results()
                .map(MatchResult::group)
                .collect(Collectors.toSet());
    }

}
