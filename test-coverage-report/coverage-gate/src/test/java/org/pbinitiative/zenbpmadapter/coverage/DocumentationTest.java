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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The README and the pages of the wiki, held to what can be checked without understanding them. The
 * pages are written in the directory <code>wiki/</code> and copied into the wiki after each merge,
 * see decision 63 in the repository's DECISIONS.md, so they are checked like code:
 * <ul>
 * <li>every test a document names exists, the method it names included;</li>
 * <li>every link between the documents leads to a page and a heading which exist;</li>
 * <li>every row of <code>GAPS.md</code> has its entry on the page <code>Deviations</code>, and every
 * gap a document names is a row;</li>
 * <li>every key the adapter reads stands on the page <code>Configuration</code> with the levels it
 * may be set at, and no other;</li>
 * <li>every call of the REST client, every endpoint of the engine and the job stream stand on the
 * page <code>Engine-APIs</code>;</li>
 * <li>the coordinates, the versions and the Java of the page <code>Home</code> are the build's, and
 * every meter a page names is one the README's table names.</li>
 * </ul>
 * Whether a sentence says what its test holds is not checked: that stays the reviewer's.
 */
@ExtendWith(SuppressOutputExtension.class)
class DocumentationTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final Path WIKI = ROOT.resolve("wiki");

    private static final List<String> PAGES = List.of("Home", "Configuration", "Deviations", "Engine-APIs",
            "Native-images", "_Footer");

    private static final String REPOSITORY = "https://github.com/pbinitiative/zenbpm-vanillabp-adapter/";

    /**
     * A test class a document names, with the method after a <code>#</code>, or a method alone, which
     * names a method of a class named before it in the same paragraph.
     */
    private static final Pattern CITATION = Pattern.compile(
            "(?<![\\w#.])(?<type>[A-Z][a-z][A-Za-z0-9]*(?:Test|IT))(?:#(?<method>[a-z][A-Za-z0-9]*))?\\b|`#(?<alone>[a-z][A-Za-z0-9]*)`");

    /** Test classes of the frameworks, which a document may name as such. */
    private static final Set<String> FOREIGN_TESTS = Set.of("QuarkusTest", "QuarkusExtensionTest",
            "QuarkusProdModeTest", "QuarkusIntegrationTest", "SpringBootTest");

    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");

    /** A block of code, which holds no link. */
    private static final Pattern CODE_BLOCK = Pattern.compile("(?ms)^```.*?^```");

    /** Code in a line, which may show a link rather than be one, e.g. <code>`[Home](Home)`</code>. */
    private static final Pattern CODE_SPAN = Pattern.compile("`[^`]*`");

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*#*\\s*$");

    private static final Pattern GAP_ROW = Pattern.compile("(?m)^\\|\\s*(\\d+)\\s*\\|");

    private static final Pattern GAP_ENTRY = Pattern.compile("(?m)^### GAPS (\\d+): ");

    private static final Pattern GAPS_NAMED = Pattern.compile("GAPS ((?:\\d+(?:, | and )?)+)");

    private static final Pattern KEY_ROW = Pattern.compile("(?m)^\\| `([a-z-]+)` \\| ([^|]+?) \\|");

    private static final Pattern CALL_OF_THE_CLIENT = Pattern.compile("new Call\\((GET|POST|PATCH), \"([^\"]+)\"");

    private static final Pattern CALL_ON_THE_PAGE = Pattern.compile("`((?:GET|POST|PATCH|PUT|DELETE) /[^`]+)`");

    private static final Pattern RPC = Pattern.compile("(?m)^\\s*rpc (\\w+)\\(");

    private static final Pattern METER = Pattern.compile("vanillabp\\.zenbpm\\.[a-z]+\\.[a-z]+");

    @Test
    @DisplayName("The wiki has exactly the pages the repository publishes")
    void theWikiHasItsPages() throws IOException {
        try (var files = Files.list(WIKI)) {
            assertEquals(new TreeSet<>(PAGES), files
                    .map(file -> file
                            .getFileName()
                            .toString()
                            .replaceFirst("\\.md$", ""))
                    .collect(Collectors.toCollection(TreeSet::new)));
        }
    }

    @Test
    @DisplayName("Every test the README and the wiki name exists, with the method they name")
    void everyTestTheDocumentsNameExists() {
        final var tests = sources();
        final var missing = new ArrayList<String>();
        for (final var document : documents()) {
            for (final var paragraph : read(document).split("\\n\\s*\\n")) {
                final var named = new ArrayList<String>();
                final var citations = CITATION.matcher(paragraph);
                while (citations.find()) {
                    final var alone = citations.group("alone");
                    if (alone != null) {
                        if (named
                                .stream()
                                .noneMatch(type -> declares(tests.get(type), alone))) {
                            missing.add("%s: #%s, after %s".formatted(name(document), alone, named));
                        }
                        continue;
                    }
                    final var type = citations.group("type");
                    if (FOREIGN_TESTS.contains(type)) {
                        continue;
                    }
                    named.add(type);
                    final var method = citations.group("method");
                    if (!tests.containsKey(type)) {
                        missing.add("%s: %s".formatted(name(document), type));
                    } else if ((method != null) && !declares(tests.get(type), method)) {
                        missing.add("%s: %s#%s".formatted(name(document), type, method));
                    }
                }
            }
        }

        assertEquals(List.of(), missing, """
                These tests are named by the documents, but no source of the repository declares \
                them. Name the test which holds the sentence, or rename the citation with the test.""");
    }

    @Test
    @DisplayName("Every link of the README and the wiki leads to a page and a heading which exist")
    void everyLinkLeadsToAHeading() {
        final var broken = new ArrayList<String>();
        for (final var document : documents()) {
            final var links = LINK.matcher(withoutCode(read(document)));
            while (links.find()) {
                final var target = links.group(1);
                if (!resolves(document, target)) {
                    broken.add("%s: %s".formatted(name(document), target));
                }
            }
        }

        assertEquals(List.of(), broken, """
                These links lead nowhere. A page of the wiki links another one as (Page) or (Page#heading), \
                and the repository with a full address of 'main', %s....""".formatted(REPOSITORY));
    }

    @Test
    @DisplayName("Every gap of GAPS.md has its entry on the page Deviations, in the order of GAPS.md")
    void everyGapHasItsEntry() {
        final var rows = everyNumber(GAP_ROW, read(ROOT.resolve("GAPS.md")));
        final var entries = everyNumber(GAP_ENTRY, read(WIKI.resolve("Deviations.md")));

        assertFalse(rows.isEmpty(), "GAPS.md has no row");
        assertEquals(rows, entries, """
                The page Deviations has a heading '### GAPS <n>: <what the user sees>' for every row of \
                GAPS.md, in the same order.""");
    }

    @Test
    @DisplayName("Every gap the README and the wiki name is a row of GAPS.md")
    void everyGapNamedIsARow() {
        final var rows = Set.copyOf(everyNumber(GAP_ROW, read(ROOT.resolve("GAPS.md"))));
        final var unknown = new ArrayList<String>();
        for (final var document : documents()) {
            GAPS_NAMED
                    .matcher(read(document))
                    .results()
                    .flatMap(named -> Arrays.stream(named
                            .group(1)
                            .split("\\D+")))
                    .filter(number -> !number.isEmpty())
                    .map(Integer::valueOf)
                    .filter(number -> !rows.contains(number))
                    .forEach(number -> unknown.add("%s: GAPS %d".formatted(name(document), number)));
        }

        assertEquals(List.of(), unknown, "These gaps are named, but GAPS.md has no row of their number.");
    }

    @Test
    @DisplayName("The page Configuration lists every key the adapter reads with the levels it may be set at, and no other key")
    void everyKeyStandsOnTheConfigurationPage() throws ClassNotFoundException {
        final var read = new TreeMap<String, SortedSet<String>>();
        for (final var path : OverlayKeys.ofSpringBoot()) {
            final var key = path.substring(path.lastIndexOf('.') + 1);
            read
                    .computeIfAbsent(key, any -> new TreeSet<>())
                    .add(levelOf(path));
        }
        final var listed = new TreeMap<String, SortedSet<String>>();
        KEY_ROW
                .matcher(section(read(WIKI.resolve("Configuration.md")), "every-key"))
                .results()
                .forEach(row -> listed.put(row.group(1), Arrays
                        .stream(row
                                .group(2)
                                .split(",\\s*"))
                        .map(String::trim)
                        .collect(Collectors.toCollection(TreeSet::new))));

        assertTrue(read.containsKey("rest-address"), read::toString);
        assertEquals(read, listed, """
                The table of the page Configuration, section 'Every key', has to list every key the \
                configuration overlay reads, with the levels it may be set at (adapter, workflow module, \
                workflow, task), and no key it does not read.""");
    }

    @Test
    @DisplayName("The page Engine-APIs names every call of the REST client, every endpoint of the engine and the job stream, and calls nothing the client does not")
    void everyCallStandsOnTheEngineApisPage() {
        final var page = read(WIKI.resolve("Engine-APIs.md"));
        final var onThePage = callsIn(page);
        final var called = callsIn(section(page, "calls"));
        final var ofTheClient = CALL_OF_THE_CLIENT
                .matcher(read(ROOT.resolve(
                        "core/src/main/java/org/pbinitiative/zenbpmadapter/client/ZenBpmRestClient.java")))
                .results()
                .map(call -> withoutParameterNames("%s %s".formatted(call.group(1), call.group(2))))
                .collect(Collectors.toCollection(TreeSet::new));
        final var ofTheEngine = endpointsOfTheEngine();
        final var streams = RPC
                .matcher(read(ROOT.resolve("core/src/main/proto/zenbpm.proto")))
                .results()
                .map(rpc -> "`ZenBpm/%s`".formatted(rpc.group(1)))
                .toList();

        assertTrue(ofTheEngine.contains("POST /v1/messages"), ofTheEngine::toString);
        assertEquals(Set.of(), difference(called, ofTheClient), "The page names calls the REST client does not make.");
        assertEquals(Set.of(), difference(ofTheClient, onThePage),
                "The page does not name these calls of the REST client.");
        assertEquals(Set.of(), difference(ofTheEngine, onThePage),
                "The page does not name these endpoints of the engine.");
        assertEquals(List.of(), streams
                .stream()
                .filter(stream -> !page.contains(stream))
                .toList(), "The page does not name these calls of the job stream.");
    }

    @Test
    @DisplayName("The Spring Boot dependency of the page Home brings the adapter's core and VanillaBP's Spring Boot integration with it")
    void theSpringBootDependencyBringsThePlatform() {
        final var home = read(WIKI.resolve("Home.md"));
        final var pom = read(ROOT.resolve("spring-boot/pom.xml"));

        assertTrue(home.contains("<artifactId>%s</artifactId>".formatted(artifactIdOf(pom))), home);
        assertTrue(home.contains("<artifactId>%s</artifactId>".formatted(artifactIdOf(read(ROOT.resolve(
                "quarkus/runtime/pom.xml"))))), home);
        assertEquals(List.of(true, true), Stream
                .of("zenbpm-vanillabp-adapter", "vanillabp-spring-boot-integration")
                .map(artifactId -> compileDependencies(pom).contains(artifactId))
                .toList(), "spring-boot/pom.xml has to hand both to an application, in the scope compile");
    }

    @Test
    @DisplayName("The page Home names the version this build publishes and the Java its classes are compiled for")
    void theHomePageNamesTheBuildsVersions() {
        final var home = read(WIKI.resolve("Home.md"));
        final var root = read(ROOT.resolve("pom.xml"));
        final var revision = first(Pattern.compile("<revision>([^<]+)</revision>"), root);
        final var java = first(Pattern.compile("<version\\.java>([^<]+)</version\\.java>"), root);

        assertEquals(Set.of(revision), Pattern
                .compile("<version>([^<]+)</version>")
                .matcher(home)
                .results()
                .map(version -> version.group(1))
                .collect(Collectors.toSet()));
        assertEquals(java, first(Pattern.compile("needs Java (\\d+) or newer"), home));
    }

    @Test
    @DisplayName("Every meter a page of the wiki names is one of the README's table, which the adapter's meters are held to")
    void everyMeterOfTheWikiIsInTheReadme() {
        final var readme = section(read(ROOT.resolve("README.md")), "## Metrics", "\n## ");
        final var unknown = new TreeSet<String>();
        for (final var page : pages()) {
            METER
                    .matcher(read(page))
                    .results()
                    .map(MatchResult::group)
                    .filter(meter -> !readme.contains("`%s`".formatted(meter)))
                    .forEach(unknown::add);
        }

        assertEquals(Set.of(), unknown, "The README's section 'Metrics' does not name these meters.");
    }

    private static List<Path> documents() {
        final var documents = new ArrayList<Path>();
        documents.add(ROOT.resolve("README.md"));
        documents.addAll(pages());
        return documents;
    }

    private static List<Path> pages() {
        return PAGES
                .stream()
                .map(page -> WIKI.resolve("%s.md".formatted(page)))
                .toList();
    }

    private static String name(final Path document) {
        return ROOT
                .relativize(document)
                .toString()
                .replace('\\', '/');
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // the sources of every module by the simple name of their class, the main sources included,
    // where a class of the test support may be named as well
    private static Map<String, List<String>> sources() {
        try (var files = Files.walk(ROOT)) {
            return files
                    .filter(file -> file
                            .toString()
                            .endsWith(".java"))
                    .filter(file -> {
                        final var path = name(file);
                        return path.matches(".*src/(test|main)/java/.*") && !path.contains("/target/");
                    })
                    .collect(Collectors.groupingBy(file -> file
                            .getFileName()
                            .toString()
                            .replaceFirst("\\.java$", ""), Collectors.mapping(DocumentationTest::read,
                                    Collectors.toList())));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean declares(
        final List<String> sources,
        final String method
    ) {
        final var declaration = Pattern.compile("\\bvoid\\s+%s\\s*\\(".formatted(method));
        return (sources != null) && sources
                .stream()
                .anyMatch(source -> declaration
                        .matcher(source)
                        .find());
    }

    // the text without its code, in which a link is shown rather than made
    private static String withoutCode(final String text) {
        return CODE_SPAN
                .matcher(CODE_BLOCK
                        .matcher(text)
                        .replaceAll(""))
                .replaceAll(span -> span
                        .group()
                        .contains("](") ? "" : Matcher.quoteReplacement(span.group()));
    }

    private static boolean resolves(
        final Path document,
        final String target
    ) {
        if (target.startsWith("#")) {
            return headingsOf(document).contains(target.substring(1));
        }
        if (target.startsWith("%swiki/".formatted(REPOSITORY))) {
            return resolvesToAPage(target.substring("%swiki/".formatted(REPOSITORY).length()));
        }
        for (final var tree : List.of("blob/main/", "tree/main/")) {
            if (target.startsWith(REPOSITORY + tree)) {
                return resolvesToAFile(target.substring((REPOSITORY + tree).length()));
            }
        }
        if (target.startsWith("http") || target.startsWith("mailto:")) {
            return true;
        }
        if (document.startsWith(WIKI)) {
            return resolvesToAPage(target);
        }
        return resolvesToAFile(target.replaceFirst("^\\./", ""));
    }

    private static boolean resolvesToAPage(final String target) {
        final var page = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
        if (!PAGES.contains(page)) {
            return false;
        }
        return !target.contains("#") || headingsOf(WIKI.resolve("%s.md".formatted(page))).contains(target
                .substring(target.indexOf('#') + 1));
    }

    private static boolean resolvesToAFile(final String target) {
        final var path = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
        final var file = ROOT.resolve(path);
        if (path.isEmpty() || !Files.exists(file)) {
            return false;
        }
        return !target.contains("#") || (path.endsWith(".md") && headingsOf(file).contains(target.substring(target
                .indexOf('#') + 1)));
    }

    // the anchors GitHub gives the headings of a document, code blocks left out
    private static Set<String> headingsOf(final Path document) {
        final var anchors = new LinkedHashSet<String>();
        final var counts = new HashMap<String, Integer>();
        var inCode = false;
        for (final var line : read(document).split("\\n")) {
            if (line.startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            final var heading = HEADING.matcher(line);
            if (inCode || !heading.matches()) {
                continue;
            }
            final var anchor = heading
                    .group(1)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N} _-]", "")
                    .replace(' ', '-');
            final int seen = counts.merge(anchor, 1, Integer::sum);
            anchors.add(seen == 1 ? anchor : "%s-%d".formatted(anchor, seen - 1));
        }
        return anchors;
    }

    private static List<Integer> everyNumber(
        final Pattern pattern,
        final String text
    ) {
        return pattern
                .matcher(text)
                .results()
                .map(found -> Integer.valueOf(found.group(1)))
                .toList();
    }

    // the part of a page between <!-- name ... --> and <!-- /name -->
    private static String section(
        final String page,
        final String name
    ) {
        return section(page, "<!-- %s".formatted(name), "<!-- /%s -->".formatted(name));
    }

    private static String section(
        final String text,
        final String start,
        final String end
    ) {
        final var from = text.indexOf(start);
        assertTrue(from >= 0, "no '%s'".formatted(start));
        final var to = text.indexOf(end, from + start.length());
        return text.substring(from, (to < 0) ? text.length() : to);
    }

    private static String levelOf(final String path) {
        final SortedMap<String, String> shapes = new TreeMap<>(Map.of(
                "vanillabp\\.adapters\\.\\*\\.[a-z-]+", "adapter",
                "vanillabp\\.workflow-modules\\.\\*\\.adapters\\.\\*\\.[a-z-]+", "workflow module",
                "vanillabp\\.workflow-modules\\.\\*\\.workflows\\.\\*\\.adapters\\.\\*\\.[a-z-]+", "workflow",
                "vanillabp\\.workflow-modules\\.\\*\\.workflows\\.\\*\\.tasks\\.\\*\\.adapters\\.\\*\\.[a-z-]+",
                "task"));
        return shapes
                .entrySet()
                .stream()
                .filter(shape -> path.matches(shape.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the key '%s' stands at no level the page knows".formatted(
                        path)));
    }

    private static SortedSet<String> callsIn(final String text) {
        return CALL_ON_THE_PAGE
                .matcher(text)
                .results()
                .map(call -> withoutParameterNames(call.group(1)))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String withoutParameterNames(final String call) {
        return call.replaceAll("\\{[^}]*}|%d", "{}");
    }

    // every operation of the copy of the engine's REST contract, under the path the client sends it to
    private static SortedSet<String> endpointsOfTheEngine() {
        final var endpoints = new TreeSet<String>();
        String path = null;
        var inPaths = false;
        for (final var line : read(ROOT.resolve("core/src/main/zenbpm/api.yaml")).split("\\n")) {
            if (line.startsWith("paths:")) {
                inPaths = true;
                continue;
            }
            if (inPaths && line.matches("^\\S.*")) {
                break;
            }
            final var pathLine = Pattern
                    .compile("^  (/\\S+):\\s*$")
                    .matcher(line);
            if (inPaths && pathLine.matches()) {
                path = pathLine.group(1);
                continue;
            }
            final var operation = Pattern
                    .compile("^    (get|post|put|patch|delete):\\s*$")
                    .matcher(line);
            if (inPaths && (path != null) && operation.matches()) {
                endpoints.add(withoutParameterNames("%s /v1%s".formatted(operation
                        .group(1)
                        .toUpperCase(Locale.ROOT), path)));
            }
        }
        return endpoints;
    }

    private static Set<String> difference(
        final Set<String> these,
        final Set<String> others
    ) {
        return these
                .stream()
                .filter(one -> !others.contains(one))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String artifactIdOf(final String pom) {
        return first(Pattern.compile("</parent>\\s*<artifactId>([^<]+)</artifactId>"), pom);
    }

    // the artifacts a POM hands an application: no scope or compile, not optional
    private static Set<String> compileDependencies(final String pom) {
        final var dependencies = section(pom, "<dependencies>", "</dependencies>");
        return Pattern
                .compile("(?s)<dependency>(.*?)</dependency>")
                .matcher(dependencies)
                .results()
                .map(dependency -> dependency.group(1))
                .filter(dependency -> !dependency.contains("<optional>true</optional>"))
                .filter(dependency -> !dependency.contains("<scope>") || dependency.contains("<scope>compile</scope>"))
                .map(dependency -> first(Pattern.compile("<artifactId>([^<]+)</artifactId>"), dependency))
                .collect(Collectors.toSet());
    }

    private static String first(
        final Pattern pattern,
        final String text
    ) {
        final var found = pattern.matcher(text);
        assertTrue(found.find(), () -> "no %s in the text".formatted(pattern));
        return found.group(1);
    }

}
