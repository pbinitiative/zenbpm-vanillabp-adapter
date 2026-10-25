/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Switches the simulated payment provider off and on, so the demo can show an outage. It belongs
 * to the simulation, not to the shop: a real provider goes down without being asked.
 */
@Path("/simulation/payment-provider")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PaymentProviderController {

    private final LocalPaymentProvider provider;

    /**
     * Built by CDI.
     *
     * @param provider The simulated provider
     */
    @Inject
    public PaymentProviderController(final LocalPaymentProvider provider) {
        this.provider = provider;
    }

    /**
     * Whether the provider answers.
     *
     * @return The state of the provider
     */
    @GET
    public ProviderState state() {
        return new ProviderState(provider.isAvailable());
    }

    /**
     * Starts or ends an outage.
     *
     * @param state <code>false</code> for an outage
     * @return The state of the provider
     */
    @PUT
    public ProviderState change(final ProviderState state) {
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
