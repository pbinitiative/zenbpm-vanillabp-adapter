/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache.InstanceFacts;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmExecutor;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;

import io.vanillabp.spi.service.TaskEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds the user tasks of the workflow modules of one adapter id and has each creation and each
 * cancellation told to the application once per node, one poller per adapter id. A ZenBPM user task
 * is a job, and the engine tells nobody about it: the job stream would lock it for the whole life of
 * the task and never hand out a job the engine took away, so the stream never subscribes a user
 * task's type, and the poller asks for the jobs instead, see decision 12 in the repository's
 * DECISIONS.md.
 * <p>
 * <b>A look.</b> Every <code>user-task-poll-interval</code> it lists, for each job type of a user
 * task a running workflow module is notified of, the <code>active</code> and the
 * <code>terminated</code> jobs (<code>GET /v1/jobs</code>), page by page to the last, because the
 * engine filters jobs by neither time nor key: a terminated job stays listed for as long as the
 * engine keeps its instance's history. A job of another element type which shares the type, a task
 * of an older version, is left to the job stream. An active job is a created user task, a terminated
 * one a cancelled user task. A completed user task is neither: the application completed it.
 * <p>
 * <b>Once per node.</b> Each job and event which was handed over and is done with goes into a
 * bounded set of {@value #SEEN_BOUND} deliveries, which forgets the one used longest ago first, and
 * is not handed over again while the set holds it. A job seen active is still handed over as
 * cancelled once it is terminated, and a terminated job never seen active, after a restart or where
 * the user task came and went between two looks, is handed over as cancelled only. Across a restart
 * the platform's record of deliveries answers a notification it took before, by its delivery id.
 * <p>
 * <b>Which module.</b> On a handler slot the facts of the job's instance are read through the
 * instance cache of the adapter id, and the job goes to the module which claims the instance's
 * process and is notified of the job's type in it: the scoped id of a process tells whose it is, as
 * it does for the probes. The engine's default type <code>user-task-type</code> is shared by every
 * application on the engine, so a job of a process this application does not claim is done with at
 * the cost of one read of its instance, and never reaches the core.
 * <p>
 * <b>Threads.</b> The look is timed by the timing threads of the adapter id and runs on a virtual
 * thread of its own, so an engine slow to answer never holds the job stream's hand-overs up, and the
 * next look is timed only once a look ended. A job is handed to a handler slot with
 * {@link ZenBpmExecutor#tryExecute(Runnable)}, which never waits: where no slot is free, the look
 * ends and the rest waits for the next one, out of the set, as a notification which threw does. A
 * job whose notification still runs is not handed over a second time.
 * <p>
 * <b>Failures.</b> A look the engine did not answer adds nothing to the set, is said once per outage
 * at WARN, and the next look asks again: a failed page is never read as an empty one. A failed read
 * of one job's instance skips that job only.
 */
@Slf4j
public final class ZenBpmUserTaskPoller {

    /**
     * How many deliveries the set of the deliveries done with holds at most.
     */
    static final int SEEN_BOUND = 100_000;

    private static final String USER_TASK = "USER_TASK";

    private final String adapterId;

    private final ZenBpmRestClient rest;

    private final ZenBpmExecutor executor;

    private final ScheduledExecutorService timing;

    private final ZenBpmInstanceCache instances;

    private final Duration interval;

    private final ThreadFactory looks;

    /**
     * The notifiers of the workflow modules whose processing runs, by workflow module id.
     */
    private final Map<String, ZenBpmUserTaskNotifier> modules = new ConcurrentHashMap<>();

    /**
     * The job keys whose notification was handed to a handler slot and has not returned yet.
     */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    // guarded by itself; in the order used, so the eldest is the one used longest ago
    private final Map<Delivery, Boolean> seen;

    // guarded by this: whether a module wants its user tasks looked for
    private boolean looking;

    // guarded by this: a look runs right now, and times the next one when it ends
    private boolean lookRuns;

    // guarded by this: the look timed next, which starts only while it is still the one timed
    private TimedLook timedLook;

    // read and written by the thread of a look only, and looks never overlap
    private boolean outage;

    /**
     * The poller of an adapter id. Contacts nothing until a workflow module with user tasks to be
     * notified of registers.
     *
     * @param adapterId The adapter id
     * @param factory The clients of the adapter id
     */
    public ZenBpmUserTaskPoller(
        final String adapterId,
        final ZenBpmClientFactory factory
    ) {
        this(adapterId, factory.rest(), factory.executor(), factory.instances(), factory
                .configuration()
                .getUserTaskPollInterval(), SEEN_BOUND);
    }

    ZenBpmUserTaskPoller(
        final String adapterId,
        final ZenBpmRestClient rest,
        final ZenBpmExecutor executor,
        final ZenBpmInstanceCache instances,
        final Duration interval,
        final int seenBound
    ) {
        this.adapterId = adapterId;
        this.rest = rest;
        this.executor = executor;
        this.timing = executor.timing();
        this.instances = instances;
        this.interval = interval;
        this.looks = Thread
                .ofVirtual()
                .name("zenbpm-%s-user-tasks".formatted(adapterId))
                .factory();
        this.seen = new LinkedHashMap<>(16, 0.75f, true) {

            @Override
            protected boolean removeEldestEntry(final Map.Entry<Delivery, Boolean> eldest) {
                return size() > seenBound;
            }

        };
    }

    /**
     * Looks for the user tasks of a workflow module from now on, and says so: the job types and the
     * interval, or that <code>user-task-poll-interval</code> switched the looks off. The first look
     * is one interval after the first module with user tasks registered. A module without user
     * tasks to be notified of changes nothing.
     *
     * @param module The notifier of the module
     */
    public void register(final ZenBpmUserTaskNotifier module) {
        final var userTaskTypes = module.userTaskTypes();
        if (userTaskTypes.isEmpty()) {
            return;
        }
        if (interval.isZero()) {
            log.info("""
                    ZenBPM[{}]: workflow module '{}' has user tasks of the job type(s) {}, which are not looked for, \
                    because {} is {}: no @WorkflowTask method hears of their creation or cancellation""", adapterId,
                    module.workflowModuleId(), String.join(", ", userTaskTypes), ZenBpmAdapterConfiguration
                            .propertyKey(adapterId, "user-task-poll-interval"),
                    interval);
            return;
        }
        modules.put(module.workflowModuleId(), module);
        log.info("""
                ZenBPM[{}]: workflow module '{}' has its user tasks of the job type(s) {} looked for every {} ({}): \
                each look lists the active and the terminated jobs of each type, every page of them, so a look \
                reads more the more terminated user tasks the engine keeps, until the history-time-to-live of \
                their workflows passed, and a creation or a cancellation reaches the application up to one interval \
                late""", adapterId, module.workflowModuleId(), String.join(", ", userTaskTypes), interval,
                ZenBpmAdapterConfiguration.propertyKey(adapterId, "user-task-poll-interval"));
        synchronized (this) {
            if (looking) {
                return;
            }
            looking = true;
            // a look which met the stop of the last module still runs, and times the next one
            if (!lookRuns) {
                timeTheNextLook();
            }
        }
    }

    /**
     * Looks for the user tasks of a workflow module no longer. The looks end with the last module.
     * A notification of the module already handed to a handler slot runs within the module's drain,
     * which the shutdown waits for; one which starts while the module stops is not run.
     *
     * @param workflowModuleId The workflow module
     */
    public void unregister(final String workflowModuleId) {
        if (modules.remove(workflowModuleId) == null) {
            return;
        }
        synchronized (this) {
            if (!modules.isEmpty()) {
                return;
            }
            looking = false;
            if (timedLook != null) {
                timedLook.future.cancel(false);
                timedLook = null;
            }
        }
        log.debug("ZenBPM[{}]: no workflow module has user tasks to be looked for any more", adapterId);
    }

    /**
     * One look at the user tasks of every registered module, on the calling thread.
     */
    void look() {
        final var userTaskTypes = new LinkedHashSet<String>();
        modules
                .values()
                .forEach(module -> userTaskTypes.addAll(module.userTaskTypes()));
        try {
            for (final var userTaskType : userTaskTypes) {
                if (!lookAt(userTaskType, JobRef.State.ACTIVE, TaskEvent.Event.CREATED) || !lookAt(userTaskType,
                        JobRef.State.TERMINATED, TaskEvent.Event.CANCELED)) {
                    break;
                }
            }
        } catch (final RuntimeException e) {
            reportTheOutage(userTaskTypes, e);
            return;
        }
        if (outage) {
            outage = false;
            log.info("ZenBPM[{}]: the engine answers the looks at the user tasks again", adapterId);
        }
    }

    // false where a job could not be handed over, which ends the look
    private boolean lookAt(
        final String userTaskType,
        final JobRef.State state,
        final TaskEvent.Event event
    ) {
        final var filter = JobFilter
                .anyJob()
                .withJobType(userTaskType)
                .withState(state);
        for (var page = 1;; ++page) {
            final var jobs = rest.jobs(filter.withPage(page));
            for (final var job : jobs.items()) {
                if (!handOver(job, event)) {
                    return false;
                }
            }
            if (jobs.last()) {
                return true;
            }
        }
    }

    private boolean handOver(
        final JobRef job,
        final TaskEvent.Event event
    ) {
        // a task of another version may carry the type, which the job stream serves
        if (!USER_TASK.equals(job.elementType())) {
            return true;
        }
        final var delivery = new Delivery(job.key(), event);
        if (wasSeen(delivery) || !running.add(job.key())) {
            return true;
        }
        final boolean handedOver;
        try {
            handedOver = executor.tryExecute(() -> deliver(job, delivery));
        } catch (final RejectedExecutionException e) {
            running.remove(job.key());
            log.debug("ZenBPM[{}]: the adapter shuts down, so user task {} is not handed over", adapterId, job.key());
            return false;
        }
        if (!handedOver) {
            running.remove(job.key());
            log.debug(
                    "ZenBPM[{}]: every handler slot is taken, so user task {} and the ones behind it wait for the next look",
                    adapterId, job.key());
        }
        return handedOver;
    }

    // on a handler thread
    private void deliver(
        final JobRef job,
        final Delivery delivery
    ) {
        try {
            if (deliveredTheModule(job, delivery.event())) {
                synchronized (seen) {
                    seen.put(delivery, Boolean.TRUE);
                }
            }
        } finally {
            running.remove(job.key());
        }
    }

    private boolean deliveredTheModule(
        final JobRef job,
        final TaskEvent.Event event
    ) {
        final Optional<InstanceFacts> facts;
        try {
            facts = instances.facts(job.instanceKey(), this::idVariableNameOf);
        } catch (final RuntimeException e) {
            log.debug(
                    "ZenBPM[{}]: instance {} of user task {} could not be read; the next look hands the user task over again: {}",
                    adapterId, job.instanceKey(), job.key(), e.getMessage());
            return false;
        }
        if (facts.isEmpty()) {
            log.debug(
                    "ZenBPM[{}]: instance {} of user task {} is not known to the engine node asked, which may lag behind; the next look hands the user task over again",
                    adapterId, job.instanceKey(), job.key());
            return false;
        }
        final var scopedProcessId = facts
                .get()
                .scopedProcessId();
        final var polling = modules
                .values()
                .stream()
                .flatMap(module -> module
                        .processPolling(scopedProcessId, job.type())
                        .map(process -> new Polling(module, process))
                        .stream())
                .findFirst();
        if (polling.isEmpty()) {
            // the engine's default type is every application's, and a module which stopped hears
            // of nothing any more
            log.debug(
                    "ZenBPM[{}]: user task {} of type '{}' belongs to process '{}', of which no running workflow module of this application is notified",
                    adapterId, job.key(), job.type(), scopedProcessId);
            return true;
        }
        return polling
                .get()
                .module()
                .deliver(job, event, facts.get(), polling
                        .get()
                        .process());
    }

    private String idVariableNameOf(final String scopedProcessId) {
        return modules
                .values()
                .stream()
                .map(module -> module.idVariableNameOf(scopedProcessId))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean wasSeen(final Delivery delivery) {
        synchronized (seen) {
            return seen.get(delivery) != null;
        }
    }

    private void reportTheOutage(
        final Set<String> userTaskTypes,
        final RuntimeException failure
    ) {
        synchronized (this) {
            // a look which ran into the shutdown of the clients has nothing to report
            if (!looking) {
                log.debug("ZenBPM[{}]: a look at the user tasks ended with the looks: {}", adapterId, failure
                        .getMessage());
                return;
            }
        }
        if (outage) {
            log.debug("ZenBPM[{}]: the engine still does not answer the looks at the user tasks: {}", adapterId,
                    failure.getMessage());
            return;
        }
        outage = true;
        log.warn("""
                ZenBPM[{}]: the engine did not answer the look at the user tasks of the job type(s) {}: {}. No user \
                task is taken for new or for gone because of it, and the next look asks again in {}; this is said \
                once until a look is answered""", adapterId, String.join(", ", userTaskTypes), failure.getMessage(),
                interval);
    }

    // called holding this, so the timed look cannot start before it is the one timed
    private void timeTheNextLook() {
        final var timed = new TimedLook();
        try {
            timed.future = timing.schedule(timed, interval.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final RejectedExecutionException e) {
            // the timing threads stop only once the adapter shuts down
            looking = false;
            return;
        }
        timedLook = timed;
    }

    // on a timing thread: a look timed before a stop, whose cancellation came too late, does not
    // start, so a start right after the stop has one line of looks and not two
    private synchronized void startTheLook(final TimedLook timed) {
        if (timedLook != timed) {
            return;
        }
        timedLook = null;
        lookRuns = true;
        looks
                .newThread(this::lookAndTimeTheNext)
                .start();
    }

    private void lookAndTimeTheNext() {
        try {
            look();
        } finally {
            synchronized (this) {
                lookRuns = false;
                if (looking) {
                    timeTheNextLook();
                }
            }
        }
    }

    /**
     * One look timed on the timing threads.
     */
    private final class TimedLook implements Runnable {

        // guarded by the poller
        private Future<?> future;

        @Override
        public void run() {
            startTheLook(this);
        }

    }

    /**
     * What one notification is about: a user task's job and what happened to it.
     */
    private record Delivery(
        long jobKey,
        TaskEvent.Event event
    ) {
    }

    /**
     * The module notified of a job, with the process the job belongs to.
     */
    private record Polling(
        ZenBpmUserTaskNotifier module,
        WiredProcess process
    ) {
    }

}
