/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A warehouse simulated in memory: it remembers what it keeps aside for which order.
 */
@ApplicationScoped
public class LocalWarehouse implements Warehouse {

    private static final Logger LOG = LoggerFactory.getLogger(LocalWarehouse.class);

    private final Map<String, String> reserved = new ConcurrentHashMap<>();

    private final Map<String, String> shipped = new ConcurrentHashMap<>();

    private final AtomicInteger lastParcel = new AtomicInteger(100_000);

    @Override
    public void reserve(
        final String orderId,
        final String item
    ) {
        reserved.put(orderId, item);
        LOG.info("'{}' reserved for order {}", item, orderId);
    }

    @Override
    public String ship(final String orderId) {
        return shipped.computeIfAbsent(orderId, id -> {
            final var item = reserved.remove(id);
            final var trackingNumber = "PARCEL-%d".formatted(lastParcel.incrementAndGet());
            LOG.info("'{}' of order {} shipped as {}", item, id, trackingNumber);
            return trackingNumber;
        });
    }

    @Override
    public void release(final String orderId) {
        final var item = reserved.remove(orderId);
        if (item != null) {
            LOG.info("'{}' of order {} is back on the shelf", item, orderId);
        }
    }

}
