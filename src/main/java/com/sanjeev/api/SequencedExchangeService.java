package com.sanjeev.api;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.OrderType;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.Trade;
import com.sanjeev.infra.Command;
import com.sanjeev.infra.Sequencer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The exchange as the REST layer sees it. Every call goes through the {@link Sequencer}:
 * commands (place, cancel, modify) are numbered, logged and applied on its one engine thread;
 * reads (book, trades) are queries that also run on that thread. This class never touches the
 * engine itself.
 *
 * <p>Each call hands work to the engine thread and then WAITS (up to a timeout) on a future that
 * the engine thread completes. The calling thread is an HTTP worker thread, so blocking it is fine.
 */
final class SequencedExchangeService implements ExchangeService {

    private final Sequencer sequencer;
    private final RecentTrades recentTrades;
    private final Duration timeout;
    private final AtomicLong nextOrderId = new AtomicLong(1);

    /**
     * @param sequencer    a STARTED sequencer, whose publisher feeds {@code recentTrades}
     * @param recentTrades touched only on the engine thread
     * @param timeout      how long a request waits for the engine thread before giving up
     */
    SequencedExchangeService(Sequencer sequencer, RecentTrades recentTrades, Duration timeout) {
        this.sequencer = sequencer;
        this.recentTrades = recentTrades;
        this.timeout = timeout;
    }

    @Override
    public Domain.PlacedOrder placeOrder(Domain.NewOrderSpec spec) {
        long orderId = nextOrderId.getAndIncrement(); // unique across all HTTP threads
        Command command = spec.type() == OrderType.MARKET
                ? Command.NewOrder.market(orderId, spec.trader(), spec.side(), spec.quantity())
                : Command.NewOrder.limit(orderId, spec.trader(), spec.side(), spec.pricePaise(),
                        spec.quantity(), spec.timeInForce());

        Sequencer.Applied applied = await(submit(command));

        long filled = 0;
        long cancelled = 0;
        List<Trade> trades = new ArrayList<>();
        for (Event event : applied.events()) {
            switch (event) {
                case Event.OrderRejected rejected -> throw new OrderRejectedException(rejected.reason());
                case Event.TradeExecuted executed -> {
                    trades.add(executed.trade());
                    filled += executed.trade().quantity();
                }
                case Event.OrderCancelled c -> cancelled += c.cancelledQuantity();
                case Event.OrderAccepted a -> { }
                case Event.OrderModified m -> { }
            }
        }
        long resting = spec.quantity() - filled - cancelled; // whatever was neither traded nor dropped waits on the book
        return new Domain.PlacedOrder(orderId, applied.sequence(), spec.quantity(), filled, resting,
                cancelled, List.copyOf(trades));
    }

    @Override
    public Domain.CancelledOrder cancelOrder(long orderId) {
        Sequencer.Applied applied = await(submit(new Command.CancelOrder(orderId)));
        Event first = applied.events().get(0);
        if (first instanceof Event.OrderCancelled cancelled) {
            return new Domain.CancelledOrder(orderId, applied.sequence(), cancelled.cancelledQuantity());
        }
        throw new OrderNotFoundException(orderId); // OrderRejected: nothing resting under that id
    }

    @Override
    public Domain.ModifiedOrder modifyOrder(long orderId, long newPricePaise, long newQuantity) {
        Sequencer.Applied applied = await(submit(new Command.ModifyOrder(orderId, newPricePaise, newQuantity)));
        Event first = applied.events().get(0);
        if (first instanceof Event.OrderModified modified) {
            List<Trade> trades = new ArrayList<>();
            for (Event event : applied.events()) {
                if (event instanceof Event.TradeExecuted executed) {
                    trades.add(executed.trade());
                }
            }
            return new Domain.ModifiedOrder(orderId, applied.sequence(), modified.keptPriority(), List.copyOf(trades));
        }
        // The API validated the price and quantity already, so the only way the engine refuses a
        // modify is that no such order is resting.
        throw new OrderNotFoundException(orderId);
    }

    @Override
    public Domain.BookSnapshot book(int depth) {
        return await(query(engine -> new Domain.BookSnapshot(
                engine.book().depth(Side.BUY, depth),
                engine.book().depth(Side.SELL, depth),
                engine.book().bestBid(),
                engine.book().bestAsk())));
    }

    @Override
    public List<Trade> recentTrades(int limit) {
        return await(query(engine -> recentTrades.latest(limit)));
    }

    @Override
    public void close() {
        try {
            sequencer.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ talking to the sequencer

    private CompletableFuture<Sequencer.Applied> submit(Command command) {
        try {
            return sequencer.submitForResult(command);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExchangeUnavailableException("interrupted while handing the request over", e);
        } catch (IllegalStateException e) {
            throw new ExchangeUnavailableException("the exchange is not accepting requests: " + e.getMessage(), e);
        }
    }

    private <T> CompletableFuture<T> query(Function<MatchingEngine, T> reader) {
        try {
            return sequencer.query(reader);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExchangeUnavailableException("interrupted while handing the request over", e);
        } catch (IllegalStateException e) {
            throw new ExchangeUnavailableException("the exchange is not accepting requests: " + e.getMessage(), e);
        }
    }

    /** Wait for the engine thread's answer, but never forever. */
    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExchangeUnavailableException("interrupted while waiting for the exchange", e);
        } catch (TimeoutException e) {
            throw new ExchangeUnavailableException("the exchange did not answer within " + timeout, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ExchangeUnavailableException("the exchange stopped: " + cause.getMessage(), cause);
        }
    }
}
