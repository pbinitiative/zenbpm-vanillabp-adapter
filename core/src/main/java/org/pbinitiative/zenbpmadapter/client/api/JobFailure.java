/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.time.Duration;
import java.util.Map;

/**
 * What a failure of a job sends (<code>POST /jobs/{key}/fail</code>). Start with
 * {@link #withErrorCode(String)} for a BPMN error or {@link #withMessage(String)} for a failure
 * without one; every <code>with</code> method answers a new failure.
 *
 * @param errorCode The BPMN error code an error event of the model may catch; <code>null</code>
 *            for a failure which spends one of the job's retries
 * @param message Why the job failed, which the engine keeps on the job and puts into an incident;
 *            <code>null</code> for none
 * @param variables The variables handed to the catching event, or kept on a job which has no
 *            retries left; <code>null</code> for none
 * @param retries How many retries the job has left after this failure; <code>null</code> for one
 *            less than now. <code>0</code> raises an incident at once.
 * @param retryBackoff How long the engine waits before it hands the job out again, in whole
 *            seconds; <code>null</code> for the <code>retryBackoff</code> of the task's model, else
 *            the engine's default. The engine caps it at its <code>jobs.maxRetryBackoff</code>.
 * @param deliveryToken The delivery the failure belongs to, the <code>delivery_token</code> of the
 *            job as the job stream handed it out, so a repeated failure counts once and the
 *            failure of a delivery the engine handed out again is refused; <code>null</code> for
 *            none
 * @param clientId The client id of the job stream which received the job, whose lock the failure
 *            releases; <code>null</code> leaves the lock to lapse
 */
public record JobFailure(
    String errorCode,
    String message,
    Map<String, Object> variables,
    Integer retries,
    Duration retryBackoff,
    Long deliveryToken,
    String clientId
) {

    /**
     * A BPMN error.
     *
     * @param errorCode The error code
     * @return The failure
     */
    public static JobFailure withErrorCode(final String errorCode) {
        return withErrorCode(errorCode, null);
    }

    /**
     * A BPMN error with a message, which the engine puts into the incident it raises where no
     * error event of the model catches the code.
     *
     * @param errorCode The error code
     * @param message What the incident says before the code
     * @return The failure
     */
    public static JobFailure withErrorCode(
        final String errorCode,
        final String message
    ) {
        return new JobFailure(errorCode, message, null, null, null, null, null);
    }

    /**
     * A failure without an error code.
     *
     * @param message Why the job failed
     * @return The failure
     */
    public static JobFailure withMessage(final String message) {
        return new JobFailure(null, message, null, null, null, null, null);
    }

    /**
     * This failure, with variables.
     *
     * @param variables The variables
     * @return A new failure
     */
    public JobFailure withVariables(final Map<String, Object> variables) {
        return new JobFailure(errorCode, message, variables, retries, retryBackoff, deliveryToken, clientId);
    }

    /**
     * This failure, with the retries the job has left after it.
     *
     * @param retries The retries; <code>0</code> raises an incident at once
     * @return A new failure
     */
    public JobFailure withRetries(final int retries) {
        return new JobFailure(errorCode, message, variables, retries, retryBackoff, deliveryToken, clientId);
    }

    /**
     * This failure, with the wait before the job is handed out again.
     *
     * @param retryBackoff The wait in whole seconds; <code>null</code> for the backoff of the task's
     *            model, else the engine's default
     * @return A new failure
     */
    public JobFailure withRetryBackoff(final Duration retryBackoff) {
        return new JobFailure(errorCode, message, variables, retries, retryBackoff, deliveryToken, clientId);
    }

    /**
     * This failure, naming the delivery it belongs to and the job stream which received it.
     *
     * @param deliveryToken The <code>delivery_token</code> of the delivery; <code>0</code>, which
     *            the job stream reports where the engine issued none, names none
     * @param clientId The client id of the job stream
     * @return A new failure
     */
    public JobFailure ofTheDelivery(
        final long deliveryToken,
        final String clientId
    ) {
        return new JobFailure(errorCode, message, variables, retries, retryBackoff, deliveryToken == 0 ? null
                : deliveryToken, clientId);
    }

}
