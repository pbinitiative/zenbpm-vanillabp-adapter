/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.sun.net.httpserver.HttpServer;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow module whose BPMN file uses what ZenBPM cannot run, booted on Spring Boot: the start
 * ends with the message naming file, module, process and every element, and nothing reaches the
 * engine but the platform's startup question how many tasks of the process are open, a read; with
 * <code>deployment-failure: warn</code> on an adapter id which is nowhere first, the application
 * boots and the block says the same. The engine is a server which answers <code>503</code> and
 * writes down method and path of every request it gets.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmUnsupportedModelBootTest {

    // a workflow module with one BPMN file, the process of the sample workflow service with a
    // script task and an instantiating receive task. The file is named after the process,
    // because the BPMS double takes the process id from the file name
    private static final String WITH_REFUSED_BPMN = "classpath*:with-bpmn-refused";

    private static final String REFUSAL = """
            BPMN file 'SampleWorkflowService.bpmn' of workflow module 'smoke-app' uses what ZenBPM cannot run:
              ZenBPM refuses to deploy a file with these:
                - SampleWorkflowService/Calculate (scriptTask)
              These would start a workflow for which nobody built a workflow aggregate, because the engine starts it on its own:
                - SampleWorkflowService/OrderReceived (receiveTask with instantiate="true")
            Model them with what ZenBPM runs, or take them out of the file.""";

    private final List<String> requests = new CopyOnWriteArrayList<>();

    private HttpServer engine;

    @BeforeEach
    void startTheEngine() throws IOException {
        engine = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        engine.createContext("/", exchange -> {
            requests.add("%s %s".formatted(exchange.getRequestMethod(), exchange
                    .getRequestURI()
                    .getPath()));
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        engine.start();
    }

    @AfterEach
    void stopTheEngine() {
        engine.stop(0);
    }

    @Test
    @DisplayName("A file with what ZenBPM cannot run ends the start, naming file, module, process and every element, and nothing is deployed")
    void anUnsupportedModelEndsTheStartBeforeAnythingIsDeployed() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=%s".formatted(address()),
                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(WITH_REFUSED_BPMN)).close()));

        assertAll(
                () -> assertEquals(REFUSAL, failure.getMessage()),
                () -> assertEquals(List.of("GET /v1/process-definitions"), requests));
    }

    @Test
    @DisplayName("With policy 'warn' on an adapter id nowhere first, the application boots on the first adapter, and the refusal stands in the block")
    void anUnsupportedModelOfAnAdapterIdWhichMayFailIsAWarning(final CapturedOutput output) {
        try (var context = run("vanillabp.prioritized-adapters=dummy,zenbpm", "vanillabp.adapters.dummy.type=dummy",
                "vanillabp.adapters.dummy.test=7",
                "vanillabp.adapters.dummy.resources-location=%s".formatted(WITH_REFUSED_BPMN),
                "vanillabp.adapters.zenbpm.type=zenbpm", "vanillabp.adapters.zenbpm.deployment-failure=warn",
                "vanillabp.adapters.zenbpm.rest-address=%s".formatted(address()),
                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(WITH_REFUSED_BPMN))) {
            final var log = output.getAllOfThisTest();
            final var failure = log.indexOf("Deployment of workflow module 'smoke-app' failed for adapter 'zenbpm'!");

            assertAll(
                    () -> assertTrue(context.isActive()),
                    () -> assertTrue(failure >= 0, log),
                    () -> assertTrue(log.indexOf("- SampleWorkflowService/Calculate (scriptTask)", failure) > 0, log),
                    () -> assertEquals(List.of("GET /v1/process-definitions"), requests));
        }
    }

    private String address() {
        return "http://localhost:%d".formatted(engine
                .getAddress()
                .getPort());
    }

    // command-line arguments win over every other source of properties
    private static ConfigurableApplicationContext run(final String... properties) {
        return new SpringApplicationBuilder(SmokeTestApplication.class)
                .web(WebApplicationType.NONE)
                .run(Stream
                        .of(properties)
                        .map("--%s"::formatted)
                        .toArray(String[]::new));
    }

    private static Throwable rootOf(final Throwable throwable) {
        var cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

}
