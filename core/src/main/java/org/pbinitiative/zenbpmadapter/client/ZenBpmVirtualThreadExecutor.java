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
 * A virtual thread per handler means no limit, so <code>worker-threads-bound</code> is the bound
 * which {@link ZenBpmExecutor} holds. It is taken before the virtual thread starts, so a submission
 * beyond it waits in the submitting thread. Camunda 8's adapter takes it inside the virtual thread
 * instead, because the thread which submits there belongs to the Camunda client. Here it is the
 * dispatcher's, which submits only with a slot in hand and so does not wait.
 */
public class ZenBpmVirtualThreadExecutor extends ZenBpmExecutor {

    /**
     * Builds the virtual-thread half.
     *
     * @param adapterId The adapter id, used to name the threads
     * @param bound How many handlers may run at the same time
     */
    public ZenBpmVirtualThreadExecutor(
        final String adapterId,
        final int bound
    ) {
        super(adapterId, bound, handlingHalf(adapterId));
    }

    private static ExecutorService handlingHalf(final String adapterId) {
        return Executors.newThreadPerTaskExecutor(Thread
                .ofVirtual()
                .name("zenbpm-%s-handler-".formatted(adapterId), 0)
                .factory());
    }

}
