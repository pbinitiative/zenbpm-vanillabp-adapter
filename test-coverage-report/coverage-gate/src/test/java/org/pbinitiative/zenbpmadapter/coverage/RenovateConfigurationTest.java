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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Renovate reads <code>renovate.json</code> on GitHub only, and nothing in a build tells when one
 * of its rules stops matching what the POMs use. This test holds it to what the README's section
 * "Dependency updates" and the comments of the root POM say: every version the root POM says never
 * moves on its own is read only by artifacts a rule switches off, because Renovate moves a property
 * with whichever of its artifacts has an update; every rule says why and still matches an artifact
 * of the build; the image a workflow runs by a version and the JDK of the required check are found
 * by the custom managers; and every workflow builds on one JDK.
 */
@ExtendWith(SuppressOutputExtension.class)
class RenovateConfigurationTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final Path WORKFLOWS = ROOT.resolve(".github/workflows");

    /** The versions of the root POM which move with another one; the POM says so above each. */
    private static final Set<String> MOVING_WITH_ANOTHER = Set.of("springboot.version", "quarkus.version",
            "grpc.version", "protobuf.version", "netty.version.quarkus");

    private static final Pattern NEVER_ON_ITS_OWN = Pattern.compile("never on (its|their) own");

    private static final Pattern PROPERTIES = Pattern.compile("<properties>(.*?)</properties>", Pattern.DOTALL);

    /** A comment, or a property with its value, in the order the root POM's properties stand. */
    private static final Pattern COMMENT_OR_PROPERTY = Pattern.compile(
            "<!--(.*?)-->|<([A-Za-z0-9.-]+)>[^<]*</\\2>|<([A-Za-z0-9.-]+)/>", Pattern.DOTALL);

    /** The form Renovate itself writes a prefix in: the prefix, then <code>{/,}**</code>. */
    private static final String PREFIX_GLOB = "{/,}**";

    /** An image a workflow names by a version, as a 'run:' line names actionlint. */
    private static final Pattern IMAGE_BY_VERSION = Pattern.compile("\\b[a-z0-9.-]+(?:/[a-z0-9._-]+)+:[0-9][^\\s\"']*");

    /** The custom manager for the engine's releases, which the plan holds ready in a 'json' block. */
    private static final Pattern ENGINE_ENTRY = Pattern.compile(
            "```json\\s*(\\{[^`]*?\"depNameTemplate\": \"pbinitiative/zenbpm\"[^`]*?})\\s*```");

    /** A full version of the JDK, as the workflows name the one they build on. */
    private static final Pattern FULL_JDK = Pattern.compile("\\b\\d+\\.\\d+\\.\\d+\\+[0-9.]+\\.LTS\\b");

    @Test
    @DisplayName("Every version the root POM moves with another one is read only by artifacts a rule switches off")
    void whatMovesWithAnotherIsSwitchedOff() {
        assertEquals(new TreeSet<>(MOVING_WITH_ANOTHER), new TreeSet<>(propertiesNeverMovingOnTheirOwn()), """
                The root POM says of exactly these versions that they never move on their own: the comment \
                above each of them, and of no other property, says 'never on its own' or 'never on their \
                own'. Add a new one to this test, and to a rule of renovate.json which switches its \
                artifacts off.""");

        final var notSwitchedOff = new TreeSet<String>();
        for (final var artifact : artifacts()) {
            final var property = artifact.property();
            if ((property != null) && MOVING_WITH_ANOTHER.contains(property) && !switchedOff(artifact.name())) {
                notSwitchedOff.add("%s (%s, in %s)".formatted(artifact.name(), property, artifact.pom()));
            }
        }
        assertEquals(Set.of(), notSwitchedOff, """
                These artifacts read a version which never moves on its own, and no rule of renovate.json \
                with "enabled": false names them. Renovate moves the property with an update of any of its \
                artifacts, so each needs its name in the rule which says why the version stays.""");
    }

    @Test
    @DisplayName("Every rule of renovate.json says why, and every name a switched-off rule matches is still an artifact of the build")
    void everyRuleSaysWhyAndMatchesTheBuild() {
        final var names = artifacts()
                .stream()
                .map(Artifact::name)
                .toList();
        final var withoutReason = new ArrayList<String>();
        final var matchingNothing = new TreeSet<String>();
        for (final var rule : packageRules()) {
            if (String
                    .valueOf(rule.getOrDefault("description", ""))
                    .isBlank()) {
                withoutReason.add(String.valueOf(rule));
            }
            if (!Boolean.FALSE.equals(rule.get("enabled"))) {
                continue;
            }
            for (final var pattern : strings(rule.get("matchPackageNames"))) {
                if (names
                        .stream()
                        .noneMatch(name -> matches(pattern, name))) {
                    matchingNothing.add(pattern);
                }
            }
        }

        assertEquals(List.of(), withoutReason,
                "Every rule of renovate.json says in its 'description' why it is there.");
        assertEquals(Set.of(), matchingNothing, """
                These names of a switched-off rule match no artifact of any POM any more. Name the artifact \
                as the POMs name it now, or remove the name.""");
    }

    @Test
    @DisplayName("The custom managers find every image a workflow runs by a version and the JDK of the required check")
    void theCustomManagersFindWhatTheirDescriptionsSay() throws IOException {
        final var files = new ArrayList<Path>(workflows());
        files.addAll(poms());
        final var found = new TreeSet<String>();
        for (final var manager : list(configuration().get("customManagers"), "customManagers")) {
            final var description = String.valueOf(manager.getOrDefault("description", ""));
            assertFalse(description.isBlank(), () -> "The custom manager %s says nothing about why it is there."
                    .formatted(manager));
            final var foundByTheManager = new TreeSet<String>();
            for (final var file : files) {
                final var path = ROOT
                        .relativize(file)
                        .toString();
                foundByTheManager.addAll(whatTheManagerFinds(manager, path, Files.readString(file)));
            }
            assertFalse(foundByTheManager.isEmpty(), () -> """
                    The custom manager '%s' finds nothing in the workflows and POMs its managerFilePatterns \
                    select.""".formatted(description));
            found.addAll(foundByTheManager);
        }

        final var named = new TreeSet<String>();
        for (final var workflow : workflows()) {
            final var path = ROOT
                    .relativize(workflow)
                    .toString();
            IMAGE_BY_VERSION
                    .matcher(Files.readString(workflow))
                    .results()
                    .forEach(image -> named.add("%s %s".formatted(path, image
                            .group()
                            .substring(image
                                    .group()
                                    .lastIndexOf(':') + 1))));
        }
        named.add(".github/workflows/checks.yaml %s".formatted(jdkOfTheRequiredCheck()));
        assertTrue(found.containsAll(named), () -> """
                The custom managers of renovate.json have to find every image a workflow runs by its version \
                and the JDK of the required check, which the GitHub Actions manager does not see. They find \
                %s, the workflows name %s.""".formatted(found, named));
    }

    @Test
    @DisplayName("The entry the plan holds ready for the engine's releases finds the release a re-pinned root POM names")
    void theEntryForTheEnginesReleasesFindsTheRelease() {
        final var plan = read(ROOT.resolve("docs/plan/epic-12-documentation-and-release.md"));
        final var entry = ENGINE_ENTRY.matcher(plan);
        assertTrue(entry.find(), """
                The plan's story S12.3.1 holds, in a 'json' block of its own, the custom manager which \
                follows the engine's releases once the pin is one; the README's section 'Dependency \
                updates' points to it.""");
        @SuppressWarnings("unchecked")
        final var manager = (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(entry
                .group(1));
        assertEquals("github-releases", manager.get("datasourceTemplate"));
        assertEquals("pbinitiative/zenbpm", manager.get("depNameTemplate"));

        final var pom = read(ROOT.resolve("pom.xml"))
                .replaceAll("\\s*<zenbpm\\.version>[^<]*</zenbpm\\.version>", "")
                .replace("<zenbpm.commit>", "<zenbpm.version>v1.9.0</zenbpm.version>\n    <zenbpm.commit>");
        assertEquals(Set.of("pom.xml v1.9.0"), whatTheManagerFinds(manager, "pom.xml", pom), """
                The entry finds the engine's release in 'zenbpm.version' of the root POM, and nothing else.""");
    }

    @Test
    @DisplayName("Every workflow builds on the JDK of the required check, and Renovate moves it in one pull request")
    void everyWorkflowBuildsOnOneJdk() throws IOException {
        final var jdk = jdkOfTheRequiredCheck();
        final var others = new TreeMap<String, String>();
        for (final var workflow : workflows()) {
            FULL_JDK
                    .matcher(Files.readString(workflow))
                    .results()
                    .filter(version -> !version
                            .group()
                            .equals(jdk))
                    .forEach(version -> others.put(String.valueOf(workflow.getFileName()), version.group()));
        }
        assertEquals(Map.of(), others, () -> """
                The required check builds on %s, and every workflow says it builds on the same JDK.""".formatted(
                jdk));

        final var grouping = packageRules()
                .stream()
                .filter(rule -> strings(rule.get("matchDepNames")).contains("java-jdk") && rule
                        .containsKey("groupName"))
                .toList();
        assertEquals(1, grouping.size(), """
                One rule of renovate.json groups the updates of 'java-jdk', so the JDK moves in every \
                workflow in one pull request.""");
        assertTrue(matches(String.valueOf(grouping
                .getFirst()
                .get("matchCurrentValue")), jdk), () -> "The rule grouping the JDK has to match '%s'.".formatted(jdk));
    }

    /** What a custom manager finds in a file its managerFilePatterns select: the path and each current value. */
    private static Set<String> whatTheManagerFinds(
        final Map<String, Object> manager,
        final String path,
        final String content
    ) {
        final var found = new TreeSet<String>();
        if (strings(manager.get("managerFilePatterns"))
                .stream()
                .noneMatch(pattern -> matches(pattern, path))) {
            return found;
        }
        for (final var matchString : strings(manager.get("matchStrings"))) {
            final var matcher = Pattern
                    .compile(matchString)
                    .matcher(content);
            while (matcher.find()) {
                found.add("%s %s".formatted(path, matcher
                        .group("currentValue")
                        .strip()));
            }
        }
        return found;
    }

    /**
     * Whether Renovate's name pattern matches a name, for the three forms this repository uses: a
     * regular expression between slashes, a prefix followed by <code>{/,}**</code>, and an exact
     * name. Any other form fails the test, so a pattern it cannot judge is never passed by mistake.
     */
    private static boolean matches(
        final String pattern,
        final String name
    ) {
        if ((pattern.length() > 1) && pattern.startsWith("/") && pattern.endsWith("/")) {
            return Pattern
                    .compile(pattern.substring(1, pattern.length() - 1))
                    .matcher(name)
                    .find();
        }
        if (pattern.endsWith(PREFIX_GLOB)) {
            return name.startsWith(pattern.substring(0, pattern.length() - PREFIX_GLOB.length()));
        }
        assertFalse(pattern.contains("*") || pattern.contains("{") || pattern.contains("?") || pattern.contains("!"),
                () -> """
                        renovate.json names '%s', a form of pattern this test cannot judge: write a regular \
                        expression between slashes, a prefix followed by '{/,}**' or an exact name."""
                        .formatted(pattern));
        return pattern.equals(name);
    }

    private static boolean switchedOff(final String name) {
        return packageRules()
                .stream()
                .filter(rule -> Boolean.FALSE.equals(rule.get("enabled")))
                .flatMap(rule -> strings(rule.get("matchPackageNames")).stream())
                .anyMatch(pattern -> matches(pattern, name));
    }

    /** The properties of the root POM whose nearest comment above says they never move on their own. */
    private static Set<String> propertiesNeverMovingOnTheirOwn() {
        final var properties = PROPERTIES.matcher(read(ROOT.resolve("pom.xml")));
        assertTrue(properties.find(), "The root POM has no <properties>.");
        final var moving = new TreeSet<String>();
        var comment = "";
        final var items = COMMENT_OR_PROPERTY.matcher(properties.group(1));
        while (items.find()) {
            if (items.group(1) != null) {
                comment = items
                        .group(1)
                        .replaceAll("\\s+", " ");
                continue;
            }
            final var property = (items.group(2) != null) ? items.group(2) : items.group(3);
            if (NEVER_ON_ITS_OWN
                    .matcher(comment)
                    .find()) {
                moving.add(property);
            }
        }
        return moving;
    }

    /** The full JDK version the job 'build' of the required check builds on. */
    private static String jdkOfTheRequiredCheck() {
        final var check = FULL_JDK.matcher(read(WORKFLOWS.resolve("checks.yaml")));
        assertTrue(check.find(), "checks.yaml names no full JDK version for its job 'build' any more.");
        return check.group();
    }

    /**
     * Every artifact a POM names with a version: dependencies, plugins, the paths of annotation
     * processors and extensions. A plugin without a groupId is one of Maven's own.
     */
    private static List<Artifact> artifacts() {
        final var artifacts = new ArrayList<Artifact>();
        for (final var pom : poms()) {
            final var document = parse(pom);
            for (final var kind : List.of("dependency", "plugin", "path", "extension")) {
                final var elements = document.getElementsByTagName(kind);
                for (var index = 0; index < elements.getLength(); index++) {
                    final var element = (Element) elements.item(index);
                    final var artifactId = child(element, "artifactId");
                    final var version = child(element, "version");
                    if ((artifactId == null) || (version == null)) {
                        continue;
                    }
                    final var groupId = child(element, "groupId");
                    final var property = version.matches("\\$\\{[^}]+}") ? version.substring(2, version.length() - 1)
                            : null;
                    artifacts.add(
                            new Artifact("%s:%s".formatted((groupId != null) ? groupId : "org.apache.maven.plugins",
                                    artifactId), property, String.valueOf(ROOT.relativize(pom))));
                }
            }
        }
        assertFalse(artifacts.isEmpty(), "No POM names an artifact with a version.");
        return artifacts;
    }

    private static String child(
        final Element element,
        final String name
    ) {
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if ((node.getNodeType() == Node.ELEMENT_NODE) && name.equals(node.getNodeName())) {
                return node
                        .getTextContent()
                        .strip();
            }
        }
        return null;
    }

    private static Document parse(final Path pom) {
        try {
            final var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory
                    .newDocumentBuilder()
                    .parse(pom.toFile());
        } catch (final Exception e) {
            throw new AssertionError("%s cannot be read as XML".formatted(pom), e);
        }
    }

    private static List<Path> poms() {
        try (Stream<Path> files = Files.walk(ROOT)) {
            return files
                    .filter(file -> "pom.xml".equals(String.valueOf(file.getFileName())))
                    .filter(file -> {
                        final var relative = ROOT.relativize(file);
                        for (final var part : relative) {
                            if ("target".equals(part.toString()) || part
                                    .toString()
                                    .startsWith(".")) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
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

    private static List<Map<String, Object>> packageRules() {
        return list(configuration().get("packageRules"), "packageRules");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configuration() {
        // JSON is YAML, so the reader of the workflows reads renovate.json as well
        final Object configuration = new Yaml(new SafeConstructor(new LoaderOptions())).load(read(ROOT.resolve(
                "renovate.json")));
        assertTrue(configuration instanceof Map<?, ?>, "renovate.json holds no object.");
        return (Map<String, Object>) configuration;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(
        final Object value,
        final String where
    ) {
        assertTrue(value instanceof List<?>, () -> "'%s' of renovate.json is no list but '%s'".formatted(where, value));
        return (List<Map<String, Object>>) value;
    }

    private static List<String> strings(final Object value) {
        return (value instanceof List<?> values) ? values
                .stream()
                .map(String::valueOf)
                .toList() : List.of();
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An artifact a POM names, with the property its version is read from, if any. */
    private record Artifact(
        String name,
        String property,
        String pom
    ) {
    }

}
