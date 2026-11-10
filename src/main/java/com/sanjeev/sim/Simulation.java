package com.sanjeev.sim;

import com.sanjeev.engine.BookInvariants;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.Order;
import com.sanjeev.engine.OrderBook;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.Trade;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the engine with a seeded random stream of operations and checks, after EVERY
 * operation, that the book is still valid. At the end it also checks that no quantity was
 * created or lost: for every order, added = filled + still resting + cancelled.
 *
 * <p>This lives outside the core engine package on purpose: the engine stays pure (no
 * printing, no IO); the simulation is just a user of the engine's public API.
 */
public final class Simulation {

    /** What one run produced. */
    public record Result(
            long seed,
            int operations,
            int limitOrders,
            int marketOrders,
            int cancels,
            int modifies,
            List<Trade> trades,
            long tradedQuantity,
            Long bestBid,
            Long bestAsk,
            int restingOrders,
            int orderIdsChecked,
            long elapsedNanos) {

        public int ordersSubmitted() {
            return limitOrders + marketOrders;
        }
    }

    /** Quantity bookkeeping for one order id. */
    static final class Ledger {
        long added;      // quantity the order was given (a re-submitted modify adds its new quantity)
        long filled;     // quantity traded
        long cancelled;  // quantity removed without trading (cancel, IOC/market leftover, FOK refusal, reduction)
    }

    /** What the simulation remembers about an order while it rests: its price and arrival number. */
    record RestingInfo(long priceInPaise, long sequence) { }

    private Simulation() { }

    /**
     * Price-time priority, checked on the trades one incoming order made, in the order they happened.
     * Against its resting counterparts, an incoming order must trade:
     * <ul>
     *   <li>at the RESTING order's price, never its own limit;</li>
     *   <li>best price first (a buyer meets the cheapest sellers first, a seller the dearest buyers);</li>
     *   <li>at one price, the oldest resting order first.</li>
     * </ul>
     *
     * @param resting price and arrival number of every order that was resting before the operation
     * @throws IllegalStateException describing the first rule broken
     */
    static void verifyPriority(List<Trade> made, Side aggressorSide, Map<Long, RestingInfo> resting) {
        long previousPrice = 0;
        long previousSequence = 0;
        boolean first = true;
        for (Trade trade : made) {
            long restingId = aggressorSide == Side.BUY ? trade.sellOrderId() : trade.buyOrderId();
            RestingInfo info = resting.get(restingId);
            if (info == null) {
                throw new IllegalStateException(trade + " is against order " + restingId
                        + ", which was never seen resting");
            }
            if (trade.price() != info.priceInPaise()) {
                throw new IllegalStateException("a trade must happen at the RESTING order's price "
                        + info.priceInPaise() + " but " + trade + " did not");
            }
            if (!first) {
                boolean worse = aggressorSide == Side.BUY
                        ? trade.price() < previousPrice
                        : trade.price() > previousPrice;
                if (worse) {
                    throw new IllegalStateException("best price first was broken: " + trade
                            + " came after a trade at " + previousPrice);
                }
                if (trade.price() == previousPrice && info.sequence() <= previousSequence) {
                    throw new IllegalStateException("oldest order first was broken at price "
                            + previousPrice + ": " + trade + " (arrival " + info.sequence()
                            + ") came after arrival " + previousSequence);
                }
            }
            previousPrice = trade.price();
            previousSequence = info.sequence();
            first = false;
        }
    }

    /** Run {@code operations} seeded operations. Throws IllegalStateException on ANY violation. */
    public static Result run(long seed, int operations) {
        Runner runner = new Runner(seed);
        long start = System.nanoTime();
        for (int i = 1; i <= operations; i++) {
            runner.step(i);
        }
        verifyConservation(runner.ledgers, runner.book, runner.tradedQuantity);
        long elapsed = System.nanoTime() - start;
        return new Result(seed, operations, runner.limitOrders, runner.marketOrders,
                runner.cancels, runner.modifies, List.copyOf(runner.trades), runner.tradedQuantity,
                runner.book.bestBid(), runner.book.bestAsk(), runner.book.restingOrderCount(),
                runner.ledgers.size(), elapsed);
    }

