/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The executor of an adapter id which runs <code>worker-threads: virtual</code>. It uses one
 * virtual thread named <code>zenbpm-&lt;id&gt;-handler-N</code> per handler. The timing runs on
 * the platform threads which {@link ZenBpmExecutor} keeps for it.
 * <p>
 * A virtual thread per handler has no limit of its own, so the slots of {@link ZenBpmExecutor},
 * as many as <code>worker-threads-bound</code> says, are the limit. A slot is taken before the
 * virtual thread starts. Camunda 8's adapter takes its permit inside the virtual thread instead,
 * because the thread which submits there belongs to the Camunda client, which must not wait. Here
 * the dispatcher takes a slot with {@link #tryExecute(Runnable)}, which does not wait either.
 */
public class ZenBpmVirtualThreadExecutor extends ZenBpmExecutor {

    /**
     * Builds the virtual handler threads.
     *
     * @param adapterId The adapter id, used to name the threads
     * @param slots How many handlers may run at the same time
     */
    public ZenBpmVirtualThreadExecutor(
        final String adapterId,
        final int slots
    ) {
        super(adapterId, slots, handlerThreads(adapterId));
    }

    private static ExecutorService handlerThreads(final String adapterId) {
        return Executors.newThreadPerTaskExecutor(Thread
                .ofVirtual()
                .name("zenbpm-%s-handler-".formatted(adapterId), 0)
                .factory());
    }

}
