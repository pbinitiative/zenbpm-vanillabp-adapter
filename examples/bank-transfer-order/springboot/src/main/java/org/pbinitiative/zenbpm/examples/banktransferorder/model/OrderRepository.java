/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder.model;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

/**
 * The orders. VanillaBP loads and saves the workflow aggregate through this repository whenever the
 * process calls a task.
 */
public interface OrderRepository extends JpaRepository<Order, String> {

    /**
     * Loads an order and locks it until the transaction ends. The bank may deliver the same
     * transfer twice at once, and only one of the deliveries may find the order waiting for it.
     *
     * @param orderId The number of the order
     * @return The order, if there is one
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findLockedByOrderId(String orderId);

}