    /**
     * For every order: added = filled + remaining on the book + cancelled. Also: every trade
     * fills two orders (so total filled = 2 x traded quantity), and every resting order must be
     * accounted for.
     */
    static void verifyConservation(Map<Long, Ledger> ledgers, OrderBook book, long tradedQuantity) {
        long totalFilled = 0;
        int restingFound = 0;
        for (Map.Entry<Long, Ledger> entry : ledgers.entrySet()) {
            long id = entry.getKey();
            Ledger ledger = entry.getValue();
            Order live = book.restingOrder(id);
            long remaining = live == null ? 0 : live.getRemainingQuantity();
            if (live != null) {
                restingFound++;
            }
            if (ledger.added != ledger.filled + remaining + ledger.cancelled) {
                throw new IllegalStateException("conservation broken for order " + id + ": added "
                        + ledger.added + " != filled " + ledger.filled + " + remaining " + remaining
                        + " + cancelled " + ledger.cancelled);
            }
            totalFilled += ledger.filled;
        }
        if (restingFound != book.restingOrderCount()) {
            throw new IllegalStateException("book has " + book.restingOrderCount()
                    + " resting orders but only " + restingFound + " are accounted for");
        }
        if (totalFilled != 2 * tradedQuantity) {
            throw new IllegalStateException("orders were filled " + totalFilled + " in total but "
                    + tradedQuantity + " units traded (each trade should fill two orders)");
        }
    }

    /** One run's moving parts. */
    private static final class Runner {
        final long seed;
        final MatchingEngine engine = new MatchingEngine();
        final OrderBook book = engine.book();
        final OrderGenerator generator;
        final Map<Long, Ledger> ledgers = new HashMap<>();
        final List<Trade> trades = new ArrayList<>();
        final Map<Long, RestingInfo> restingInfo = new HashMap<>();
        long tradedQuantity;
        int limitOrders;
        int marketOrders;
        int cancels;
        int modifies;

        Runner(long seed) {
            this.seed = seed;
            this.generator = new OrderGenerator(seed, book, engine::nextSequence);
        }

        /** Generate, apply and verify one operation; any failure names the operation. */
        void step(int number) {
            Operation operation = generator.next();
            try {
                switch (operation) {
                    case Operation.SubmitLimit s -> submitLimit(s.order());
                    case Operation.SubmitMarket s -> submitMarket(s.order());
                    case Operation.Cancel c -> cancel(c.orderId());
                    case Operation.Modify m -> modify(m);
                }
                BookInvariants.check(book);
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new IllegalStateException("seed " + seed + ", operation #" + number + " "
                        + operation + " failed: " + e.getMessage(), e);
            }
        }

        private void submitLimit(Order order) {
            limitOrders++;
            ledger(order.getId()).added += order.getOriginalQuantity();
            List<Trade> made = engine.submitLimit(order);
            record(made);
            verifyPriority(made, order.getSide(), restingInfo);
            if (book.contains(order.getId())) {
                restingInfo.put(order.getId(), new RestingInfo(order.getPriceInPaise(), order.getSequence()));
            }

            for (Trade trade : made) { // an aggressor never trades at a worse price than its limit
                boolean withinLimit = order.getSide() == Side.BUY
                        ? trade.price() <= order.getPriceInPaise()
                        : trade.price() >= order.getPriceInPaise();
                require(withinLimit, "trade " + trade + " is outside the limit of " + order);
            }

            long leftover = order.getRemainingQuantity();
            boolean resting = book.contains(order.getId());
            switch (order.getTimeInForce()) {
                case GTC -> require(resting == (leftover > 0),
                        "GTC order should rest exactly when it has quantity left: " + order);
                case IOC -> require(!resting, "IOC order must never rest: " + order);
                case FOK -> require(!resting && (made.isEmpty() || leftover == 0),
                        "FOK order must fill completely or not at all: " + order);
            }
            if (!resting) {
                ledger(order.getId()).cancelled += leftover;
            }
        }

