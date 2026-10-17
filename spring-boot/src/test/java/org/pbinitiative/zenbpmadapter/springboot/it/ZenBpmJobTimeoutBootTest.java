/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.springboot.it.timeouts.TimeoutsApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two tasks of one job type, whose task-level <code>job-timeout</code>s differ, end the start of a
 * Spring Boot application, naming both values and the keys to set, before the engine is asked
 * anything: the engine locks the jobs of a type for one duration.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmJobTimeoutBootTest {

    @Test
    @DisplayName("Two task-level job-timeouts of one job type end the start, naming both and the keys to set")
    void twoJobTimeoutsOfOneJobTypeEndTheStart() {
        final var failure = assertThrows(Exception.class, () -> new SpringApplicationBuilder(TimeoutsApplication.class)
                .web(WebApplicationType.NONE)
                .run(Stream
                        .of("vanillabp.prioritized-adapters=zenbpm",
                                "vanillabp.adapters.zenbpm.rest-address=http://localhost:1",
                                "vanillabp.adapters.zenbpm.name-clash-avoidance=none",
                                "vanillabp.adapters.zenbpm.accept-unscoped-identifiers=true",
                                "vanillabp.adapters.zenbpm.resources-location=classpath*:job-timeouts",
                                "vanillabp.workflow-modules.deploy-it.workflows.Packing.tasks.pack.adapters.zenbpm.job-timeout=PT1M",
                                "vanillabp.workflow-modules.deploy-it.workflows.Returns.tasks.pack.adapters.zenbpm.job-timeout=PT2M",
                                "vanillabp.outbox.housekeeping.zone=UTC")
                        .map("--%s"::formatted)
                        .toArray(String[]::new))
                .close());
        var root = (Throwable) failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        final var message = root.getMessage();

        assertAll(
                () -> assertTrue(message.startsWith(
                        "Job type 'pack' of workflow module 'deploy-it' is served by two tasks with different job-timeouts:"),
                        message),
                () -> assertTrue(message.contains("PT1M for task 'pack' of process 'Packing'"), message),
                () -> assertTrue(message.contains("PT2M for task 'pack' of process 'Returns'"), message),
                () -> assertTrue(message.contains(
                        "vanillabp.workflow-modules.deploy-it.workflows.Packing.tasks.pack.adapters.zenbpm.job-timeout"),
                        message),
                () -> assertTrue(message.contains(
                        "vanillabp.workflow-modules.deploy-it.workflows.Returns.tasks.pack.adapters.zenbpm.job-timeout"),
                        message));
    }

}
