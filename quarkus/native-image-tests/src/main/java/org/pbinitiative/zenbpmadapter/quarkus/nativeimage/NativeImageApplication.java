/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.nativeimage;

import java.time.Duration;
import java.util.UUID;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/**
 * The assertion of this module, written as the application's main: the binary boots, runs one
 * workflow from its start to its end, and says by its exit code whether it did.
 * <p>
 * The boot deploys the workflow module: the adapter reads the BPMN file, writes what it adds and
 * sends the result to the engine. The main then starts a workflow through the platform's phase-two
 * outbox, waits until the engine handed the job of the first task to its handler in this binary,
 * correlates the message the workflow waits for after it, and waits for the handler of the second
 * task. Each handler writes its status into the aggregate within its own transaction, so a step
 * which goes wrong either throws or leaves the status where it was, and the exit code is not 0.
 */
@QuarkusMain
public class NativeImageApplication implements QuarkusApplication {

    /**
     * How long a step may take until its handler committed. Each step takes a second or two
     * against a freshly started engine; the window is wide because a busy runner is slower than a
     * laptop, and it has a limit so that a job which never arrives ends the run instead of hanging
     * it.
     */
    private static final Duration UNTIL_A_STEP_IS_DONE = Duration.ofMinutes(1);

    private static final Duration BETWEEN_READS = Duration.ofMillis(250);

    @Inject
    NativeImageWorkflow workflow;

    @Inject
    NativeImagePersistence persistence;

    /**
     * Built by Quarkus.
     */
    public NativeImageApplication() {
    }

    @Override
    public int run(final String... args) throws InterruptedException {
        final var id = UUID
                .randomUUID()
                .toString();
        QuarkusTransaction
                .requiringNew()
                .run(() -> workflow.start(new NativeImageAggregate(id)));
        if (!reached(id, NativeImageWorkflow.SERVED)) {
            return 1;
        }

        QuarkusTransaction
                .requiringNew()
                .run(() -> workflow.goAhead(persistence.loadById(id)));
        if (!reached(id, NativeImageWorkflow.FINISHED)) {
            return 1;
        }

        System.out
                .println("VanillaBP ran the workflow of aggregate '%s' on ZenBPM to its end (status '%s')."
                        .formatted(id, NativeImageWorkflow.FINISHED));
        return 0;
    }

    // the handler's own transaction makes its work visible after it returned, so this reads the
    // aggregate and does not watch the handler
    private boolean reached(
        final String id,
        final String status
    ) throws InterruptedException {
        final var deadline = System.nanoTime() + UNTIL_A_STEP_IS_DONE.toNanos();
        String read = null;
        while (System.nanoTime() < deadline) {
            read = QuarkusTransaction
                    .requiringNew()
                    .call(() -> persistence
                            .loadById(id)
                            .getStatus());
            if (status.equals(read)) {
                return true;
            }
            Thread.sleep(BETWEEN_READS.toMillis());
        }
        System.err
                .println("The workflow of aggregate '%s' did not reach status '%s' within %s; it reads '%s'."
                        .formatted(id, status, UNTIL_A_STEP_IS_DONE, read));
        return false;
    }

}
