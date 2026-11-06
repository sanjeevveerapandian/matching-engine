package com.sanjeev.engine;

/**
 * Something that HAPPENED inside the engine. Events are facts in the past tense: they are
 * immutable, and the engine returns them instead of leaving callers to guess what changed.
 *
 * <p>They live in the engine package because the engine produces them. Dependencies point
 * one way only: infrastructure code may use the engine; the engine never uses infrastructure.
 */
public sealed interface Event {

    /**
     * An order passed validation and entered the engine.
     *
     * @param priceInPaise the limit price, or null for a market order (it has none)
     * @param quantity     the quantity the order was submitted with
     * @param sequence     the arrival number the order was given
     */
    record OrderAccepted(
            long orderId,
            String trader,
            Side side,
            OrderType type,
            TimeInForce timeInForce,
            Long priceInPaise,
            long quantity,
            long sequence) implements Event { }

    /** A command was refused and changed nothing. The reason is for humans. */
    record OrderRejected(long orderId, String reason) implements Event { }

    /** Two orders traded. */
    record TradeExecuted(Trade trade) implements Event { }

    /** Quantity was removed from an order without trading. */
    record OrderCancelled(long orderId, long cancelledQuantity, CancelReason reason) implements Event { }

    /**
     * A resting order was changed.
     *
     * @param keptPriority true if it kept its place in the queue (a pure size reduction)
     */
    record OrderModified(long orderId, long newPriceInPaise, long newQuantity, boolean keptPriority)
            implements Event { }
}
