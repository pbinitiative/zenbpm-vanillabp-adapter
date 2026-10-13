/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

/**
 * The engine answered a REST call with a status the call does not expect. The status, the
 * method and the path decide what the answer means; the engine's message is for people, so no
 * code reads it to decide anything.
 * <p>
 * The message of the exception names the adapter id, the method, the path and the status, plus
 * the engine's error code and message where it sent them, and nothing else of the answer.
 * <p>
 * Each field has an accessor of its own, see decision 13 in the repository's DECISIONS.md.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public class ZenBpmApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The adapter id whose engine was called.
     */
    private final String adapterId;

    /**
     * The HTTP status of the answer.
     */
    private final int status;

    /**
     * The <code>code</code> of the engine's error, <code>null</code> where there is none.
     */
    private final String engineCode;

    /**
     * The <code>message</code> of the engine's error, <code>null</code> where there is none.
     */
    private final String engineMessage;

    /**
     * The HTTP method of the call.
     */
    private final String method;

    /**
     * The path of the call as the engine names it.
     */
    private final String path;

    /**
     * An answer of the engine the call did not expect.
     *
     * @param adapterId The adapter id
     * @param status The HTTP status
     * @param engineCode The engine's error code, <code>null</code> where there is none
     * @param engineMessage The engine's error message, <code>null</code> where there is none
     * @param method The HTTP method
     * @param path The path as the engine names it
     */
    public ZenBpmApiException(
        final String adapterId,
        final int status,
        final String engineCode,
        final String engineMessage,
        final String method,
        final String path
    ) {

        super(message(adapterId, status, engineCode, engineMessage, method, path));
        this.adapterId = adapterId;
        this.status = status;
        this.engineCode = engineCode;
        this.engineMessage = engineMessage;
        this.method = method;
        this.path = path;

    }

    /**
     * The adapter id whose engine answered.
     *
     * @return The adapter id
     */
    public String getAdapterId() {

        return adapterId;

    }

    /**
     * The HTTP status of the answer.
     *
     * @return E.g. <code>404</code>
     */
    public int getStatus() {

        return status;

    }

    /**
     * The <code>code</code> of the engine's error.
     *
     * @return E.g. <code>NOT_FOUND</code>; <code>null</code> where the answer carried none, as the
     *         answer of a proxy does
     */
    public String getEngineCode() {

        return engineCode;

    }

    /**
     * The <code>message</code> of the engine's error, for people.
     *
     * @return The message; <code>null</code> where the answer carried none
     */
    public String getEngineMessage() {

        return engineMessage;

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

    private static String message(
        final String adapterId,
        final int status,
        final String engineCode,
        final String engineMessage,
        final String method,
        final String path
    ) {

        final var answer = "ZenBPM adapter '%s': %s %s was answered with status %d".formatted(adapterId, method, path,
                status);
        if (engineCode == null) {
            return engineMessage == null ? answer : "%s: %s".formatted(answer, engineMessage);
        }
        return engineMessage == null
                ? "%s, %s".formatted(answer, engineCode)
                : "%s, %s: %s".formatted(answer, engineCode, engineMessage);

    }

}
