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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * says about it: when it runs, which jobs it has, that they do not wait for one another but only
 * for the job which decides whether they run, that a job runs where its inputs changed or cannot be
 * read and records them once its build ran, that only the job against the engine's newest build may
 * fail, that it writes nothing, and that every job writes its line into the run's summary.
 */
@ExtendWith(SuppressOutputExtension.class)
class NightlyWorkflowTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final String WORKFLOW = ".github/workflows/nightly.yaml";

    private static final String RESOLVING_SCRIPT = "bin/resolve-latest-engine.sh";

    private static final String BLUEPRINTS_SCRIPT = "bin/build-blueprints-on-zenbpm.sh";

    private static final String INPUTS_SCRIPT = "bin/what-the-nightly-builds-on.sh";

    /** The job which reads what the others build on and decides which of them run. */
    private static final String DECIDING_JOB = "what-changed";

    /** What a change of starts the nightly in its pull request: the workflow, what it runs, the overlay. */
    private static final List<String> STARTING_PATHS = List.of(WORKFLOW, INPUTS_SCRIPT, RESOLVING_SCRIPT,
            BLUEPRINTS_SCRIPT, "bin/zenbpm-engine.sh", "blueprints-overlay/**");

    /** The jobs the plan asks of the nightly; a later one is added next to them. */
    private static final Set<String> REQUIRED_JOBS = Set.of(DECIDING_JOB, "pinned-engine", "latest-engine",
            "native-image", "blueprints");

    /** The days of the two schedules: the runs of Monday to Saturday, and the run of Sunday. */
    private static final List<String> SCHEDULED_DAYS = List.of("1-6", "0");

    private static final Pattern DECIDED_JOBS = Pattern.compile("(?m)^for job in ([a-z0-9 -]+); do$");

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
    @DisplayName("The nightly runs every night at the time the README names, every job on Sunday, on a dispatch, and on a pull request changing it")
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

        final var schedules = list(triggers.get("schedule"), "on.schedule")
                .stream()
                .map(schedule -> String.valueOf(map(schedule, "a schedule").get("cron")))
                .toList();
        final var cron = schedules.getFirst();
        assertTrue(cron.matches("[1-5]?[0-9] (1?[0-9]|2[0-3]) \\* \\* [0-9,-]+"), () -> """
                The schedule '%s' is not one fixed time on some days of the week.""".formatted(cron));
        final var fields = cron.split(" ");
        final var time = "%s %s".formatted(fields[0], fields[1]);
        assertEquals(SCHEDULED_DAYS
                .stream()
                .map(days -> "%s * * %s".formatted(time, days))
                .toList(), schedules, """
                        The nightly runs once a night, at one time: Monday to Saturday ('1-6') only the jobs \
                        whose inputs changed, and on Sunday ('0') every job, as the README says.""");
        assertEquals("%s * * %s".formatted(time, SCHEDULED_DAYS.getLast()), deciding()
                .get("EVERY_JOB_ON"), """
                        The step of 'what-changed' which decides which jobs run has to name the schedule of \
                        Sunday in EVERY_JOB_ON, so that the run of Sunday runs every job.""");
        assertTrue(readmeSection().contains("every job on Sunday"), """
                The README's section 'What CI runs' has to say that the nightly runs 'every job on \
                Sunday'.""");
        final var minute = Integer.parseInt(fields[0]);
        assertTrue(minute != 0, "GitHub delays the runs it starts at the full hour; take another minute.");
        final var named = "**Every night** at %02d:%02d UTC".formatted(Integer.parseInt(fields[1]), minute);
        assertTrue(readmeSection().contains(named), () -> """
                The README's section 'What CI runs' has to say '%s', the time of the schedule '%s'."""
                .formatted(named, cron));
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
    @DisplayName("Every job waits only for what-changed and runs where it failed, and only latest-engine may fail")
    void theJobsAreIndependentAndOnlyTheLatestEngineMayFail() {
        final var waiting = new TreeMap<String, Object>();
        final var gated = new TreeMap<String, Object>();
        final var mayFail = new TreeSet<String>();
        for (final var job : jobs().entrySet()) {
            if (DECIDING_JOB.equals(job.getKey())) {
                waiting.put(job.getKey(), job
                        .getValue()
                        .get("needs"));
                continue;
            }
            if (!DECIDING_JOB.equals(job
                    .getValue()
                    .get("needs"))) {
                waiting.put(job.getKey(), job
                        .getValue()
                        .get("needs"));
            }
            final var expected = """
                    ${{ !cancelled() && (needs.%s.result != 'success' || needs.%s.outputs.%s == 'run') }}"""
                    .formatted(DECIDING_JOB, DECIDING_JOB, job.getKey());
            if (!expected.equals(job
                    .getValue()
                    .get("if"))) {
                gated.put(job.getKey(), job
                        .getValue()
                        .get("if"));
            }
            if (mayFail(job.getValue())) {
                mayFail.add(job.getKey());
            }
        }

        final var nobody = new TreeMap<String, Object>();
        nobody.put(DECIDING_JOB, null);
        assertEquals(nobody, waiting, """
                Every job of the nightly but '%s' has to wait for it alone ('needs: %s'), and '%s' \
                for none. The README promises jobs which do not depend on one another, so a red job \
                never hides the answer of another.""".formatted(DECIDING_JOB, DECIDING_JOB, DECIDING_JOB));
        assertEquals(Map.of(), gated, """
                These jobs of the nightly are not run exactly where '%s' decided so or failed. A job \
                has to say "if: ${{ !cancelled() && (needs.%s.result != 'success' || \
                needs.%s.outputs.<job> == 'run') }}": an input which cannot be read is no reason to \
                skip a build.""".formatted(DECIDING_JOB, DECIDING_JOB, DECIDING_JOB));
        assertEquals(Set.of("latest-engine"), mayFail, """
                Only 'latest-engine' may fail without turning the nightly red ('continue-on-error'): it \
                runs against an engine this repository does not pin. Every other job has to fail the run.""");
    }

    @Test
    @DisplayName("The job which decides reads every job's inputs with the script, looks each key up, and decides for every job")
    void whatChangedDecidesForEveryJob() {
        final var decided = jobs()
                .keySet()
                .stream()
                .filter(job -> !DECIDING_JOB.equals(job))
                .toList();
        final var deciding = jobs().get(DECIDING_JOB);
        final var steps = steps(DECIDING_JOB);
        final var outputs = map(deciding.get("outputs"), "the outputs of '%s'".formatted(DECIDING_JOB));
        final var script = String.valueOf(steps
                .stream()
                .filter(step -> "decide".equals(step.get("id")))
                .findFirst()
                .orElseThrow()
                .get("run"));
        final var loop = DECIDED_JOBS.matcher(script);

        assertTrue(steps
                .stream()
                .anyMatch(step -> "read".equals(step.get("id")) && INPUTS_SCRIPT.equals(step.get("run"))),
                () -> """
                        '%s' has to read the inputs with '%s' in a step of the id 'read'."""
                        .formatted(DECIDING_JOB, INPUTS_SCRIPT));
        assertTrue(loop.find() && List
                .of(loop
                        .group(1)
                        .split(" "))
                .equals(decided), () -> """
                        The step 'decide' of '%s' has to decide for every other job, in the order of the \
                        workflow: 'for job in %s; do'.""".formatted(DECIDING_JOB, String.join(" ", decided)));
        for (final var job : decided) {
            assertEquals("${{ steps.decide.outputs.%s }}".formatted(job), outputs.get(job), () -> """
                    '%s' has to hand on the decision for '%s' as its output '%s'; without it the job \
                    never runs.""".formatted(DECIDING_JOB, job, job));
            assertEquals("${{ steps.read.outputs.%s-key }}".formatted(job), outputs.get(job
                    + "-key"),
                    () -> """
                            '%s' has to hand on the key of the inputs of '%s' as its output '%s-key'."""
                            .formatted(DECIDING_JOB, job, job));
            assertEquals("${{ steps.%s.outputs.cache-hit }}".formatted(job), outputs.get(job
                    + "-seen"), () -> """
                            '%s' has to hand on whether '%s' ran against these inputs before as its output \
                            '%s-seen'.""".formatted(DECIDING_JOB, job, job));
            final var lookup = steps
                    .stream()
                    .filter(step -> job.equals(step.get("id")))
                    .findFirst();
            assertTrue(lookup.isPresent() && String
                    .valueOf(lookup
                            .get()
                            .get("uses"))
                    .startsWith("actions/cache/restore@") && "${{ steps.read.outputs.%s-key != '' }}".formatted(job)
                            .equals(lookup
                                    .get()
                                    .get("if")),
                    () -> """
                            '%s' has to look the key of '%s' up with actions/cache/restore in \
                            a step of the id '%s', only where the key is not empty."""
                            .formatted(DECIDING_JOB, job, job));
            final var with = map(lookup
                    .get()
                    .get("with"), "the inputs of the lookup of '%s'".formatted(job));
            assertEquals(Boolean.TRUE, with.get("lookup-only"),
                    () -> "The lookup of '%s' only asks; 'lookup-only: true'."
                            .formatted(job));
            assertEquals("${{ steps.read.outputs.%s-key }}".formatted(job), with.get("key"), () -> """
                    The lookup of '%s' has to look up the key of its inputs.""".formatted(job));
        }
    }

    /**
     * A job which recorded its inputs before its build ran would be skipped the next night without
     * a build behind it. A job which has to fail the run and recorded a failed build would be skipped
     * the next night as well, and GitHub reports a skipped job as passed: the run and the badge would
     * turn green while the build is still broken. So such a job records only a passed build, and
     * 'latest-engine', which never turns the run red, records a failed one too, so that a new build
     * of the engine is reported once. A step which writes no file into the saved path, or a lookup
     * of another path, would find nothing and never skip a job, without any test noticing.
     */
    @Test
    @DisplayName("Every job records the key of its inputs after its build, a failed build only in latest-engine, and only on main")
    void everyJobRecordsItsInputsAfterItsBuild() {
        final var lookups = steps(DECIDING_JOB)
                .stream()
                .filter(step -> String
                        .valueOf(step.get("uses"))
                        .startsWith("actions/cache/restore@"))
                .map(step -> map(step.get("with"), "the inputs of actions/cache/restore").get("path"))
                .collect(Collectors.toSet());
        assertEquals(1, lookups.size(), () -> "The lookups of '%s' have to look up one path, but look up %s."
                .formatted(DECIDING_JOB, lookups));
        final var path = String.valueOf(lookups
                .iterator()
                .next());

        final var unrecorded = new TreeMap<String, String>();
        for (final var job : jobs().keySet()) {
            if (DECIDING_JOB.equals(job)) {
                continue;
            }
            final var outcome = "latest-engine".equals(job)
                    ? "(steps.build.outcome == 'success' || steps.build.outcome == 'failure')"
                    : "steps.build.outcome == 'success'";
            final var condition = """
                    ${{ !cancelled() && %s && github.ref == 'refs/heads/main' \
                    && needs.%s.outputs.%s-key != '' && needs.%s.outputs.%s-seen != 'true' }}"""
                    .formatted(outcome, DECIDING_JOB, job, DECIDING_JOB, job);
            final var key = "${{ needs.%s.outputs.%s-key }}".formatted(DECIDING_JOB, job);
            final var steps = steps(job);
            final var building = indexOf(steps, step -> "build".equals(step.get("id")));
            final var noting = indexOf(steps,
                    step -> condition.equals(step.get("if")) && "echo \"$KEY\" >\"$RUNNER_TEMP/nightly-inputs\""
                            .equals(step.get("run")) && key
                                    .equals(map(step.get("env"), "the env of the note").get("KEY")));
            final var saving = indexOf(steps, step -> String
                    .valueOf(step.get("uses"))
                    .startsWith("actions/cache/save@") && condition.equals(step.get("if")) && key.equals(map(step.get(
                            "with"), "the inputs of actions/cache/save").get("key")) && path.equals(map(
                                    step.get(
                                            "with"),
                                    "the inputs of actions/cache/save").get("path")));
            if ((building < 0) || (noting <= building) || (saving <= noting)) {
                unrecorded.put(job, "build at %d, note at %d, save at %d".formatted(building, noting, saving));
            }
        }

        assertEquals(Map.of(), unrecorded, """
                These jobs of the nightly do not record the key of their inputs as the README says. \
                Each needs, in this order, a step of the id 'build'; a step which writes the key with \
                'echo "$KEY" >"$RUNNER_TEMP/nightly-inputs"' and KEY set to \
                '${{ needs.%s.outputs.<job>-key }}'; and actions/cache/save of that key and of the path \
                the lookups of '%s' look up, '%s'. Both run with "if: ${{ !cancelled() && \
                steps.build.outcome == 'success' && github.ref == 'refs/heads/main' && \
                needs.%s.outputs.<job>-key != '' && needs.%s.outputs.<job>-seen != 'true' }}", and \
                'latest-engine' also after a failed build, with "(steps.build.outcome == 'success' || \
                steps.build.outcome == 'failure')"."""
                .formatted(DECIDING_JOB, DECIDING_JOB, path, DECIDING_JOB, DECIDING_JOB));
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

    /** The position of the first step which matches, or -1. */
    private static int indexOf(
        final List<Map<String, Object>> steps,
        final Predicate<Map<String, Object>> matching
    ) {
        for (var index = 0; index < steps.size(); ++index) {
            if (matching.test(steps.get(index))) {
                return index;
            }
        }
        return -1;
    }

    /** The environment of the step of the deciding job which decides which jobs run. */
    private static Map<String, Object> deciding() {
        return map(steps(DECIDING_JOB)
                .stream()
                .filter(step -> "decide".equals(step.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("'%s' has no step of the id 'decide' any more."
                        .formatted(DECIDING_JOB)))
                .get("env"), "the env of the step 'decide'");
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
