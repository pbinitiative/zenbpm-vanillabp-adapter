/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * The bounded repetition of what a handler sends to the engine about the job it runs: the update of
 * the variables, the completion, the failure, and the reads the handler needs before it can invoke.
 * One is built per delivery.
 * <p>
 * <b>What is repeated.</b> A call the engine could not be asked for ({@link ZenBpmErrors#isUnavailable}:
 * no answer in time, a broken connection, <code>502</code>, <code>503</code>, <code>504</code>), and
 * whatever the caller names as worth another attempt on top, such as a <code>404</code> of a node
 * which lags behind. A read may also answer something the caller wants to ask again, such as an
 * instance a lagging node does not know yet. Everything else comes back at once: a refusal the
 * engine repeats however often it is asked, and a job which is gone, which the handler takes as an
 * answer.
 * <p>
 * <b>What bounds it.</b> The tighter of two limits wins:
 * <ul>
 * <li><b>the job's lock.</b> A repetition after the lock lapsed is a command about a job the engine
 * may have handed out again. So no attempt is made whose pause would end after the lock, which the
 * caller reads anew before each attempt, because the lock renewal moves it.</li>
 * <li><b>{@value #MAX_ATTEMPTS} attempts.</b> A handler which waits holds a handler slot, and a slot
 * which waits delivers nothing. The pauses start at 50 ms and double, so five attempts take less
 * than a second: a hiccup of the engine. Anything longer is an outage an operator has to see.</li>
 * </ul>
 * And it stops at once while the workflow module shuts down: the failure is then the shutdown's,
 * and the job is left to its lock instead of being held up by a repetition.
 * <p>
 * When no further attempt is made, the last failure is thrown, or the last answer returned, as
 * the call gave it.
 */
@Slf4j
public final class ZenBpmCommandRetry {

    /**
     * How often a call is made in total, the first attempt included.
     */
    static final int MAX_ATTEMPTS = 5;

    /**
     * The pause after the first attempt. Each pause after it is twice the one before.
     */
    static final Duration FIRST_PAUSE = Duration.ofMillis(50);

    private final String adapterId;

    private final long jobKey;

    private final String taskDefinition;

    private final Supplier<Instant> lockEnd;

    private final BooleanSupplier shuttingDown;

    /**
     * The repetition of the calls about one delivery of a job.
     *
     * @param adapterId The adapter id, for the messages
     * @param jobKey The job, for the messages
     * @param taskDefinition The task definition as the application knows it, for the messages
     * @param lockEnd When the job's lock lapses, read before every attempt
     * @param shuttingDown Whether the workflow module of the job shuts down
     */
    public ZenBpmCommandRetry(
        final String adapterId,
        final long jobKey,
        final String taskDefinition,
        final Supplier<Instant> lockEnd,
        final BooleanSupplier shuttingDown
    ) {
        this.adapterId = adapterId;
        this.jobKey = jobKey;
        this.taskDefinition = taskDefinition;
        this.lockEnd = Objects.requireNonNull(lockEnd, "lockEnd");
        this.shuttingDown = Objects.requireNonNull(shuttingDown, "shuttingDown");
    }

    /**
     * Sends a command, and repeats it where the engine could not be asked.
     *
     * @param command What is sent, as the log names it, e.g. <code>completion</code>
     * @param send The command
     * @throws RuntimeException The last failure, once no further attempt is made
     */
    public void send(
        final String command,
        final Runnable send
    ) {
        send(command, failure -> false, send);
    }

    /**
     * Sends a command, and repeats it where the engine could not be asked or where the caller
     * says another attempt may change the answer.
     *
     * @param command What is sent, as the log names it, e.g. <code>completion</code>
     * @param worthAnotherAttempt Which other failures are repeated
     * @param send The command
     * @throws RuntimeException The last failure, once no further attempt is made
     */
    public void send(
        final String command,
        final Predicate<RuntimeException> worthAnotherAttempt,
        final Runnable send
    ) {
        call(command, () -> {
            send.run();
            return Boolean.TRUE;
        }, answer -> false, worthAnotherAttempt);
    }

    /**
     * Reads something, and reads it again where the engine could not be asked or where it answered
     * what the caller wants to ask again.
     *
     * @param <T> What the read answers
     * @param what What is read, as the log names it, e.g. <code>instance</code>
     * @param read The read
     * @param notYet Which answers are asked again
     * @return The first answer which is not asked again, or the last one
     * @throws RuntimeException The last failure, once no further attempt is made
     */
    public <T> T read(
        final String what,
        final Supplier<T> read,
        final Predicate<? super T> notYet
    ) {
        return call(what, read, notYet, failure -> false);
    }

    private <T> T call(
        final String what,
        final Supplier<T> call,
        final Predicate<? super T> notYet,
        final Predicate<RuntimeException> worthAnotherAttempt
    ) {
        var attempt = 1;
        while (true) {
            final T answer;
            try {
                answer = call.get();
            } catch (final RuntimeException e) {
                final var repeatable = ZenBpmErrors.isUnavailable(e) || worthAnotherAttempt.test(e);
                if (!repeatable || !anotherAttempt(what, attempt)) {
                    throw e;
                }
                ++attempt;
                continue;
            }
            if (!notYet.test(answer) || !anotherAttempt(what, attempt)) {
                if (attempt > 1) {
                    log.debug("ZenBPM[{}]: the {} of job {} ('{}') ended on attempt {}", adapterId, what, jobKey,
                            taskDefinition, attempt);
                }
                return answer;
            }
            ++attempt;
        }
    }

    // whether a further attempt is made, after the pause before it
    private boolean anotherAttempt(
        final String what,
        final int attempt
    ) {
        if (shuttingDown.getAsBoolean()) {
            log.debug("ZenBPM[{}]: the {} of job {} ('{}') is not repeated, because the workflow module shuts down",
                    adapterId, what, jobKey, taskDefinition);
            return false;
        }
        if (attempt >= MAX_ATTEMPTS) {
            log.debug("ZenBPM[{}]: the {} of job {} ('{}') is not repeated, all {} attempts were made", adapterId,
                    what, jobKey, taskDefinition, MAX_ATTEMPTS);
            return false;
        }
        final var pause = FIRST_PAUSE.multipliedBy(1L << (attempt - 1));
        final var remainingLock = Duration.between(Instant.now(), lockEnd.get());
        if (remainingLock.compareTo(pause) <= 0) {
            log.debug("ZenBPM[{}]: the {} of job {} ('{}') is not repeated, its lock lapses within the next pause",
                    adapterId, what, jobKey, taskDefinition);
            return false;
        }
        log.debug("ZenBPM[{}]: the {} of job {} ('{}') failed on attempt {}, the next one follows in {}", adapterId,
                what, jobKey, taskDefinition, attempt, pause);
        try {
            Thread.sleep(pause);
            return true;
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            return false;
        }
    }

}
