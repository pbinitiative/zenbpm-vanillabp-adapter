/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns a checkout of the VanillaBP blueprints into one which also builds with <code>-Pzenbpm</code>,
 * see decision 64 in the repository's DECISIONS.md. The blueprints are not changed by hand: what
 * the overlay adds is derived from what every blueprint has for Camunda 8, the other remote engine.
 * <ul>
 * <li>every POM with a profile <code>camunda8</code> gets a profile <code>zenbpm</code> beside it,
 * which selects this adapter instead of the Camunda 8 one, and the POM declaring the version of the
 * Camunda 8 adapter declares this adapter's version, too;</li>
 * <li>every <code>application-camunda8.yaml</code> gets an <code>application-zenbpm.yaml</code>
 * beside it, which names the engine <code>bin/zenbpm-engine.sh</code> starts;</li>
 * <li>every directory <code>processes/camunda8</code> gets a directory <code>processes/zenbpm</code>
 * beside it with the same models, which the engine reads as they are, except where the overlay
 * holds a ZenBPM variant of a model in <code>variants/</code>.</li>
 * </ul>
 * Anything of a blueprint the overlay does not know ends the run with a message naming it, so that
 * a change of the blueprints the overlay cannot follow turns the nightly red instead of building
 * less than it says: a blueprint which is not in <code>blueprints.txt</code>, a listed one which is
 * gone, a Camunda 8 profile or configuration with more than the overlay translates, and a model
 * whose variant was written for another version of it.
 * <p>
 * Run with the Java of the build, as a source file:
 * <code>java blueprints-overlay/ZenBpmOverlay.java blueprints-overlay path/to/blueprints</code>. It
 * writes the directories of the twins to build, one per line, to <code>zenbpm-twins.txt</code> in
 * the checkout.
 */
class ZenBpmOverlay {

    static final String RUNS = "runs";

    static final String NOT_RUNNABLE = "not-runnable";

    static final String TWINS = "zenbpm-twins.txt";

    private static final List<String> PLATFORMS = List.of("springboot", "quarkus");

    private static final Pattern GROUP = Pattern.compile("blueprints-[a-z0-9-]+");

    private static final Pattern CAMUNDA8_PROFILE = Pattern.compile(
            "(?s)([ \\t]*)<profile>\\s*<id>camunda8</id>.*?</profile>");

    private static final Pattern CAMUNDA8_ADAPTER = Pattern.compile("""
            <groupId>org\\.camunda\\.community\\.vanillabp</groupId>(\\s*)\
            <artifactId>camunda8-adapter-(spring-boot|quarkus)</artifactId>(\\s*)\
            <version>\\$\\{camunda8-adapter\\.version\\}</version>""");

    private static final Pattern CAMUNDA8_VERSION = Pattern.compile(
            "(?m)^([ \\t]*)<camunda8-adapter\\.version>[^<]*</camunda8-adapter\\.version>[ \\t]*$");

    private static final Pattern COMMENT = Pattern.compile("(?s)\\s*<!--.*?-->");

    private static final Pattern ACTIVATION = Pattern.compile("(?s)\\s*<activation>.*?</activation>");

    private static final Pattern BPMS_PROFILE = Pattern.compile(
            "(?s)<profile>\\s*<id>(camunda7|camunda8|process-engine-api)</id>(.*?)</profile>");

    /** The one activation '-Pzenbpm' switches off: Maven ends it once another profile is named. */
    private static final String BY_DEFAULT = "<activation><activeByDefault>true</activeByDefault></activation>";

    private static final Pattern REVISION = Pattern.compile("<revision>([^<]+)</revision>");

    /** A file named for Camunda 8, such as <code>db/changelog-camunda8.xml</code>. */
    private static final Pattern CAMUNDA8_FILE = Pattern.compile(".+-camunda8\\.[^.]+");

    /** A user task with a body, its prefix, its attributes and its body. */
    private static final Pattern USER_TASK = Pattern.compile(
            "(?s)<((?:[\\w-]+:)?)userTask\\b([^>]*)(?<!/)>(.*?)</\\1userTask>");

