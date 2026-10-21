/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.Locale;
import java.util.ResourceBundle;

import org.pbinitiative.zenbpmadapter.client.api.EngineStatus;

import io.vanillabp.integration.spi.startup.StartupTopic;
import lombok.extern.slf4j.Slf4j;

/**
 * Says once per adapter id which engine the adapter reached: one INFO line with the addresses,
 * the client id, and the version and commit the engine reports in <code>GET /system/status</code>,
 * beside the engine commit this adapter was tested with.
 * <p>
 * Where the engine runs another commit, that is a finding: the adapter supports the engine it was
 * tested with and nothing newer, see decision 15 in the repository's DECISIONS.md. So it goes to
 * the platform's startup report as a warning about the infrastructure, scoped to the adapter id,
 * and so does an engine which does not say what it runs. The INFO line stays in the log, because
 * which engine the adapter reached is a report about the adapter doing its work, which the startup
 * report leaves out, see decision 60 in the repository's DECISIONS.md. Versions are not compared:
 * an engine built from <code>main</code> reports the version of the last release, the same for
 * every build since.
 */
@Slf4j
public final class ZenBpmEnvironmentInfo {

    /**
     * The shortest commit which is taken for one: git's own shortest form. The engine reports
     * twelve characters.
     */
    private static final int SHORTEST_COMMIT = 7;

    private static final String BUNDLE = "META-INF/vanillabp/adapter-zenbpm";

    private static final String TESTED_COMMIT_KEY = "zenbpm.engine";

    private ZenBpmEnvironmentInfo() {
    }

    /**
     * Asks the engine of an adapter id what it runs and says so, once per adapter id. Never throws
     * because the engine did not answer: that is a finding as well.
     *
     * @param factory The factory of the adapter id; one without clients is skipped
     * @param findings Where a commit the adapter was not tested with, or an engine which did not
     *            say its commit, is reported
     */
    public static void logOnce(
        final ZenBpmClientFactory factory,
        final ZenBpmFindings findings
    ) {
        if (!factory.isConfigured()) {
            return;
        }
        factory.logTheEnvironmentOnce(() -> log(factory, findings));
    }

    /**
     * The engine commit this build of the adapter was tested with, filtered into the adapter's
     * jar by the build.
     *
     * @return The full commit
     */
    static String testedCommit() {
        return ResourceBundle
                .getBundle(BUNDLE, Locale.ROOT)
                .getString(TESTED_COMMIT_KEY);
    }

    private static EngineStatus log(
        final ZenBpmClientFactory factory,
        final ZenBpmFindings findings
    ) {
        final var adapterId = factory.getAdapterId();
        final var configuration = factory.configuration();
        final var testedCommit = testedCommit();
        final var scope = ZenBpmFindings.ofAdapter(adapterId);
        final EngineStatus status;
        try {
            status = factory
                    .rest()
                    .status();
        } catch (final RuntimeException e) {
            findings.warn(StartupTopic.INFRASTRUCTURE, scope, """
                    The engine at %s did not say which version it runs (%s), so whether it runs engine commit %s, \
                    which this adapter was tested with, is unknown, and an engine this adapter was not tested with \
                    is not supported. Check that the engine answers GET /system/status; the next start asks \
                    again.""".formatted(configuration.engineAddress(), ZenBpmErrors.withoutAdapterId(adapterId, e),
                    testedCommit));
            return null;
        }
        log.info(
                "ZenBPM[{}]: engine at {}, job stream at {} with client id '{}': version {}, commit {}, node(s) {}; this adapter was tested with engine commit {}",
                adapterId, configuration.engineAddress(), configuration.grpcAddressInUse(), factory.clientId(), status
                        .version(),
                status.commit(), String.join(", ", status.nodes()), testedCommit);
        if (!isTested(status.commit(), testedCommit)) {
            findings.warn(StartupTopic.INFRASTRUCTURE, scope,
                    "The engine reports commit %s (version %s), but this adapter was tested with engine commit %s, and an engine is supported only where it was tested. Run the engine image this adapter's release names, or an adapter release tested with this engine."
                            .formatted(status.commit(), status.version(), testedCommit));
        }
        return status;
    }

    // the engine shortens its commit, so the one it reports is a beginning of the tested one
    private static boolean isTested(
        final String reported,
        final String tested
    ) {
        return (reported != null) && (reported.length() >= SHORTEST_COMMIT) && tested
                .toLowerCase(Locale.ROOT)
                .startsWith(reported.toLowerCase(Locale.ROOT));
    }

}
