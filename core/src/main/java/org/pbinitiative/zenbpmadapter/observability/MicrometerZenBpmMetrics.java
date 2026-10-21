/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.observability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Publishes what {@link ZenBpmMetrics} measures as Micrometer meters. Both platforms apply
 * {@link MeterBinder} beans to their registries themselves, so this one class serves both, as the
 * platform's own meters do.
 * <p>
 * Every count is kept here, in a {@link LongAdder} per meter and adapter id, and a registry is
 * handed a function counter which reads it. So nothing counted while the beans are being built,
 * before any registry is bound, is lost, and an application which binds the binder to more than one
 * registry reads the same number in each.
 * <p>
 * The counters of an adapter id exist from its registration on, at zero, so a rate over a counter
 * which never moved reads zero rather than nothing. The slot gauges read the executor's semaphore and
 * the reconnects counter reads the job stream: values already in memory, which a
 * <code>CachedGaugeValue</code> of the platform would only make stale, so they are read at every
 * collection.
 */
public class MicrometerZenBpmMetrics implements ZenBpmMetrics, MeterBinder {

    /**
     * The counters, each with what an operator reads from it.
     */
    private enum Counted {

        RECEIVED(JOBS_RECEIVED, "Jobs the job stream of this adapter id handed over"),
        COMPLETED(JOBS_COMPLETED, "Jobs a handler of this adapter id answered with a completion or a BPMN error"),
        FAILED(JOBS_FAILED, "Jobs this adapter id failed at the engine"),
        QUEUED(JOBS_QUEUED, "Jobs which waited for a free handler slot of this adapter id"),
        REDELIVERED(JOBS_REDELIVERED, "Deliveries of a job the engine had handed out before"),
        RENEWED(LOCK_RENEWED, "Extensions of the lock of a running handler's job the engine granted"),
        LOST(LOCK_LOST, "Locks which ended while their handler still ran"),
        POLLS(USER_TASK_POLLS, "Looks at the user tasks of this adapter id");

        private final String meterName;

        private final String description;

        Counted(
            final String meterName,
            final String description
        ) {
            this.meterName = meterName;
            this.description = description;
        }

    }

    private record Counter(
        Counted counted,
        String adapterId
    ) {
    }

    private record Read(
        LongSupplier streamReconnects,
        IntSupplier busySlots,
        IntSupplier freeSlots
    ) {
    }

    private final Map<Counter, LongAdder> counts = new ConcurrentHashMap<>();

    // guarded by this, as are the two below
    private final Map<String, Read> adapters = new LinkedHashMap<>();

    private final List<MeterRegistry> registries = new ArrayList<>();

    /**
     * Opens the binder without a registry. Counting starts at once; the platform hands the registry
     * over later.
     */
    public MicrometerZenBpmMetrics() {
    }

    @Override
    public synchronized void bindTo(final MeterRegistry registry) {
        registries.add(registry);
        counts.forEach((
            counter,
            count) -> registerCounter(registry, counter, count));
        adapters.forEach((
            adapterId,
            read) -> registerRead(registry, adapterId, read));
    }

    @Override
    public synchronized void registerAdapter(
        final String adapterId,
        final LongSupplier streamReconnects,
        final IntSupplier busySlots,
        final IntSupplier freeSlots
    ) {
        final var read = new Read(streamReconnects, busySlots, freeSlots);
        adapters.put(adapterId, read);
        for (final var counted : Counted.values()) {
            countOf(counted, adapterId);
        }
        registries.forEach(registry -> registerRead(registry, adapterId, read));
    }

    @Override
    public void jobReceived(final String adapterId) {
        count(Counted.RECEIVED, adapterId);
    }

    @Override
    public void jobRedelivered(final String adapterId) {
        count(Counted.REDELIVERED, adapterId);
    }

    @Override
    public void jobQueued(final String adapterId) {
        count(Counted.QUEUED, adapterId);
    }

    @Override
    public void jobCompleted(final String adapterId) {
        count(Counted.COMPLETED, adapterId);
    }

    @Override
    public void jobFailed(final String adapterId) {
        count(Counted.FAILED, adapterId);
    }

    @Override
    public void lockRenewed(final String adapterId) {
        count(Counted.RENEWED, adapterId);
    }

    @Override
    public void lockLost(final String adapterId) {
        count(Counted.LOST, adapterId);
    }

    @Override
    public void userTaskPoll(final String adapterId) {
        count(Counted.POLLS, adapterId);
    }

    // lock-free once the counter exists, which is from the registration of the adapter id on
    private void count(
        final Counted counted,
        final String adapterId
    ) {
        final var count = counts.get(new Counter(counted, adapterId));
        if (count != null) {
            count.increment();
            return;
        }
        countOf(counted, adapterId).increment();
    }

    // a new counter is handed to every registry bound so far, and a registry bound later takes
    // every counter there is, which the lock keeps from passing each other
    private synchronized LongAdder countOf(
        final Counted counted,
        final String adapterId
    ) {
        final var counter = new Counter(counted, adapterId);
        final var known = counts.get(counter);
        if (known != null) {
            return known;
        }
        final var count = new LongAdder();
        counts.put(counter, count);
        registries.forEach(registry -> registerCounter(registry, counter, count));
        return count;
    }

    private static void registerCounter(
        final MeterRegistry registry,
        final Counter counter,
        final LongAdder count
    ) {
        FunctionCounter
                .builder(counter.counted().meterName, count, LongAdder::doubleValue)
                .tags(Tags.of(TAG_ADAPTER, counter.adapterId()))
                .description(counter.counted().description)
                .register(registry);
    }

    private static void registerRead(
        final MeterRegistry registry,
        final String adapterId,
        final Read read
    ) {
        final var tags = Tags.of(TAG_ADAPTER, adapterId);
        FunctionCounter
                .builder(STREAM_RECONNECTS, read.streamReconnects(), LongSupplier::getAsLong)
                .tags(tags)
                .description("Attempts of the job stream of this adapter id to open its call again")
                .register(registry);
        Gauge
                .builder(SLOTS_BUSY, read.busySlots(), IntSupplier::getAsInt)
                .tags(tags)
                .description("Handler slots of this adapter id taken right now")
                .register(registry);
        Gauge
                .builder(SLOTS_FREE, read.freeSlots(), IntSupplier::getAsInt)
                .tags(tags)
                .description("Handler slots of this adapter id free right now")
                .register(registry);
    }

}
