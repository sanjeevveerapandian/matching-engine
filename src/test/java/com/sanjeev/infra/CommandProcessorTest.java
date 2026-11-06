package com.sanjeev.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.CancelReason;
import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.OrderType;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandProcessorTest {

    private MatchingEngine engine;
    private CommandProcessor processor;

    @BeforeEach
    void freshProcessor() {
        engine = new MatchingEngine();
        processor = new CommandProcessor(engine);
    }

    private static Event.OrderRejected onlyRejection(List<Event> events) {
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof Event.OrderRejected, events.toString());
        return (Event.OrderRejected) events.get(0);
    }

    @Test
    void aNewLimitOrderIsAcceptedAndRestsWithTheCommandsSequenceNumber() {
        List<Event> events = processor.apply(7,
                Command.NewOrder.limit(1, "Ravi", Side.SELL, 3000, 10, TimeInForce.GTC));

        assertEquals(List.of(new Event.OrderAccepted(1, "Ravi", Side.SELL, OrderType.LIMIT,
                TimeInForce.GTC, 3000L, 10, 7)), events);
        assertEquals(7, engine.book().restingOrder(1).getSequence());
    }

    @Test
    void aNewMarketOrderTradesAgainstTheBook() {
        processor.apply(1, Command.NewOrder.limit(1, "Ravi", Side.SELL, 3000, 10, TimeInForce.GTC));

        List<Event> events = processor.apply(2, Command.NewOrder.market(2, "Anu", Side.BUY, 4));

        assertEquals(2, events.size());
        Event.TradeExecuted trade = (Event.TradeExecuted) events.get(1);
        assertEquals("Anu", trade.trade().buyer());
        assertEquals("Ravi", trade.trade().seller());
        assertEquals(3000, trade.trade().price());
        assertEquals(4, trade.trade().quantity());
    }

    @Test
    void aCrossingLimitOrderProducesTradesAtTheRestingPrice() {
        processor.apply(1, Command.NewOrder.limit(1, "Ravi", Side.SELL, 3000, 10, TimeInForce.GTC));

        List<Event> events = processor.apply(2,
                Command.NewOrder.limit(2, "Anu", Side.BUY, 3200, 10, TimeInForce.GTC));

        assertEquals(3000, ((Event.TradeExecuted) events.get(1)).trade().price());
        assertFalse(engine.book().contains(1));
        assertFalse(engine.book().contains(2));
    }

    @Test
    void aCancelCommandCancelsTheRestingOrder() {
        processor.apply(1, Command.NewOrder.limit(1, "Ravi", Side.SELL, 3000, 10, TimeInForce.GTC));

        List<Event> events = processor.apply(2, new Command.CancelOrder(1));

        assertEquals(List.of(new Event.OrderCancelled(1, 10, CancelReason.REQUESTED)), events);
    }

    @Test
    void aModifyCommandChangesTheRestingOrder() {
        processor.apply(1, Command.NewOrder.limit(1, "Ravi", Side.SELL, 3000, 10, TimeInForce.GTC));

        List<Event> events = processor.apply(2, new Command.ModifyOrder(1, 3000, 4));

        assertEquals(List.of(new Event.OrderModified(1, 3000, 4, true)), events);
    }

    // ---------------------------------------------------------------- bad commands never throw

    @Test
    void aLimitOrderWithoutAPriceIsRejected() {
        Event.OrderRejected rejected = onlyRejection(processor.apply(1,
                new Command.NewOrder(1, "Ravi", Side.SELL, OrderType.LIMIT, TimeInForce.GTC, null, 10)));

        assertTrue(rejected.reason().contains("price"));
        assertEquals(1, rejected.orderId());
    }

    @Test
    void aMarketOrderWithAPriceIsRejected() {
        Event.OrderRejected rejected = onlyRejection(processor.apply(1,
                new Command.NewOrder(1, "Anu", Side.BUY, OrderType.MARKET, TimeInForce.IOC, 3000L, 10)));

        assertTrue(rejected.reason().contains("price"));
    }

    @Test
    void aNewOrderWithANonPositiveQuantityOrPriceIsRejected() {
        onlyRejection(processor.apply(1,
                Command.NewOrder.limit(1, "Ravi", Side.SELL, 3000, 0, TimeInForce.GTC)));
        onlyRejection(processor.apply(2,
                Command.NewOrder.limit(2, "Ravi", Side.SELL, 0, 10, TimeInForce.GTC)));
    }

    @Test
    void aNewOrderWithAMissingSideTraderTypeOrTimeInForceIsRejected() {
        onlyRejection(processor.apply(1,
                Command.NewOrder.limit(1, "Ravi", null, 3000, 10, TimeInForce.GTC)));
        onlyRejection(processor.apply(2,
                Command.NewOrder.limit(2, " ", Side.SELL, 3000, 10, TimeInForce.GTC)));
        onlyRejection(processor.apply(3,
                new Command.NewOrder(3, "Ravi", Side.SELL, null, TimeInForce.GTC, 3000L, 10)));
        onlyRejection(processor.apply(4,
                Command.NewOrder.limit(4, "Ravi", Side.SELL, 3000, 10, null)));
    }

    @Test
    void aRejectedNewOrderLeavesTheBookEmpty() {
        processor.apply(1, Command.NewOrder.limit(1, "Ravi", Side.SELL, 3000, 0, TimeInForce.GTC));

        assertEquals(0, engine.book().restingOrderCount());
    }

    @Test
    void aDuplicateIdIsRejected() {
        processor.apply(1, Command.NewOrder.limit(1, "Ravi", Side.SELL, 3000, 10, TimeInForce.GTC));

        Event.OrderRejected rejected = onlyRejection(processor.apply(2,
                Command.NewOrder.limit(1, "Anu", Side.BUY, 2900, 10, TimeInForce.GTC)));

        assertTrue(rejected.reason().contains("duplicate"));
    }

    @Test
    void cancellingAndModifyingAnUnknownOrderAreRejected() {
        onlyRejection(processor.apply(1, new Command.CancelOrder(99)));
        onlyRejection(processor.apply(2, new Command.ModifyOrder(99, 3000, 5)));
    }
}
