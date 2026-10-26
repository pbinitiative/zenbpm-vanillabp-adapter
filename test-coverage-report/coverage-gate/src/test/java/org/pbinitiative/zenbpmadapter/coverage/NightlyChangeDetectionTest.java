/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What decides whether a job of the nightly runs is code which nothing else runs before GitHub
 * does: <code>bin/what-the-nightly-builds-on.sh</code>, which reads the inputs and builds every job's
 * key, and the step <code>decide</code> of the job <code>what-changed</code> in
 * <code>.github/workflows/nightly.yaml</code>. This test runs both, the script against stand-ins for
 * curl and git which answer as the snapshot repository, the registry and the blueprints' repository
 * would, and the step's bash as the workflow holds it. It holds them to what the README's section
 * "What CI runs" says: which inputs every job builds on, that a key holds for one week beginning on
 * Sunday, that an input which cannot be read runs its jobs, that a job runs only where its inputs
 * are new, and that the run of Sunday, a pull request and a person's start run every job.
 */
@ExtendWith(SuppressOutputExtension.class)
class NightlyChangeDetectionTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final String WORKFLOW = ".github/workflows/nightly.yaml";

    private static final List<String> JOBS = List.of("pinned-engine", "latest-engine", "native-image", "blueprints");

    private static final String COMMIT = "3fbb73420be504177df9b48748dd6e2c20bf4242";

    private static final String SNAPSHOT = "20261022.153345-23";

    private static final String DIGEST = "sha256:a5bcf1e0b203d47bcfe1a633c07647bd50531e439ab5c27661a5098bc624b2cb";

    private static final String BLUEPRINTS = "7acfef0827cbd7d88022aef40a9bfcf7bf7eef9d";

    private static final Pattern VANILLABP_ARTIFACT = Pattern.compile(
            "<groupId>io\\.vanillabp</groupId>\\s*<artifactId>([^<]+)</artifactId>");

    private static final Pattern BLUEPRINTS_REPOSITORY = Pattern.compile("(?m)^REPOSITORY=(.+)$");

    /**
     * The time of a run of Monday, and its week as <code>date -u +%Y-%U</code> names it: weeks begin
     * on Sunday. The times of the tests are the schedule's, in UTC.
     */
    private static final String TODAY = "2026-10-26 02:37 UTC";

    private static final String WEEK = "2026-43";

    /** The schedule of the runs from Monday to Saturday. */
    private static final String WEEKDAY = "37 2 * * 1-6";

    @TempDir
    private Path temporary;

    private Path adapter;

    @BeforeEach
    void copyTheScriptAndWriteTheStandIns() {
        adapter = temporary.resolve("adapter");
        for (final var file : List.of("bin/what-the-nightly-builds-on.sh", "bin/build-blueprints-on-zenbpm.sh",
                "pom.xml")) {
            copy(ROOT.resolve(file), adapter.resolve(file));
        }
        writeStandIns();
    }

    @Test
    @DisplayName("The script names every input and every job's key, and the same inputs give the same keys")
    void theScriptNamesEveryInputAndEveryKey() {
        final var outputs = inputs(Map.of());

        assertEquals(COMMIT, outputs.get("adapter"));
        final var named = new TreeSet<String>();
        for (final var artifact : outputs
                .get("vanillabp")
                .split(", ")) {
            assertTrue(artifact.matches("[a-z0-9-]+ [0-9]+\\.[0-9]+\\.[0-9]+-%s".formatted(Pattern.quote(SNAPSHOT))),
                    () -> """
                            The script has to name '%s' by the timestamp and build number of its newest \
                            snapshot, '%s'.""".formatted(artifact, SNAPSHOT));
            named.add(artifact.substring(0, artifact.indexOf(' ')));
        }
        assertEquals(artifactsOfTheRootPom(), named,
                "The script has to read every io.vanillabp artifact of the root POM.");
        assertEquals(DIGEST, outputs.get("engine"));
        assertEquals(BLUEPRINTS, outputs.get("blueprints"));
        assertEquals(WEEK, outputs.get("week"));
        for (final var job : JOBS) {
            assertTrue(outputs
                    .get(job
                            + "-key")
                    .matches("nightly-%s-[0-9a-f]{64}".formatted(job)),
                    () -> "The key of '%s' is '%s'."
                            .formatted(job, outputs.get(job
                                    + "-key")));
        }
        assertEquals(keys(outputs), keys(inputs(Map.of())), "The same inputs have to give the same keys.");
    }

    /**
     * A key which held from one week into the next would let a build of Sunday, which runs every
     * job, fail against inputs an earlier night passed with: it saves nothing, the earlier key stays,
     * Monday skips the job, and GitHub reports the skipped job as passed.
     */
    @Test
    @DisplayName("A key holds from Sunday to Saturday, and every job gets a new one on Sunday")
    void aKeyHoldsForOneWeekBeginningOnSunday() {
        final var sunday = keys(inputs(Map.of("TODAY", "2026-10-25 02:37 UTC")));

        assertEquals(sunday, keys(inputs(Map.of())), "Monday is in the week of the Sunday before it.");
        assertEquals(sunday, keys(inputs(Map.of("TODAY", "2026-10-31 02:37 UTC"))), "Saturday is in the week as well.");
        assertEquals(Set.copyOf(JOBS), changed(keys(inputs(Map.of("TODAY", "2026-10-24 02:37 UTC"))), sunday),
                "Every job gets a new key on Sunday.");
        assertEquals(Set.copyOf(JOBS), changed(sunday, keys(inputs(Map.of("TODAY", "2026-11-01 02:37 UTC")))),
                "Every job gets a new key on the next Sunday.");
    }

    @Test
    @DisplayName("A release of VanillaBP is named by itself, without asking the snapshot repository")
    void aReleaseIsNamedByItself() {
        write(adapter.resolve("pom.xml"), """
                <project>
                  <properties>
                    <spi-for-java.version>2.0.0</spi-for-java.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>io.vanillabp</groupId>
                        <artifactId>spi-for-java</artifactId>
                        <version>${spi-for-java.version}</version>
                      </dependency>
                      <dependency>
                        <groupId>io.vanillabp</groupId>
                        <artifactId>test-utils</artifactId>
                        <version>2.0.1</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        final var outputs = inputs(Map.of("SNAPSHOT", ""));

        assertEquals("spi-for-java 2.0.0, test-utils 2.0.1", outputs.get("vanillabp"));
        assertEquals(Set.of(), empty(outputs), "A release never changes, so its jobs have their keys.");
    }

    @Test
    @DisplayName("A changed input changes the keys of exactly the jobs which build on it")
    void aChangedInputChangesTheKeysOfItsJobs() {
        final var before = keys(inputs(Map.of()));

        assertEquals(Set.copyOf(JOBS), changed(before, keys(inputs(Map.of("COMMIT",
                "0000000000000000000000000000000000000001")))), "Every job builds on the adapter's commit.");
        assertEquals(Set.copyOf(JOBS), changed(before, keys(inputs(Map.of("SNAPSHOT", "20261027.010203-24")))),
                "Every job builds on VanillaBP's snapshots.");
        assertEquals(Set.of("latest-engine"), changed(before, keys(inputs(Map.of("DIGEST", "sha256:%s".formatted("1"
                .repeat(64)))))), "Only 'latest-engine' builds on the engine's newest build.");
        assertEquals(Set.of("blueprints"), changed(before, keys(inputs(Map.of("LS_REMOTE",
                "0000000000000000000000000000000000000002\trefs/heads/main")))),
                "Only 'blueprints' builds on the blueprints' main.");
    }

    @Test
    @DisplayName("An input which cannot be read leaves the keys of exactly its jobs empty, and the script still ends with 0")
    void anUnreadInputLeavesTheKeysOfItsJobsEmpty() {
        final var unreadSnapshot = inputs(Map.of("SNAPSHOT", ""));
        assertEquals("unread", unreadSnapshot.get("vanillabp"));
        assertEquals(Set.copyOf(JOBS), empty(unreadSnapshot), "Every job builds on VanillaBP's snapshots.");

        final var unreadArtifact = inputs(Map.of("UNREADABLE", "spi-for-java"));
        assertEquals("unread", unreadArtifact.get("vanillabp"));
        assertEquals(Set.copyOf(JOBS), empty(unreadArtifact), "One unread artifact leaves VanillaBP unread.");

        final var unreadDigest = inputs(Map.of("DIGEST", ""));
        assertEquals("unread", unreadDigest.get("engine"));
        assertEquals(Set.of("latest-engine"), empty(unreadDigest), "Only 'latest-engine' builds on the engine.");

        final var noToken = inputs(Map.of("TOKEN", ""));
        assertEquals("unread", noToken.get("engine"));
        assertEquals(Set.of("latest-engine"), empty(noToken), "Without a token the registry names no digest.");

        final var unreadBlueprints = inputs(Map.of("LS_REMOTE", ""));
        assertEquals("unread", unreadBlueprints.get("blueprints"));
        assertEquals(Set.of("blueprints"), empty(unreadBlueprints), "Only 'blueprints' builds on the blueprints.");
    }

    @Test
    @DisplayName("From Monday to Saturday a job runs only where its key was not seen, and an unseen key runs its job")
    void aWeekdayRunsOnlyTheJobsWhoseInputsAreNew() {
        final var nothingNew = decide("schedule", WEEKDAY, Set.copyOf(JOBS));
        assertEquals(decisions("skip", "skip", "skip", "skip"), nothingNew.decisions());
        assertTrue(nothingNew
                .summary()
                .contains("no job runs, because nothing any of them builds on changed since its last run this week."),
                nothingNew.summary());
        assertTrue(nothingNew
                .summary()
                .contains("in the week `%s`".formatted(WEEK)), nothingNew.summary());

        final var newEngine = decide("schedule", WEEKDAY, Set.of("pinned-engine", "native-image", "blueprints"));
        assertEquals(decisions("skip", "run", "skip", "skip"), newEngine.decisions());
        assertTrue(newEngine
                .summary()
                .contains(
                        "running `latest-engine`; skipped, because nothing they build on changed since their last run this "
                                + "week: "
                                + "`pinned-engine`, `native-image`, `blueprints`."),
                newEngine.summary());

        final var unread = decide("schedule", WEEKDAY, Set.of());
        assertEquals(decisions("run", "run", "run", "run"), unread.decisions(), """
                A key which was not looked up, because an input could not be read, has to run its job.""");
    }

    @Test
    @DisplayName("The run of Sunday, a pull request and a person's start run every job, also where every key was seen")
    void sundayAPullRequestAndADispatchRunEveryJob() {
        final var sunday = String.valueOf(map(decidingStep().get("env"), "the env of 'decide'").get("EVERY_JOB_ON"));
        final var everyJob = decisions("run", "run", "run", "run");

        final var ofSunday = decide("schedule", sunday, Set.copyOf(JOBS));
        assertEquals(everyJob, ofSunday.decisions());
        assertTrue(ofSunday
                .summary()
                .contains("every job runs, because this is the run of Sunday."), ofSunday.summary());

        final var ofAPullRequest = decide("pull_request", "", Set.copyOf(JOBS));
        assertEquals(everyJob, ofAPullRequest.decisions());
        assertTrue(ofAPullRequest
                .summary()
                .contains("every job runs, because a pull request started this run."), ofAPullRequest.summary());

        final var ofAPerson = decide("workflow_dispatch", "", Set.copyOf(JOBS));
        assertEquals(everyJob, ofAPerson.decisions());
        assertTrue(ofAPerson
                .summary()
                .contains("every job runs, because a person started this run."), ofAPerson.summary());
    }

    /** The outputs of the script for the inputs the stand-ins answer, changed by <code>changes</code>. */
    private Map<String, String> inputs(final Map<String, String> changes) {
        final var environment = new HashMap<String, String>();
        environment.put("COMMIT", COMMIT);
        environment.put("SNAPSHOT", SNAPSHOT);
        environment.put("DIGEST", DIGEST);
        environment.put("TOKEN", "a-token");
        environment.put("LS_REMOTE", "%s\trefs/heads/main".formatted(BLUEPRINTS));
        environment.put("UNREADABLE", "");
        environment.put("TODAY", TODAY);
        // where it is still Saturday evening when it is Sunday morning in UTC, so a script which
        // read the day of the runner's time zone would fail; GitHub's runners are set to UTC
        environment.put("TZ", "America/New_York");
        environment.putAll(changes);
        final var run = run(List.of("bash", adapter
                .resolve("bin/what-the-nightly-builds-on.sh")
                .toString()), environment);
        assertEquals(0, run.status(), run.output());
        assertEquals(run
                .stdout()
                .strip(),
                run
                        .githubOutput()
                        .strip(),
                "The script has to write what it prints into the step's outputs as well.");
        return lines(run.githubOutput());
    }

    /** What the step 'decide' writes, for an event, its schedule and the jobs whose key was seen. */
    private Decision decide(
        final String event,
        final String schedule,
        final Set<String> seen
    ) {
        final var step = decidingStep();
        final var environment = new HashMap<String, String>();
        environment.put("EVENT", event);
        environment.put("SCHEDULE", schedule);
        environment.put("EVERY_JOB_ON", String.valueOf(map(step.get("env"), "the env of 'decide'").get(
                "EVERY_JOB_ON")));
        environment.put("ADAPTER", COMMIT);
        environment.put("VANILLABP", "spi-for-java 2.0.0-%s".formatted(SNAPSHOT));
        environment.put("ENGINE", DIGEST);
        environment.put("BLUEPRINTS", BLUEPRINTS);
        environment.put("WEEK", WEEK);
        for (final var job : JOBS) {
            environment.put("SEEN_%s".formatted(job
                    .replace('-', '_')
                    .toUpperCase()), seen.contains(job) ? "true" : "");
        }
        final var script = temporary.resolve("decide.sh");
        write(script, String.valueOf(step.get("run")));
        // the shell GitHub runs a step's 'run' with where the step names none
        final var run = run(List.of("bash", "-e", script.toString()), environment);
        assertEquals(0, run.status(), run.output());
        return new Decision(lines(run.githubOutput()), run.summary());
    }

    private static Map<String, String> decisions(
        final String pinnedEngine,
        final String latestEngine,
        final String nativeImage,
        final String blueprints
    ) {
        return Map.of("pinned-engine", pinnedEngine, "latest-engine", latestEngine, "native-image", nativeImage,
                "blueprints", blueprints);
    }

    private static Map<String, String> keys(final Map<String, String> outputs) {
        final var keys = new LinkedHashMap<String, String>();
        for (final var job : JOBS) {
            keys.put(job, outputs.get(job
                    + "-key"));
        }
        return keys;
    }

    private static Set<String> changed(
        final Map<String, String> before,
        final Map<String, String> after
    ) {
        final var changed = new TreeSet<String>();
        for (final var job : JOBS) {
            assertNotEquals("", after.get(job), () -> "The key of '%s' is empty.".formatted(job));
            if (!before
                    .get(job)
                    .equals(after.get(job))) {
                changed.add(job);
            }
        }
        return changed;
    }

    private static Set<String> empty(final Map<String, String> outputs) {
        final var empty = new TreeSet<String>();
        for (final var job : JOBS) {
            if (outputs
                    .get(job
                            + "-key")
                    .isEmpty()) {
                empty.add(job);
            }
        }
        return empty;
    }

    /** The repository <code>bin/build-blueprints-on-zenbpm.sh</code> clones the blueprints from. */
    private static String blueprintsRepository() {
        try {
            final var repository = BLUEPRINTS_REPOSITORY
                    .matcher(Files.readString(ROOT.resolve("bin/build-blueprints-on-zenbpm.sh")));
            assertTrue(repository.find(), "bin/build-blueprints-on-zenbpm.sh names no REPOSITORY any more.");
            return repository.group(1);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> artifactsOfTheRootPom() {
        try {
            final var artifacts = new TreeSet<String>();
            VANILLABP_ARTIFACT
                    .matcher(Files.readString(ROOT.resolve("pom.xml")))
                    .results()
                    .forEach(artifact -> artifacts.add(artifact.group(1)));
            return artifacts;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The lines <code>name=value</code> of a step's outputs. */
    private static Map<String, String> lines(final String outputs) {
        final var lines = new LinkedHashMap<String, String>();
        for (final var line : outputs.split("\n")) {
            final var equals = line.indexOf('=');
            if (equals > 0) {
                lines.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return lines;
    }

    private static Map<String, Object> decidingStep() {
        try {
            final var workflow = map(new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(ROOT
                    .resolve(WORKFLOW))), WORKFLOW);
            final var job = map(map(workflow.get("jobs"), "jobs").get("what-changed"), "jobs.what-changed");
            for (final var step : (List<?>) job.get("steps")) {
                final var candidate = map(step, "a step of 'what-changed'");
                if ("decide".equals(candidate.get("id"))) {
                    return candidate;
                }
            }
            throw new AssertionError("'what-changed' of '%s' has no step of the id 'decide' any more."
                    .formatted(WORKFLOW));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Run run(
        final List<String> command,
        final Map<String, String> environment
    ) {
        try {
            final var output = Files.createTempFile(temporary, "output", ".txt");
            final var stdout = Files.createTempFile(temporary, "stdout", ".txt");
            final var githubOutput = Files.createTempFile(temporary, "github-output", ".txt");
            final var summary = Files.createTempFile(temporary, "summary", ".txt");
            final var builder = new ProcessBuilder(command)
                    .redirectError(output.toFile())
                    .redirectOutput(stdout.toFile());
            builder
                    .environment()
                    .putAll(environment);
            builder
                    .environment()
                    .put("PATH", "%s:%s".formatted(temporary.resolve("stand-ins"), System.getenv("PATH")));
            builder
                    .environment()
                    .put("STAND_INS", temporary
                            .resolve("stand-ins")
                            .toString());
            builder
                    .environment()
                    .put("ADAPTER", adapter.toString());
            builder
                    .environment()
                    .put("BLUEPRINTS_REPOSITORY", blueprintsRepository());
            builder
                    .environment()
                    .put("GITHUB_OUTPUT", githubOutput.toString());
            builder
                    .environment()
                    .put("GITHUB_STEP_SUMMARY", summary.toString());
            final var process = builder.start();
            if (!process.waitFor(1, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError("%s did not end within a minute:%n%s".formatted(command, Files.readString(
                        output)));
            }
            return new Run(process.exitValue(), "%s%n%s".formatted(Files.readString(stdout),
                    Files.readString(output)), Files
                            .readString(stdout), Files.readString(githubOutput), Files.readString(summary));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * The stand-ins: git names <code>ADAPTER</code> as the checkout and <code>COMMIT</code> as its
     * commit, and answers <code>ls-remote</code> of the main of the repository the blueprints are
     * cloned from with <code>LS_REMOTE</code>; curl answers the
     * snapshot repository's metadata with the upload <code>SNAPSHOT</code>, except for the artifact
     * <code>UNREADABLE</code>, the registry's token with <code>TOKEN</code> and the digest of the
     * engine's tag with <code>DIGEST</code>. An empty value is an answer of 4xx or 5xx, or a
     * repository which names nothing. date is the system's, asked about the time <code>TODAY</code>
     * instead of now, so the format the script asks for is the one tested.
     */
    private void writeStandIns() {
        final var standIns = temporary.resolve("stand-ins");
        write(standIns.resolve("git"),
                """
                        #!/usr/bin/env bash
                        [ "$1" != -C ] || shift 2
                        case "$*" in
                          "rev-parse --show-toplevel") echo "$ADAPTER" ;;
                          "rev-parse HEAD") echo "$COMMIT" ;;
                          "ls-remote $BLUEPRINTS_REPOSITORY refs/heads/main") [ -n "$LS_REMOTE" ] || exit 128; printf '%s\\n' "$LS_REMOTE" ;;
                          *) exit 3 ;;
                        esac
                        """);
        write(standIns.resolve("curl"),
                """
                        #!/usr/bin/env bash
                        url="${*: -1}"
                        case "$url" in
                          */maven-metadata.xml)
                            artifact="$(basename "$(dirname "$(dirname "$url")")")"
                            [ -n "$SNAPSHOT" ] && [ "$artifact" != "$UNREADABLE" ] || exit 22
                            printf '<metadata><versioning><snapshot><timestamp>%s</timestamp><buildNumber>%s</buildNumber></snapshot></versioning></metadata>\\n' \\
                              "${SNAPSHOT%-*}" "${SNAPSHOT##*-}" ;;
                          https://ghcr.io/token*)
                            [ -n "$TOKEN" ] || exit 22
                            printf '{"token":"%s"}\\n' "$TOKEN" ;;
                          https://ghcr.io/v2/*)
                            [ -n "$TOKEN" ] && [ -n "$DIGEST" ] && [[ " $* " == *" --header Authorization: Bearer $TOKEN "* ]] || exit 22
                            printf 'HTTP/2 200\\r\\ncontent-type: application/vnd.oci.image.index.v1+json\\r\\ndocker-content-digest: %s\\r\\n\\r\\n' "$DIGEST" ;;
                          *) exit 6 ;;
                        esac
                        """);
        write(standIns.resolve("date"), """
                #!/usr/bin/env bash
                PATH=/usr/bin:/bin exec date --date "$TODAY" "$@"
                """);
        for (final var command : List.of("git", "curl", "date")) {
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(
        final Object value,
        final String where
    ) {
        assertTrue(value instanceof Map<?, ?>, () -> "'%s' of '%s' is no mapping but '%s'".formatted(where, WORKFLOW,
                value));
        return (Map<String, Object>) value;
    }

    /** What a script printed, wrote into the step's outputs and appended to the summary. */
    private record Run(
        int status,
        String output,
        String stdout,
        String githubOutput,
        String summary
    ) {
    }

    /** What the step 'decide' wrote: every job's decision and the summary line. */
    private record Decision(
        Map<String, String> decisions,
        String summary
    ) {
    }

}
