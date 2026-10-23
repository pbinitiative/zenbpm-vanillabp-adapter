/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The README's section "What CI runs" says that no workflow of this repository needs a secret of
 * its own and that only the two publications may write with the workflow's token: the jobs of
 * <code>publish-snapshots.yaml</code> and the job <code>publish</code> of <code>release.yaml</code>,
 * each with exactly the permissions named here. This test holds every workflow to it, the ones
 * added later included: a pull request from a fork runs a workflow without the repository's
 * secrets, and a token which may write is one more thing a workflow running on a pull request could
 * misuse.
 */
@ExtendWith(SuppressOutputExtension.class)
class WorkflowPermissionsTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final Path WORKFLOWS = ROOT.resolve(".github/workflows");

    /**
     * Every permission which writes, by workflow and job: the snapshots and the coverage pages of
     * <code>main</code>, and a release from a pushed tag.
     */
    private static final Set<String> WRITING = Set.of("publish-snapshots.yaml, job 'publish': packages: write",
            "publish-snapshots.yaml, job 'deploy-coverage-reports': pages: write",
            "publish-snapshots.yaml, job 'deploy-coverage-reports': id-token: write",
            "release.yaml, job 'publish': contents: write", "release.yaml, job 'publish': packages: write");

    private static final Pattern SECRET = Pattern.compile("secrets\\.([A-Za-z0-9_]+)");

    @Test
    @DisplayName("No workflow uses a secret but the workflow's token")
    void noWorkflowNeedsASecretOfItsOwn() throws IOException {
        final var secrets = new TreeSet<String>();
        for (final var workflow : workflows()) {
            SECRET
                    .matcher(Files.readString(workflow))
                    .results()
                    .filter(secret -> !"GITHUB_TOKEN".equals(secret.group(1)))
                    .forEach(secret -> secrets.add("%s: %s".formatted(workflow.getFileName(), secret.group(1))));
        }

        assertEquals(Set.of(), secrets, """
                These workflows use a secret of their own. Each has to work with the workflow's \
                GITHUB_TOKEN, as the README's section 'What CI runs' says, so a pull request from a fork \
                builds like any other.""");
    }

    @Test
    @DisplayName("Only the jobs of the two publications ask for a permission which writes, each for its own, and every workflow names its permissions")
    void onlyThePublicationsWrite() throws IOException {
        final var writing = new TreeSet<String>();
        final var unnamed = new TreeSet<String>();
        for (final var workflow : workflows()) {
            final var name = String.valueOf(workflow.getFileName());
            final var content = map(new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(workflow)),
                    name);
            if (!(content.get("permissions") instanceof Map<?, ?>)) {
                unnamed.add(name);
            }
            final var asked = new LinkedHashMap<String, Object>();
            asked.put(name, content.get("permissions"));
            for (final var job : map(content.get("jobs"), "%s: jobs".formatted(name)).entrySet()) {
                asked.put("%s, job '%s'".formatted(name, job.getKey()), map(job.getValue(), "%s: job '%s'".formatted(
                        name, job.getKey())).get("permissions"));
            }
            for (final var permissions : asked.entrySet()) {
                if ((permissions.getValue() != null) && !(permissions.getValue() instanceof Map<?, ?>)) {
                    writing.add("%s: %s".formatted(permissions.getKey(), permissions.getValue()));
                } else if (permissions.getValue() instanceof Map<?, ?> scopes) {
                    scopes
                            .entrySet()
                            .stream()
                            .filter(scope -> !Set
                                    .of("read", "none")
                                    .contains(String.valueOf(scope.getValue())))
                            .forEach(scope -> writing.add("%s: %s: %s".formatted(permissions.getKey(), scope
                                    .getKey(), scope.getValue())));
                }
            }
        }

        assertEquals(Set.of(), unnamed, """
                These workflows name no permissions at the top, so a job which names none gets the \
                repository's default rather than what the workflow asks for.""");
        assertEquals(WRITING, writing, """
                Only the jobs of publish-snapshots.yaml and the job 'publish' of release.yaml may write \
                with the workflow's token, each with exactly the permissions this test names, as the \
                README's section 'What CI runs' says. List every other permission by its scope, as \
                'read' or 'none'.""");
    }

    private static List<Path> workflows() throws IOException {
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            final var workflows = files
                    .filter(file -> file
                            .getFileName()
                            .toString()
                            .matches(".+\\.ya?ml"))
                    .sorted()
                    .toList();
            assertFalse(workflows.isEmpty(), () -> "%s holds no workflow.".formatted(WORKFLOWS));
            return workflows;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(
        final Object value,
        final String where
    ) {
        assertTrue(value instanceof Map<?, ?>, () -> "'%s' is no mapping but '%s'".formatted(where, value));
        return (Map<String, Object>) value;
    }

}
