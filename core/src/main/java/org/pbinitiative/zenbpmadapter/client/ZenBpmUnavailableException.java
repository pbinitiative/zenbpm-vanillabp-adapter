/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.OptionalInt;

/**
 * A REST call got no answer the adapter can use: the engine could not be reached, did not answer
 * within <code>request-timeout</code>, the connection broke, or what came back could not be read.
 * <p>
 * Where no answer arrived, whether the call took effect in the engine is unknown. Where the
 * engine's status arrived, {@link #getStatus()} names it, and only the body is missing or cannot be
 * read. That status is one the call expects, so a 2xx status says the engine did what was asked:
 * a start answered 201 created the instance, although the client cannot say which.
 * <p>
 * Each field has an accessor of its own, see decision 13 in the repository's DECISIONS.md.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public class ZenBpmUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The adapter id whose engine was called.
     */
    private final String adapterId;

    /**
     * The HTTP method of the call.
     */
    private final String method;

    /**
     * The path of the call as the engine names it.
     */
    private final String path;

    /**
     * The HTTP status the engine answered, <code>null</code> where no answer arrived.
     */
    private final Integer status;

    /**
     * A call which got no answer.
     *
     * @param adapterId The adapter id
     * @param what What happened instead of an answer, completing the sentence "GET /v1/jobs/42 ..."
     * @param cause What the HTTP client or the JSON decoder reported
     * @param method The HTTP method
     * @param path The path as the engine names it
     */
    public ZenBpmUnavailableException(
        final String adapterId,
        final String what,
        final Throwable cause,
        final String method,
        final String path
    ) {
        this(adapterId, null, what, cause, method, path);
    }

    /**
     * A call whose answer arrived with a status, but whose body did not arrive or cannot be read.
     *
     * @param adapterId The adapter id
     * @param status The HTTP status the engine answered
     * @param what What happened to the body, completing the sentence "GET /v1/jobs/42 ..."
     * @param cause What the HTTP client or the JSON decoder reported
     * @param method The HTTP method
     * @param path The path as the engine names it
     */
    public ZenBpmUnavailableException(
        final String adapterId,
        final int status,
        final String what,
        final Throwable cause,
        final String method,
        final String path
    ) {
        this(adapterId, Integer.valueOf(status), what, cause, method, path);
    }

    private ZenBpmUnavailableException(
        final String adapterId,
        final Integer status,
        final String what,
        final Throwable cause,
        final String method,
        final String path
    ) {
        super("ZenBPM adapter '%s': %s %s %s".formatted(adapterId, method, path, what), cause);
        this.adapterId = adapterId;
        this.status = status;
        this.method = method;
        this.path = path;
    }

    /**
     * The adapter id whose engine did not answer.
     *
     * @return The adapter id
     */
    public String getAdapterId() {
        return adapterId;
    }

    /**
     * The HTTP method of the call.
     *
     * @return E.g. <code>POST</code>
     */
    public String getMethod() {
        return method;
    }

    /**
     * The path of the call as the engine names it, without a query and without a path of
     * <code>rest-address</code> in front of it.
     *
     * @return E.g. <code>/v1/jobs/42/complete</code>
     */
    public String getPath() {
        return path;
    }

    /**
     * The HTTP status the engine answered before its body went missing or proved unreadable.
     *
     * @return The status, empty where no answer arrived
     */
    public OptionalInt getStatus() {
        return status == null
                ? OptionalInt.empty()
                : OptionalInt.of(status);
    }

}
