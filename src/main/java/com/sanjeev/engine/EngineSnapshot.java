package com.sanjeev.engine;

import java.util.List;

/**
 * Everything needed to rebuild a {@link MatchingEngine} exactly as it was: the resting orders
 * and the engine's two counters. A plain immutable value: the engine produces and accepts it,
 * but never writes it anywhere (that is the infrastructure's job).
 *
 * <p>The counters matter as much as the orders. Without {@code nextTradeId} the first trade
 * after recovery would get the wrong id; without {@code lastArrivalSequence} the next
 * re-submitted order could clash with a queued one.
 *
 * @param nextTradeId         the id the next trade will get
 * @param lastArrivalSequence the highest arrival number the engine has seen
 * @param orders              every resting order in book order: bids best price first, then
 *                            asks best price first, each price level oldest order first
 */
public record EngineSnapshot(long nextTradeId, long lastArrivalSequence, List<OrderState> orders) {

    public EngineSnapshot {
        orders = List.copyOf(orders);
    }

    /** One resting order, with everything needed to put it back (including its trader). */
    public record OrderState(
            long id,
            String trader,
            Side side,
            long priceInPaise,
            long originalQuantity,
            long remainingQuantity,
            long sequence) { }
}
