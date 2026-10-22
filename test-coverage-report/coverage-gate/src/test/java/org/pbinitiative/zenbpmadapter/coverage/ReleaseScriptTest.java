/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * <code>bin/release.sh</code> runs the steps of <code>.github/workflows/release.yaml</code>, and
 * nothing runs its refusals before the first tag is pushed, which is the one run that must not get
 * them wrong. This test runs each command against stand-ins for git, curl, the GitHub CLI and Maven,
 * which answer as GitHub, the engine's registry and a build would, and holds the script to what the
 * README's section "Releasing" says: which tag releases which version, that a release refuses an
 * engine pin which is no engine release, a tag without notes and a POM naming a snapshot, that the
 * build stages exactly the published files, and that the upload carries them unchanged.
 */
@ExtendWith(SuppressOutputExtension.class)
class ReleaseScriptTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final String RELEASED_COMMIT = "9b8a0f02b02915b47f707b5b380e2a66a4a10e9e";

    private static final String RELEASED_DIGEST = "sha256:295ca2a270b747615aff5f7fee193224df418f8a7aa55e606cd47ee152c23157";

    private static final String RELEASED_IMAGE = "ghcr.io/pbinitiative/zenbpm@%s".formatted(RELEASED_DIGEST);

    private static final String FIX = "as the README's section 'Supported engine version' says";

    @TempDir
    private Path temporary;

    private Path adapter;

    @BeforeEach
    void copyTheScriptsAndWriteTheStandIns() {
        adapter = temporary.resolve("adapter");
        for (final var script : List.of("bin/release.sh", "bin/published-artifacts.sh", "pom.xml")) {
            copy(ROOT.resolve(script), adapter.resolve(script));
        }
        writeStandIns();
    }

    @Test
    @DisplayName("A tag v<major>.<minor>.<patch> releases its version, and no tag the one the root POM's revision leads to")
    void theVersionIsTheTagWithoutItsV() {
        assertEquals(new Run(0, "2.0.0\n"), release(Map.of(), "version", "v2.0.0"));
        assertEquals(new Run(0, "12.30.4\n"), release(Map.of(), "version", "v12.30.4"));

        write(adapter.resolve("pom.xml"),
                "<project>\n  <properties>\n    <revision>3.1.0-SNAPSHOT</revision>\n  </properties>\n</project>\n");
        assertEquals(new Run(0, "3.1.0\n"), release(Map.of(), "version"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2.0.0", "v2.0", "v2.0.0-rc1", "v2.0.0-SNAPSHOT", "vnext", "v2.0.0.1"
    })
    @DisplayName("A tag of another form releases nothing")
    void aTagOfAnotherFormIsRefused(final String tag) {
        final var run = release(Map.of(), "version", tag);

        assertEquals(1, run.status(), run.output());
        assertTrue(run
                .output()
                .contains("A release tag reads v<major>.<minor>.<patch>, for example v2.0.0, but this one is '%s'."
                        .formatted(tag)),
                run.output());
    }

    @Test
    @DisplayName("Today's pin, a build of the engine's main, is refused, with the keys and the fix, also in the summary")
    void todaysPinIsRefused() throws IOException {
        final var summary = temporary.resolve("summary.md");
        final var run = release(Map.of("GITHUB_STEP_SUMMARY", summary.toString()), "engine");

        assertEquals(1, run.status(), run.output());
        for (final var expected : List.of("The engine pin is no engine release", "names no 'zenbpm.version'",
                "'zenbpm.commit' %s".formatted(System.getProperty("zenbpm.commit")),
                "'zenbpm.image' %s".formatted(System
                        .getProperty("zenbpm.image")),
                FIX)) {
            assertTrue(run
                    .output()
                    .contains(expected), () -> "'%s' is missing from: %s".formatted(expected, run.output()));
        }
        assertTrue(Files
                .readString(summary)
                .contains("The engine pin is no engine release"), "the refusal is written into the job's summary");
        assertEquals(List.of(), calls("git"), "nothing is asked of GitHub where no release is named");
    }

    @Test
    @DisplayName("An engine release is accepted where the commit is the tag's and the image the digest of the tag's image")
    void anEngineReleaseIsAccepted() {
        pinEngine("v1.9.0", RELEASED_COMMIT, RELEASED_IMAGE);

        final var run = release(Map.of("LS_REMOTE", "%s\trefs/tags/v1.9.0".formatted(RELEASED_COMMIT)), "engine");

        assertEquals(new Run(0, "The engine pin is the engine release v1.9.0: commit %s, image %s.\n".formatted(
                RELEASED_COMMIT, RELEASED_IMAGE)), run);
        assertEquals(
                List.of("ls-remote https://github.com/pbinitiative/zenbpm.git refs/tags/v1.9.0 refs/tags/v1.9.0^{}"),
                calls("git"));
        assertTrue(calls("curl")
                .getLast()
                .endsWith("https://ghcr.io/v2/pbinitiative/zenbpm/manifests/v1.9.0"), () -> calls("curl").toString());
    }

    @Test
    @DisplayName("Of an annotated tag of the engine, the commit it points at is compared")
    void theCommitOfAnAnnotatedTagIsCompared() {
        pinEngine("v1.9.0", RELEASED_COMMIT, RELEASED_IMAGE);

        final var run = release(Map.of("LS_REMOTE",
                "1111111111111111111111111111111111111111\trefs/tags/v1.9.0\n%s\trefs/tags/v1.9.0^{}"
                        .formatted(RELEASED_COMMIT)),
                "engine");

        assertEquals(0, run.status(), run.output());
    }

    @Test
    @DisplayName("A commit other than the one the engine's tag points at is refused")
    void anotherCommitIsRefused() {
        pinEngine("v1.9.0", "bdd795a399b6484d062d52ac90c0d0725cb0ad91", RELEASED_IMAGE);

        final var run = release(Map.of("LS_REMOTE", "%s\trefs/tags/v1.9.0".formatted(RELEASED_COMMIT)), "engine");

        assertRefused(run,
                "The engine's tag v1.9.0 points at commit %s, but 'zenbpm.commit' of the root pom.xml is 'bdd795a399b6484d062d52ac90c0d0725cb0ad91'."
                        .formatted(RELEASED_COMMIT));
    }

    @Test
    @DisplayName("An image other than the one the engine's tag names is refused")
    void anotherImageIsRefused() {
        final var image = "ghcr.io/pbinitiative/zenbpm@sha256:d7808e30821a7349e0f42476d8aaa21ff92fc752c0ccc432771764d307b87ed0";
        pinEngine("v1.9.0", RELEASED_COMMIT, image);

        final var run = release(Map.of("LS_REMOTE", "%s\trefs/tags/v1.9.0".formatted(RELEASED_COMMIT)), "engine");

        assertRefused(run,
                "The engine's image ghcr.io/pbinitiative/zenbpm:v1.9.0 is %s, but 'zenbpm.image' of the root pom.xml is '%s'."
                        .formatted(RELEASED_IMAGE, image));
    }

    @Test
    @DisplayName("A tag the engine does not have, or whose image the registry does not hold, is refused")
    void aMissingReleaseIsRefused() {
        pinEngine("v9.9.9", RELEASED_COMMIT, RELEASED_IMAGE);
        assertRefused(release(Map.of("LS_REMOTE", ""), "engine"), "The engine has no tag 'v9.9.9'");

        assertRefused(release(Map.of("LS_REMOTE", "%s\trefs/tags/v9.9.9".formatted(RELEASED_COMMIT), "DIGEST", ""),
                "engine"), "The engine's registry has no image ghcr.io/pbinitiative/zenbpm:v9.9.9.");
    }

    @Test
    @DisplayName("A pre-release of the engine is no engine release")
    void aPreReleaseIsRefused() {
        pinEngine("v1.9.0-rc2", RELEASED_COMMIT, RELEASED_IMAGE);

        assertRefused(release(Map.of(), "engine"),
                "'zenbpm.version' of the root pom.xml is 'v1.9.0-rc2', which names no engine release.");
        assertEquals(List.of(), calls("git"));
    }

    @Test
    @DisplayName("The notes are the message of the annotated tag, without its signature")
    void theNotesAreTheMessageOfTheTag() throws IOException {
        final var notes = temporary.resolve("notes.md");
        final var run = release(Map.of("TAG_OBJECT", "tag 0123abcd", "TAG_MESSAGE", """
                The first release.

                - every feature
                -----BEGIN PGP SIGNATURE-----
                abc
                -----END PGP SIGNATURE-----"""), "notes", "v2.0.0", notes.toString());

        assertEquals(0, run.status(), run.output());
        assertEquals("The first release.\n\n- every feature\n", Files.readString(notes));
        assertEquals(List.of("api repos/pbinitiative/zenbpm-vanillabp-adapter/git/ref/tags/v2.0.0",
                "api repos/pbinitiative/zenbpm-vanillabp-adapter/git/tags/0123abcd"), calls("gh"));
    }

    @Test
    @DisplayName("A tag which is missing, lightweight or without a message gives no notes and is refused")
    void aTagWithoutNotesIsRefused() {
        final var notes = temporary
                .resolve("notes.md")
                .toString();

        assertRefused(release(Map.of(), "notes", "v2.0.0", notes), "This repository has no tag 'v2.0.0' on GitHub");
        assertRefused(release(Map.of("TAG_OBJECT", "commit 0123abcd"), "notes", "v2.0.0", notes),
                "The tag 'v2.0.0' is a lightweight tag");
        assertRefused(release(Map.of("TAG_OBJECT", "tag 0123abcd", "TAG_MESSAGE", "  "), "notes", "v2.0.0", notes),
                "The message of the tag 'v2.0.0' is empty");
    }

    @Test
    @DisplayName("The build stages exactly the published files of the version, with every test, into a local directory")
    void theBuildStagesExactlyThePublishedFiles() {
        final var release = temporary.resolve("release");

        final var run = release(Map.of(), "stage", "2.0.0", release.toString());

        assertEquals(0, run.status(), run.output());
        assertTrue(run
                .output()
                .contains(
                        "    zenbpm-vanillabp-adapter-spring-boot/zenbpm-vanillabp-adapter-spring-boot-2.0.0-sources.jar"),
                run.output());
        final var build = calls("mvn").getFirst();
        for (final var argument : List.of(" -Drevision=2.0.0 ", " -Dmaven.install.skip=true ",
                " -DaltDeploymentRepository=github::file://%s/repository ".formatted(release), " deploy")) {
            assertTrue(build.contains(argument), () -> "'%s' is missing from the build: %s".formatted(argument, build));
        }
        assertFalse(build.matches(".*(skipTests|skipITs|maven\\.test\\.skip).*"), "the release runs every test");
    }

    @Test
    @DisplayName("A missing or an additional file of the staged release is refused")
    void anotherSetOfFilesIsRefused() {
        assertRefused(release(Map.of("MISSING", "zenbpm-vanillabp-adapter/zenbpm-vanillabp-adapter-2.0.0-sources.jar"),
                "stage", "2.0.0", temporary
                        .resolve("missing")
                        .toString()),
                "missing:    zenbpm-vanillabp-adapter/zenbpm-vanillabp-adapter-2.0.0-sources.jar");
        assertRefused(release(
                Map.of("EXTRA", "zenbpm-vanillabp-adapter-smoke-test/zenbpm-vanillabp-adapter-smoke-test-2.0.0.jar"),
                "stage", "2.0.0", temporary
                        .resolve("extra")
                        .toString()),
                "unexpected: zenbpm-vanillabp-adapter-smoke-test/zenbpm-vanillabp-adapter-smoke-test-2.0.0.jar");
    }

    @Test
    @DisplayName("A failed build stages nothing, and a version of another form or a used directory is not built")
    void aFailedOrRefusedBuildStagesNothing() {
        final var release = temporary.resolve("release");
        final var failed = release(Map.of("BUILD_FAILS", "yes"), "stage", "2.0.0", release.toString());
        assertEquals(1, failed.status(), failed.output());
        assertFalse(failed
                .output()
                .contains("Staged"), failed.output());

        assertRefused(release(Map.of(), "stage", "2.0", release.toString()),
                "A release version reads <major>.<minor>.<patch>");
        write(release.resolve("repository/left.txt"), "a former run");
        assertRefused(release(Map.of(), "stage", "2.0.0", release.toString()), "/repository is not empty");
        assertEquals(1, calls("mvn").size(), "only the first build ran");
    }

    @Test
    @DisplayName("A staged POM which names a snapshot is refused, naming it and the VanillaBP versions")
    void aSnapshotIsRefused() {
        final var release = stage();
        write(release.resolve(
                "repository/org/pbinitiative/zenbpm/zenbpm-vanillabp-adapter/2.0.0/zenbpm-vanillabp-adapter-2.0.0.pom"),
                """
                        <project>
                          <artifactId>zenbpm-vanillabp-adapter</artifactId>
                          <version>2.0.0</version>
                          <dependencies>
                            <dependency>
                              <groupId>io.vanillabp</groupId>
                              <artifactId>vanillabp-adapter-spi</artifactId>
                              <version>2.0.0-SNAPSHOT</version>
                            </dependency>
                          </dependencies>
                        </project>
                        """);

        final var run = release(Map.of(), "dependencies", release.toString());

        assertRefused(run, "    zenbpm-vanillabp-adapter-2.0.0.pom: vanillabp-adapter-spi 2.0.0-SNAPSHOT");
        assertTrue(run
                .output()
                .contains("Set 'spi-for-java.version' and 'adapter-platform.version' of the root pom.xml"),
                run.output());
    }

    @Test
    @DisplayName("A staged release which names releases only is accepted")
    void releasesOnlyAreAccepted() {
        final var run = release(Map.of(), "dependencies", stage().toString());

        assertEquals(new Run(0, "No POM of the release names a snapshot.\n"), run);
    }

    @Test
    @DisplayName("The upload deploys the parent first, then each module's jar with its sources and POM, with the server 'github'")
    void theUploadDeploysEveryStagedArtifact() {
        final var release = stage();
        final var staged = "%s/repository/org/pbinitiative/zenbpm".formatted(release);

        final var run = release(Map.of(), "upload", release.toString(), "https://maven.example/releases");

        assertEquals(0, run.status(), run.output());
        final var uploads = calls("mvn").subList(1, calls("mvn").size());
        assertEquals(5, uploads.size(), uploads::toString);
        final var parent = "%s/zenbpm-vanillabp-adapter-parent/2.0.0/zenbpm-vanillabp-adapter-parent-2.0.0.pom"
                .formatted(staged);
        assertTrue(uploads
                .getFirst()
                .contains("-Dfile=%s -DpomFile=%s ".formatted(parent, parent)), uploads::toString);
        final var core = "%s/zenbpm-vanillabp-adapter/2.0.0/zenbpm-vanillabp-adapter-2.0.0".formatted(staged);
        assertTrue(uploads
                .stream()
                .anyMatch(
                        upload -> upload.contains("-Dfile=%s.jar -Dsources=%s-sources.jar -DpomFile=%s.pom ".formatted(
                                core, core, core))),
                uploads::toString);
        for (final var upload : uploads) {
            assertTrue(
                    upload.contains(" --non-recursive --settings %s/.github/workflows/settings.xml deploy:deploy-file "
                            .formatted(adapter)) && upload
                                    .endsWith(" -DrepositoryId=github -Durl=https://maven.example/releases"),
                    upload);
        }
    }

    @Test
    @DisplayName("The rehearsal accepts an upload which carried every staged file unchanged, and refuses one which did not")
    void theRehearsalComparesWhatArrived() {
        final var release = stage();
        final var run = release(Map.of(), "rehearse", release.toString());
        assertEquals(new Run(0, "The upload carried the 13 staged files unchanged into a local repository.\n"), run);

        assertRefused(release(Map.of("DROPPED", "-sources.jar"), "rehearse", release.toString()),
                "missing:    zenbpm-vanillabp-adapter/zenbpm-vanillabp-adapter-2.0.0-sources.jar");
        assertRefused(
                release(Map.of("CHANGED", "zenbpm-vanillabp-adapter-quarkus-2.0.0.jar"), "rehearse",
                        release.toString()),
                "The upload changed zenbpm-vanillabp-adapter-quarkus/zenbpm-vanillabp-adapter-quarkus-2.0.0.jar");
    }

    @Test
    @DisplayName("An upload without a staged release uploads nothing, and a command without its arguments is refused")
    void nothingIsUploadedWithoutAStagedRelease() {
        assertRefused(release(Map.of(), "upload", temporary.toString(), "https://maven.example/releases"),
                "holds no staged release");
        assertEquals(List.of(), calls("mvn"));

        for (final var command : List.of(List.of("stage", "2.0.0"), List.of("engine", "x"), List.of("publish"))) {
            final var arguments = new String[command.size()];
            final var run = release(Map.of(), command.toArray(arguments));
            assertEquals(2, run.status(), run.output());
            assertTrue(run
                    .output()
                    .contains("bin/release.sh stage <version> <dir>"), run.output());
        }
    }

    private void assertRefused(
        final Run run,
        final String expected
    ) {
        assertEquals(1, run.status(), run.output());
        assertTrue(run
                .output()
                .contains(expected), () -> "'%s' is missing from: %s".formatted(expected, run.output()));
    }

    /** A staged release of 2.0.0, as the stand-in of Maven builds it. */
    private Path stage() {
        final var release = temporary.resolve("release");
        final var run = release(Map.of(), "stage", "2.0.0", release.toString());
        assertEquals(0, run.status(), run.output());
        return release;
    }

    private void pinEngine(
        final String version,
        final String commit,
        final String image
    ) {
        write(adapter.resolve("pom.xml"), """
                <project>
                  <properties>
                    <revision>2.0.0-SNAPSHOT</revision>
                    <zenbpm.version>%s</zenbpm.version>
                    <zenbpm.commit>%s</zenbpm.commit>
                    <zenbpm.image>%s</zenbpm.image>
                  </properties>
                </project>
                """.formatted(version, commit, image));
    }

    private Run release(
        final Map<String, String> environment,
        final String... arguments
    ) {
        final var command = new ArrayList<String>();
        command.add("bash");
        command.add(adapter
                .resolve("bin/release.sh")
                .toString());
        command.addAll(List.of(arguments));
        final var all = new HashMap<String, String>();
        all.put("PATH", "%s:%s".formatted(temporary.resolve("stand-ins"), System.getenv("PATH")));
        all.put("STAND_INS", temporary
                .resolve("stand-ins")
                .toString());
        all.put("ADAPTER", adapter.toString());
        all.put("DIGEST", RELEASED_DIGEST);
        all.putAll(environment);
        try {
            final var output = Files.createTempFile(temporary, "output", ".txt");
            final var builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder
                    .environment()
                    .remove("GITHUB_STEP_SUMMARY");
            builder
                    .environment()
                    .remove("GITHUB_REPOSITORY");
            builder
                    .environment()
                    .putAll(all);
            final var process = builder.start();
            if (!process.waitFor(1, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError("%s did not end within a minute:%n%s".formatted(command,
                        Files.readString(output)));
            }
            return new Run(process.exitValue(), Files.readString(output));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new AssertionError(e);
        }
    }

    /** What a stand-in was called with, one line per call. */
    private List<String> calls(final String command) {
        final var calls = temporary.resolve("stand-ins/%s.calls".formatted(command));
        try {
            return Files.exists(calls) ? Files.readAllLines(calls) : List.of();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The stand-ins: git answers <code>ls-remote</code> with <code>LS_REMOTE</code>; curl answers the
     * registry's token and the digest <code>DIGEST</code> of a tag's image; gh answers the tag's
     * reference with <code>TAG_OBJECT</code> and its message with <code>TAG_MESSAGE</code>; Maven
     * stages the published files of the version into the deploy directory a build names, leaving out
     * <code>MISSING</code> and adding <code>EXTRA</code>, and copies what a <code>deploy-file</code>
     * names into a <code>file://</code> repository, leaving out <code>DROPPED</code> and changing
     * <code>CHANGED</code>.
     */
    private void writeStandIns() {
        final var standIns = temporary.resolve("stand-ins");
        write(standIns.resolve("git"), """
                #!/usr/bin/env bash
                echo "$*" >>"$STAND_INS/git.calls"
                [ "$1" = ls-remote ] || exit 3
                [ -z "${LS_REMOTE:-}" ] || printf '%s\\n' "$LS_REMOTE"
                """);
        write(standIns.resolve("curl"),
                """
                        #!/usr/bin/env bash
                        echo "$*" >>"$STAND_INS/curl.calls"
                        case " $* " in
                          *" --head "*)
                            [ -n "$DIGEST" ] || exit 22
                            printf 'HTTP/2 200\\r\\ncontent-type: application/vnd.oci.image.index.v1+json\\r\\ndocker-content-digest: %s\\r\\n\\r\\n' "$DIGEST" ;;
                          *) echo '{"token":"a-token"}' ;;
                        esac
                        """);
        write(standIns.resolve("gh"),
                """
                        #!/usr/bin/env bash
                        echo "$1 $2" >>"$STAND_INS/gh.calls"
                        case "$2" in
                          */git/ref/tags/*) [ -n "${TAG_OBJECT:-}" ] || { echo 'HTTP 404: Not Found' >&2; exit 1; }; echo "$TAG_OBJECT" ;;
                          */git/tags/*) printf '%s\\n' "$TAG_MESSAGE" ;;
                          *) exit 3 ;;
                        esac
                        """);
        write(standIns.resolve("mvn"),
                """
                        #!/usr/bin/env bash
                        echo "$*" >>"$STAND_INS/mvn.calls"
                        for argument in "$@"; do
                          case "$argument" in
                            -Drevision=*) version="${argument#-Drevision=}" ;;
                            -DaltDeploymentRepository=github::file://*) staging="${argument#-DaltDeploymentRepository=github::file://}" ;;
                            -Dfile=*) file="${argument#-Dfile=}" ;;
                            -DpomFile=*) pom="${argument#-DpomFile=}" ;;
                            -Dsources=*) sources="${argument#-Dsources=}" ;;
                            -Durl=file://*) target="${argument#-Durl=file://}" ;;
                          esac
                        done
                        if [ -n "${staging:-}" ]; then
                          if [ -n "${BUILD_FAILS:-}" ]; then echo 'BUILD FAILURE'; exit 1; fi
                          { "$ADAPTER/bin/published-artifacts.sh" "$version"; [ -z "${EXTRA:-}" ] || echo "$EXTRA"; } |
                            while read -r published; do
                              [ "$published" != "${MISSING:-}" ] || continue
                              directory="$staging/org/pbinitiative/zenbpm/${published%%/*}/$version"
                              mkdir -p "$directory"
                              echo "the content of $published" >"$directory/${published#*/}"
                              echo checksum >"$directory/${published#*/}.sha1"
                              echo '<metadata/>' >"$directory/../maven-metadata.xml"
                            done
                          exit 0
                        fi
                        if [ -n "${target:-}" ]; then
                          for source in "$file" "$pom" ${sources:+"$sources"}; do
                            if [ -n "${DROPPED:-}" ] && [[ "$source" == *"$DROPPED" ]]; then continue; fi
                            versioned="$(dirname "$source")"
                            directory="$target/org/pbinitiative/zenbpm/$(basename "$(dirname "$versioned")")/$(basename "$versioned")"
                            mkdir -p "$directory"
                            cp "$source" "$directory/"
                            if [ -n "${CHANGED:-}" ] && [[ "$source" == *"/$CHANGED" ]]; then echo changed >>"$directory/$CHANGED"; fi
                          done
                        fi
                        """);
        for (final var command : List.of("git", "curl", "gh", "mvn")) {
            standIns
                    .resolve(command)
                    .toFile()
                    .setExecutable(true);
        }
    }

    private static void copy(
        final Path from,
        final Path to
    ) {
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(
        final Path file,
        final String content
    ) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The status a command ended with and everything it printed. */
    private record Run(
        int status,
        String output
    ) {
    }

}
