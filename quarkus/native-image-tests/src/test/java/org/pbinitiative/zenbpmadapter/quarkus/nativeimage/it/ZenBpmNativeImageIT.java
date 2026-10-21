/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.nativeimage.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Runs the native binary of this module against the pinned engine: it boots, deploys its workflow
 * module, starts a workflow, serves both of its tasks, correlates the message between them and
 * serves the end of the workflow.
 * <p>
 * The Quarkus plugin built the binary in the <code>package</code> phase, so this is an integration
 * test: at <code>test</code> time there is no binary yet. The application's own main decides the
 * exit code (see {@code NativeImageApplication}); this test hands it the engine, reads the code and
 * keeps what the binary printed in <code>target/native-image-application.log</code>, the only report
 * a failing run leaves behind. The exit code says that every handler ran, and not that the engine
 * took the completion of the last job, which the adapter sends after the handler returned, so the
 * test asks the engine whether the workflow ended.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmNativeImageIT {

    /**
     * How long the binary may take to boot, run its workflow and exit. Its main waits a minute for
     * the first task and a minute for the end, so this is those two minutes and time for the boot.
     */
    private static final Duration UNTIL_EXITED = Duration.ofMinutes(4);

    /**
     * How long the engine may take to show the workflow's end after the binary exited. The binary
     * drains its handlers, the completion of the last job included, before it exits, so the end is
     * there at once; the window only covers the engine writing it.
     */
    private static final Duration UNTIL_THE_ENGINE_SHOWS_THE_END = Duration.ofSeconds(30);

    private static final Duration BETWEEN_READS = Duration.ofMillis(250);

    private static final Pattern THE_AGGREGATE = Pattern.compile("the workflow of aggregate '([^']+)'");

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Test
    @DisplayName("The native binary boots, deploys its workflow module and runs a workflow with two tasks and a message to its end")
    void theNativeBinaryRunsAWorkflow() throws IOException, InterruptedException {
        final var binary = Path.of(System.getProperty("native.image.runner"));
        assertTrue(Files.isExecutable(binary), () -> """
                '%s' is not an executable binary. This test runs what the native build produced, so it \
                needs the profile which builds it: 'mvn -Dnative -pl quarkus/native-image-tests verify'."""
                .formatted(binary));

        final var output = run(binary);

        assertEquals(0, output.exitCode(), () -> "The native binary exited with %d. Its output was:%n%s"
                .formatted(output.exitCode(), output.text()));
        assertTrue(output
                .text()
                .contains("on ZenBPM to its end (status 'ended')"), () -> """
                        The native binary exited with 0 without saying it ran the workflow to its end:
                        %s""".formatted(output.text()));
        final var aggregate = THE_AGGREGATE.matcher(output.text());
        assertTrue(aggregate.find(), () -> "The native binary named no aggregate:%n%s".formatted(output.text()));
        final var instances = awaitTheEndOfTheWorkflowOf(aggregate.group(1));
        assertEquals(List.of("completed"), states(instances), () -> """
                The native binary exited with 0, but the engine did not complete the workflow of \
                aggregate '%s': %s
                What the binary printed:
                %s""".formatted(aggregate.group(1), instances, output.text()));
        // the adapter's check that it fits the platform reads the platform's version descriptor,
        // which the extension puts into the image
        assertFalse(output
                .text()
                .contains("and unknown is on the classpath"), () -> """
                        The native binary could not read the version of the VanillaBP platform integration:
                        %s""".formatted(output.text()));
    }

    /**
     * What the binary printed and what it returned, kept together because the output is the only
     * report a failing run leaves behind.
     *
     * @param exitCode The exit code of the binary
     * @param text Everything it wrote to standard output and standard error
     */
    private record BinaryOutput(
        int exitCode,
        String text
    ) {
    }

    private static BinaryOutput run(final Path binary) throws IOException, InterruptedException {
        final var log = binary.resolveSibling("native-image-application.log");
        final var process = new ProcessBuilder(binary.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .directory(binary
                        .getParent()
                        .toFile());
        // only the test knows the ports the container was given
        process
                .environment()
                .put("ZENBPM_REST_ADDRESS", ENGINE.restAddress());
        process
                .environment()
                .put("ZENBPM_GRPC_ADDRESS", ENGINE.grpcAddress());

        final var running = process.start();
        if (!running.waitFor(UNTIL_EXITED.toSeconds(), TimeUnit.SECONDS)) {
            running.destroyForcibly();
            return new BinaryOutput(-1, "The binary did not exit within %s. What it printed until then:%n%s"
                    .formatted(UNTIL_EXITED, Files.readString(log)));
        }
        return new BinaryOutput(running.exitValue(), Files.readString(log));
    }

    // the workflow has no call activity, so the business key names its one instance; reads until
    // no instance is active any more or the window is over, and hands back what was read last
    private static JsonNode awaitTheEndOfTheWorkflowOf(final String aggregateId) throws InterruptedException {
        final var deadline = System.nanoTime() + UNTIL_THE_ENGINE_SHOWS_THE_END.toNanos();
        var instances = ENGINE.json("/v1/process-instances?businessKey=%s".formatted(aggregateId));
        while (states(instances).contains("active") && System.nanoTime() < deadline) {
            Thread.sleep(BETWEEN_READS.toMillis());
            instances = ENGINE.json("/v1/process-instances?businessKey=%s".formatted(aggregateId));
        }
        return instances;
    }

    private static List<String> states(final JsonNode instances) {
        final var states = new ArrayList<String>();
        instances
                .path("partitions")
                .forEach(partition -> partition
                        .path("items")
                        .forEach(instance -> states.add(instance
                                .path("state")
                                .asText())));
        return states;
    }

}
