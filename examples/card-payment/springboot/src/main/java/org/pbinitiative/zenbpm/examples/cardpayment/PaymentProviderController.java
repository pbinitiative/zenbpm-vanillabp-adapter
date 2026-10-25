/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Switches the simulated payment provider off and on, so the demo can show an outage. It belongs
 * to the simulation, not to the shop: a real provider goes down without being asked.
 */
@RestController
@RequestMapping("/simulation/payment-provider")
public class PaymentProviderController {

    private final LocalPaymentProvider provider;

    /**
     * Built by Spring.
     *
     * @param provider The simulated provider
     */
    public PaymentProviderController(final LocalPaymentProvider provider) {
        this.provider = provider;
    }

    /**
     * Whether the provider answers.
     *
     * @return The state of the provider
     */
    @GetMapping
    public ProviderState state() {
        return new ProviderState(provider.isAvailable());
    }

    /**
     * Starts or ends an outage.
     *
     * @param state <code>false</code> for an outage
     * @return The state of the provider
     */
    @PutMapping
    public ProviderState change(@RequestBody final ProviderState state) {
        provider.setAvailable(state.available());
        return state();
    }

    /**
     * The state of the simulated provider.
     *
     * @param available Whether it answers
     */
    public record ProviderState(boolean available) {
    }

}
