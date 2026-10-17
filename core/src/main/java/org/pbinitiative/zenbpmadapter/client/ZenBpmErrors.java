/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one classification of what a call of the engine failed with. A caller asks what the failure
 * means for its next step: whether another attempt can change the answer, whether the job it
 * worked on is gone, whether nothing waits for its message yet, whether its lock on a job is lost,
 * or whether the engine could not be asked at all.
 * <p>
 * Every answer is read from the status, the method and the path of the call, never from the
 * engine's message, which any version of the engine may reword, see decision 30 in the
 * repository's DECISIONS.md. Each question walks the chain of causes, so a failure wrapped by a
 * {@link java.util.concurrent.CompletionException} or by a caller still answers. The first
 * {@link ZenBpmApiException} or {@link ZenBpmUnavailableException} in the chain answers alone:
 * what the HTTP client reported beneath it is how it came about, not a second answer, and a
 * failure wrapped around it is no answer either. So a status the call did not expect, whose body
 * then stalled, is that status, although its cause is a timeout.
 * <p>
 * Hand in only what a call of the engine failed with, or what reading a key failed with: every
 * <code>NumberFormatException</code> in the chain is read as a key which is not a number, also one
 * thrown by other code.
 */
public final class ZenBpmErrors {

    /**
     * Statuses the engine answers the same way however often the request is sent: the request
     * itself is refused (<code>400</code>), too large (<code>413</code>) or of a content type the
     * endpoint does not take (<code>415</code>).
     */
    private static final Set<Integer> PERMANENT_STATUSES = Set.of(400, 413, 415);

    /**
     * Statuses which say that the engine could not be asked rather than what it decided:
     * <code>502</code> is the engine's own answer where the node which holds the data could not
     * be reached; <code>503</code> and <code>504</code> come from a proxy in front of it.
     */
    private static final Set<Integer> UNAVAILABLE_STATUSES = Set.of(502, 503, 504);

    /**
     * The failures of the transport a caller may meet without this client between it and the
     * engine. The client itself wraps them into a {@link ZenBpmUnavailableException}.
     */
    private static final Set<Class<? extends Throwable>> TRANSPORT_FAILURES = Set.of(SocketTimeoutException.class,
            HttpTimeoutException.class, ConnectException.class);

    private static final String POST = "POST";

    private static final Pattern JOB_PATH = Pattern.compile("/v1/jobs/-?\\d+(/[a-z-]+)?");

    private static final Pattern COMPLETION_OR_FAILURE_PATH = Pattern.compile("/v1/jobs/-?\\d+/(complete|fail)");

    private static final Pattern LOCK_EXTENSION_PATH = Pattern.compile("/v1/jobs/-?\\d+/extend-lock");

    private static final Pattern MESSAGES_PATH = Pattern.compile("/v1/messages");

    private static final Pattern START_PATH = Pattern.compile("/v1/process-instances");

    private ZenBpmErrors() {
    }

    /**
     * Whether another attempt is refused the same way: the engine answered <code>400</code>,
     * <code>413</code> or <code>415</code>, it answered <code>404</code> to a start
     * (<code>POST /v1/process-instances</code>), or a key was not a number before anything was
     * sent. The list is short on purpose, because repeating is the safe answer for everything
     * else.
     * <p>
     * The engine answers <code>404</code> to a start where it holds no process of the id, which
     * nothing but a deployment changes. It reads the process on the leader of a partition, which a
     * deployment wrote to before it answered, so the <code>404</code> is no node lagging behind a
     * deployment, see decision 30 in the repository's DECISIONS.md.
     *
     * @param throwable What the call failed with
     * @return Whether no repetition can change the answer
     */
    public static boolean permanentFailure(final Throwable throwable) {
        final var answer = answerIn(throwable);
        if (answer instanceof ZenBpmApiException refusal) {
            return PERMANENT_STATUSES.contains(refusal.getStatus()) || ((refusal.getStatus() == 404) && isPostTo(
                    START_PATH, refusal));
        }
        return answer instanceof NumberFormatException;
    }

    /**
     * Whether another attempt may change the answer: everything which is not a
     * {@link #permanentFailure(Throwable)}, a status of <code>500</code> included, which the
     * engine answers for a technical fault of its own.
     * <p>
     * A failure whose status arrived but whose body was lost or cannot be read is repeatable as
     * well, although a <code>2xx</code> status says that the engine did what was asked. A read is
     * answered again, and a completion of a completed job is answered as the first one was. A
     * repeated start may start a second workflow where the core's question whether one is there
     * already gets a wrong "unknown", a repeated message may be correlated twice, and a repeated
     * failure without a delivery token counts twice. It stays repeatable all the same, because a
     * permanent failure would block an operation the engine carried out; the caller of such a
     * call decides whether it accepts the duplicate.
     *
     * @param throwable What the call failed with
     * @return Whether another attempt is worth making
     */
    public static boolean isRepeatable(final Throwable throwable) {
        return !permanentFailure(throwable);
    }

