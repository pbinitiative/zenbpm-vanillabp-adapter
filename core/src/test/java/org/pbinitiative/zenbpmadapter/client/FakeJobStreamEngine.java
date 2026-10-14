/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.eventually;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.pbinitiative.zenbpmadapter.client.grpc.ZenBpmGrpc;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.JobStreamRequest;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.JobStreamResponse;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.StreamSubscriptionRequest;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/**
 * The engine's job stream as a test plays it, in the same JVM: it remembers every call and what
 * the stream sent on it, and sends what the test tells it to. It can be stopped and started again
 * under the same name, which a channel built before finds again, like an engine which restarted.
 * That the requests are what the engine expects is proven by {@link ZenBpmJobStreamIT}.
 */
final class FakeJobStreamEngine implements AutoCloseable {

    private static final Context.Key<String> CLIENT_ID = Context.key("client_id");

    private final String name = InProcessServerBuilder.generateName();

    private final List<FakeCall> calls = new CopyOnWriteArrayList<>();

    private volatile Status refusal;

    private volatile boolean ignoringHalfClose;

    private Server server;

    /**
     * Starts the engine, or starts it again after {@link #stop()}.
     *
     * @return This engine
     */
    FakeJobStreamEngine start() {
        final ServerInterceptor clientId = new ServerInterceptor() {

            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(
                final ServerCall<Q, R> call,
                final Metadata headers,
                final ServerCallHandler<Q, R> next
            ) {
                final var id = headers.get(ZenBpmJobStream.CLIENT_ID);
                return Contexts.interceptCall(Context
                        .current()
                        .withValue(CLIENT_ID, id), call, headers, next);
            }

        };
        try {
            server = InProcessServerBuilder
                    .forName(name)
                    .addService(ServerInterceptors.intercept(new JobStream(), clientId))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    /**
     * Keeps every call open after the stream stopped sending on it, unlike the engine, which ends
     * it then.
     *
     * @return This engine
     */
    FakeJobStreamEngine ignoreHalfClose() {
        ignoringHalfClose = true;
        return this;
    }

    /**
     * Stops the engine at once: every open call fails as if the connection broke.
     */
    void stop() {
        server.shutdownNow();
        try {
            server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new AssertionError(e);
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * A channel to this engine, which finds it again after a restart.
     *
     * @return A new channel
     */
    ManagedChannel channel() {
        return InProcessChannelBuilder
                .forName(name)
                .build();
    }

    /**
     * Ends every call opened from now on as soon as it opens, before anything is sent on it.
     *
     * @param status The status to end them with, <code>null</code> to take calls again
     */
    void refuseCallsWith(final Status status) {
        refusal = status;
    }

    /**
     * How many calls were opened so far, refused ones included.
     *
     * @return The number of calls
     */
    int callCount() {
        return calls.size();
    }

    /**
     * A call, once it was opened.
     *
     * @param index 0 for the first call
     * @return The call
     */
    FakeCall call(final int index) {
        return eventually(() -> calls.size() > index ? Optional.of(calls.get(index)) : Optional.empty());
    }

    /**
     * One call of a job stream: what it sent, and the way back to it.
     */
    static final class FakeCall {

        private final String clientId;

        private final StreamObserver<JobStreamResponse> responses;

        private final List<JobStreamRequest> requests = new CopyOnWriteArrayList<>();

        private volatile boolean halfClosed;

        private volatile boolean cancelled;

        private FakeCall(
            final String clientId,
            final StreamObserver<JobStreamResponse> responses
        ) {
            this.clientId = clientId;
            this.responses = responses;
        }

        /**
         * The client id the call was opened with.
         *
         * @return The value of the metadata <code>client_id</code>
         */
        String clientId() {
            return clientId;
        }

        /**
         * The subscriptions and unsubscriptions sent on the call so far.
         *
         * @return The requests in the order they arrived
         */
        List<StreamSubscriptionRequest> subscriptions() {
            return requests
                    .stream()
                    .map(JobStreamRequest::getSubscription)
                    .toList();
        }

        /**
         * Waits until the call carries a number of requests.
         *
         * @param count How many
         * @return The subscriptions and unsubscriptions, at least <code>count</code>
         */
        List<StreamSubscriptionRequest> subscriptions(final int count) {
            eventually(() -> requests.size() >= count ? Optional.of(requests.size()) : Optional.empty());
            return subscriptions();
        }

        /**
         * Whether the stream ended the call from its side, as {@link ZenBpmJobStream#close()}
         * does.
         *
         * @return <code>true</code> once the stream half-closed the call
         */
        boolean halfClosed() {
            return halfClosed;
        }

        /**
         * Whether the stream cancelled the call.
         *
         * @return <code>true</code> once the stream cancelled the call
         */
        boolean cancelled() {
            return cancelled;
        }

        /**
         * Sends a frame to the stream.
         *
         * @param response The frame
         */
        void send(final JobStreamResponse response) {
            responses.onNext(response);
        }

        /**
         * Ends the call without a failure, as an engine does which shuts down.
         */
        void end() {
            responses.onCompleted();
        }

        /**
         * Ends the call with a failure, as an engine does whose handler returns an error.
         *
         * @param status The status
         */
        void fail(final Status status) {
            responses.onError(status.asRuntimeException());
        }

    }

    private final class JobStream extends ZenBpmGrpc.ZenBpmImplBase {

        @Override
        public StreamObserver<JobStreamRequest> jobStream(final StreamObserver<JobStreamResponse> responses) {
            final var call = new FakeCall(CLIENT_ID.get(), responses);
            // also where the stream half-closed the call before, which the request side no
            // longer hears of
            ((ServerCallStreamObserver<JobStreamResponse>) responses).setOnCancelHandler(() -> call.cancelled = true);
            calls.add(call);
            final var refused = refusal;
            if (refused != null) {
                responses.onError(refused.asRuntimeException());
            }
            return new StreamObserver<>() {

                @Override
                public void onNext(final JobStreamRequest request) {
                    call.requests.add(request);
                }

                @Override
                public void onError(final Throwable failure) {
                    call.cancelled = true;
                }

                @Override
                public void onCompleted() {
                    // the engine ends the call once the client stops sending, and releases
                    // the jobs locked to it
                    call.halfClosed = true;
                    if (!ignoringHalfClose) {
                        responses.onCompleted();
                    }
                }

            };
        }

    }

}