    /** The form of a user task named by an external reference, with its indentation and prefix. */
    private static final Pattern FORM_REFERENCE = Pattern.compile(
            "([ \\t]*)<((?:[\\w-]+:)?)formDefinition\\b[^>]*\\bexternalReference=\"([^\"]+)\"");

    /** What an <code>application-camunda8.yaml</code> may say, comments left aside. */
    private static final Set<String> CAMUNDA8_CONFIGURATION = Set.of(
            "vanillabp:",
            "adapters:",
            "camunda8:",
            "name-clash-avoidance: use-prefix",
            "rest-address: http://localhost:8080",
            "job-lease: use");

    private static final String ZENBPM_CONFIGURATION = """
            # Everything specific to ZenBPM, loaded only while the 'zenbpm' profile applies. Written by the
            # overlay of pbinitiative/zenbpm-vanillabp-adapter beside 'application-camunda8.yaml'.
            vanillabp:
              adapters:
                zenbpm:
                  # The same mode as on the other engines of this blueprint. ZenBPM has no tenant, so
                  # the adapter refuses VanillaBP's default 'by-adapter' and a mode has to be written.
                  name-clash-avoidance: use-prefix
                  # The engine bin/zenbpm-engine.sh of the adapter starts; the job stream is found on
                  # the same host, port 9090.
                  rest-address: http://localhost:18080
            """;

    private final Path overlay;

    private final Path blueprints;

    private final String adapterVersion;

    private final Map<String, String> variants;

    ZenBpmOverlay(
        final Path overlay,
        final Path blueprints
    ) {
        this.overlay = overlay;
        this.blueprints = blueprints;
        this.adapterVersion = adapterVersion(overlay);
        this.variants = variants(overlay);
    }