    /**
     * Whether the job a command named is gone, so no repetition of the command is accepted: the
     * engine answered <code>404</code> to a call of a job (it holds no such job), or
     * <code>409</code> to a completion or a failure (the job no longer takes the command: it was
     * completed, terminated, or failed with an incident; or a failure named a delivery token, and a
     * newer delivery of the job decides). This is what at-least-once delivery leaves over, and a
     * handler accepts it. A completion of a job the engine completed already is answered as the
     * first one was, not with <code>409</code>.
     * <p>
     * A <code>409</code> to a lock extension is not a gone job, see {@link #lockLost(Throwable)}.
     *
     * @param throwable What the call of a job failed with
     * @return Whether the job is gone
     */
    public static boolean isGoneJob(final Throwable throwable) {
        if (!(answerIn(throwable) instanceof ZenBpmApiException refusal)) {
            return false;
        }
        return switch (refusal.getStatus()) {
            case 404 -> matches(JOB_PATH, refusal);
            case 409 -> isPostTo(COMPLETION_OR_FAILURE_PATH, refusal);
            default -> false;
        };
    }

    /**
     * Whether nothing waits for a published message yet: the engine answered <code>404</code> to
     * <code>POST /v1/messages</code>. The engine keeps no message nobody waits for, so the
     * publication has to be repeated once something waits.
     *
     * @param throwable What the publication failed with
     * @return Whether nothing waits for the message
     */
    public static boolean nobodyWaits(final Throwable throwable) {
        return isPostAnsweredWith(throwable, 404, MESSAGES_PATH);
    }

    /**
     * Whether a lock extension found the lock lost: the engine answered <code>409</code> to
     * <code>POST /v1/jobs/{key}/extend-lock</code>. The lock lapsed, the job was completed or
     * failed, the job was never delivered over the job stream, or another client holds it. The
     * renewal stops, and a second delivery of the job may be under way. A job the engine does not
     * hold at all answers <code>404</code>, which {@link #isGoneJob(Throwable)} reads.
     *
     * @param throwable What the lock extension failed with
     * @return Whether the lock is lost
     */
    public static boolean lockLost(final Throwable throwable) {
        return isPostAnsweredWith(throwable, 409, LOCK_EXTENSION_PATH);
    }

    /**
     * Whether the engine could not be asked: no answer arrived in time, the connection failed or
     * broke, an answer could not be read, or the engine (<code>502</code>) or a proxy in front of
     * it (<code>503</code>, <code>504</code>) answered that the request did not get through.
     * <p>
     * A <code>502</code> is also what the engine answers for a key whose partition it does not
     * have, for example a key another engine handed out. The status alone does not tell the two
     * apart.
     *
     * @param throwable What the call failed with
     * @return Whether the engine could not be asked
     */
    public static boolean isUnavailable(final Throwable throwable) {
        final var answer = answerIn(throwable);
        if (answer instanceof ZenBpmApiException refusal) {
            return UNAVAILABLE_STATUSES.contains(refusal.getStatus());
        }
        return (answer instanceof ZenBpmUnavailableException) || isOneOf(TRANSPORT_FAILURES, answer);
    }

    /**
     * What a failure says, without the adapter id every message of this client starts with: for
     * a sentence which names the adapter id already, such as the failure of the engine wait, or
     * an answer which carries it beside the text, such as a health.
     *
     * @param adapterId The adapter id
     * @param throwable The failure
     * @return Its message without a leading <code>ZenBPM adapter '&lt;id&gt;': </code>; the
     *         failure itself where it has no message
     */
    public static String withoutAdapterId(
        final String adapterId,
        final Throwable throwable
    ) {
        final var message = throwable.getMessage();
        if (message == null) {
            return throwable.toString();
        }
        final var prefix = "ZenBPM adapter '%s': ".formatted(adapterId);
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    /**
     * The failure in the chain of causes which answers every question, <code>null</code> where
     * none does. That is the first exception of this client, wherever it is in the chain, so a
     * timeout or a <code>NumberFormatException</code> wrapped around it does not hide it. Where the
     * chain holds none, it is the first failure this class reads without one.
     * <p>
     * A chain of causes does not always end: a failure wrapped into one it wrapped before forms a
     * ring. So the walk remembers each failure it saw, by identity, and stops at the first it sees
     * again.
     */
    private static Throwable answerIn(final Throwable throwable) {
        final var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable readWithoutTheClient = null;
        var current = throwable;
        while ((current != null) && seen.add(current)) {
            if ((current instanceof ZenBpmApiException) || (current instanceof ZenBpmUnavailableException)) {
                return current;
            }
            if ((readWithoutTheClient == null) && isReadWithoutTheClient(current)) {
                readWithoutTheClient = current;
            }
            current = current.getCause();
        }
        return readWithoutTheClient;
    }

    private static boolean isReadWithoutTheClient(final Throwable failure) {
        return (failure instanceof NumberFormatException) || isOneOf(TRANSPORT_FAILURES, failure);
    }

    private static boolean isPostAnsweredWith(
        final Throwable throwable,
        final int status,
        final Pattern path
    ) {
        if (!(answerIn(throwable) instanceof ZenBpmApiException refusal)) {
            return false;
        }
        return (refusal.getStatus() == status) && isPostTo(path, refusal);
    }

    private static boolean isOneOf(
        final Set<Class<? extends Throwable>> failures,
        final Throwable throwable
    ) {
        return failures
                .stream()
                .anyMatch(failure -> failure.isInstance(throwable));
    }

    private static boolean isPostTo(
        final Pattern path,
        final ZenBpmApiException refusal
    ) {
        return POST.equals(refusal.getMethod()) && matches(path, refusal);
    }

    private static boolean matches(
        final Pattern path,
        final ZenBpmApiException refusal
    ) {
        return (refusal.getPath() != null) && path
                .matcher(refusal.getPath())
                .matches();
    }

}
