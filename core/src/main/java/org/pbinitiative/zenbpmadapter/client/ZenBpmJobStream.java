/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.pbinitiative.zenbpmadapter.client.grpc.ZenBpmGrpc;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.JobStreamRequest;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.JobStreamResponse;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.StreamSubscriptionRequest;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.MetadataUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * The job stream of one adapter id: one gRPC connection to the engine, and on it one
 * bidirectional <code>JobStream</code> call. The call carries the job types the workflow modules
 * subscribe and brings back the jobs the engine hands out for them. The adapter completes and
 * fails jobs and extends their locks over REST, so the stream sends nothing but subscriptions,
 * see decision 9 in the repository's DECISIONS.md.
 * <p>
 * Building a stream contacts nothing. The call is opened by the first subscription and stays
 * open until {@link #close()}. Where it fails, it is opened again after a pause. The pause starts
 * at one second and doubles up to thirty seconds, and each one is cut short by a random part of up
 * to a fifth: nodes which lost the engine at the same moment then do not all come back at the same
 * moment. Every job type somebody still subscribes is then sent again, with its settings. A job created while
 * the call was down is handed out once it is back: the engine keeps a job until a subscribed
 * client takes it.
 * <p>
 * A job goes to the consumer of {@link #onJob(Consumer)} on one of the timing threads the stream
 * was given, never on a thread of gRPC, and handing it over never waits.
 * <p>
 * The engine answers a subscription with nothing where it takes it, and with an error naming no
 * job where it refuses it. That error carries no code and names its job type only in prose, which
 * nothing reads, see decision 10 in the repository's DECISIONS.md. So the stream cannot tell
 * which subscription was refused: it logs the error with the job types it subscribed, and opens
 * the call again, which sends every one of them again.
 * <p>
 * The engine refuses a second call with a client id it already serves. The stream reports that
 * once per outage with the property to change, and keeps trying: after a lost connection the
 * engine may still hold the old call for a moment.
 */
@Slf4j
public class ZenBpmJobStream implements AutoCloseable {

    /**
     * The metadata key the engine reads the client id from.
     */
    static final Metadata.Key<String> CLIENT_ID = Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * How the call is opened again after a failure, as the class comment describes it.
     */
    static final Backoff BACKOFF = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(2));

    /**
     * How long {@link #close()} waits for the engine to end the call before it cuts the
     * connection.
     */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final String adapterId;

    private final String clientId;

    private final String address;

    private final ManagedChannel channel;

    private final ZenBpmGrpc.ZenBpmStub stub;

    private final ScheduledExecutorService timing;

    private final Backoff backoff;

    private final Duration closeTimeout;

    private final AtomicLong reconnects = new AtomicLong();

    private volatile Consumer<ReceivedJob> consumer;

    /**
     * The job types subscribed, in the order of their first subscription. Guarded by
     * <code>this</code>, like every field below.
     */
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();

    /**
     * The call open now; <code>null</code> before the first subscription, while the stream waits
     * to open it again, and after {@link #close()}.
     */
    private Call call;

    private Future<?> reopening;

    /**
     * Since when the call is down, <code>null</code> while it is up.
     */
    private Outage outage;

    private boolean closed;

    /**
     * A job stream to the engine a configuration names. Contacts nothing.
     *
     * @param adapterId The adapter id, named by every message of the stream
     * @param configuration A configuration which {@link ZenBpmAdapterConfiguration#validate} found
     *            complete
     * @param clientId The client id the engine knows this stream by, unique per node; see
     *            {@link ZenBpmAdapterConfiguration#clientIdInUse}
     * @param timing The timing threads of the adapter id: jobs are handed to the consumer on
     *            them, and the call is opened again on them. The stream does not shut it down.
     * @throws IllegalStateException Where the application's protobuf runtime cannot run the
     *             stream, see {@link ProtobufRuntimeCheck}
     */
    public ZenBpmJobStream(
        final String adapterId,
        final ZenBpmAdapterConfiguration configuration,
        final String clientId,
        final ScheduledExecutorService timing
    ) {
        this(adapterId, clientId, configuration
                .grpcAddressInUse(), channel(configuration), timing, BACKOFF, CLOSE_TIMEOUT);
    }

    ZenBpmJobStream(
        final String adapterId,
        final String clientId,
        final String address,
        final ManagedChannel channel,
        final ScheduledExecutorService timing,
        final Backoff backoff,
        final Duration closeTimeout
    ) {
        this.adapterId = adapterId;
        this.clientId = clientId;
        this.address = address;
        this.channel = channel;
        this.timing = timing;
        this.backoff = backoff;
        this.closeTimeout = closeTimeout;
        final var metadata = new Metadata();
        metadata.put(CLIENT_ID, clientId);
        this.stub = ZenBpmGrpc
                .newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    /**
     * Who receives the jobs. A job which arrives while nobody does is logged and left to its lock,
     * after which the engine hands it out again.
     *
     * @param consumer Called once per job on a timing thread; an exception it throws is logged
     */
    public void onJob(final Consumer<ReceivedJob> consumer) {
        this.consumer = consumer;
    }

    /**
     * Subscribes a job type. Several subscribers may share one, as two workflow modules do where
     * their job types are not scoped; the type stays subscribed until the last of them
     * unsubscribes. A subscriber subscribing again with the same settings changes nothing. Opens
     * the call where it is not open yet.
     *
     * @param subscriber Who subscribes, e.g. the workflow module; named where the settings
     *            conflict
     * @param jobType The job type
     * @param lockDuration How long a job stays locked to this stream after delivery, sent in whole
     *            milliseconds rounded up; zero for the engine's default. The engine lowers a
     *            duration above its maximum to that maximum.
     * @param maxActiveJobs How many jobs of the type this stream holds at once; zero for the
     *            engine's default. The engine lowers a number above its maximum to that maximum.
     * @throws IllegalArgumentException Where a duration or a number is negative
     * @throws IllegalStateException Where another subscriber subscribed the type with other
     *             settings: the engine keeps one setting per client and job type. Also after
     *             {@link #close()}.
     */
    public void subscribe(
        final String subscriber,
        final String jobType,
        final Duration lockDuration,
        final int maxActiveJobs
    ) {
        Objects.requireNonNull(subscriber, "subscriber");
        Objects.requireNonNull(jobType, "jobType");
        final var settings = Settings.of(lockDuration, maxActiveJobs);
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException(
                        "ZenBPM[%s]: the job stream is closed, so '%s' cannot subscribe job type '%s'."
                                .formatted(adapterId, subscriber, jobType));
            }
            final var subscribed = subscriptions.get(jobType);
            if (subscribed != null) {
                if (!subscribed.settings.equals(settings)) {
                    throw new IllegalStateException(conflict(jobType, subscribed, subscriber, settings));
                }
                subscribed.subscribers.add(subscriber);
                return;
            }
            final var subscription = new Subscription(settings);
            subscription.subscribers.add(subscriber);
            subscriptions.put(jobType, subscription);
            if (call != null) {
                call.send(subscribeRequest(jobType, settings));
            } else if ((reopening == null) && (outage == null)) {
                open();
            }
        }
    }

    /**
     * Ends a subscriber's subscription of a job type. The type is unsubscribed at the engine once
     * nobody subscribes it any more. A subscriber who did not subscribe the type changes nothing.
     * Jobs delivered before keep their locks.
     *
     * @param subscriber Who subscribed
     * @param jobType The job type
     */
    public synchronized void unsubscribe(
        final String subscriber,
        final String jobType
    ) {
        final var subscribed = subscriptions.get(jobType);
        if ((subscribed == null) || !subscribed.subscribers.remove(subscriber)) {
            return;
        }
        if (!subscribed.subscribers.isEmpty()) {
            return;
        }
        subscriptions.remove(jobType);
        if (call != null) {
            call.send(JobStreamRequest
                    .newBuilder()
                    .setSubscription(StreamSubscriptionRequest
                            .newBuilder()
                            .setJobType(jobType)
                            .setType(StreamSubscriptionRequest.Type.TYPE_UNSUBSCRIBE))
                    .build());
        }
    }

    /**
     * How often the call was opened again after a failure since the stream was built.
     *
     * @return The number of attempts, successful or not
     */
    public long reconnects() {
        return reconnects.get();
    }

    /**
     * Ends the call and closes the connection. The engine hands the jobs locked to this stream to
     * other clients at once instead of after their locks lapse. Waits up to five seconds for the
     * engine to end the call, then cuts the connection; a subscription afterwards throws {@link IllegalStateException}.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            if (reopening != null) {
                reopening.cancel(false);
                reopening = null;
            }
            if (call != null) {
                call.requests.onCompleted();
                call = null;
            }
        }
        channel.shutdown();
        try {
            if (!channel.awaitTermination(closeTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                channel.shutdownNow();
            }
        } catch (final InterruptedException e) {
            channel.shutdownNow();
            Thread
                    .currentThread()
                    .interrupt();
        }
    }

    private static ManagedChannel channel(final ZenBpmAdapterConfiguration configuration) {
        ProtobufRuntimeCheck.requireCompatibleRuntime();
        final var credentials = configuration.isGrpcPlaintext()
                ? InsecureChannelCredentials.create()
                : TlsChannelCredentials.create();
        return Grpc
                .newChannelBuilder(configuration.grpcAddressInUse(), credentials)
                .build();
    }

    private static JobStreamRequest subscribeRequest(
        final String jobType,
        final Settings settings
    ) {
        return JobStreamRequest
                .newBuilder()
                .setSubscription(StreamSubscriptionRequest
                        .newBuilder()
                        .setJobType(jobType)
                        .setType(StreamSubscriptionRequest.Type.TYPE_SUBSCRIBE)
                        .setLockDurationMs(settings.lockDurationMs())
                        .setMaxActiveJobs(settings.maxActiveJobs()))
                .build();
    }

    private String conflict(
        final String jobType,
        final Subscription subscribed,
        final String subscriber,
        final Settings settings
    ) {
        return ("ZenBPM[%s]: job type '%s' is subscribed by %s with %s, and '%s' asks for %s. The engine keeps one "
                + "lock duration and one cap of active jobs per client and job type, so every subscriber of a job type "
                + "has to ask for the same.").formatted(adapterId, jobType, subscribed.subscribers
                        .stream()
                        .map("'%s'"::formatted)
                        .toList(), subscribed.settings, subscriber, settings);
    }

    /**
     * Opens a call and sends every subscription on it. Called holding <code>this</code>.
     */
    private void open() {
        final var opened = new Call();
        call = opened;
        stub.jobStream(opened);
        subscriptions.forEach((
            jobType,
            subscription) -> opened.send(subscribeRequest(jobType,
                    subscription.settings)));
        if (outage != null) {
            schedule(() -> stayedOpen(opened), backoff.stableAfter());
        }
    }

    private synchronized void reopen() {
        reopening = null;
        if (closed) {
            return;
        }
        reconnects.incrementAndGet();
        // the channel keeps a backoff of its own, up to two minutes, which would delay the
        // attempt beyond the stream's
        channel.resetConnectBackoff();
        open();
    }

    private synchronized void stayedOpen(final Call opened) {
        if ((opened == call) && (outage != null)) {
            back();
        }
    }

    /**
     * Ends an outage. Called holding <code>this</code>.
     */
    private void back() {
        log.info("ZenBPM[{}]: the job stream to {} is back after {} and {} attempt(s), job types subscribed: {}",
                adapterId, address, Duration.between(outage.since, Instant.now()), outage.attempts,
                subscriptions.keySet());
        outage = null;
    }

    private void received(
        final Call from,
        final JobStreamResponse response
    ) {
        synchronized (this) {
            if (from != call) {
                return;
            }
            from.answered = true;
            if (outage != null) {
                back();
            }
        }
        if (response.hasError()) {
            refused(from, response);
        } else if (response.hasJob()) {
            handOver(new ReceivedJob(response.getJob(), Instant.now()));
        } else {
            log.debug("ZenBPM[{}]: the job stream received a frame it never asked for: {}", adapterId, response);
        }
    }

    private void handOver(final ReceivedJob job) {
        try {
            timing.execute(() -> deliver(job));
        } catch (final RejectedExecutionException e) {
            log.warn("ZenBPM[{}]: job {} of type '{}' arrived while the adapter shuts down, so nobody runs it; "
                    + "the engine hands it out again once its lock lapses", adapterId,
                    job
                            .job()
                            .getKey(),
                    job
                            .job()
                            .getType());
        }
    }

    private void deliver(final ReceivedJob job) {
        final var receiver = consumer;
        if (receiver == null) {
            log.warn("ZenBPM[{}]: job {} of type '{}' arrived, but nothing in the adapter takes jobs yet; the engine "
                    + "hands it out again once its lock lapses", adapterId,
                    job
                            .job()
                            .getKey(),
                    job
                            .job()
                            .getType());
            return;
        }
        try {
            receiver.accept(job);
        } catch (final RuntimeException e) {
            log.error("ZenBPM[{}]: job {} of type '{}' could not be handed on; the engine hands it out again once "
                    + "its lock lapses", adapterId,
                    job
                            .job()
                            .getKey(),
                    job
                            .job()
                            .getType(),
                    e);
        }
    }

    /**
     * Logs an error frame. One naming a job or a lock answers a request this stream never sends,
     * and changes nothing. One naming neither answers a subscription: the call is opened again.
     */
    private void refused(
        final Call from,
        final JobStreamResponse response
    ) {
        final var error = response.getError();
        if (response.hasJob() || response.hasLockExtended()) {
            log.warn("ZenBPM[{}]: the job stream received an error answering a request it never sends, for job {}: "
                    + "code {}, '{}'", adapterId,
                    response.hasJob()
                            ? response
                                    .getJob()
                                    .getKey()
                            : response
                                    .getLockExtended()
                                    .getKey(),
                    error.getCode(), error.getMessage());
            return;
        }
        synchronized (this) {
            log.error("ZenBPM[{}]: the engine at {} refused a subscription of the job stream: code {}, '{}'. The "
                    + "engine does not say which job type it refused, so the job stream opens its call again and "
                    + "subscribes every job type again: {}", adapterId, address, error.getCode(), error.getMessage(),
                    subscriptions.keySet());
        }
        // ends the call like a failure, which opens it again
        from.requests.cancel("the engine refused a subscription", null);
    }

    private synchronized void failed(
        final Call from,
        final Status status
    ) {
        // a call ends once, and only the open one can end before close(): lost() replaces it
        if (closed) {
            return;
        }
        // the engine answers a client id it already serves by ending the call before anything
        // else, with UNKNOWN, the status of every error its handler returns
        lost(status, (status.getCode() == Status.Code.UNKNOWN) && !from.answered);
    }

    /**
     * Ends the call and schedules the next one. Called holding <code>this</code>.
     */
    private void lost(
        final Status status,
        final boolean clientIdRefused
    ) {
        call = null;
        final var first = outage == null;
        if (first) {
            outage = new Outage(Instant.now());
        }
        if (clientIdRefused && !outage.clientIdReported) {
            outage.clientIdReported = true;
            log.warn("ZenBPM[{}]: the engine at {} ended the job stream as soon as it was opened ({}). It does that "
                    + "where another job stream of the same client id '{}' is still open: another node of the "
                    + "application, another adapter id on the same engine, or the stream this node lost a moment "
                    + "ago, which the engine has not noticed yet. Where '{}' is set, give every node and adapter id "
                    + "its own, or remove it to derive one: <application name>-<adapter id>-<8 random hex digits>. "
                    + "The job stream tries again with a backoff.", adapterId, address, describe(status), clientId,
                    ZenBpmAdapterConfiguration.propertyKey(adapterId, "client-id"));
        } else if (first) {
            log.warn("ZenBPM[{}]: the job stream to {} is down ({}); no job arrives until it is back. It is opened "
                    + "again after {}, then after twice as long each time, up to {}.", adapterId, address,
                    describe(status), backoff.first(), backoff.max());
        } else {
            log.debug("ZenBPM[{}]: the job stream to {} is still down ({})", adapterId, address, describe(status));
        }
        final var delay = backoff.delay(outage.attempts);
        outage.attempts++;
        reopening = schedule(this::reopen, delay);
    }

    private Future<?> schedule(
        final Runnable task,
        final Duration delay
    ) {
        try {
            return timing.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final RejectedExecutionException e) {
            log.warn("ZenBPM[{}]: the job stream to {} is not opened again, because the adapter shuts down",
                    adapterId, address);
            return null;
        }
    }

    private static String describe(final Status status) {
        return status.getDescription() == null
                ? status
                        .getCode()
                        .toString()
                : "%s: %s".formatted(status.getCode(), status.getDescription());
    }

    /**
     * How the call is opened again after a failure.
     *
     * @param first The pause before the first attempt
     * @param max The longest pause
     * @param stableAfter How long a call opened again has to stay open to count as back, where no
     *            frame arrived before. The engine sends nothing on a call which it took, and ends
     *            one it refuses at once.
     */
    record Backoff(
        Duration first,
        Duration max,
        Duration stableAfter
    ) {

        /**
         * The pause before an attempt: <code>first</code> doubled once per attempt before it, at
         * most <code>max</code>, cut short by a random part of up to a fifth.
         *
         * @param attempt The attempt, 0 for the first after a failure
         * @return The pause
         */
        Duration delay(final int attempt) {
            final var doubled = first.multipliedBy(1L << Math.min(attempt, 20));
            final var pause = doubled.compareTo(max) > 0 ? max : doubled;
            final var cut = pause.toNanos() / 5;
            return pause.minusNanos(ThreadLocalRandom
                    .current()
                    .nextLong(cut + 1));
        }

    }

    /**
     * What a subscription asks of the engine, in the units of the wire.
     *
     * @param lockDurationMs The lock duration in milliseconds, 0 for the engine's default
     * @param maxActiveJobs The cap of active jobs, 0 for the engine's default
     */
    record Settings(
        long lockDurationMs,
        int maxActiveJobs
    ) {

        static Settings of(
            final Duration lockDuration,
            final int maxActiveJobs
        ) {
            Objects.requireNonNull(lockDuration, "lockDuration");
            if (lockDuration.isNegative()) {
                throw new IllegalArgumentException("The lock duration %s is negative; zero asks for the engine's default."
                        .formatted(lockDuration));
            }
            if (maxActiveJobs < 0) {
                throw new IllegalArgumentException(
                        "The cap of active jobs %d is negative; zero asks for the engine's default.".formatted(
                                maxActiveJobs));
            }
            final long millis;
            try {
                millis = lockDuration.toMillis();
            } catch (final ArithmeticException tooLong) {
                // far beyond the engine's maximum, which it lowers the duration to anyway
                return new Settings(Long.MAX_VALUE, maxActiveJobs);
            }
            if (Duration
                    .ofMillis(millis)
                    .equals(lockDuration)) {
                return new Settings(millis, maxActiveJobs);
            }
            return new Settings(millis + 1, maxActiveJobs);
        }

        @Override
        public String toString() {
            return "a lock of %s and %s".formatted(lockDurationMs == 0
                    ? "the engine's default"
                    : Duration.ofMillis(lockDurationMs),
                    maxActiveJobs == 0
                            ? "the engine's default cap of active jobs"
                            : "at most %d active jobs".formatted(maxActiveJobs));
        }

    }

    private static final class Subscription {

        private final Settings settings;

        private final Set<String> subscribers = new LinkedHashSet<>();

        private Subscription(final Settings settings) {
            this.settings = settings;
        }

    }

    private static final class Outage {

        private final Instant since;

        private int attempts;

        private boolean clientIdReported;

        private Outage(final Instant since) {
            this.since = since;
        }

    }

    /**
     * One <code>JobStream</code> call. gRPC calls it back on its own threads.
     */
    private final class Call implements ClientResponseObserver<JobStreamRequest, JobStreamResponse> {

        private ClientCallStreamObserver<JobStreamRequest> requests;

        /**
         * Whether the engine sent anything on this call. Guarded by the stream.
         */
        private boolean answered;

        @Override
        public void beforeStart(final ClientCallStreamObserver<JobStreamRequest> requests) {
            this.requests = requests;
        }

        @Override
        public void onNext(final JobStreamResponse response) {
            received(this, response);
        }

        @Override
        public void onError(final Throwable failure) {
            failed(this, Status.fromThrowable(failure));
        }

        @Override
        public void onCompleted() {
            failed(this, Status.OK.withDescription("the engine ended the call"));
        }

        /**
         * Sends a request. Called holding the stream, which keeps two threads from sending at
         * once, as gRPC requires.
         */
        private void send(final JobStreamRequest request) {
            requests.onNext(request);
        }

    }

}
