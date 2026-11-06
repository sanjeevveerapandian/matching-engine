package com.sanjeev.infra;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.Order;
import com.sanjeev.engine.OrderType;
import java.util.List;

/**
 * Turns one command into engine calls and returns the resulting events. It never throws for a
 * bad command: it returns an OrderRejected event instead.
 *
 * <p>It has no threads and no IO, so the live sequencer and a replay of the log use exactly
 * this same code. That is what makes replaying the log reproduce the original run.
 *
 * <p>The command's global sequence number becomes the new order's arrival number, so there is
 * one source of numbers and time priority follows the order commands were sequenced.
 */
public final class CommandProcessor {

    private final MatchingEngine engine;

    public CommandProcessor(MatchingEngine engine) {
        this.engine = engine;
    }

    /** Apply one command. @param sequence its global sequence number */
    public List<Event> apply(long sequence, Command command) {
        return switch (command) {
            case Command.NewOrder n -> newOrder(sequence, n);
            case Command.CancelOrder c -> engine.processCancel(c.orderId());
            case Command.ModifyOrder m ->
                    engine.processModify(m.orderId(), m.newPriceInPaise(), m.newQuantity());
        };
    }

    private List<Event> newOrder(long sequence, Command.NewOrder command) {
        Order order;
        try {
            order = toOrder(sequence, command);
        } catch (IllegalArgumentException e) {
            return List.of(new Event.OrderRejected(command.orderId(), e.getMessage()));
        }
        return order.getType() == OrderType.MARKET
                ? engine.processMarket(order)
                : engine.processLimit(order);
    }

    private static Order toOrder(long sequence, Command.NewOrder n) {
        if (n.orderType() == null) {
            throw new IllegalArgumentException("order type must not be null");
        }
        if (n.orderType() == OrderType.MARKET) {
            if (n.priceInPaise() != null) {
                throw new IllegalArgumentException("a market order must not have a price");
            }
            return Order.market(n.orderId(), n.trader(), n.side(), n.quantity(), sequence);
        }
        if (n.priceInPaise() == null) {
            throw new IllegalArgumentException("a limit order needs a price");
        }
        return Order.limit(n.orderId(), n.trader(), n.side(), n.priceInPaise(), n.quantity(),
                sequence, n.timeInForce());
    }
}
