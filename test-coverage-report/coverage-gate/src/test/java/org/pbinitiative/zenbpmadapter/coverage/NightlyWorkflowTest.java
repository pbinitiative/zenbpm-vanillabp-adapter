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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The nightly workflow runs only on GitHub, and nothing else reads it before it runs: actionlint in
 * the pull-request check finds what is no valid workflow, not what contradicts the README. This test
 * holds <code>.github/workflows/nightly.yaml</code> to what the README's section "What CI runs"
 * says about it: when it runs, which jobs it has, that they do not wait for one another, that only
 * the job against the engine's newest build may fail, that it writes nothing, and that every job
 * writes its line into the run's summary.
 */
@ExtendWith(SuppressOutputExtension.class)
class NightlyWorkflowTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final String WORKFLOW = ".github/workflows/nightly.yaml";

    private static final String RESOLVING_SCRIPT = "bin/resolve-latest-engine.sh";

    private static final String BLUEPRINTS_SCRIPT = "bin/build-blueprints-on-zenbpm.sh";

    /** What a change of starts the nightly in its pull request: the workflow, what it runs, the overlay. */
    private static final List<String> STARTING_PATHS = List.of(WORKFLOW, RESOLVING_SCRIPT, BLUEPRINTS_SCRIPT,
            "bin/zenbpm-engine.sh", "blueprints-overlay/**");

    /** The jobs the plan asks of the nightly; a later one is added next to them. */
    private static final Set<String> REQUIRED_JOBS = Set.of("pinned-engine", "latest-engine", "native-image",
            "blueprints");

    private static final Pattern JOB_OF_THE_README = Pattern.compile("(?m)^- `([a-z0-9-]+)`:");

    private static final Pattern SECRET = Pattern.compile("secrets\\.([A-Za-z0-9_]+)");

    private static final Pattern SKIPPED_TESTS = Pattern.compile("skipTests|skipITs|maven\\.test\\.skip|-Dit\\.test");

    private static final Pattern WRITTEN_SUMMARY = Pattern.compile(">>\\s*\"\\$GITHUB_STEP_SUMMARY\"");

    private static final String NIGHTLY_BADGE = """
            [![Checks](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/checks.yaml/badge.svg?branch=main)]\
            (https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/checks.yaml)
            [![Nightly](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/nightly.yaml/badge.svg?branch=main)]\
            (https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/nightly.yaml)
            """;

    @Test
    @DisplayName("The nightly runs every night at the time the README names, on a dispatch, and on a pull request changing it")
    void theNightlyRunsWhenTheReadmeSays() {
        final var triggers = map(workflow().get("on"), "on");
        assertEquals(Set.of("schedule", "workflow_dispatch", "pull_request"), triggers.keySet(), """
                The nightly runs on its schedule, on a dispatch and on a pull request which changes it, \
                and on nothing else, as the README's section 'What CI runs' says.""");

        assertEquals(Map.of("paths", STARTING_PATHS), triggers.get("pull_request"), """
                A pull request starts the nightly only where it changes the nightly itself, a script it \
                runs or the blueprints' overlay: its native build and the blueprints take too long for \
                every pull request, and the README promises that no other pull request starts it.""");
        for (final var path : STARTING_PATHS) {
            assertTrue(readmeSection().contains("`%s`".formatted(path.replace("/**", "/"))), () -> """
                    The README's section 'What CI runs' has to name '%s', a change of which starts the \
                    nightly in its pull request.""".formatted(path));
        }

        final var schedules = list(triggers.get("schedule"), "on.schedule");
        assertEquals(1, schedules.size(), "The nightly runs once a night: one schedule.");
        final var cron = String.valueOf(map(schedules.getFirst(), "on.schedule[0]").get("cron"));
        final var fields = cron.split(" ");
        assertTrue(
                (fields.length == 5) && fields[0].matches("[1-5]?[0-9]") && fields[1].matches("1?[0-9]|2[0-3]") && cron
                        .endsWith(" * * *"),
                () -> "The schedule '%s' is not one fixed time every day.".formatted(cron));
        final var minute = Integer.parseInt(fields[0]);
        assertTrue(minute != 0, "GitHub delays the runs it starts at the full hour; take another minute.");
        final var time = "**Every night** at %02d:%02d UTC".formatted(Integer.parseInt(fields[1]), minute);
        assertTrue(readmeSection().contains(time), () -> """
                The README's section 'What CI runs' has to say '%s', the time of the schedule '%s'."""
                .formatted(time, cron));
    }

    @Test
    @DisplayName("The README names every job of the nightly, and the nightly has the jobs the README names")
    void theReadmeNamesEveryJob() {
        final var jobs = jobs().keySet();
        final var named = new TreeSet<String>();
        final var nightly = readmeSection().substring(readmeSection().indexOf("**Every night**"));
        JOB_OF_THE_README
                .matcher(nightly)
                .results()
                .forEach(job -> named.add(job.group(1)));

        assertEquals(new TreeSet<>(jobs), named, """
                The jobs of '%s' and the list of jobs in the README's section 'What CI runs' have to be \
                the same: describe a new job there, and remove a job's description with the job."""
                .formatted(WORKFLOW));
        assertTrue(jobs.containsAll(REQUIRED_JOBS), () -> "The nightly has to keep the jobs %s, it has %s."
                .formatted(new TreeSet<>(REQUIRED_JOBS), jobs));
    }

    @Test
    @DisplayName("No job of the nightly waits for another, and only the job against the engine's newest build may fail")
    void theJobsAreIndependentAndOnlyTheLatestEngineMayFail() {
        final var waiting = new TreeSet<String>();
        final var mayFail = new TreeSet<String>();
        for (final var job : jobs().entrySet()) {
            if (job
                    .getValue()
                    .containsKey("needs")) {
                waiting.add(job.getKey());
            }
            if (mayFail(job.getValue())) {
                mayFail.add(job.getKey());
            }
        }

        assertEquals(Set.of(), waiting, """
                These jobs of the nightly wait for another one ('needs'). The README promises jobs \
                which do not depend on one another, so a red job never hides the answer of another.""");
        assertEquals(Set.of("latest-engine"), mayFail, """
                Only 'latest-engine' may fail without turning the nightly red ('continue-on-error'): it \
                runs against an engine this repository does not pin. Every other job has to fail the run.""");
    }

    /**
     * A job fails the run only where each of its steps does: a step which may fail, or a command
     * whose failure is swallowed, turns a red build into a green job as well as a job's
     * <code>continue-on-error</code> does. The steps which only report, run on <code>always()</code>
     * or <code>failure()</code>, may swallow what they like.
     */
    @Test
    @DisplayName("No step of a job which has to fail the run lets a failure pass")
    void noStepOfAJobWhichHasToFailLetsAFailurePass() {
        final var hiding = new TreeSet<String>();
        for (final var job : jobs().keySet()) {
            if ("latest-engine".equals(job)) {
                continue;
            }
            for (final var step : steps(job)) {
                final var run = String.valueOf(step.get("run"));
                if (mayFail(step) || (!step.containsKey("if") && run.contains("||"))) {
                    hiding.add("job '%s', step '%s'".formatted(job, step.getOrDefault("name", step.get("uses"))));
                }
            }
        }

        assertEquals(Set.of(), hiding, """
                These steps let a failure pass, with 'continue-on-error' or with a command after '||', \
                although their job has to fail the nightly when its build fails. Only 'latest-engine' may \
                fail without turning the run red.""");
    }

    @Test
    @DisplayName("The nightly writes nothing: every permission it asks for reads, and it uses no secret but its token")
    void theNightlyWritesNothing() throws IOException {
        final var workflow = workflow();
        final var asked = new LinkedHashMap<String, Object>();
        asked.put("the workflow", workflow.get("permissions"));
        for (final var job : jobs().entrySet()) {
            if (job
                    .getValue()
                    .containsKey("permissions")) {
                asked.put("job '%s'".formatted(job.getKey()), job
                        .getValue()
                        .get("permissions"));
            }
        }

        final var writing = new ArrayList<String>();
        for (final var permissions : asked.entrySet()) {
            if (!(permissions.getValue() instanceof Map<?, ?> scopes)) {
                writing.add("%s: %s".formatted(permissions.getKey(), permissions.getValue()));
                continue;
            }
            for (final var scope : scopes.entrySet()) {
                if (!Set
                        .of("read", "none")
                        .contains(String.valueOf(scope.getValue()))) {
                    writing.add("%s: %s: %s".formatted(permissions.getKey(), scope.getKey(), scope.getValue()));
                }
            }
        }
        assertTrue(workflow.get("permissions") instanceof Map<?, ?>, """
                The nightly has to name its permissions at the top, so a job which names none gets \
                these and not the repository's default.""");
        assertEquals(List.of(), writing, """
                The nightly may only read: list every permission by its scope, as 'read' or 'none'. \
                No job of it writes to a repository, a package or an issue tracker.""");

        final var secrets = new TreeSet<String>();
        SECRET
                .matcher(Files.readString(ROOT.resolve(WORKFLOW)))
                .results()
                .forEach(secret -> secrets.add(secret.group(1)));
        assertTrue(Set
                .of("GITHUB_TOKEN")
                .containsAll(secrets), () -> """
                        The nightly uses the secrets %s. It may use the workflow's GITHUB_TOKEN only, so a \
                        pull request from a fork which changes it runs like any other.""".formatted(secrets));
    }

    @Test
    @DisplayName("Every job of the nightly writes its line into the run's summary, also when it failed")
    void everyJobWritesItsSummaryLine() {
        final var silent = new TreeSet<String>();
        for (final var name : jobs().keySet()) {
            final var writes = steps(name)
                    .stream()
                    .anyMatch(step -> String
                            .valueOf(step.get("if"))
                            .contains("always()") && WRITTEN_SUMMARY
                                    .matcher(String.valueOf(step.get("run")))
                                    .find());
            if (!writes) {
                silent.add(name);
            }
        }

        assertEquals(Set.of(), silent, """
                These jobs of the nightly write no line into the run's summary. Give each a last step \
                with 'if: always()' which appends its line to "$GITHUB_STEP_SUMMARY", as the README's \
                section 'What CI runs' promises.""");
    }

    @Test
    @DisplayName("The job against the engine's newest build runs the build against the image it resolved, without the coverage gate")
    void theLatestEngineIsTheResolvedOne() {
        final var steps = steps("latest-engine");
        final var resolving = steps
                .stream()
                .filter(step -> "bin/resolve-latest-engine.sh ghcr.io/pbinitiative/zenbpm:dev".equals(step.get("run")))
                .findFirst();
        assertTrue(resolving.isPresent(), """
                'latest-engine' has to resolve 'ghcr.io/pbinitiative/zenbpm:dev' with \
                'bin/resolve-latest-engine.sh ghcr.io/pbinitiative/zenbpm:dev', as the README says.""");
        final var id = resolving
                .get()
                .get("id");

        final var building = steps
                .stream()
                .filter(step -> String
                        .valueOf(step.get("run"))
                        .startsWith("mvn "))
                .toList();
        assertEquals(1, building.size(), "'latest-engine' runs one build.");
        final var build = building.getFirst();
        final var run = String.valueOf(build.get("run"));
        final var env = map(build.get("env"), "the env of the build of 'latest-engine'");
        assertTrue(
                run.contains("\"-Dzenbpm.image=$ENGINE_IMAGE\"") && run
                        .contains("\"-Dzenbpm.commit=$ENGINE_COMMIT\"") && "${{ steps.%s.outputs.image }}".formatted(id)
                                .equals(env.get("ENGINE_IMAGE")) && "${{ steps.%s.outputs.commit }}".formatted(id)
                                        .equals(env.get("ENGINE_COMMIT")),
                () -> """
                        The build of 'latest-engine' has to run with '-Dzenbpm.image' and '-Dzenbpm.commit' \
                        set to the outputs 'image' and 'commit' of the step '%s', but runs '%s' with %s."""
                        .formatted(id, run, env));
        assertTrue(run.contains("--projects '!test-coverage-report/coverage-gate'"), () -> """
                The build of 'latest-engine' has to leave out the coverage gate, whose EnginePinTest \
                compares the README with the engine the build runs against, but runs '%s'.""".formatted(run));
    }

    @Test
    @DisplayName("The native image is built with Mandrel for Java 25 on the runner, not in the builder container")
    void theNativeImageIsBuiltOnTheRunner() {
        final var steps = steps("native-image");
        final var setup = steps
                .stream()
                .filter(step -> String
                        .valueOf(step.get("uses"))
                        .startsWith("graalvm/setup-graalvm@"))
                .findFirst();
        assertTrue(setup.isPresent(), "'native-image' has to install its GraalVM with graalvm/setup-graalvm.");
        final var with = map(setup
                .get()
                .get("with"), "the inputs of graalvm/setup-graalvm");
        assertEquals("mandrel", with.get("distribution"), "The README names Mandrel, the GraalVM Quarkus builds with.");
        assertEquals("25", with.get("java-version"), "The README names Mandrel for Java 25.");

        final var building = steps
                .stream()
                .map(step -> String.valueOf(step.get("run")))
                .filter(run -> run.contains("-Dnative"))
                .toList();
        assertEquals(1, building.size(), "'native-image' builds the image in one step, with '-Dnative'.");
        final var build = building
                .getFirst()
                .strip();
        assertTrue(build.contains("-Dquarkus.native.container-build=false"), """
                'native-image' has to build the image with '-Dquarkus.native.container-build=false', so \
                that the GraalVM on the runner builds it.""");
        assertTrue(
                build.startsWith("mvn ") && build.contains("--projects quarkus/native-image-tests") && build.endsWith(
                        " verify") && !SKIPPED_TESTS
                                .matcher(build)
                                .find(),
                () -> """
                        'native-image' has to run ZenBpmNativeImageIT against the binary it built: a Maven \
                        call of 'quarkus/native-image-tests' up to 'verify', which runs failsafe, and without \
                        skipping tests, but runs '%s'.""".formatted(build));
    }

    @Test
    @DisplayName("The blueprints are built by the script with the adapter installed from the checkout, into a directory of the runner")
    void theBlueprintsAreBuiltWithTheAdapterOfTheCheckout() {
        final var runs = steps("blueprints")
                .stream()
                .map(step -> String
                        .valueOf(step.get("run"))
                        .strip())
                .toList();
        final var installing = runs.indexOf(
                "mvn --batch-mode --no-transfer-progress --update-snapshots -Dmaven.test.skip=true --projects spring-boot,quarkus/deployment --also-make install");
        final var building = runs.indexOf("%s \"$RUNNER_TEMP/blueprints\"".formatted(BLUEPRINTS_SCRIPT));

        assertTrue(installing >= 0, () -> """
                'blueprints' has to install the Spring Boot starter and the Quarkus extension of this \
                checkout, which the blueprints' profile 'zenbpm' reads, but runs %s.""".formatted(runs));
        assertTrue(building > installing, () -> """
                'blueprints' has to run '%s "$RUNNER_TEMP/blueprints"' after installing the adapter, so \
                the blueprints are cloned fresh and built against this checkout, but runs %s."""
                .formatted(BLUEPRINTS_SCRIPT, runs));
    }

    @Test
    @DisplayName("The README shows the nightly's badge of main next to the badge of the checks")
    void theReadmeShowsTheBadge() throws IOException {
        assertTrue(Files
                .readString(ROOT.resolve("README.md"))
                .contains(NIGHTLY_BADGE), () -> """
                        The README has to show the nightly's badge of 'main' in the line after the badge of \
                        the checks:
                        %s""".formatted(NIGHTLY_BADGE));
    }

    /**
     * Whether a job or step may fail without failing what it stands in: a
     * <code>continue-on-error</code> which is anything but <code>false</code>, an expression included.
     */
    private static boolean mayFail(final Map<String, Object> jobOrStep) {
        return jobOrStep.containsKey("continue-on-error") && !Boolean.FALSE.equals(jobOrStep.get("continue-on-error"));
    }

    private static Map<String, Object> workflow() {
        try {
            return map(
                    new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(ROOT.resolve(WORKFLOW))),
                    WORKFLOW);
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
        return list(jobs
                .get(job)
                .get("steps"), "jobs.%s.steps".formatted(job))
                .stream()
                .map(step -> map(step, "a step of job '%s'".formatted(job)))
                .toList();
    }

    private static String readmeSection() {
        try {
            final var readme = Files.readString(ROOT.resolve("README.md"));
            final var start = readme.indexOf("## What CI runs");
            assertTrue(start >= 0, "README.md has no section '## What CI runs' any more.");
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

    private static List<?> list(
        final Object value,
        final String where
    ) {
        assertTrue(value instanceof List<?>, () -> "'%s' of '%s' is no list but '%s'".formatted(where, WORKFLOW,
                value));
        return (List<?>) value;
    }

}
