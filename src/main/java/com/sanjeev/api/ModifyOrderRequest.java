package com.sanjeev.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code PATCH /api/orders/{id}}. Both fields are required: a modify REPLACES the order's
 * price and quantity. (Leaving one out and filling it in from the current order would race with
 * trades happening in between: a fill could change the quantity under us, and a stale value would
 * turn a reduction into an increase and cost the order its place in the queue.)
 *
 * <pre>
 *   {"price":"41.00","quantity":8}
 * </pre>
 *
 * @param price    rupees as a JSON STRING
 * @param quantity the quantity still wanted (the unfilled part). The same price and a SMALLER
 *                 quantity keeps the order's place in the queue; anything else sends it to the back.
 */
public record ModifyOrderRequest(
        @NotNull(message = "price is required")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String price,

        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be greater than zero")
        @Max(value = Domain.MAX_QUANTITY, message = "quantity must not exceed 1000000000")
        Long quantity) {

    /** @throws InvalidRequestException if the price is not an acceptable amount */
    long newPricePaise() {
        try {
            return Money.rupeesToPaise(price);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("price", e.getMessage());
        }
    }
}
