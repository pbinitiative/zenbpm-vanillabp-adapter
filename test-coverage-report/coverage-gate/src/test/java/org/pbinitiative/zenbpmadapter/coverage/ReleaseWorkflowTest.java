/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A release is the one run of this repository nobody can rehearse on GitHub without publishing,
 * and its workflow runs nothing else before the first tag is pushed. This test holds
 * <code>.github/workflows/release.yaml</code> to what the README's section "Releasing" says: a
 * pushed tag releases and every other run is a dry run, the refusals come before the build and stop
 * a release but not a dry run, only the job <code>publish</code> uploads and creates the GitHub
 * release, and only for a pushed tag, and no other workflow can publish a release. What the steps do
 * is <code>bin/release.sh</code>, which <code>ReleaseScriptTest</code> runs.
 */
@ExtendWith(SuppressOutputExtension.class)
class ReleaseWorkflowTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final Path WORKFLOWS = ROOT.resolve(".github/workflows");

    private static final String WORKFLOW = "release.yaml";

    private static final String SCRIPT = "bin/release.sh";

    /** What a change of starts the dry run in its pull request: the workflow and what it runs. */
    private static final List<String> STARTING_PATHS = List.of(".github/workflows/release.yaml",
            ".github/workflows/settings.xml", SCRIPT, "bin/published-artifacts.sh");

    /** A refusal stops a release, which a pushed tag is, and lets a dry run go on. */
    private static final String ONLY_A_RELEASE_STOPS = "${{ github.event_name != 'push' }}";

    private static final String ONLY_A_PUSHED_TAG = "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')";

    private static final Pattern WRITTEN_SUMMARY = Pattern.compile(">>\\s*\"\\$GITHUB_STEP_SUMMARY\"");

    /** What publishes a release: an upload of the staged files, a GitHub release, a deploy of a version. */
    private static final Pattern PUBLISHING = Pattern.compile(
            "bin/release\\.sh upload|gh release|deploy:deploy-file|-Drevision|altDeploymentRepository");

    /** A Maven call which runs the phase or goal deploy. */
    private static final Pattern MAVEN_DEPLOY = Pattern.compile("(?s)^\\s*mvn\\b.*\\sdeploy(\\s|$)");

    @Test
    @DisplayName("A pushed tag v* releases, and a dispatch and a pull request changing the release run a dry run")
    void theReleaseRunsWhenTheReadmeSays() {
        final var triggers = map(workflow().get("on"), "on");
        assertEquals(Set.of("push", "workflow_dispatch", "pull_request"), triggers.keySet(), """
                The release runs on a pushed tag, on a dispatch and on a pull request which changes it, \
                and on nothing else, as the README's section 'Releasing' says.""");
        assertEquals(Map.of("tags", List.of("v*")), triggers.get("push"), """
                A release is started by pushing a tag v<major>.<minor>.<patch> and by nothing else: no \
                push of a branch.""");
        assertEquals(Map.of("paths", STARTING_PATHS), triggers.get("pull_request"), """
                A pull request runs the dry run only where it changes the release itself or a file it \
                runs, as the README promises.""");
        for (final var path : STARTING_PATHS) {
            assertTrue(readmeSection().contains("`%s`".formatted(path)), () -> """
                    The README's section 'Releasing' has to name '%s', a change of which runs the dry run \
                    in its pull request.""".formatted(path));
        }
    }

    @Test
    @DisplayName("Only the job publish uploads and creates the GitHub release, after the build and only for a pushed tag")
    void onlyThePublishJobOfAPushedTagPublishes() {
        final var jobs = jobs();
        assertEquals(List.of("build", "publish"), List.copyOf(jobs.keySet()),
                "The release has the jobs 'build' and 'publish'.");
        final var publish = jobs.get("publish");
        assertEquals("build", publish.get("needs"), "'publish' waits for 'build', which builds, tests and refuses.");
        assertEquals(ONLY_A_PUSHED_TAG, publish.get("if"), "'publish' runs for a pushed tag only.");
        assertTrue(!jobs
                .get("build")
                .containsKey("if"), "'build' runs on every trigger: a release and a dry run build the same way.");

        final var publishing = new TreeSet<String>();
        for (final var job : jobs.keySet()) {
            for (final var step : steps(job)) {
                if (PUBLISHING
                        .matcher(String.valueOf(step.get("run")))
                        .find()) {
                    publishing.add("job '%s', step '%s'".formatted(job, step.get("name")));
                }
            }
        }
        assertEquals(Set.of("job 'publish', step 'Upload the artifacts to GitHub Packages'",
                "job 'publish', step 'Create the GitHub release'"), publishing, """
                        Only the job 'publish' uploads the staged release and creates the GitHub release.""");
        final var upload = steps("publish")
                .stream()
                .map(step -> String.valueOf(step.get("run")))
                .filter(run -> run.startsWith("%s upload ".formatted(SCRIPT)))
                .toList();
        assertEquals(List.of(
                "%s upload \"$RUNNER_TEMP/release\" https://maven.pkg.github.com/pbinitiative/zenbpm-vanillabp-adapter"
                        .formatted(SCRIPT)),
                upload, "'publish' uploads what 'build' staged to this repository's GitHub Packages.");
    }

    @Test
    @DisplayName("The refusals come before the build and stop a release, not a dry run, and nothing else may fail")
    void theRefusalsStopOnlyARelease() {
        final var runs = steps("build")
                .stream()
                .map(step -> String
                        .valueOf(step.get("run"))
                        .strip())
                .toList();
        final var version = indexOf(runs, "%s version \"$TAG\"".formatted(SCRIPT));
        final var engine = indexOf(runs, "%s engine".formatted(SCRIPT));
        final var notes = indexOf(runs, "%s notes \"v$VERSION\" \"$RUNNER_TEMP/release/notes.md\"".formatted(SCRIPT));
        final var stage = indexOf(runs, "%s stage \"$VERSION\" \"$RUNNER_TEMP/release\"".formatted(SCRIPT));
        final var dependencies = indexOf(runs, "%s dependencies \"$RUNNER_TEMP/release\"".formatted(SCRIPT));
        final var rehearse = indexOf(runs, "%s rehearse \"$RUNNER_TEMP/release\"".formatted(SCRIPT));
        assertTrue(
                (version < engine) && (engine < stage) && (notes < stage) && (stage < dependencies) && (stage < rehearse),
                () -> """
                        'build' reads the version, refuses an engine which is no release and reads the notes \
                        before it builds and stages the release, and refuses a snapshot and rehearses the \
                        upload after, but runs %s.""".formatted(runs));

        final var mayFail = new LinkedHashMap<String, Object>();
        final var hiding = new ArrayList<String>();
        for (final var step : steps("build")) {
            if (step.containsKey("continue-on-error")) {
                mayFail.put(String.valueOf(step.get("id")), step.get("continue-on-error"));
            }
            if (!step.containsKey("if") && String
                    .valueOf(step.get("run"))
                    .contains("||")) {
                hiding.add(String.valueOf(step.get("name")));
            }
        }
        assertEquals(Map.of("engine", ONLY_A_RELEASE_STOPS, "notes", ONLY_A_RELEASE_STOPS, "dependencies",
                ONLY_A_RELEASE_STOPS), mayFail, """
                        Only the three refusals may fail without failing 'build', and only in a dry run: a \
                        pushed tag stops at each of them.""");
        assertEquals(List.of(), hiding, "These steps of 'build' let a failure pass with '||'.");
        assertTrue(steps("publish")
                .stream()
                .noneMatch(step -> step.containsKey("continue-on-error")), "No step of 'publish' may fail.");
        assertTrue(!jobs()
                .get("build")
                .containsKey("continue-on-error") && !jobs()
                        .get("publish")
                        .containsKey("continue-on-error"),
                "No job of the release may fail without failing the run.");
    }

    @Test
    @DisplayName("The build stages into a local directory only, and no other workflow publishes a release")
    void noOtherWayPublishesARelease() throws IOException {
        final var script = Files.readString(ROOT.resolve(SCRIPT));
        assertTrue(script.contains("-DaltDeploymentRepository=\"github::file://$target/repository\"") && script
                .contains("-Dmaven.install.skip=true"), """
                        bin/release.sh stages a release into a local directory and the local Maven repository \
                        stays untouched: the build of 'build' uploads nowhere.""");

        final var publishing = new TreeSet<String>();
        final var deploying = new TreeSet<String>();
        for (final var file : workflows()) {
            final var name = String.valueOf(file.getFileName());
            if (WORKFLOW.equals(name)) {
                continue;
            }
            for (final var run : runs(file)) {
                if (PUBLISHING
                        .matcher(run)
                        .find() || run.contains(SCRIPT)) {
                    publishing.add(name);
                }
                if (MAVEN_DEPLOY
                        .matcher(run)
                        .find()) {
                    deploying.add(name);
                }
            }
        }
        assertEquals(Set.of(), publishing, """
                These workflows upload, deploy a version of their own or create a GitHub release. Only \
                release.yaml publishes a release, as the README's section 'Releasing' says.""");
        assertEquals(Set.of("publish-snapshots.yaml"), deploying,
                "Only publish-snapshots.yaml deploys, and only snapshots.");
        final var revision = Pattern
                .compile("<revision>([^<]+)</revision>")
                .matcher(Files.readString(ROOT.resolve("pom.xml")));
        assertTrue(revision.find() && revision
                .group(1)
                .endsWith("-SNAPSHOT"), """
                        The root POM's revision has to be a snapshot: publish-snapshots.yaml deploys it as it \
                        stands, so a release version there would be published by every push to main.""");
    }

    @Test
    @DisplayName("Every job of the release writes into the run's summary, also when it failed")
    void everyJobWritesItsSummary() {
        final var silent = new TreeSet<String>();
        for (final var job : jobs().keySet()) {
            if (steps(job)
                    .stream()
                    .noneMatch(step -> String
                            .valueOf(step.get("if"))
                            .contains("always()") && WRITTEN_SUMMARY
                                    .matcher(String.valueOf(step.get("run")))
                                    .find())) {
                silent.add(job);
            }
        }
        assertEquals(Set.of(), silent, """
                These jobs of the release write nothing into the run's summary. Give each a last step with \
                'if: always()' which appends to "$GITHUB_STEP_SUMMARY", as the README's section 'Releasing' \
                promises.""");
    }

    /** The first step whose command runs the given one, on a line of its own or in an assignment. */
    private static int indexOf(
        final List<String> runs,
        final String run
    ) {
        for (var index = 0; index < runs.size(); index++) {
            if (runs
                    .get(index)
                    .lines()
                    .anyMatch(line -> line.equals(run) || line.equals("version=\"$(%s)\"".formatted(run)))) {
                return index;
            }
        }
        throw new AssertionError("'build' has to run '%s', but runs %s.".formatted(run, runs));
    }

    private static Map<String, Object> workflow() {
        try {
            return map(new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(WORKFLOWS.resolve(
                    WORKFLOW))), WORKFLOW);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Map<String, Object>> jobs() {
        final var jobs = new LinkedHashMap<String, Map<String, Object>>();
        for (final var job : map(workflow().get("jobs"), "jobs").entrySet()) {
            jobs.put(job.getKey(), map(job.getValue(), "jobs.%s".formatted(job.getKey())));
        }
        return jobs;
    }

    private static List<Map<String, Object>> steps(final String job) {
        final var jobs = jobs();
        assertTrue(jobs.containsKey(job), () -> "'%s' has no job '%s' any more.".formatted(WORKFLOW, job));
        final var steps = jobs
                .get(job)
                .get("steps");
        assertTrue(steps instanceof List<?>, () -> "'jobs.%s.steps' of '%s' is no list.".formatted(job, WORKFLOW));
        return ((List<?>) steps)
                .stream()
                .map(step -> map(step, "a step of job '%s'".formatted(job)))
                .toList();
    }

    /** The command of every step of every job of a workflow. */
    private static List<String> runs(final Path workflow) throws IOException {
        final var name = String.valueOf(workflow.getFileName());
        final var runs = new ArrayList<String>();
        final var content = map(new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(workflow)),
                name);
        for (final var job : map(content.get("jobs"), "%s: jobs".formatted(name)).values()) {
            final var steps = map(job, "%s: a job".formatted(name)).get("steps");
            if (steps instanceof List<?> list) {
                list
                        .stream()
                        .map(step -> map(step, "%s: a step".formatted(name)).get("run"))
                        .filter(run -> run != null)
                        .forEach(run -> runs.add(String.valueOf(run)));
            }
        }
        return runs;
    }

    private static List<Path> workflows() throws IOException {
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            return files
                    .filter(file -> file
                            .getFileName()
                            .toString()
                            .matches(".+\\.ya?ml"))
                    .sorted()
                    .toList();
        }
    }

    private static String readmeSection() {
        try {
            final var readme = Files.readString(ROOT.resolve("README.md"));
            final var start = readme.indexOf("## Releasing");
            assertTrue(start >= 0, "README.md has no section '## Releasing' any more.");
            final var end = readme.indexOf("\n## ", start + 1);
            return readme.substring(start, (end < 0) ? readme.length() : end);
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

}
