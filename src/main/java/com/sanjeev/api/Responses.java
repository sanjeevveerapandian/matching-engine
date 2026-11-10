package com.sanjeev.api;

import com.sanjeev.engine.OrderBook;
import java.util.List;

/** The JSON the endpoints return. Prices are always rupees text; quantities are whole numbers. */
public final class Responses {

    private Responses() { }

    /**
     * @param status FILLED, RESTING, PARTIALLY_FILLED, CANCELLED or PARTIALLY_FILLED_REST_CANCELLED
     */
    public record PlaceOrder(long orderId, long sequence, String status, long quantity, long filledQuantity,
                             long restingQuantity, long cancelledQuantity, List<TradeView> trades) {

        static PlaceOrder from(Domain.PlacedOrder placed) {
            return new PlaceOrder(placed.orderId(), placed.sequence(), placed.status(), placed.quantity(),
                    placed.filledQuantity(), placed.restingQuantity(), placed.cancelledQuantity(),
                    placed.trades().stream().map(TradeView::from).toList());
        }
    }

    public record CancelOrder(long orderId, long sequence, long cancelledQuantity) {

        static CancelOrder from(Domain.CancelledOrder cancelled) {
            return new CancelOrder(cancelled.orderId(), cancelled.sequence(), cancelled.cancelledQuantity());
        }
    }

    /** @param keptPriority true if the order kept its place in the queue */
    public record ModifyOrder(long orderId, long sequence, boolean keptPriority, List<TradeView> trades) {

        static ModifyOrder from(Domain.ModifiedOrder modified) {
            return new ModifyOrder(modified.orderId(), modified.sequence(), modified.keptPriority(),
                    modified.trades().stream().map(TradeView::from).toList());
        }
    }

    /** What a simulation run did. See {@link Domain.SimulationResult}. */
    public record Simulation(int requested, int limitOrders, int marketOrders, int cancels, int modifies,
                             int notFound, int rejected, int trades, long seed) {

        static Simulation from(Domain.SimulationResult result, long seed) {
            return new Simulation(result.requested(), result.limitOrders(), result.marketOrders(),
                    result.cancels(), result.modifies(), result.notFound(), result.rejected(), result.trades(), seed);
        }
    }

    public record Level(String price, long quantity) {

        static Level from(OrderBook.Level level) {
            return new Level(Money.paiseToRupees(level.priceInPaise()), level.totalQuantity());
        }
    }

    /** The ladder: best level first on each side. Best prices and spread are null when a side is empty. */
    public record Book(List<Level> bids, List<Level> asks, String bestBid, String bestAsk, String spread) {

        static Book from(Domain.BookSnapshot snapshot) {
            String spread = snapshot.bestBid() == null || snapshot.bestAsk() == null
                    ? null
                    : Money.paiseToRupees(snapshot.bestAsk() - snapshot.bestBid());
            return new Book(
                    snapshot.bids().stream().map(Level::from).toList(),
                    snapshot.asks().stream().map(Level::from).toList(),
                    snapshot.bestBid() == null ? null : Money.paiseToRupees(snapshot.bestBid()),
                    snapshot.bestAsk() == null ? null : Money.paiseToRupees(snapshot.bestAsk()),
                    spread);
        }
    }
}
