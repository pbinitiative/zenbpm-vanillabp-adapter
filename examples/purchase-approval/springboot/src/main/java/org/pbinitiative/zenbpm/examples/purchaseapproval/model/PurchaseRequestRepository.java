/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval.model;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

/**
 * The purchase requests. VanillaBP loads and saves the workflow aggregate through this repository
 * whenever the process calls a task.
 */
public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, String> {

    /**
     * Loads a purchase request to answer one of its tasks, with a write lock until the transaction
     * ends. Two answers to the same task then wait for each other, and the second finds the task
     * answered, instead of both deciding.
     *
     * @param id The id of the request
     * @return The request, if there is one
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PurchaseRequest> findLockedById(String id);

}
