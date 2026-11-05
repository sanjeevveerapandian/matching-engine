package com.sanjeev.sim;

import com.sanjeev.engine.Order;

/** One thing a simulated user can do to the exchange. Plain data, no behaviour. */
public sealed interface Operation {

    /** Submit a limit order (GTC, IOC or FOK, as the order says). */
    record SubmitLimit(Order order) implements Operation { }

    /** Submit a market order. */
    record SubmitMarket(Order order) implements Operation { }

    /** Cancel a resting order. */
    record Cancel(long orderId) implements Operation { }

    /** Change the price and/or quantity of a resting order. */
    record Modify(long orderId, long newPrice, long newQuantity) implements Operation { }
}