        private void submitMarket(Order order) {
            marketOrders++;
            ledger(order.getId()).added += order.getOriginalQuantity();
            List<Trade> made = engine.submitMarket(order);
            record(made);
            verifyPriority(made, order.getSide(), restingInfo);
            require(!book.contains(order.getId()), "market order must never rest: " + order);
            ledger(order.getId()).cancelled += order.getRemainingQuantity();
        }

        private void cancel(long orderId) {
            cancels++;
            Order live = book.restingOrder(orderId);
            require(live != null, "generator picked order " + orderId + " but it is not resting");
            long remaining = live.getRemainingQuantity();
            require(engine.cancel(orderId), "cancel of resting order " + orderId + " returned false");
            ledger(orderId).cancelled += remaining;
        }

        private void modify(Operation.Modify m) {
            modifies++;
            Order before = book.restingOrder(m.orderId());
            require(before != null, "generator picked order " + m.orderId() + " but it is not resting");
            long oldPrice = before.getPriceInPaise();
            long oldRemaining = before.getRemainingQuantity();

            List<Trade> made = engine.modify(m.orderId(), m.newPrice(), m.newQuantity());
            record(made);
            verifyPriority(made, before.getSide(), restingInfo);
            Order after = book.restingOrder(m.orderId());
            if (after != null) { // still resting: its price and place in the queue may have changed
                restingInfo.put(m.orderId(), new RestingInfo(after.getPriceInPaise(), after.getSequence()));
            }

            Ledger ledger = ledger(m.orderId());
            boolean inPlace = m.newPrice() == oldPrice && m.newQuantity() <= oldRemaining;
            if (inPlace) {
                require(made.isEmpty(), "an in-place modify must not trade");
                ledger.cancelled += oldRemaining - m.newQuantity(); // the part given up
            } else {
                // cancelled and re-submitted as a new order: old quantity gone, new quantity added
                ledger.cancelled += oldRemaining;
                ledger.added += m.newQuantity();
                long tradedNow = made.stream().mapToLong(Trade::quantity).sum();
                require(book.contains(m.orderId()) == (m.newQuantity() - tradedNow > 0),
                        "re-submitted order should rest exactly when it has quantity left");
            }
        }

        private void record(List<Trade> made) {
            for (Trade trade : made) {
                trades.add(trade);
                tradedQuantity += trade.quantity();
                ledger(trade.buyOrderId()).filled += trade.quantity();
                ledger(trade.sellOrderId()).filled += trade.quantity();
            }
        }

        private Ledger ledger(long orderId) {
            return ledgers.computeIfAbsent(orderId, id -> new Ledger());
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalStateException(message);
            }
        }
    }

    /** Usage: Simulation [seed] [operations]. Defaults: seed 42, 100000 operations. */
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42;
        int operations = args.length > 1 ? Integer.parseInt(args[1]) : 100_000;

        Result r = run(seed, operations);

        System.out.printf("Seed:               %d%n", r.seed());
        System.out.printf("Operations:         %,d%n", r.operations());
        System.out.printf("Total orders:       %,d (limit %,d, market %,d)%n",
                r.ordersSubmitted(), r.limitOrders(), r.marketOrders());
        System.out.printf("Cancels / modifies: %,d / %,d%n", r.cancels(), r.modifies());
        System.out.printf("Trades:             %,d%n", r.trades().size());
        System.out.printf("Traded quantity:    %,d%n", r.tradedQuantity());
        System.out.printf("Final best bid/ask: %s / %s%n", r.bestBid(), r.bestAsk());
        System.out.printf("Resting orders:     %,d%n", r.restingOrders());
        System.out.printf("Conservation:       OK for %,d order ids%n", r.orderIdsChecked());
        System.out.printf("Time taken:         %,d ms (invariants checked after every operation)%n",
                r.elapsedNanos() / 1_000_000);
    }
}
