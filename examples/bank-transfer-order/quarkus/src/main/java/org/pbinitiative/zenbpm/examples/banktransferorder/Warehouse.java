/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

/**
 * The warehouse, which keeps stock for an order until it is paid. A real shop calls its warehouse
 * system; this example simulates it with {@link LocalWarehouse}.
 */
public interface Warehouse {

    /**
     * Keeps the item of an order aside, so nobody else buys it while the shop waits for the money.
     *
     * @param orderId The number of the order
     * @param item What is ordered
     */
    void reserve(
        String orderId,
        String item
    );

    /**
     * Ships what was reserved for an order.
     *
     * @param orderId The number of the order
     * @return The tracking number of the parcel
     */
    String ship(String orderId);

    /**
     * Puts what was reserved for an order back on the shelf.
     *
     * @param orderId The number of the order
     */
    void release(String orderId);

}
