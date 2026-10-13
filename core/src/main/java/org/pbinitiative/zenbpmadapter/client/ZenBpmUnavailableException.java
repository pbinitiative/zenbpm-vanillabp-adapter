/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

/**
 * A REST call got no answer the adapter can use: the engine could not be reached, did not answer
 * within <code>request-timeout</code>, the connection broke, or what came back could not be read.
 * Whether the call took effect in the engine is unknown.
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
     * A call which got no answer the adapter can use.
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

        super("ZenBPM adapter '%s': %s %s %s".formatted(adapterId, method, path, what), cause);
        this.adapterId = adapterId;
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

}
