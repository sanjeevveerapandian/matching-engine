package com.sanjeev.api;

import com.sanjeev.engine.Trade;
import java.util.List;
import java.util.Optional;

/**
 * Everything the REST layer can ask of the exchange, in the engine's own terms. The only
 * implementation sends each call through the {@code Sequencer}; nothing here touches the engine
 * directly. It is an interface so the controllers can be tested with a stand-in.
 */
public interface ExchangeService extends AutoCloseable {

    /** Place an order. @throws OrderRejectedException if the engine refuses it */
    Domain.PlacedOrder placeOrder(Domain.NewOrderSpec spec);

    /** @throws OrderNotFoundException if no such order is resting */
    Domain.CancelledOrder cancelOrder(long orderId);

    /**
     * Change a resting order. {@code newQuantity} is the quantity still wanted (the unfilled part).
     *
     * @throws OrderNotFoundException if no such order is resting
     */
    Domain.ModifiedOrder modifyOrder(long orderId, long newPricePaise, long newQuantity);

    /** The best {@code depth} price levels on each side. */
    Domain.BookSnapshot book(int depth);

    /** The most recent trades, newest first, at most {@code limit}. */
    List<Trade> recentTrades(int limit);

    /**
     * Why the exchange has stopped working, or empty while it is running. A plain read that never waits
     * in the queue behind orders, so a health check can call it at any time.
     */
    default Optional<String> failure() {
        return Optional.empty();
    }

    /** Stop accepting requests, finish what was accepted, close the log. */
    @Override
    void close();
}
