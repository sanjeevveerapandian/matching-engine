package com.sanjeev.api;

import com.sanjeev.engine.OrderBook;
import com.sanjeev.engine.OrderType;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import com.sanjeev.engine.Trade;
import java.util.List;

/**
 * The language the controllers and the exchange service use to talk to each other: plain values in
 * the engine's own terms (prices are long paise). No JSON, no decimals, no HTTP.
 */
public final class Domain {

    /** The most units one order may ask for: keeps every sum inside a long, however many orders add up. */
    public static final long MAX_QUANTITY = 1_000_000_000L;

    private Domain() { }

    /** A validated request to place an order. {@code pricePaise} is null for a market order. */
    public record NewOrderSpec(String trader, Side side, OrderType type, TimeInForce timeInForce,
                        Long pricePaise, long quantity) { }

    /**
     * What happened to a new order. Every unit of {@code quantity} ends up in exactly one of
     * filled, resting (waiting on the book) or cancelled.
     */
    public record PlacedOrder(long orderId, long sequence, long quantity, long filledQuantity,
                              long restingQuantity, long cancelledQuantity, List<Trade> trades) {

        public String status() {
            if (filledQuantity == quantity) {
                return "FILLED";
            }
            if (restingQuantity > 0) {
                return filledQuantity > 0 ? "PARTIALLY_FILLED" : "RESTING";
            }
            return filledQuantity > 0 ? "PARTIALLY_FILLED_REST_CANCELLED" : "CANCELLED";
        }
    }

    public record CancelledOrder(long orderId, long sequence, long cancelledQuantity) { }

    public record ModifiedOrder(long orderId, long sequence, boolean keptPriority, List<Trade> trades) { }

    /** The top of the book: best levels first, and the best bid and ask in paise (null if empty). */
    public record BookSnapshot(List<OrderBook.Level> bids, List<OrderBook.Level> asks, Long bestBid, Long bestAsk) { }
}
