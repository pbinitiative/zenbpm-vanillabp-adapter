/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of the meters: a crate is weighed and sealed in parallel, on one handler slot, so
 * whichever job comes second waits for it. Each task takes a second; the first weighing throws, and
 * the second one takes longer than the <code>job-timeout</code> of its task, so its lock is renewed.
 */
@Service
@WorkflowService(workflowAggregateClass = Crate.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Crating"))
public class CrateWorkflow {

    /**
     * How long the second weighing takes, beyond the <code>job-timeout</code> of PT2S of its task.
     */
    static final Duration SECOND_WEIGHING = Duration.ofMillis(2500);

    // the weighings of each crate, kept outside the aggregate, whose changes a throw rolls back
    private static final Map<String, AtomicInteger> WEIGHINGS = new ConcurrentHashMap<>();

    /**
     * Built by Spring.
     */
    public CrateWorkflow() {
    }

    /**
     * Weighs the crate: the first time it throws, the second time it takes longer than its lock.
     *
     * @param crate The crate
     */
    @WorkflowTask(taskDefinition = "weigh")
    public void weigh(final Crate crate) {
        final var weighing = WEIGHINGS
                .computeIfAbsent(crate.getId(), id -> new AtomicInteger())
                .incrementAndGet();
        if (weighing == 1) {
            pause(Duration.ofSeconds(1));
            throw new IllegalStateException("the scales are not calibrated");
        }
        pause(SECOND_WEIGHING);
    }

    /**
     * Seals the crate.
     *
     * @param crate The crate
     */
    @WorkflowTask(taskDefinition = "seal")
    public void seal(final Crate crate) {
        pause(Duration.ofSeconds(1));
    }

    private static void pause(final Duration duration) {
        LockSupport.parkNanos(duration.toNanos());
    }

}
