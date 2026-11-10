package com.sanjeev.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.sanjeev.engine.OrderType;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/orders}.
 *
 * <pre>
 *   {"trader":"Ravi","side":"SELL","type":"LIMIT","price":"40.50","quantity":10,"timeInForce":"GTC"}
 * </pre>
 *
 * <p>The annotations check each field on its own; {@link #toSpec()} checks the rules that involve
 * several fields (a limit order needs a price, a market order must not have one) and converts the
 * price from rupees text to paise.
 *
 * @param price       rupees as a JSON STRING, e.g. "40.50"; required for LIMIT, forbidden for MARKET
 * @param timeInForce GTC, IOC or FOK; optional, defaults to GTC for LIMIT; a MARKET order is always IOC
 */
public record PlaceOrderRequest(
        @NotBlank(message = "trader must not be blank")
        @Size(max = 64, message = "trader must be at most 64 characters")
        String trader,

        @NotNull(message = "side is required (BUY or SELL)")
        Side side,

        @NotNull(message = "type is required (LIMIT or MARKET)")
        OrderType type,

        @JsonDeserialize(using = StrictStringDeserializer.class)
        String price,

        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be greater than zero")
        @Max(value = Domain.MAX_QUANTITY, message = "quantity must not exceed 1000000000")
        Long quantity,

        TimeInForce timeInForce) {

    /** Cross-field checks and the rupees-to-paise conversion. @throws InvalidRequestException on a bad request */
    Domain.NewOrderSpec toSpec() {
        boolean noPrice = price == null || price.isBlank();
        if (type == OrderType.MARKET) {
            if (!noPrice) {
                throw new InvalidRequestException("price", "a MARKET order must not have a price");
            }
            if (timeInForce != null && timeInForce != TimeInForce.IOC) {
                throw new InvalidRequestException("timeInForce",
                        "a MARKET order is always IOC: leave timeInForce out or use IOC");
            }
            return new Domain.NewOrderSpec(trader.strip(), side, type, TimeInForce.IOC, null, quantity);
        }
        if (noPrice) {
            throw new InvalidRequestException("price", "price is required for a LIMIT order");
        }
        long paise;
        try {
            paise = Money.rupeesToPaise(price);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("price", e.getMessage());
        }
        return new Domain.NewOrderSpec(trader.strip(), side, type,
                timeInForce == null ? TimeInForce.GTC : timeInForce, paise, quantity);
    }
}