    public static void main(final String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java ZenBpmOverlay.java <overlay directory> <blueprints checkout>");
            System.exit(2);
        }
        try {
            final var twins = new ZenBpmOverlay(Path.of(args[0]), Path.of(args[1])).apply();
            System.out.printf("The overlay is applied; %d twins build with -Pzenbpm:%n", twins.size());
            twins.forEach(twin -> System.out.printf("  %s%n", twin));
        } catch (final OverlayDoesNotFit e) {
            System.err.printf("The ZenBPM overlay does not fit this checkout of the blueprints:%n%s%n", e
                    .getMessage());
            System.exit(1);
        }
    }

    /**
     * Applies the overlay to every blueprint which runs on ZenBPM.
     *
     * @return The twins to build, relative to the checkout
     */
    List<String> apply() {
        final var listed = listed();
        final var found = blueprints();
        final var problems = new ArrayList<String>();
        for (final var blueprint : found) {
            if (!listed.containsKey(blueprint)) {
                problems.add("""
                        The blueprint '%s' is not in blueprints.txt. Add it with 'runs', or with \
                        'not-runnable' and its reason on the wiki page 'Blueprints'.""".formatted(blueprint));
            }
        }
        for (final var blueprint : listed.keySet()) {
            if (!found.contains(blueprint)) {
                problems.add("""
                        The blueprint '%s' of blueprints.txt is not in the checkout any more. Remove it \
                        from the list and from the wiki page 'Blueprints'.""".formatted(blueprint));
            }
        }
        final var twins = new ArrayList<String>();
        final var used = new TreeSet<String>();
        for (final var blueprint : listed.entrySet()) {
            if (!RUNS.equals(blueprint.getValue()) || !found.contains(blueprint.getKey())) {
                continue;
            }
            for (final var platform : PLATFORMS) {
                final var twin = blueprints.resolve(blueprint.getKey()).resolve(platform);
                if (!Files.isRegularFile(twin.resolve("pom.xml"))) {
                    continue;
                }
                problems.addAll(applyTo(blueprint.getKey(), twin, used));
                twins.add("%s/%s".formatted(blueprint.getKey(), platform));
            }
        }
        for (final var variant : variants.keySet()) {
            if (!used.contains(variant)) {
                problems.add("""
                        The variant '%s' replaces no model of a blueprint which runs: its blueprint or its \
                        model is gone. Remove it from variants/ and variants.txt.""".formatted(variant));
            }
        }
        if (!problems.isEmpty()) {
            throw new OverlayDoesNotFit(String.join("\n", problems));
        }
        write(blueprints.resolve(TWINS), String.join("\n", twins)
                + "\n");
        return twins;
    }

    /**
     * The blueprints of the checkout: every directory below a group directory with a twin.
     */
    private Set<String> blueprints() {
        final var found = new TreeSet<String>();
        for (final var group : list(blueprints)) {
            if (!Files.isDirectory(group) || !GROUP
                    .matcher(group.getFileName().toString())
                    .matches()) {
                continue;
            }
            for (final var blueprint : list(group)) {
                if (PLATFORMS
                        .stream()
                        .anyMatch(platform -> Files.isRegularFile(blueprint.resolve(platform).resolve("pom.xml")))) {
                    found.add("%s/%s".formatted(group.getFileName(), blueprint.getFileName()));
                }
            }
        }
        return found;
    }

    private Map<String, String> listed() {
        final var listed = new LinkedHashMap<String, String>();
        for (final var line : read(overlay.resolve("blueprints.txt")).split("\n")) {
            final var entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            final var fields = entry.split("\\s+");
            if ((fields.length != 2) || !Set
                    .of(RUNS, NOT_RUNNABLE)
                    .contains(fields[1])) {
                throw new OverlayDoesNotFit("""
                        blueprints.txt: '%s' is no '<blueprint directory> runs' and no \
                        '<blueprint directory> not-runnable'.""".formatted(entry));
            }
            listed.put(fields[0], fields[1]);
        }
        return listed;
    }

    private List<String> applyTo(
        final String blueprint,
        final Path twin,
        final Set<String> used
    ) {
        final var problems = new ArrayList<String>();
        final var poms = files(twin, path -> "pom.xml".equals(path.getFileName().toString()));
        var versionDeclared = false;
        for (final var pom : poms) {
            final var original = read(pom);
            final var where = blueprints.relativize(pom);
            final var withProfile = withZenBpmProfile(original, where, problems);
            final var withVersion = withAdapterVersion(withProfile);
            versionDeclared |= !withVersion.equals(withProfile);
            if ((original.contains("<id>camunda7</id>") || original.contains("<id>camunda8</id>")) && withProfile
                    .equals(original)) {
                problems.add("""
                        %s chooses a BPMS but has no profile 'camunda8' the overlay could copy, so \
                        '-Pzenbpm' would leave its default BPMS on.""".formatted(where));
            }
            problems.addAll(activatedOtherwise(original, where));
            write(pom, withVersion);
        }
        if (!versionDeclared) {
            problems.add("""
                    No POM of %s declares 'camunda8-adapter.version', beside which the overlay \
                    declares 'zenbpm-vanillabp-adapter.version'.""".formatted(blueprints.relativize(twin)));
        }
        for (final var configuration : files(twin, path -> "application-camunda8.yaml".equals(path
                .getFileName()
                .toString()))) {
            problems.addAll(addZenBpmConfiguration(configuration));
        }
        for (final var file : files(twin, path -> CAMUNDA8_FILE
                .matcher(path
                        .getFileName()
                        .toString())
                .matches() && !"application-camunda8.yaml".equals(path
                        .getFileName()
                        .toString()))) {
            problems.addAll(addZenBpmFile(file));
        }
        for (final var models : directories(twin, path -> path.endsWith(Path.of("processes", "camunda8")))) {
            problems.addAll(addZenBpmModels(blueprint, twin, models, used));
        }
        return problems;
    }

    private String withZenBpmProfile(
        final String pom,
        final Path where,
        final List<String> problems
    ) {
        final Matcher profile = CAMUNDA8_PROFILE.matcher(pom);
        if (!profile.find()) {
            return pom;
        }
        final var indentation = profile.group(1);
        var zenbpm = COMMENT
                .matcher(profile.group())
                .replaceAll("");
        zenbpm = ACTIVATION
                .matcher(zenbpm)
                .replaceAll("");
        zenbpm = zenbpm
                .replace("<id>camunda8</id>", "<id>zenbpm</id>")
                .replace("<bpms>camunda8</bpms>", "<bpms>zenbpm</bpms>")
                .replace("<blueprint.bpms>camunda8</blueprint.bpms>", "<blueprint.bpms>zenbpm</blueprint.bpms>");
        zenbpm = CAMUNDA8_ADAPTER
                .matcher(zenbpm)
                .replaceAll(adapter -> Matcher.quoteReplacement("""
                        <groupId>org.pbinitiative.zenbpm</groupId>%s\
                        <artifactId>zenbpm-vanillabp-adapter-%s</artifactId>%s\
                        <version>${zenbpm-vanillabp-adapter.version}</version>""".formatted(adapter.group(1), adapter
                        .group(2), adapter.group(3))));
        if (zenbpm.contains("camunda")) {
            problems.add("""
                    The profile 'camunda8' of %s has more than the overlay translates, the profile \
                    'zenbpm' would keep:
                    %s""".formatted(where, zenbpm.strip()));
        }
        final var comment = "%s<!-- the profile of the ZenBPM overlay of pbinitiative/zenbpm-vanillabp-adapter -->"
                .formatted(indentation);
        // the copy starts with the indentation of the profile it was made from
        return "%s\n%s\n%s%s".formatted(pom.substring(0, profile.end()), comment, zenbpm, pom.substring(profile
                .end()));
    }

    /** The profiles of a BPMS which '-Pzenbpm' would leave on next to the profile zenbpm. */
    private static List<String> activatedOtherwise(
        final String pom,
        final Path where
    ) {
        final var problems = new ArrayList<String>();
        final var profile = BPMS_PROFILE.matcher(pom);
        while (profile.find()) {
            final var activation = ACTIVATION.matcher(COMMENT
                    .matcher(profile.group(2))
                    .replaceAll(""));
            if (activation.find() && !activation
                    .group()
                    .replaceAll("\\s+", "")
                    .equals(BY_DEFAULT)) {
                problems.add("""
                        The profile '%s' of %s is activated by more than activeByDefault, which \
                        '-Pzenbpm' does not switch off, so it would stay on next to the profile 'zenbpm':
                        %s""".formatted(profile.group(1), where, activation
                        .group()
                        .strip()));
            }
        }
        return problems;
    }

    private String withAdapterVersion(final String pom) {
        final var version = CAMUNDA8_VERSION.matcher(pom);
        if (!version.find()) {
            return pom;
        }
        return "%s\n%s<zenbpm-vanillabp-adapter.version>%s</zenbpm-vanillabp-adapter.version>%s".formatted(pom
                .substring(0, version.end()), version.group(1), adapterVersion, pom.substring(version.end()));
    }

    private List<String> addZenBpmConfiguration(final Path configuration) {
        final var unknown = new ArrayList<String>();
        var mode = false;
        for (final var line : read(configuration).split("\n")) {
            final var content = line.strip();
            if (content.isEmpty() || content.startsWith("#")) {
                continue;
            }
            mode |= content.startsWith("name-clash-avoidance:");
            if (!CAMUNDA8_CONFIGURATION.contains(content)) {
                unknown.add(content);
            }
        }
        if (!unknown.isEmpty() || !mode) {
            return List.of("""
                    %s says more than the overlay translates into 'application-zenbpm.yaml' (%s), or \
                    no 'name-clash-avoidance'.""".formatted(blueprints.relativize(configuration), unknown));
        }
        write(configuration.resolveSibling("application-zenbpm.yaml"), ZENBPM_CONFIGURATION);
        return List.of();
    }

    /**
     * A file a blueprint keeps for the remote engine Camunda 8, such as the changelog of a build
     * without an embedded engine, serves ZenBPM, the other remote engine, as it is, unless it says
     * something about Camunda.
     */
    private List<String> addZenBpmFile(final Path file) {
        final var content = read(file);
        if (content
                .toLowerCase(Locale.ROOT)
                .contains("camunda")) {
            return List.of("""
                    %s is a file for Camunda 8 which names Camunda, so the overlay cannot tell whether \
                    ZenBPM can use it as it is. Translate it, or teach ZenBpmOverlay.java how.""".formatted(blueprints
                    .relativize(file)));
        }
        write(file.resolveSibling(file
                .getFileName()
                .toString()
                .replaceFirst("-camunda8\\.([^.]+)$", "-zenbpm.$1")), content);
        return List.of();
    }

    private List<String> addZenBpmModels(
        final String blueprint,
        final Path twin,
        final Path models,
        final Set<String> used
    ) {
        final var problems = new ArrayList<String>();
        final var zenbpm = models.resolveSibling("zenbpm");
        for (final var model : list(models)) {
            final var target = zenbpm.resolve(model.getFileName());
            final var variant = "%s/%s".formatted(blueprint, twin
                    .relativize(target)
                    .toString()
                    .replace('\\', '/'));
            if (!variants.containsKey(variant)) {
                write(target, model
                        .getFileName()
                        .toString()
                        .endsWith(".bpmn") ? withUserTasksNamedByTheirForm(read(model)) : read(model));
                continue;
            }
            used.add(variant);
            final var hash = sha256(model);
            if (!hash.equals(variants.get(variant))) {
                problems.add("""
                        The model %s changed since its variant '%s' was written from it (SHA-256 %s, \
                        variants.txt names %s). Carry the change into the variant and write the new hash \
                        into variants.txt.""".formatted(blueprints.relativize(model), variant, hash, variants
                        .get(variant)));
                continue;
            }
            write(target, read(overlay.resolve("variants").resolve(variant)));
        }
        return problems;
    }

    /**
     * The Camunda 8 adapter finds a user task by the external reference of its form, this adapter
     * by its task definition. So a user task which names the first and not the second gets the
     * reference as its task definition, beside its form.
     */
    private static String withUserTasksNamedByTheirForm(final String model) {
        return USER_TASK
                .matcher(model)
                .replaceAll(userTask -> {
                    final var body = userTask.group(3);
                    final var form = FORM_REFERENCE.matcher(body);
                    if (body.contains("taskDefinition") || !form.find()) {
                        return Matcher.quoteReplacement(userTask.group());
                    }
                    final var named = "%s%s<%staskDefinition type=\"%s\" />\n%s".formatted(body.substring(0, form
                            .start()), form.group(1), form.group(2), form.group(3), body.substring(form.start()));
                    return Matcher.quoteReplacement("<%1$suserTask%2$s>%3$s</%1$suserTask>".formatted(userTask.group(
                            1), userTask.group(2), named));
                });
    }

    private static Map<String, String> variants(final Path overlay) {
        final var variants = new TreeMap<String, String>();
        for (final var line : read(overlay.resolve("variants.txt")).split("\n")) {
            final var entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            final var fields = entry.split("\\s+");
            if ((fields.length != 2) || !fields[1].matches("[0-9a-f]{64}") || !Files.isRegularFile(overlay
                    .resolve("variants")
                    .resolve(fields[0]))) {
                throw new OverlayDoesNotFit("""
                        variants.txt: '%s' is no '<file below variants/> <SHA-256 of the Camunda 8 model \
                        it replaces>' of a file which exists.""".formatted(entry));
            }
            variants.put(fields[0], fields[1]);
        }
        return variants;
    }

    private static String adapterVersion(final Path overlay) {
        final var revision = REVISION.matcher(read(overlay
                .toAbsolutePath()
                .getParent()
                .resolve("pom.xml")));
        if (!revision.find()) {
            throw new OverlayDoesNotFit("The adapter's root POM beside the overlay names no <revision>.");
        }
        return revision.group(1);
    }

    private static String sha256(final Path file) {
        try {
            return HexFormat
                    .of()
                    .formatHex(MessageDigest
                            .getInstance("SHA-256")
                            .digest(Files.readAllBytes(file)));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> list(final Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> files(
        final Path root,
        final Predicate<Path> wanted
    ) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains("%starget%s".formatted(File.separator, File.separator)))
                    .filter(wanted)
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> directories(
        final Path root,
        final Predicate<Path> wanted
    ) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(wanted)
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
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
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What makes the overlay not fit the checkout, said in words that name the fix. */
    static class OverlayDoesNotFit extends RuntimeException {

        private static final long serialVersionUID = 1L;

        OverlayDoesNotFit(final String message) {
            super(message);
        }

    }

}
