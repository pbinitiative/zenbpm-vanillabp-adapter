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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * The overlay of <code>blueprints-overlay/</code> is applied to the VanillaBP blueprints by the
 * nightly only, against a checkout of their newest <code>main</code>, see decision 64 in the
 * repository's DECISIONS.md. This test applies it to a small checkout of the same shape, so that
 * what the overlay adds, and that it ends the run where the blueprints changed in a way it cannot
 * follow, is seen in every build and not only in a red night. It also holds the overlay's list of
 * blueprints to the wiki's page <code>Blueprints</code>, which says why a blueprint does not run on
 * ZenBPM.
 */
@ExtendWith(SuppressOutputExtension.class)
class BlueprintsOverlayTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final Path OVERLAY = ROOT.resolve("blueprints-overlay");

    private static final String RUNS = "blueprints-bpmn/bpmn-service-task";

    private static final String NOT_RUNNABLE = "blueprints-bpmn/bpmn-signals";

    private static final String MODEL = "loan-approval/src/main/resources/loan-approval/processes/%s/loan_approval.bpmn";

    private static final String CAMUNDA8_MODEL = "<bpmn:definitions id=\"camunda8\" />\n";

    private static final String VARIANT = "<bpmn:definitions id=\"zenbpm\" />\n";

    /** A row of the table on the page <code>Blueprints</code>: the directory, whether it runs, why not. */
    private static final Pattern ROW = Pattern.compile(
            "(?m)^\\|[ \\t]*`(blueprints-[a-z0-9-]+/[a-z0-9-]+)`[ \\t]*\\|[ \\t]*([^|\\n]*?)[ \\t]*\\|[ \\t]*([^|\\n]*?)[ \\t]*\\|$");

    private static final Pattern REST_PORT = Pattern.compile("(?m)^REST_PORT=(\\d+)$");

    @TempDir
    private Path temporary;

    private Path overlay;

    private Path checkout;

    @BeforeEach
    void writeACheckoutAndAnOverlay() throws IOException {
        overlay = temporary.resolve("adapter/blueprints-overlay");
        checkout = temporary.resolve("blueprints");
        write(temporary.resolve("adapter/pom.xml"), """
                <project>
                  <properties>
                    <revision>9.9.9-SNAPSHOT</revision>
                    <zenbpm.image>ghcr.io/pbinitiative/zenbpm@sha256:pinned</zenbpm.image>
                  </properties>
                </project>
                """);
        write(overlay.resolve("blueprints.txt"), """
                # a comment
                %s runs
                %s not-runnable
                """.formatted(RUNS, NOT_RUNNABLE));
        write(overlay.resolve("variants.txt"), "");
        for (final var blueprint : List.of(RUNS, NOT_RUNNABLE)) {
            for (final var platform : List.of("springboot", "quarkus")) {
                writeTwin(checkout.resolve(blueprint).resolve(platform), platform);
            }
        }
    }

    @Test
    @DisplayName("Every POM with a profile camunda8 gets a profile zenbpm beside it which selects this adapter")
    void everyCamunda8ProfileGetsAZenBpmProfile() {
        final var run = overlay();

        assertEquals(0, run.exitCode(), run::output);
        final var module = words(checkout.resolve(RUNS).resolve("springboot/loan-approval/pom.xml"));
        assertTrue(module.contains("<profile> <id>camunda8</id> <activation> <activeByDefault>true</activeByDefault>"),
                "the profile camunda8 stays as it was");
        final var zenbpm = module.substring(module.indexOf("<!-- the profile of the ZenBPM overlay"));
        assertTrue(zenbpm.contains("""
                --> <profile> <id>zenbpm</id> <properties> <bpms>zenbpm</bpms> \
                <blueprint.bpms>zenbpm</blueprint.bpms> </properties> <dependencies> <dependency> \
                <groupId>org.pbinitiative.zenbpm</groupId> \
                <artifactId>zenbpm-vanillabp-adapter-spring-boot</artifactId> \
                <version>${zenbpm-vanillabp-adapter.version}</version> <scope>test</scope> </dependency> \
                </dependencies> </profile> </profiles>"""), () -> """
                The profile zenbpm has to be the profile camunda8 with this adapter, without the \
                activation by default and without the comment, but is:
                %s""".formatted(zenbpm));
        assertTrue(words(checkout.resolve(RUNS).resolve("quarkus/application/pom.xml")).contains(
                "<artifactId>zenbpm-vanillabp-adapter-quarkus</artifactId>"), "the Quarkus twin gets the extension");
        assertTrue(words(checkout.resolve(RUNS).resolve("springboot/pom.xml")).contains("""
                <camunda8-adapter.version>2.0.0-8.10-SNAPSHOT</camunda8-adapter.version> \
                <zenbpm-vanillabp-adapter.version>9.9.9-SNAPSHOT</zenbpm-vanillabp-adapter.version>"""),
                "the version of the adapter beside the overlay is declared beside Camunda 8's");
    }

    @Test
    @DisplayName("Every application-camunda8.yaml gets an application-zenbpm.yaml naming the engine of the engine script")
    void everyCamunda8ConfigurationGetsAZenBpmOne() {
        final var run = overlay();

        assertEquals(0, run.exitCode(), run::output);
        final var configuration = checkout.resolve(RUNS)
                .resolve("quarkus/application/src/main/resources/application-zenbpm.yaml");
        final Map<String, Object> zenbpm = new Yaml(new SafeConstructor(new LoaderOptions())).load(read(configuration));
        final var restPort = REST_PORT.matcher(read(ROOT.resolve("bin/zenbpm-engine.sh")));
        assertTrue(restPort.find(), "bin/zenbpm-engine.sh names no REST_PORT");
        assertEquals(Map.of("vanillabp", Map.of("adapters", Map.of("zenbpm", Map.of("name-clash-avoidance",
                "use-prefix", "rest-address", "http://localhost:%s".formatted(restPort.group(1)))))), zenbpm);
    }

    @Test
    @DisplayName("Another file named for Camunda 8 is copied for ZenBPM, the other remote engine, where it names no Camunda")
    void anotherFileForCamunda8IsCopied() {
        final var changelog = checkout.resolve(RUNS)
                .resolve("quarkus/application/src/main/resources/db/changelog-camunda8.xml");
        write(changelog, "<databaseChangeLog><include file=\"db/changelog.xml\" /></databaseChangeLog>\n");

        final var run = overlay();

        assertEquals(0, run.exitCode(), run::output);
        assertEquals(read(changelog), read(changelog.resolveSibling("changelog-zenbpm.xml")));
    }

    @Test
    @DisplayName("Another file named for Camunda 8 which names Camunda ends the run")
    void anotherFileForCamunda8WhichNamesCamundaEndsTheRun() {
        write(checkout.resolve(RUNS).resolve("quarkus/application/src/main/resources/db/changelog-camunda8.xml"),
                "<databaseChangeLog><include file=\"camunda/engine.xml\" /></databaseChangeLog>\n");

        assertTheRunEndsNaming(
                "%s/quarkus/application/src/main/resources/db/changelog-camunda8.xml is a file for Camunda 8 which names Camunda"
                        .formatted(RUNS));
    }

    @Test
    @DisplayName("Every model for Camunda 8 is copied for ZenBPM, and a variant of the overlay replaces its model")
    void theModelsAreCopiedAndAVariantReplacesItsModel() {
        final var variant = "%s/%s".formatted(RUNS, MODEL.formatted("zenbpm"));
        write(overlay.resolve("variants").resolve(variant), VARIANT);
        write(overlay.resolve("variants.txt"), "%s %s%n".formatted(variant, sha256(CAMUNDA8_MODEL)));
        write(checkout.resolve(RUNS).resolve("quarkus").resolve(MODEL.formatted("camunda8")).resolveSibling(
                "decision.dmn"), "<dmn />");

        final var run = overlay();

        assertEquals(0, run.exitCode(), run::output);
        for (final var platform : List.of("springboot", "quarkus")) {
            assertEquals(VARIANT, read(checkout.resolve(RUNS).resolve(platform).resolve(MODEL.formatted("zenbpm"))));
            assertEquals(CAMUNDA8_MODEL, read(checkout.resolve(RUNS).resolve(platform).resolve(MODEL.formatted(
                    "camunda8"))));
        }
        assertEquals("<dmn />", read(checkout.resolve(RUNS).resolve("quarkus").resolve(MODEL.formatted("zenbpm"))
                .resolveSibling("decision.dmn")));
    }

    @Test
    @DisplayName("A user task which a model names by the external reference of its form only gets that name as its task definition")
    void aUserTaskNamedByItsFormGetsItsTaskDefinition() {
        final var model = MODEL.replace("loan_approval", "user_tasks");
        write(checkout.resolve(RUNS).resolve("springboot").resolve(model.formatted("camunda8")), """
                <bpmn:definitions>
                  <bpmn:userTask id="AssessRisk">
                    <bpmn:extensionElements>
                      <zeebe:userTask />
                      <zeebe:formDefinition externalReference="assessRisk" />
                    </bpmn:extensionElements>
                  </bpmn:userTask>
                  <bpmn:userTask id="Approve" name="already named">
                    <bpmn:extensionElements>
                      <zeebe:taskDefinition type="approve" />
                      <zeebe:formDefinition externalReference="approval" />
                    </bpmn:extensionElements>
                  </bpmn:userTask>
                  <bpmn:userTask id="Review">
                    <bpmn:extensionElements>
                      <zeebe:formDefinition formId="review" />
                    </bpmn:extensionElements>
                  </bpmn:userTask>
                </bpmn:definitions>
                """);

        final var run = overlay();

        assertEquals(0, run.exitCode(), run::output);
        assertEquals("""
                <bpmn:definitions>
                  <bpmn:userTask id="AssessRisk">
                    <bpmn:extensionElements>
                      <zeebe:userTask />
                      <zeebe:taskDefinition type="assessRisk" />
                      <zeebe:formDefinition externalReference="assessRisk" />
                    </bpmn:extensionElements>
                  </bpmn:userTask>
                  <bpmn:userTask id="Approve" name="already named">
                    <bpmn:extensionElements>
                      <zeebe:taskDefinition type="approve" />
                      <zeebe:formDefinition externalReference="approval" />
                    </bpmn:extensionElements>
                  </bpmn:userTask>
                  <bpmn:userTask id="Review">
                    <bpmn:extensionElements>
                      <zeebe:formDefinition formId="review" />
                    </bpmn:extensionElements>
                  </bpmn:userTask>
                </bpmn:definitions>
                """, read(checkout.resolve(RUNS).resolve("springboot").resolve(model.formatted("zenbpm"))));
    }

    @Test
    @DisplayName("Only the twins of the blueprints which run are listed to be built, and nothing is added to the others")
    void onlyTheTwinsOfTheBlueprintsWhichRunAreBuilt() {
        final var run = overlay();

        assertEquals(0, run.exitCode(), run::output);
        assertEquals("%1$s/springboot%n%1$s/quarkus%n".formatted(RUNS), read(checkout.resolve("zenbpm-twins.txt")));
        assertFalse(read(checkout.resolve(NOT_RUNNABLE).resolve("springboot/application/pom.xml")).contains("zenbpm"));
        assertFalse(Files.exists(checkout.resolve(NOT_RUNNABLE).resolve("springboot").resolve(MODEL.formatted(
                "zenbpm"))));
    }

    @Test
    @DisplayName("A blueprint the overlay's list does not name ends the run, naming the list and the page")
    void aBlueprintTheListDoesNotNameEndsTheRun() {
        writeTwin(checkout.resolve("blueprints-modules/module-new/springboot"), "springboot");

        assertTheRunEndsNaming("The blueprint 'blueprints-modules/module-new' is not in blueprints.txt",
                "the wiki page 'Blueprints'");
    }

    @Test
    @DisplayName("A blueprint of the list which is gone ends the run")
    void aListedBlueprintWhichIsGoneEndsTheRun() {
        delete(checkout.resolve(NOT_RUNNABLE));

        assertTheRunEndsNaming("The blueprint '%s' of blueprints.txt is not in the checkout any more".formatted(
                NOT_RUNNABLE));
    }

    @Test
    @DisplayName("A profile camunda8 with more than the Camunda 8 adapter ends the run")
    void aCamunda8ProfileWithMoreThanTheAdapterEndsTheRun() {
        final var pom = checkout.resolve(RUNS).resolve("springboot/application/pom.xml");
        write(pom, read(pom).replace("<artifactId>camunda8-adapter-spring-boot</artifactId>",
                "<artifactId>camunda8-adapter-spring-boot-webapps</artifactId>"));

        assertTheRunEndsNaming(
                "The profile 'camunda8' of %s/springboot/application/pom.xml has more than the overlay translates"
                        .formatted(RUNS),
                "camunda8-adapter-spring-boot-webapps");
    }

    @Test
    @DisplayName("A POM which chooses a BPMS without a profile camunda8 ends the run")
    void aPomChoosingABpmsWithoutACamunda8ProfileEndsTheRun() {
        final var pom = checkout.resolve(RUNS).resolve("quarkus/loan-approval/pom.xml");
        write(pom, read(pom).replace("<id>camunda8</id>", "<id>camunda7</id>"));

        assertTheRunEndsNaming(
                "%s/quarkus/loan-approval/pom.xml chooses a BPMS but has no profile 'camunda8'".formatted(
                        RUNS));
    }

    @Test
    @DisplayName("A profile of a BPMS activated by more than activeByDefault ends the run, because -Pzenbpm would leave it on")
    void aBpmsProfileActivatedByMoreThanItsDefaultEndsTheRun() {
        final var pom = checkout.resolve(RUNS).resolve("springboot/application/pom.xml");
        write(pom, read(pom).replace("<activeByDefault>true</activeByDefault>",
                "<!-- a comment --><property><name>!bpms</name></property>"));

        assertTheRunEndsNaming(
                "The profile 'camunda8' of %s/springboot/application/pom.xml is activated by more than activeByDefault"
                        .formatted(RUNS),
                "<property><name>!bpms</name></property>");
    }

    @Test
    @DisplayName("A twin without the version of the Camunda 8 adapter ends the run")
    void aTwinWithoutTheVersionOfTheCamunda8AdapterEndsTheRun() {
        final var pom = checkout.resolve(RUNS).resolve("quarkus/pom.xml");
        write(pom, read(pom).replace("camunda8-adapter.version", "camunda-adapter.version"));

        assertTheRunEndsNaming("No POM of %s/quarkus declares 'camunda8-adapter.version'".formatted(RUNS));
    }

    @Test
    @DisplayName("An application-camunda8.yaml with more than the overlay translates ends the run")
    void aCamunda8ConfigurationWithMoreThanTheOverlayTranslatesEndsTheRun() {
        final var configuration = checkout.resolve(RUNS).resolve(
                "springboot/loan-approval/src/test/resources/application-camunda8.yaml");
        write(configuration, read(configuration)
                + "      retry-backoff: PT2S\n");

        assertTheRunEndsNaming(
                "%s/springboot/loan-approval/src/test/resources/application-camunda8.yaml says more than the overlay translates"
                        .formatted(RUNS),
                "retry-backoff: PT2S");
    }

    @Test
    @DisplayName("A model which changed since its variant was written ends the run, naming the hash to check")
    void aModelWhichChangedSinceItsVariantEndsTheRun() {
        final var variant = "%s/%s".formatted(RUNS, MODEL.formatted("zenbpm"));
        write(overlay.resolve("variants").resolve(variant), VARIANT);
        write(overlay.resolve("variants.txt"), "%s %s%n".formatted(variant, sha256("<an older model />")));

        assertTheRunEndsNaming("The model %s/springboot/%s changed since its variant".formatted(RUNS, MODEL.formatted(
                "camunda8")), sha256(CAMUNDA8_MODEL));
    }

    @Test
    @DisplayName("A variant which replaces no model ends the run")
    void aVariantWhichReplacesNoModelEndsTheRun() {
        final var variant = "%s/%s".formatted(RUNS, MODEL
                .formatted("zenbpm")
                .replace("loan_approval", "gone"));
        write(overlay.resolve("variants").resolve(variant), VARIANT);
        write(overlay.resolve("variants.txt"), "%s %s%n".formatted(variant, sha256(CAMUNDA8_MODEL)));

        assertTheRunEndsNaming("The variant '%s' replaces no model".formatted(variant));
    }

    @Test
    @DisplayName("The engine of the blueprints gives a task whose model names no retries three attempts, as Camunda 8 does")
    void theEngineGivesATaskWithoutRetriesThreeAttempts() {
        assertTrue(read(ROOT.resolve("bin/zenbpm-engine.sh")).contains("--env JOBS_DEFAULT_RETRIES=3"), """
                bin/zenbpm-engine.sh has to start the engine with JOBS_DEFAULT_RETRIES=3: the blueprints' \
                models are written for Camunda 8, which tries a task without 'retries' three times.""");
    }

    @Test
    @DisplayName("The overlay's list names every blueprint of the page Blueprints with the same answer, and every one which does not run has its reason")
    void theListAndThePageBlueprintsAgree() {
        final var listed = new TreeMap<String, String>();
        for (final var line : read(OVERLAY.resolve("blueprints.txt")).split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                final var fields = line
                        .strip()
                        .split("\\s+");
                listed.put(fields[0], fields[1]);
            }
        }
        final var page = new TreeMap<String, String>();
        final var withoutReason = new TreeSet<String>();
        ROW
                .matcher(read(ROOT.resolve("wiki/Blueprints.md")))
                .results()
                .forEach(row -> {
                    page.put(row.group(1), "runs".equals(row.group(2)) ? "runs" : "not-runnable");
                    if (!"runs".equals(row.group(2)) && row
                            .group(3)
                            .isBlank()) {
                        withoutReason.add(row.group(1));
                    }
                });

        assertEquals(listed, page, """
                The table of wiki/Blueprints.md has to name every blueprint of \
                blueprints-overlay/blueprints.txt, 'runs' where the list says 'runs' and 'not on ZenBPM' \
                where it says 'not-runnable'.""");
        assertEquals(new TreeSet<String>(), withoutReason,
                "A blueprint which does not run on ZenBPM has its reason on the page.");
    }

    @Test
    @DisplayName("Every file below variants/ is listed in variants.txt with the hash of the model it replaces")
    void everyVariantIsListed() throws IOException {
        final var listed = new TreeSet<String>();
        for (final var line : read(OVERLAY.resolve("variants.txt")).split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                listed.add(line
                        .strip()
                        .split("\\s+")[0]);
            }
        }
        final var files = new TreeSet<String>();
        final var variants = OVERLAY.resolve("variants");
        if (Files.isDirectory(variants)) {
            try (var paths = Files.walk(variants)) {
                paths
                        .filter(Files::isRegularFile)
                        .forEach(file -> files.add(variants
                                .relativize(file)
                                .toString()
                                .replace('\\', '/')));
            }
        }

        assertEquals(files, listed,
                "blueprints-overlay/variants.txt has to list every file below variants/, and no other.");
    }

    @Test
    @DisplayName("The build script clones into a directory of its own only, and never builds a checkout it did not clone")
    void theBuildScriptRefusesADirectoryWhichExists() throws IOException {
        final var existing = Files.createDirectories(temporary.resolve("existing"));

        final var run = run(List.of("bash", ROOT
                .resolve("bin/build-blueprints-on-zenbpm.sh")
                .toString(), existing.toString()));

        assertEquals(2, run.exitCode(), run::output);
        assertTrue(run
                .output()
                .contains("exists; name a directory which does not, the blueprints are cloned fresh"), run::output);
        try (var entries = Files.list(existing)) {
            assertEquals(0, entries.count(), "nothing is cloned into a directory which exists");
        }
    }

    @Test
    @DisplayName("The build script builds every twin, each against an engine of its own, and ends with 1 where one failed")
    void theBuildScriptEndsRedWhereATwinFailed() {
        final var clone = temporary.resolve("clone");

        final var run = buildScript(clone, Map.of("FAILING_TWIN", RUNS
                + "/springboot"));

        assertEquals(1, run.exitCode(), run::output);
        assertEquals(List.of(RUNS
                + "/springboot failed",
                RUNS
                        + "/quarkus passed"),
                results(clone), run::output);
        assertTrue(run
                .output()
                .contains("1 of 2 twins failed."), run::output);
        assertTrue(Files.exists(clone.resolve("zenbpm-logs/%s_springboot.engine.log".formatted(RUNS.replace('/',
                '_')))), "the engine's log of the failed twin is kept");
        assertEquals(List.of("rm", "run", "rm", "rm", "run", "rm"), stubCalls("docker")
                .stream()
                .filter(call -> call.equals("run") || call.equals("rm"))
                .toList(), "every twin gets a fresh engine of its own, removed after its build");
        assertTrue(stubCalls("mvn")
                .stream()
                .allMatch(call -> call.endsWith("-Pzenbpm verify")),
                () -> """
                        A twin is verified, never installed into the local Maven repository: %s""".formatted(stubCalls(
                        "mvn")));
        assertFalse(Files.exists(stubs().resolve("running")), "no engine is left running");
    }

    @Test
    @DisplayName("The build script ends with 0 where every twin passed")
    void theBuildScriptEndsGreenWhereEveryTwinPassed() {
        final var clone = temporary.resolve("clone");

        final var run = buildScript(clone, Map.of());

        assertEquals(0, run.exitCode(), run::output);
        assertEquals(List.of(RUNS
                + "/springboot passed",
                RUNS
                        + "/quarkus passed"),
                results(clone), run::output);
    }

    @Test
    @DisplayName("The build script ends with 1 and builds nothing where the overlay does not fit the clone")
    void theBuildScriptEndsRedWhereTheOverlayDoesNotFit() {
        delete(checkout.resolve(NOT_RUNNABLE));
        final var clone = temporary.resolve("clone");

        final var run = buildScript(clone, Map.of());

        assertEquals(1, run.exitCode(), run::output);
        assertTrue(run
                .output()
                .contains("The ZenBPM overlay does not fit this checkout of the blueprints"), run::output);
        assertEquals(List.of(), stubCalls("mvn"), "no twin is built");
    }

    @Test
    @DisplayName("The engine script ends with 1 where its container does not start, also where another engine answers on its port")
    void theEngineScriptEndsRedWhereItsContainerDoesNotStart() {
        final var run = engineScript(Map.of("DOCKER_RUN_FAILS", "true"));

        assertEquals(1, run.exitCode(), run::output);
        assertTrue(run
                .output()
                .contains("The engine did not become ready (attempt 3 of 3)"), run::output);
        assertFalse(run
                .output()
                .contains("answers on"), run::output);
        assertEquals("rm", stubCalls("docker").getLast(), "the container of the last attempt is removed");
    }

    @Test
    @DisplayName("The engine script leaves the engine of another run alone and ends with 1")
    void theEngineScriptLeavesTheEngineOfAnotherRunAlone() {
        write(stubs().resolve("running"), "");

        final var run = engineScript(Map.of());

        assertEquals(1, run.exitCode(), run::output);
        assertTrue(run
                .output()
                .contains("The engine zenbpm-blueprints-engine is running already, started by another run"),
                run::output);
        assertTrue(Files.exists(stubs().resolve("running")), "the other run's engine is not removed");
        assertFalse(stubCalls("docker").contains("run"), "no second engine is started");
    }

    @Test
    @DisplayName("The build script leaves the engine of another run alone, also when it ends, and builds nothing")
    void theBuildScriptLeavesTheEngineOfAnotherRunAlone() {
        write(stubs().resolve("running"), "");

        final var run = buildScript(temporary.resolve("clone"), Map.of());

        assertEquals(1, run.exitCode(), run::output);
        assertTrue(Files.exists(stubs().resolve("running")), "the other run's engine is not removed");
        assertFalse(stubCalls("docker").contains("rm"), () -> "nothing is removed: %s".formatted(stubCalls("docker")));
        assertEquals(List.of(), stubCalls("mvn"), "no twin is built");
    }

    private void assertTheRunEndsNaming(final String... parts) {
        final var run = overlay();

        assertEquals(1, run.exitCode(), run::output);
        assertTrue(run
                .output()
                .contains("The ZenBPM overlay does not fit this checkout of the blueprints"), run::output);
        for (final var part : parts) {
            assertTrue(run
                    .output()
                    .contains(part), () -> "'%s' is not named in:%n%s".formatted(part, run.output()));
        }
        assertFalse(Files.exists(checkout.resolve("zenbpm-twins.txt")), "a run which ends lists no twin to build");
    }

    /**
     * The build script of a copy of the adapter whose overlay is the one of this test, run against
     * stand-ins of git, docker, curl and Maven: git clones the test's checkout, the engine answers
     * ready, and Maven fails the twin {@code FAILING_TWIN} names.
     */
    private Run buildScript(
        final Path clone,
        final Map<String, String> environment
    ) {
        return run(List.of("bash", adapterCopy()
                .resolve("bin/build-blueprints-on-zenbpm.sh")
                .toString(), clone.toString()), withStubs(environment));
    }

    /** The engine script of a copy of the adapter, run against the stand-ins. */
    private Run engineScript(final Map<String, String> environment) {
        return run(List.of("bash", adapterCopy()
                .resolve("bin/zenbpm-engine.sh")
                .toString(), "start"), withStubs(environment));
    }

    private Path adapterCopy() {
        final var adapter = temporary.resolve("adapter");
        copy(ROOT.resolve("bin/build-blueprints-on-zenbpm.sh"), adapter.resolve("bin/build-blueprints-on-zenbpm.sh"));
        copy(ROOT.resolve("bin/zenbpm-engine.sh"), adapter.resolve("bin/zenbpm-engine.sh"));
        copy(OVERLAY.resolve("ZenBpmOverlay.java"), overlay.resolve("ZenBpmOverlay.java"));
        return adapter;
    }

    private Map<String, String> withStubs(final Map<String, String> environment) {
        final var stubs = stubs();
        write(stubs.resolve("bin/git"), """
                #!/usr/bin/env bash
                echo "$*" >>"$STUBS/git.calls"
                case "$1" in
                  clone) cp -R "$BLUEPRINTS" "${@: -1}" ;;
                  -C) echo 0123456789abcdef0123456789abcdef01234567 ;;
                  *) exit 3 ;;
                esac
                """);
        write(stubs.resolve("bin/docker"), """
                #!/usr/bin/env bash
                echo "$1" >>"$STUBS/docker.calls"
                case "$1" in
                  run)
                    if [ -n "${DOCKER_RUN_FAILS:-}" ]; then
                      echo "Bind for 0.0.0.0:18080 failed: port is already allocated" >&2
                      exit 125
                    fi
                    touch "$STUBS/running" ;;
                  rm) rm -f "$STUBS/running" ;;
                  inspect)
                    if [ -e "$STUBS/running" ]; then echo true; else echo "No such object" >&2; exit 1; fi ;;
                  logs) echo "what the engine wrote" ;;
                esac
                """);
        // the readiness of an engine, this run's or another's
        write(stubs.resolve("bin/curl"), """
                #!/usr/bin/env bash
                exit 0
                """);
        write(stubs.resolve("bin/mvn"), """
                #!/usr/bin/env bash
                echo "$PWD $*" >>"$STUBS/mvn.calls"
                if [ -n "${FAILING_TWIN:-}" ] && [ "${PWD%/$FAILING_TWIN}" != "$PWD" ]; then
                  echo "BUILD FAILURE"
                  exit 1
                fi
                echo "BUILD SUCCESS"
                """);
        for (final var command : List.of("git", "docker", "curl", "mvn")) {
            stubs
                    .resolve("bin")
                    .resolve(command)
                    .toFile()
                    .setExecutable(true);
        }
        final var java = Path
                .of(ProcessHandle
                        .current()
                        .info()
                        .command()
                        .orElseThrow())
                .getParent();
        final var all = new TreeMap<>(environment);
        all.put("PATH", "%s:%s:%s".formatted(stubs.resolve("bin"), java, System.getenv("PATH")));
        all.put("STUBS", stubs.toString());
        all.put("BLUEPRINTS", checkout.toString());
        return all;
    }

    private Path stubs() {
        return temporary.resolve("stubs");
    }

    /** What a stand-in was called with, one line per call: docker's command, Maven's directory and arguments. */
    private List<String> stubCalls(final String command) {
        final var calls = stubs().resolve("%s.calls".formatted(command));
        return Files.exists(calls) ? read(calls)
                .lines()
                .toList() : List.of();
    }

    /** The lines of the script's results without the seconds each twin took. */
    private static List<String> results(final Path clone) {
        return read(clone.resolve("zenbpm-results.txt"))
                .lines()
                .map(line -> line.replaceAll(" \\d+$", ""))
                .toList();
    }

    /** The overlay of this repository's source, run with the Java which runs this test. */
    private Run overlay() {
        return run(List.of(ProcessHandle
                .current()
                .info()
                .command()
                .orElse("java"),
                OVERLAY
                        .resolve("ZenBpmOverlay.java")
                        .toString(),
                overlay.toString(), checkout.toString()));
    }

    /**
     * Runs a command with its output in a file, so a command which does not end fails the test
     * after a while instead of holding it.
     */
    private Run run(final List<String> command) {
        return run(command, Map.of());
    }

    private Run run(
        final List<String> command,
        final Map<String, String> environment
    ) {
        try {
            final var output = Files.createTempFile(temporary, "output", ".txt");
            final var builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder
                    .environment()
                    .putAll(environment);
            final var process = builder.start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process
                        .descendants()
                        .forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new AssertionError("%s did not end within two minutes:%n%s".formatted(command, read(output)));
            }
            return new Run(process.exitValue(), read(output));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A twin as the blueprints write them: a parent, the workflow module and the application. */
    private static void writeTwin(
        final Path twin,
        final String platform
    ) {
        final var adapter = "springboot".equals(platform) ? "spring-boot" : "quarkus";
        write(twin.resolve("pom.xml"), """
                <project>
                  <properties>
                    <bpms>none</bpms>
                    <camunda7-adapter.version>2.0.0-SNAPSHOT</camunda7-adapter.version>
                    <camunda8-adapter.version>2.0.0-8.10-SNAPSHOT</camunda8-adapter.version>
                  </properties>
                </project>
                """);
        final var profiles = new LinkedHashMap<String, String>();
        profiles.put("application", "");
        profiles.put("loan-approval", "\n          <scope>test</scope>");
        for (final var module : profiles.entrySet()) {
            write(twin.resolve(module.getKey()).resolve("pom.xml"), """
                    <project>
                      <profiles>
                        <profile>
                          <id>camunda8</id>
                          <activation>
                            <activeByDefault>true</activeByDefault>
                          </activation>
                          <properties>
                            <bpms>camunda8</bpms>
                            <blueprint.bpms>camunda8</blueprint.bpms>
                          </properties>
                          <dependencies>
                            <!-- the adapter of Camunda 8 -->
                            <dependency>
                              <groupId>org.camunda.community.vanillabp</groupId>
                              <artifactId>camunda8-adapter-%s</artifactId>
                              <version>${camunda8-adapter.version}</version>%s
                            </dependency>
                          </dependencies>
                        </profile>
                      </profiles>
                    </project>
                    """.formatted(adapter, module.getValue()));
        }
        final var camunda8 = """
                # Everything specific to Camunda 8
                vanillabp:
                  adapters:
                    camunda8:
                      name-clash-avoidance: use-prefix
                      rest-address: http://localhost:8080
                      # a lease
                      job-lease: use
                """;
        write(twin.resolve("application/src/main/resources/application-camunda8.yaml"), camunda8);
        write(twin.resolve("loan-approval/src/test/resources/application-camunda8.yaml"), camunda8);
        write(twin.resolve(MODEL.formatted("camunda8")), CAMUNDA8_MODEL);
    }

    private static String sha256(final String content) {
        try {
            return HexFormat
                    .of()
                    .formatHex(MessageDigest
                            .getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The text of a file with every run of white space as one blank. */
    private static String words(final Path file) {
        return read(file).replaceAll("\\s+", " ");
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
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

    private static void copy(
        final Path from,
        final Path to
    ) {
        try {
            Files.createDirectories(to.getParent());
            // with its permissions: the build script runs the engine script as a command
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(final Path directory) {
        try (var paths = Files.walk(directory)) {
            final var all = new ArrayList<>(paths.toList());
            all
                    .reversed()
                    .forEach(path -> path
                            .toFile()
                            .delete());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Run(
        int exitCode,
        String output
    ) {
    }

}
