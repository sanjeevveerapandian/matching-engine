package com.sanjeev.infra;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.sanjeev.engine.OrderType;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;

/**
 * A REQUEST to the exchange: something a client wants done. Commands are written to the log
 * before they run, so they must be plain data that can be turned into JSON and back.
 *
 * <p>Unlike an Event ("this happened"), a Command may still be refused.
 *
 * <p>The Jackson annotations make each JSON line say which command it is, e.g.
 * {@code {"kind":"CancelOrder","orderId":7}}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Command.NewOrder.class, name = "NewOrder"),
        @JsonSubTypes.Type(value = Command.CancelOrder.class, name = "CancelOrder"),
        @JsonSubTypes.Type(value = Command.ModifyOrder.class, name = "ModifyOrder")
})
public sealed interface Command {

    /**
     * Place a new order. The records do no validation themselves: a bad command is still
     * logged, then refused by the engine with an OrderRejected event.
     *
     * @param priceInPaise the limit price; must be null for a market order
     */
    record NewOrder(
            long orderId,
            String trader,
            Side side,
            OrderType orderType,
            TimeInForce timeInForce,
            Long priceInPaise,
            long quantity) implements Command {

        /** A limit order. */
        public static NewOrder limit(long orderId, String trader, Side side, long priceInPaise,
                                     long quantity, TimeInForce timeInForce) {
            return new NewOrder(orderId, trader, side, OrderType.LIMIT, timeInForce, priceInPaise, quantity);
        }

        /** A market order: no price, always immediate-or-cancel. */
        public static NewOrder market(long orderId, String trader, Side side, long quantity) {
            return new NewOrder(orderId, trader, side, OrderType.MARKET, TimeInForce.IOC, null, quantity);
        }
    }

    /** Cancel a resting order. */
    record CancelOrder(long orderId) implements Command { }

    /** Change a resting order's price and/or quantity (see MatchingEngine.modify for the rules). */
    record ModifyOrder(long orderId, long newPriceInPaise, long newQuantity) implements Command { }
}
