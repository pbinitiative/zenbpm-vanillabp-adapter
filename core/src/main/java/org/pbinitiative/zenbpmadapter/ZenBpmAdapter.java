/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

/**
 * Constants shared by all modules of the VanillaBP adapter for ZenBPM.
 */
public final class ZenBpmAdapter {

    /**
     * The adapter type of this adapter, announced to the VanillaBP platform integrations. An
     * adapter id is configured under {@code vanillabp.adapters.<id>.*}; its type is set by
     * {@code vanillabp.adapters.<id>.type=zenbpm}, and where an application configures no
     * type, the id itself is read as the type. So an application with one ZenBPM engine can
     * name its adapter id {@code zenbpm} and set nothing else.
     */
    public static final String ADAPTER_TYPE = "zenbpm";

    /**
     * How messages name the BPMS this adapter talks to.
     */
    public static final String BPMS_NAME = "ZenBPM";

    private ZenBpmAdapter() {
        // constants holder
    }

}
