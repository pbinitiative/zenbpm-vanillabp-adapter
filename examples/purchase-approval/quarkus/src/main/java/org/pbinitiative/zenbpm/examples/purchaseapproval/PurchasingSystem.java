/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

/**
 * The purchasing system of the company, such as an ERP, which turns an approved request into a
 * purchase order. A real application calls it over its API; this example simulates it with
 * {@link LocalPurchasingSystem}.
 */
public interface PurchasingSystem {

    /**
     * Places a purchase order.
     *
     * @param item What is to be bought
     * @param amount What it costs
     * @return The number of the purchase order
     */
    String placeOrder(
        String item,
        int amount
    );

}
