/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.eventually;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory.WorkflowModuleShutdown;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory.WorkflowModuleShutdownRegistration;

import io.grpc.inprocess.InProcessChannelBuilder;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What building a factory does, and what closing it does, in which order.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmClientFactoryTest {

    private static final String ADAPTER_ID = "orders";

    /**
     * How long a test gives a client which wrongly connects while it is built. Building is
     * synchronous, so anything it opens would arrive at once; the pause covers a connection
     * opened on another thread.
     */
    private static final Duration A_MOMENT = Duration.ofMillis(300);

    @Test
    @DisplayName("Building a factory for a complete configuration opens no connection, the first call and the first subscription do")
    void buildingContactsNothing() throws InterruptedException {
        try (var engine = FakeEngine.started(); var grpc = CountingListener.started()) {
            engine.answers("GET", "/system/health/ready", 200, "{\"status\":\"UP\",\"reasons\":[]}");
            final var configuration = configuration(engine.address());
            configuration.setGrpcAddress(grpc.address());

            try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")) {
                Thread.sleep(A_MOMENT);
                assertAll(
                        () -> assertEquals(List.of(), engine.requests(), "REST requests while building"),
                        () -> assertEquals(0, grpc.accepted(), "gRPC connections while building"));

                // the two counters do count: the first call and the first subscription arrive
                factory
                        .rest()
                        .ready();
                factory
                        .stream()
                        .subscribe("module", "type", Duration.ofMinutes(1), 1);
                assertEquals(1, engine
                        .requests()
                        .size());
                eventually(() -> grpc.accepted() > 0 ? Optional.of(true) : Optional.empty());
            }
        }
    }

    @Test
    @DisplayName("The client id is derived from the application name and the adapter id where 'client-id' is not set")
    void theClientIdIsDerived() {
        final var configuration = configuration("http://localhost:8080");

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")) {
            assertTrue(factory
                    .clientId()
                    .matches("shop-orders-[0-9a-f]{8}"), factory.clientId());
        }
    }

    @Test
    @DisplayName("The client id is 'client-id' where it is set")
    void theClientIdIsTheConfiguredOne() {
        final var configuration = configuration("http://localhost:8080");
        configuration.setClientId("node-7");

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")) {
            assertEquals("node-7", factory.clientId());
        }
    }

    @Test
    @DisplayName("The executor follows the execution model of the configuration")
    void theExecutorFollowsTheModel() {
        final var configuration = configuration("http://localhost:8080");
        configuration.setWorkerThreads("virtual");
        configuration.setWorkerThreadsBound(3);

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")) {
            assertAll(
                    () -> assertInstanceOf(ZenBpmVirtualThreadExecutor.class, factory.executor()),
                    () -> assertEquals(3, factory
                            .executor()
                            .getSlots()),
                    () -> assertSame(configuration, factory.configuration()),
                    () -> assertEquals(ADAPTER_ID, factory.getAdapterId()),
                    () -> assertTrue(factory.isConfigured()),
                    () -> assertEquals(Optional.empty(), factory.unconfiguredMessage()));
        }
    }

    @Test
    @DisplayName("Closing stops the modules which did not stop, newest hook first, then the stream, the executor and the REST client")
    void closingKeepsItsOrder(final CapturedOutput output) {
        final var closed = new CopyOnWriteArrayList<String>();
        final var factory = recordingFactory(closed);
        factory.workflowModuleStarted("billing", () -> closed.add("billing: adapter"));
        factory.workflowModuleStarted("billing", () -> closed.add("billing: extension"));
        factory.workflowModuleStarted("shipping", () -> closed.add("shipping: adapter"));

        factory.close();

        assertAll(
                () -> assertEquals(List.of("billing: extension", "billing: adapter", "shipping: adapter",
                        "stream, executor running", "rest, executor shut down"), closed),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "WARN  o.p.z.client.ZenBpmClientFactory - ZenBPM[orders]: the clients are being closed while the workflow module(s) 'billing', 'shipping' still subscribe their job types"),
                        output.getAllOfThisTest()),
                () -> assertEquals(Set.of(), factory.getOpenWorkflowModules()));
    }

    @Test
    @DisplayName("A module which stopped itself is not stopped again, and nothing is warned about")
    void aModuleWhichStoppedItselfIsLeftAlone(final CapturedOutput output) {
        final var closed = new CopyOnWriteArrayList<String>();
        final var factory = recordingFactory(closed);
        final var adapter = factory.workflowModuleStarted("billing", () -> closed.add("billing: adapter"));
        final var extension = factory.workflowModuleStarted("billing", () -> closed.add("billing: extension"));

        adapter.close();
        assertEquals(Set.of("billing"), factory.getOpenWorkflowModules(), "one hook of the module is left");
        extension.close();
        // removing a hook twice changes nothing
        extension.close();
        factory.close();

        assertAll(
                () -> assertEquals(List.of("stream, executor running", "rest, executor shut down"), closed),
                () -> assertFalse(output
                        .getAllOfThisTest()
                        .contains("WARN"), output.getAllOfThisTest()));
    }

    @Test
    @DisplayName("A module whose stop fails does not keep the clients open")
    void aFailingStopDoesNotKeepTheClientsOpen(final CapturedOutput output) {
        final var closed = new CopyOnWriteArrayList<String>();
        final var factory = recordingFactory(closed);
        factory.workflowModuleStarted("billing", () -> {
            throw new IllegalStateException("the drain broke");
        });

        factory.close();

        assertAll(
                () -> assertEquals(List.of("stream, executor running", "rest, executor shut down"), closed),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains(
                                "ZenBPM[orders]: stopping workflow module 'billing' failed while the clients were being closed"),
                        output.getAllOfThisTest()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("the drain broke"), output.getAllOfThisTest()));
    }

    @Test
    @DisplayName("A hook may wait for the module's own stop, which removes the hook meanwhile")
    void aHookMayWaitForTheModulesOwnStop() {
        final var closed = new CopyOnWriteArrayList<String>();
        final var factory = recordingFactory(closed);
        final var registration = new AtomicReference<WorkflowModuleShutdownRegistration>();
        registration.set(factory.workflowModuleStarted("billing", () -> {
            // the module's own stop runs on another thread, and ends by removing its hook
            final var ownStop = Thread
                    .ofPlatform()
                    .start(() -> registration
                            .get()
                            .close());
            try {
                ownStop.join(A_MOMENT.multipliedBy(5));
            } catch (final InterruptedException e) {
                Thread
                        .currentThread()
                        .interrupt();
            }
            closed.add(ownStop.isAlive() ? "billing: its own stop still waits" : "billing: stopped by itself");
        }));

        assertTimeoutPreemptively(Duration.ofSeconds(5), factory::close);

        assertEquals(List.of("billing: stopped by itself", "stream, executor running", "rest, executor shut down"),
                closed);
    }

    @Test
    @DisplayName("A module which starts once closing began is refused, also while the hooks run, and nothing is left open")
    void aModuleCannotStartOnceClosingBegan() {
        final var factory = recordingFactory(new CopyOnWriteArrayList<>());
        final var whileClosing = new AtomicReference<Throwable>();
        factory.workflowModuleStarted("billing", () -> whileClosing.set(assertThrows(IllegalStateException.class,
                () -> factory.workflowModuleStarted("shipping", () -> {
                }))));

        factory.close();
        final var afterClosing = assertThrows(IllegalStateException.class, () -> factory.workflowModuleStarted("late",
                () -> {
                }));

        assertAll(
                () -> assertEquals(
                        "ZenBPM adapter 'orders': workflow module 'shipping' cannot start, because the clients of the adapter id are closed or being closed while the application stops.",
                        whileClosing
                                .get()
                                .getMessage()),
                () -> assertEquals(
                        "ZenBPM adapter 'orders': workflow module 'late' cannot start, because the clients of the adapter id are closed or being closed while the application stops.",
                        afterClosing.getMessage()),
                () -> assertEquals(Set.of(), factory.getOpenWorkflowModules()));
    }

    @Test
    @DisplayName("A client which fails to close does not keep the others open, and the first failure is thrown with the later ones")
    void aFailingCloseDoesNotKeepTheOthersOpen() {
        final var closed = new CopyOnWriteArrayList<String>();
        final var factory = recordingFactory(closed, true);

        final var failure = assertThrows(IllegalStateException.class, factory::close);

        assertAll(
                () -> assertEquals(List.of("stream, executor running", "rest, executor shut down"), closed),
                () -> assertEquals("the stream would not close", failure.getMessage()),
                () -> assertEquals(List.of("the HTTP client would not close"), Arrays
                        .stream(failure.getSuppressed())
                        .map(Throwable::getMessage)
                        .toList()));
    }

    @Test
    @DisplayName("A hook which throws an Error does not keep the clients open, and the Error is thrown")
    void aHookWhichThrowsAnErrorDoesNotKeepTheClientsOpen() {
        final var closed = new CopyOnWriteArrayList<String>();
        final var factory = recordingFactory(closed);
        factory.workflowModuleStarted("billing", () -> {
            throw new AssertionError("the drain broke hard");
        });

        final var failure = assertThrows(AssertionError.class, factory::close);

        assertAll(
                () -> assertEquals("the drain broke hard", failure.getMessage()),
                () -> assertEquals(List.of("stream, executor running", "rest, executor shut down"), closed));
    }

    @Test
    @DisplayName("A registration removes its own hook and no other, also where one hook object is registered twice and closed twice")
    void aRegistrationRemovesItsOwnHookOnly() {
        final var closed = new CopyOnWriteArrayList<String>();
        final var factory = recordingFactory(closed);
        final WorkflowModuleShutdown hook = () -> closed.add("billing");
        final var adapter = factory.workflowModuleStarted("billing", hook);
        factory.workflowModuleStarted("billing", hook);

        adapter.close();
        adapter.close();

        assertEquals(Set.of("billing"), factory.getOpenWorkflowModules());
        factory.close();
        assertEquals(List.of("billing", "stream, executor running", "rest, executor shut down"), closed);
    }

    @Test
    @DisplayName("A configuration changed after the clients were built does not keep the REST client open")
    void aChangedConfigurationDoesNotKeepTheRestClientOpen(final CapturedOutput output) {
        final var closed = new CopyOnWriteArrayList<String>();
        final var factory = recordingFactory(closed);
        factory
                .configuration()
                .setRestAddress("not an address");

        factory.close();

        assertAll(
                () -> assertEquals(List.of("stream, executor running", "rest, executor shut down"), closed),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("ZenBPM[orders]: closing the REST client of the engine at http://localhost:8080"),
                        output.getAllOfThisTest()));
    }

    @Test
    @DisplayName("Closing twice closes once")
    void closingTwiceClosesOnce() {
        final var closed = new CopyOnWriteArrayList<String>();
        final var factory = recordingFactory(closed);

        factory.close();
        factory.close();

        assertEquals(List.of("stream, executor running", "rest, executor shut down"), closed);
    }

    @Test
    @DisplayName("A factory without clients refuses every client with the message of the validation")
    void aFactoryWithoutClientsRefusesThemWithTheMessage() {
        final var configuration = new ZenBpmAdapterConfiguration();
        final var message = configuration
                .validate(ADAPTER_ID, true, true)
                .message();
        final var factory = ZenBpmClientFactory.unconfigured(ADAPTER_ID, configuration, message);
        factory.workflowModuleStarted("billing", () -> {
        });

        assertAll(
                () -> assertFalse(factory.isConfigured()),
                () -> assertEquals(Optional.of(message), factory.unconfiguredMessage()),
                () -> assertEquals(message, assertThrows(IllegalStateException.class, factory::rest).getMessage()),
                () -> assertEquals(message, assertThrows(IllegalStateException.class, factory::stream).getMessage()),
                () -> assertEquals(message, assertThrows(IllegalStateException.class, factory::executor).getMessage()),
                () -> assertEquals(message, assertThrows(IllegalStateException.class, factory::clientId).getMessage()));
        factory.close();
        assertEquals(Set.of(), factory.getOpenWorkflowModules());
    }

    @Test
    @DisplayName("A factory without clients needs the message which says why, and the configuration")
    void aFactoryWithoutClientsNeedsTheMessageAndTheConfiguration() {
        final var configuration = new ZenBpmAdapterConfiguration();

        assertAll(
                () -> assertEquals("message", assertThrows(NullPointerException.class, () -> ZenBpmClientFactory
                        .unconfigured(ADAPTER_ID, configuration, null)).getMessage()),
                () -> assertEquals("configuration", assertThrows(NullPointerException.class,
                        () -> ZenBpmClientFactory.unconfigured(ADAPTER_ID, null, "configure me")).getMessage()));
    }

    private static ZenBpmAdapterConfiguration configuration(final String restAddress) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        return configuration;
    }

    /**
     * A factory whose stream and REST client write into a list when they are closed, together
     * with whether the executor was shut down at that moment.
     */
    private static ZenBpmClientFactory recordingFactory(final List<String> closed) {
        return recordingFactory(closed, false);
    }

    /**
     * A factory as {@link #recordingFactory(List)} builds it, whose stream and REST client throw
     * where they are closed, if asked to.
     */
    private static ZenBpmClientFactory recordingFactory(
        final List<String> closed,
        final boolean failing
    ) {
        final var configuration = configuration("http://localhost:8080");
        final var executor = ZenBpmExecutor.of(ADAPTER_ID, ZenBpmExecutionModel.DEFAULT);
        final var rest = new ZenBpmRestClient(ADAPTER_ID, configuration) {

            @Override
            public void close() {
                closed.add("rest, executor %s".formatted(executor.isShutdown() ? "shut down" : "running"));
                super.close();
                if (failing) {
                    throw new IllegalStateException("the HTTP client would not close");
                }
            }

        };
        final var stream = new ZenBpmJobStream(ADAPTER_ID, "client", "fake-engine:9090", InProcessChannelBuilder
                .forName("nowhere")
                .build(), executor.timing(), ZenBpmJobStream.PACING, Duration.ofSeconds(1)) {

            @Override
            public void close() {
                closed.add("stream, executor %s".formatted(executor.isShutdown() ? "shut down" : "running"));
                super.close();
                if (failing) {
                    throw new IllegalStateException("the stream would not close");
                }
            }

        };
        return new ZenBpmClientFactory(ADAPTER_ID, configuration, "client", rest, stream, executor);
    }

    /**
     * A port which takes connections, counts them and never answers.
     */
    private static final class CountingListener implements AutoCloseable {

        private final ServerSocket socket;

        private final AtomicInteger accepted = new AtomicInteger();

        private final List<Socket> connections = new CopyOnWriteArrayList<>();

        private CountingListener(final ServerSocket socket) {
            this.socket = socket;
            Thread
                    .ofVirtual()
                    .start(this::accept);
        }

        static CountingListener started() {
            try {
                return new CountingListener(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String address() {
            final var bound = (InetSocketAddress) socket.getLocalSocketAddress();
            return "%s:%d".formatted(bound
                    .getAddress()
                    .getHostAddress(), bound.getPort());
        }

        int accepted() {
            return accepted.get();
        }

        @Override
        public void close() {
            try {
                socket.close();
                for (final var connection : connections) {
                    connection.close();
                }
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void accept() {
            while (!socket.isClosed()) {
                try {
                    connections.add(socket.accept());
                    accepted.incrementAndGet();
                } catch (final IOException e) {
                    // closed
                }
            }
        }

    }

}
