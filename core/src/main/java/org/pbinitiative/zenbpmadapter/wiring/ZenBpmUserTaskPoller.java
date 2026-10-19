/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <b>Once per node.</b> Each job and event which was handed over and is done with goes into a set
 * of deliveries, and is not handed over again while the set holds it. The set forgets a delivery
 * once two looks which listed every job in a row listed it no more: its job left the state, or the
 * engine deleted it with its instance's history, and it is never listed so again. Two looks, because
 * a listing by pages may pass over a job while jobs before it leave the state. So the set holds what
 * the engine lists, however much that is, and no delivery is handed over a second time because the
 * set ran full. A job seen active is still handed over as cancelled once it is terminated, and a
 * terminated job never seen active, after a restart or where the user task came and went between
 * two looks, is handed over as cancelled only. A job told as cancelled is never handed over as
 * created afterwards, where a node which lags behind lists it active again. Across a restart the
 * platform's record of deliveries answers a notification it took before, by its delivery id.
 * <p>
 * <b>Which module.</b> On a handler slot the facts of the job's instance are read through the
 * instance cache of the adapter id, and the job goes to the module which claims the instance's
 * process and is notified of the job's type in it: the scoped id of a process tells whose it is, as
 * it does for the probes. The engine's default type <code>user-task-type</code> is shared by every
 * application on the engine, so a job of a process no running module claims costs one read of its
 * instance, never reaches the core, and is not handed over again until a workflow module of the
 * adapter id starts: the module which claims it may be one which starts later than the first.
 * <p>
 * <b>Threads.</b> The look is timed by the timing threads of the adapter id and runs on a virtual
 * thread of its own, so an engine slow to answer never holds the job stream's hand-overs up, and the
 * next look is timed only once a look ended. A job is handed to a handler slot with
 * {@link ZenBpmExecutor#tryExecute(Runnable)}, which never waits: where no slot is free, the look
 * ends and the rest waits for the next one, out of the set. A job whose notification still runs is
 * not handed over a second time.
 * <p>
 * <b>Failures.</b> A look the engine did not answer adds nothing to the set, is said once per outage
 * at WARN, and the next look asks again: a failed page is never read as an empty one. A failed read
 * of one job's instance skips that job only. A notification which was handed over and not told, its
 * method threw or its instance could not be read, is handed over again by a later look: by the next
 * one first, and after each further failure one which waits twice as many looks, at most
 * {@value #LONGEST_WAIT_IN_LOOKS}. So a notification which keeps failing cannot take the handler
 * slots from the others at every look. Its method's failure is said at WARN, and a failure right
 * after another one of the method at DEBUG.
 */
@Slf4j
public final class ZenBpmUserTaskPoller {

    /**
     * How many looks a notification which keeps failing waits at most before it is handed over again.
     */
    static final int LONGEST_WAIT_IN_LOOKS = 64;

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

    // guarded by itself: the deliveries done with
    private final Set<Delivery> seen = new HashSet<>();

    // guarded by seen: the deliveries of processes no running workflow module claimed
    private final Set<Delivery> nobodys = new HashSet<>();

    // guarded by seen: the deliveries handed over and not told, each with when it is due again
    private final Map<Delivery, Retry> retries = new HashMap<>();

    // guarded by seen: the starts of workflow modules, so a delivery found to be nobody's before a
    // start is not remembered so after it
    private long starts;

    // read and written by the thread of a look only, and looks never overlap
    private long lookNumber;

    // read and written by the thread of a look only: what the last look which listed every job
    // listed
    private Set<Delivery> listedBefore = Set.of();

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
                .getUserTaskPollInterval());
    }

    ZenBpmUserTaskPoller(
        final String adapterId,
        final ZenBpmRestClient rest,
        final ZenBpmExecutor executor,
        final ZenBpmInstanceCache instances,
        final Duration interval
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
        synchronized (seen) {
            ++starts;
            nobodys.clear();
        }
        log.info("""
                ZenBPM[{}]: workflow module '{}' has its user tasks of the job type(s) {} looked for every {} ({}): \
                each look lists the active and the terminated jobs of each type, every page of them, so a look \
                reads more the more terminated user tasks the engine keeps, until the history-time-to-live of \
                their workflows passed, and a cancellation is told only while the engine still lists its job. A \
                look starts one interval after the last one ended, so a creation or a cancellation reaches the \
                application one interval and the time of a look late, and later where every handler slot is \
                taken or the engine does not answer""", adapterId, module.workflowModuleId(),
                String.join(", ", userTaskTypes), interval,
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
        ++lookNumber;
        final var userTaskTypes = new LinkedHashSet<String>();
        modules
                .values()
                .forEach(module -> userTaskTypes.addAll(module.userTaskTypes()));
        final var listed = new HashSet<Delivery>();
        var everyJobListed = true;
        try {
            for (final var userTaskType : userTaskTypes) {
                if (!lookAt(userTaskType, JobRef.State.ACTIVE, TaskEvent.Event.CREATED, listed) || !lookAt(
                        userTaskType, JobRef.State.TERMINATED, TaskEvent.Event.CANCELED, listed)) {
                    everyJobListed = false;
                    break;
                }
            }
        } catch (final RuntimeException e) {
            reportTheOutage(userTaskTypes, e);
            return;
        }
        if (everyJobListed) {
            forgetWhatIsNoLongerListed(listed);
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
        final TaskEvent.Event event,
        final Set<Delivery> listed
    ) {
        final var filter = JobFilter
                .anyJob()
                .withJobType(userTaskType)
                .withState(state);
        for (var page = 1;; ++page) {
            final var jobs = rest.jobs(filter.withPage(page));
            for (final var job : jobs.items()) {
                if (!handOver(job, event, listed)) {
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
        final TaskEvent.Event event,
        final Set<Delivery> listed
    ) {
        // a task of another version may carry the type, which the job stream serves
        if (!USER_TASK.equals(job.elementType())) {
            return true;
        }
        final var delivery = new Delivery(job.key(), event);
        listed.add(delivery);
        final Retry retry;
        final long startsSoFar;
        synchronized (seen) {
            if (seen.contains(delivery) || nobodys.contains(delivery) || toldAsCancelled(delivery)) {
                return true;
            }
            retry = retries.get(delivery);
            startsSoFar = starts;
        }
        // after the starts were counted, so a module which starts later than this is counted
        final var modulesNow = List.copyOf(modules.values());
        if (((retry != null) && (retry.dueLook() > lookNumber)) || !running.add(job.key())) {
            return true;
        }
        final var look = lookNumber;
        final boolean handedOver;
        try {
            handedOver = executor
                    .tryExecute(() -> deliver(job, delivery, retry, look, new Modules(modulesNow, startsSoFar)));
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
        final Delivery delivery,
        final Retry retry,
        final long look,
        final Modules atTheHandOver
    ) {
        try {
            final var outcome = deliveredTheModule(job, delivery.event(), (retry != null) && retry.warned(),
                    atTheHandOver.notifiers());
            synchronized (seen) {
                if (outcome.isEmpty()) {
                    retries.remove(delivery);
                    // a module which started meanwhile may claim it
                    if (starts == atTheHandOver.startsSoFar()) {
                        nobodys.add(delivery);
                    }
                } else if (outcome.get() == ZenBpmUserTaskNotifier.Outcome.DONE) {
                    retries.remove(delivery);
                    seen.add(delivery);
                } else {
                    retries.put(delivery,
                            Retry.after(retry, look, outcome.get() == ZenBpmUserTaskNotifier.Outcome.THREW));
                }
            }
        } finally {
            running.remove(job.key());
        }
    }

    // empty where no workflow module which ran at the hand-over claims the job's process
    private Optional<ZenBpmUserTaskNotifier.Outcome> deliveredTheModule(
        final JobRef job,
        final TaskEvent.Event event,
        final boolean warnedBefore,
        final List<ZenBpmUserTaskNotifier> notifiers
    ) {
        final Optional<InstanceFacts> facts;
        try {
            facts = instances.facts(job.instanceKey(), this::idVariableNameOf);
        } catch (final RuntimeException e) {
            log.debug(
                    "ZenBPM[{}]: instance {} of user task {} could not be read; the next look hands the user task over again: {}",
                    adapterId, job.instanceKey(), job.key(), e.getMessage());
            return Optional.of(ZenBpmUserTaskNotifier.Outcome.AGAIN);
        }
        if (facts.isEmpty()) {
            log.debug(
                    "ZenBPM[{}]: instance {} of user task {} is not known to the engine node asked, which may lag behind; the next look hands the user task over again",
                    adapterId, job.instanceKey(), job.key());
            return Optional.of(ZenBpmUserTaskNotifier.Outcome.AGAIN);
        }
        final var scopedProcessId = facts
                .get()
                .scopedProcessId();
        final var polling = notifiers
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
            return Optional.empty();
        }
        return Optional.of(polling
                .get()
                .module()
                .deliver(job, event, facts.get(), polling
                        .get()
                        .process(), warnedBefore));
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

    // called holding seen: a node which lags behind may list a job active which a look before
    // listed terminated, and a creation told after the cancellation would set a person to work
    // on a task which is gone
    private boolean toldAsCancelled(final Delivery delivery) {
        return seen.contains(new Delivery(delivery.jobKey(), TaskEvent.Event.CANCELED));
    }

    // a delivery which neither this look nor the one before listed: its job left the state or was
    // deleted with its instance's history, and is never listed so again
    private void forgetWhatIsNoLongerListed(final Set<Delivery> listed) {
        final var listedLately = new HashSet<>(listed);
        listedLately.addAll(listedBefore);
        synchronized (seen) {
            seen.retainAll(listedLately);
            nobodys.retainAll(listedLately);
            retries
                    .keySet()
                    .retainAll(listedLately);
        }
        listedBefore = listed;
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
     * When a notification handed over and not told is handed over again.
     *
     * @param failures How often it was not told in a row
     * @param dueLook The number of the first look which hands it over again
     * @param warned Whether it was not told because its method threw, which was said then
     */
    private record Retry(
        int failures,
        long dueLook,
        boolean warned
    ) {

        // the next look after the first failure, and after each further one a look which waits
        // twice as many looks
        static Retry after(
            final Retry before,
            final long look,
            final boolean threw
        ) {
            final var failures = before == null ? 1 : before.failures() + 1;
            final var wait = Math.min(1L << Math.min(failures - 1, 30), LONGEST_WAIT_IN_LOOKS);
            return new Retry(failures, look + wait, threw);
        }

    }

    /**
     * The workflow modules which ran when a job was handed over, and how many had started by then.
     *
     * @param notifiers Their notifiers
     * @param startsSoFar The starts of workflow modules counted before
     */
    private record Modules(
        List<ZenBpmUserTaskNotifier> notifiers,
        long startsSoFar
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
