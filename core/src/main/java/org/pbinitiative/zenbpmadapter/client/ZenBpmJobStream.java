/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.ErrorResult;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.JobStreamRequest;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.JobStreamResponse;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.StreamSubscriptionRequest;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.WaitingJob;

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
 * moment. Every job type somebody still subscribes is then sent again, with its settings. A job
 * created while the call was down is handed out once it is back: the engine keeps a job until a
 * subscribed client takes it.
 * <p>
 * The engine says nothing on a call it took, so a call counts as back once a job arrived on it,
 * or once it stayed open for two seconds after its connection was ready. A call whose connection
 * is not ready within twenty seconds counts as failed. While nothing arrives, the connection is
 * pinged every five and a half minutes, and it counts as lost where a ping stays unanswered for
 * twenty seconds: a path a network dropped silently would otherwise go unnoticed for hours.
 * <p>
 * A job goes to the consumer of {@link #onJob(Consumer)} on one of the timing threads the stream
 * was given, never on a thread of gRPC, and handing it over never waits. A job whose call ended
 * before a timing thread took it up is not handed on: the engine hands it out again.
 * <p>
 * The engine answers a subscription with nothing where it takes it, and with an error naming no
 * job where it refuses it. That error carries no code and names its job type only in prose, which
 * nothing reads, see decision 30 in the repository's DECISIONS.md. So the stream cannot tell
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
     * How the call is opened again after a failure, and when it counts as back, as the class
     * comment describes it.
     */
    static final Pacing PACING = new Pacing(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration
            .ofSeconds(2), Duration.ofSeconds(20));

    /**
     * How long the connection may stay quiet before it is pinged. The engine ends a connection
     * whose client pings more often than every five minutes, the default of the gRPC library it
     * is built on, so the pings come a little less often than that.
     */
    static final Duration KEEPALIVE_TIME = Duration.ofSeconds(330);

    /**
     * How long a ping may stay unanswered before the connection counts as lost.
     */
    static final Duration KEEPALIVE_TIMEOUT = Duration.ofSeconds(20);

    /**
     * How long {@link #close()} waits for the engine to end the call before it cuts the
     * connection.
     */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The most active jobs per client and job type an engine takes where its
     * <code>JOB_MANAGER_MAX_ACTIVE_JOBS_CAP</code> is not set. It lowers a larger cap to it.
     */
    static final int ENGINE_DEFAULT_MAX_ACTIVE_JOBS_CAP = 1000;

    private final String adapterId;

    private final String clientId;

    private final String address;

    private final ManagedChannel channel;

    private final ZenBpmGrpc.ZenBpmStub stub;

    private final ScheduledExecutorService timing;

    private final Pacing pacing;

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
     * to open it again, and after {@link #close()}. A call the stream gives up on is cancelled and
     * forgotten in one step, so nothing is sent on a cancelled call.
     */
    private Call call;

    private Future<?> reopening;

    /**
     * Since when the call is down, <code>null</code> while it is up.
     */
    private Outage outage;

    private boolean closed;

    private boolean capAboveTheEnginesReported;

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
                .grpcAddressInUse(), channel(configuration), timing, PACING, CLOSE_TIMEOUT);
    }

    ZenBpmJobStream(
        final String adapterId,
        final String clientId,
        final String address,
        final ManagedChannel channel,
        final ScheduledExecutorService timing,
        final Pacing pacing,
        final Duration closeTimeout
    ) {
        this.adapterId = adapterId;
        this.clientId = clientId;
        this.address = address;
        this.channel = channel;
        this.timing = timing;
        this.pacing = pacing;
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
                call.send(subscribeRequest(jobType, subscription));
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
     * Counts a job whose handler returned while its task stays open, locked to this stream until a
     * moment. The engine counts such a job against its type's cap of active jobs until the lock
     * lapses, so without a raise the parked jobs of a type would take every slot of it, and no
     * new job of the type would arrive. The type is subscribed again with its cap raised by the
     * number of its jobs parked under a live lock, see decision 37 in the repository's
     * DECISIONS.md. The raise of a job goes once the job arrives again or its lock lapsed; a lapsed
     * one is forgotten at the next job of the type which is parked or arrives. A type subscribed
     * with the engine's default cap, or not subscribed, is left as it is.
     *
     * @param jobType The job's type
     * @param jobKey The job
     * @param lockedUntil When its lock ends, on the application's clock
     */
    public synchronized void parked(
        final String jobType,
        final long jobKey,
        final Instant lockedUntil
    ) {
        final var subscribed = subscriptions.get(jobType);
        if ((subscribed == null) || (subscribed.settings.maxActiveJobs() == 0)) {
            return;
        }
        recount(jobType, subscribed, () -> subscribed.parked.put(jobKey, lockedUntil));
    }

    /**
     * Ends the count of a parked job whose task the application completed or cancelled. The
     * engine drops the lock of a job it completed, of one it failed for the stream which holds the
     * lock, and of one it refused a command for because the job ended, at once, so the raise of the
     * cap goes at once as well, see decision 40 in the repository's DECISIONS.md. A job this
     * stream did not park, which another node of the application may hold, changes nothing.
     *
     * @param jobType The job's type
     * @param jobKey The job
     */
    public synchronized void parkedJobEnded(
        final String jobType,
        final long jobKey
    ) {
        final var subscribed = subscriptions.get(jobType);
        if (subscribed == null) {
            return;
        }
        recount(jobType, subscribed, () -> subscribed.parked.remove(jobKey));
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
     * Where the stream stands now, in words for an operator, as the health check shows it:
     * <ul>
     * <li><code>not opened yet</code> before the first subscription;</li>
     * <li><code>connecting</code> while the call is open and not yet taken by the engine;</li>
     * <li><code>connected</code> once a job arrived on the call, or it stayed open after its
     * connection was ready;</li>
     * <li><code>down since &lt;instant&gt;, &lt;n&gt; attempt(s)</code> from a lost call until a
     * new one is taken, the attempts counted as in the line which reports it back;</li>
     * <li><code>closed</code> after {@link #close()}.</li>
     * </ul>
     *
     * @return One of the above
     */
    public synchronized String state() {
        if (closed) {
            return "closed";
        }
        if (outage != null) {
            return "down since %s, %d attempt(s)".formatted(outage.since.truncatedTo(ChronoUnit.SECONDS),
                    outage.attempts);
        }
        if (call == null) {
            return "not opened yet";
        }
        return call.established ? "connected" : "connecting";
    }

    /**
     * Ends the call and closes the connection. The engine hands the jobs locked to this stream to
     * other clients at once instead of after their locks lapse. A job still waiting for a timing
     * thread is not handed on. Waits up to five seconds for the engine to end the call, then cuts
     * the connection; a subscription afterwards throws {@link IllegalStateException}.
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

    /**
     * The channel to the engine's job stream.
     *
     * @param address <code>host:port</code>
     * @param plaintext <code>false</code> for TLS
     * @param keepAliveTime How long the connection may stay quiet before it is pinged
     * @param keepAliveTimeout How long a ping may stay unanswered
     * @return A channel which has not connected yet
     */
    static ManagedChannel channel(
        final String address,
        final boolean plaintext,
        final Duration keepAliveTime,
        final Duration keepAliveTimeout
    ) {
        final var credentials = plaintext
                ? InsecureChannelCredentials.create()
                : TlsChannelCredentials.create();
        return Grpc
                .newChannelBuilder(address, credentials)
                .keepAliveTime(keepAliveTime.toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(keepAliveTimeout.toMillis(), TimeUnit.MILLISECONDS)
                // a job carries a copy of the variables of its scope, which nothing bounds. A job
                // above a limit would end the call, and the engine would hand it to the next node,
                // whose call it would end as well
                .maxInboundMessageSize(Integer.MAX_VALUE)
                .build();
    }

    private static ManagedChannel channel(final ZenBpmAdapterConfiguration configuration) {
        ProtobufRuntimeCheck.requireCompatibleRuntime();
        return channel(configuration.grpcAddressInUse(), configuration.isGrpcPlaintext(), KEEPALIVE_TIME,
                KEEPALIVE_TIMEOUT);
    }

    private static JobStreamRequest subscribeRequest(
        final String jobType,
        final Subscription subscription
    ) {
        return JobStreamRequest
                .newBuilder()
                .setSubscription(StreamSubscriptionRequest
                        .newBuilder()
                        .setJobType(jobType)
                        .setType(StreamSubscriptionRequest.Type.TYPE_SUBSCRIBE)
                        .setLockDurationMs(subscription.settings.lockDurationMs())
                        .setMaxActiveJobs(subscription.maxActiveJobs()))
                .build();
    }

    /**
     * Whether a frame answers a subscription or an unsubscription: an error naming neither a job
     * nor a lock.
     */
    private static boolean answersASubscription(final JobStreamResponse response) {
        return response.hasError() && !response.hasJob() && !response.hasLockExtended();
    }

    private static String describe(final Status status) {
        return status.getDescription() == null
                ? status
                        .getCode()
                        .toString()
                : "%s: %s".formatted(status.getCode(), status.getDescription());
    }

    private String conflict(
        final String jobType,
        final Subscription subscribed,
        final String subscriber,
        final Settings settings
    ) {
        return """
                ZenBPM[%s]: job type '%s' is subscribed by %s with %s, and '%s' asks for %s. The engine keeps one \
                lock duration and one cap of active jobs per client and job type, so every subscriber of a job type \
                has to ask for the same."""
                .formatted(adapterId, jobType, subscribed.subscribers
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
            subscription) -> opened.send(subscribeRequest(jobType, subscription)));
        schedule(() -> notReadyInTime(opened), pacing.readyWithin());
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

    private synchronized void becameReady(final Call opened) {
        if ((opened != call) || opened.ready) {
            return;
        }
        opened.ready = true;
        schedule(() -> stayedOpen(opened), pacing.stableAfter());
    }

    private synchronized void stayedOpen(final Call opened) {
        if (opened != call) {
            return;
        }
        opened.established = true;
        if (outage != null) {
            back();
        }
    }

    private synchronized void notReadyInTime(final Call opened) {
        if ((opened != call) || opened.ready) {
            return;
        }
        final var reason = "no connection within %s".formatted(pacing.readyWithin());
        opened.requests.cancel(reason, null);
        lost(Status.UNAVAILABLE.withDescription(reason), false);
    }

    /**
     * Ends an outage. Called holding <code>this</code>.
     */
    private void back() {
        log.info("ZenBPM[{}]: the job stream to {} is back after {} and {} attempt(s), job types subscribed: {}",
                adapterId, address, Duration.between(outage.since, Instant.now()), outage.attempts, List.copyOf(
                        subscriptions.keySet()));
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
            if (answersASubscription(response)) {
                refused(from, response.getError());
                return;
            }
            from.established = true;
            if (outage != null) {
                back();
            }
            if (!response.hasError() && response.hasJob()) {
                cameBack(response.getJob());
            }
        }
        if (response.hasError()) {
            log.warn("""
                    ZenBPM[{}]: the job stream received an error answering a request it never sends, for job {}: \
                    code {}, '{}'""", adapterId, response.hasJob()
                    ? response
                            .getJob()
                            .getKey()
                    : response
                            .getLockExtended()
                            .getKey(),
                    response
                            .getError()
                            .getCode(),
                    response
                            .getError()
                            .getMessage());
        } else if (response.hasJob()) {
            handOver(from, new ReceivedJob(response.getJob(), Instant.now()));
        } else {
            log.debug("ZenBPM[{}]: the job stream received a frame it never asked for: {}", adapterId, response);
        }
    }

    /**
     * Ends the count of a parked job which the engine hands out again. Called holding
     * <code>this</code>.
     */
    private void cameBack(final WaitingJob job) {
        final var subscribed = subscriptions.get(job.getType());
        if (subscribed == null) {
            return;
        }
        recount(job.getType(), subscribed, () -> subscribed.parked.remove(job.getKey()));
    }

    /**
     * Changes the parked jobs of a type, forgets those whose lock lapsed, and subscribes the type
     * again where its cap changed. Called holding <code>this</code>. Every change of the parked
     * jobs goes through here, so the cap before the change is the one the engine was sent.
     */
    private void recount(
        final String jobType,
        final Subscription subscribed,
        final Runnable change
    ) {
        final var sent = subscribed.maxActiveJobs();
        change.run();
        final var now = Instant.now();
        subscribed.parked
                .values()
                .removeIf(lockedUntil -> !lockedUntil.isAfter(now));
        final var raised = subscribed.maxActiveJobs();
        if (raised == sent) {
            return;
        }
        if ((raised > ENGINE_DEFAULT_MAX_ACTIVE_JOBS_CAP) && !capAboveTheEnginesReported) {
            capAboveTheEnginesReported = true;
            log.warn("""
                    ZenBPM[{}]: {} jobs of type '{}' are parked for their asynchronous completion, so the job \
                    stream asks the engine for up to {} active jobs of the type. The engine lowers that to its \
                    JOB_MANAGER_MAX_ACTIVE_JOBS_CAP, {} unless it is set, and beyond it a new job of the type waits \
                    until the lock of a parked one lapses. Raise JOB_MANAGER_MAX_ACTIVE_JOBS_CAP at the engine, or \
                    shorten {}.{}.async-task-lock-renewal.""", adapterId, subscribed.parked.size(), jobType, raised,
                    ENGINE_DEFAULT_MAX_ACTIVE_JOBS_CAP, ZenBpmAdapterConfiguration.CONFIGURATION_PREFIX, adapterId);
        }
        if (call != null) {
            call.send(subscribeRequest(jobType, subscribed));
        }
    }

    /**
     * Logs a refused subscription or unsubscription and opens the call again. Called holding
     * <code>this</code>, so that no subscription is sent on the call between its cancel and its
     * end.
     */
    private void refused(
        final Call from,
        final ErrorResult error
    ) {
        log.error("""
                ZenBPM[{}]: the engine at {} refused a subscription or an unsubscription of the job stream: code {}, \
                '{}'. The engine does not say which job type it refused, so the job stream opens its call again and \
                subscribes every job type again: {}""", adapterId, address, error.getCode(), error.getMessage(),
                List.copyOf(subscriptions.keySet()));
        final var reason = "the engine refused a subscription or an unsubscription";
        from.requests.cancel(reason, null);
        // keeps an outage which is under way, so the pauses go on growing
        lost(Status.CANCELLED.withDescription(reason), false);
    }

    private void handOver(
        final Call from,
        final ReceivedJob job
    ) {
        try {
            timing.execute(() -> deliver(from, job));
        } catch (final RejectedExecutionException e) {
            log.warn(
                    """
                            ZenBPM[{}]: job {} of type '{}' arrived while the adapter shuts down, so nobody runs it; the engine \
                            hands it out again once its lock lapses""",
                    adapterId,
                    job
                            .job()
                            .getKey(),
                    job
                            .job()
                            .getType());
        }
    }

    private void deliver(
        final Call from,
        final ReceivedJob job
    ) {
        synchronized (this) {
            // the end of the call gave the job back to the engine, which hands it out again
            if (from != call) {
                log.debug("ZenBPM[{}]: job {} is not handed on, its call ended before a timing thread took it up",
                        adapterId, job
                                .job()
                                .getKey());
                return;
            }
        }
        final var receiver = consumer;
        if (receiver == null) {
            log.warn("""
                    ZenBPM[{}]: job {} of type '{}' arrived, but nothing in the adapter takes jobs yet; the engine \
                    hands it out again once its lock lapses""", adapterId,
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
            log.error("""
                    ZenBPM[{}]: job {} of type '{}' could not be handed on; the engine hands it out again once its \
                    lock lapses""", adapterId,
                    job
                            .job()
                            .getKey(),
                    job
                            .job()
                            .getType(),
                    e);
        }
    }

    private synchronized void failed(
        final Call from,
        final Status status
    ) {
        // a call the stream gave up on and forgot, or one close() ended
        if (from != call) {
            return;
        }
        // the engine answers a client id it already serves by ending the call at once, with
        // UNKNOWN, the status of every error its handler returns
        lost(status, (status.getCode() == Status.Code.UNKNOWN) && !from.established);
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
            log.warn("""
                    ZenBPM[{}]: the engine at {} ended the job stream as soon as it was opened ({}). It does that \
                    where another job stream of the same client id '{}' is still open: another node of the \
                    application, another adapter id on the same engine, or the stream this node lost a moment ago, \
                    which the engine has not noticed yet. Where '{}' is set, give every node and adapter id its own, \
                    or remove it to derive one: <application name>-<adapter id>-<8 random hex digits>. The job \
                    stream tries again with a backoff.""", adapterId, address, describe(status), clientId,
                    ZenBpmAdapterConfiguration.propertyKey(adapterId, "client-id"));
        } else if (first) {
            log.warn("""
                    ZenBPM[{}]: the job stream to {} is down ({}); no job arrives until it is back. It is opened \
                    again after {}, then after twice as long each time, up to {}.""", adapterId, address,
                    describe(status), pacing.first(), pacing.max());
        } else {
            log.debug("ZenBPM[{}]: the job stream to {} is still down ({})", adapterId, address, describe(status));
        }
        final var delay = pacing.delay(outage.attempts);
        outage.attempts++;
        reopening = schedule(this::reopen, delay);
        if (reopening == null) {
            log.warn("ZenBPM[{}]: the job stream to {} is not opened again, because the adapter shuts down",
                    adapterId, address);
        }
    }

    /**
     * Runs a task on a timing thread later.
     *
     * @return The task's future, <code>null</code> where the timing threads shut down
     */
    private Future<?> schedule(
        final Runnable task,
        final Duration delay
    ) {
        try {
            return timing.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final RejectedExecutionException e) {
            return null;
        }
    }

    /**
     * How the call is opened again after a failure, and when a call counts as back.
     *
     * @param first The pause before the first attempt
     * @param max The longest pause
     * @param stableAfter How long a call has to stay open after its connection was ready to count
     *            as back, where no job arrived before. The engine sends nothing on a call which it
     *            took, and ends one it refuses at once.
     * @param readyWithin How long a call may wait for its connection before it counts as failed.
     *            A proxy can accept a connection and never answer on it.
     */
    record Pacing(
        Duration first,
        Duration max,
        Duration stableAfter,
        Duration readyWithin
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
                    .equals(lockDuration) || (millis == Long.MAX_VALUE)) {
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

        /**
         * The jobs of the type parked for their asynchronous completion, each with the moment its
         * lock ends.
         */
        private final Map<Long, Instant> parked = new HashMap<>();

        private Subscription(final Settings settings) {
            this.settings = settings;
        }

        // the engine counts a parked job against the cap until its lock lapses, although no
        // handler runs for it, see decision 37 in the repository's DECISIONS.md
        private int maxActiveJobs() {
            return settings.maxActiveJobs() + parked.size();
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
         * Whether the connection under the call was ready. Guarded by the stream.
         */
        private boolean ready;

        /**
         * Whether the call counts as taken by the engine: a job arrived on it, or it stayed open
         * after its connection was ready. Guarded by the stream.
         */
        private boolean established;

        @Override
        public void beforeStart(final ClientCallStreamObserver<JobStreamRequest> requests) {
            this.requests = requests;
            requests.setOnReadyHandler(() -> becameReady(this));
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
