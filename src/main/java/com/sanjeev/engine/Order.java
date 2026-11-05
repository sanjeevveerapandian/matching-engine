package com.sanjeev.engine;

/**
 * An order: "I want to {side} {quantity} units", either at a limit price (LIMIT)
 * or at whatever price is available right now (MARKET).
 *
 * <p>Price is a long in paise (Rs 1 = 100 paise). Never a double: floating point
 * cannot represent values like 0.10 exactly, and money must be exact.
 *
 * <p>A MARKET order has NO price. Asking it for one throws, instead of returning a
 * made-up number like 0 that could silently corrupt matching.
 *
 * <p>Only the quantities change over the life of an order: {@code remainingQuantity}
 * on a fill, and both quantities when the owner reduces the order's size.
 */
public final class Order {

    /** Name used when the caller does not say who placed the order. */
    public static final String ANONYMOUS = "anonymous";

    /** Internal placeholder for market orders; never exposed through a getter. */
    private static final long NO_PRICE = 0;

    private final long id;
    private final String trader;
    private final OrderType type;
    private final TimeInForce timeInForce;
    private final Side side;
    private final long priceInPaise;
    private final long sequence;
    private long originalQuantity;
    private long remainingQuantity;

    /**
     * Creates a LIMIT order (time in force GTC) with no trader name.
     *
     * @param id           unique order id
     * @param side         BUY or SELL
     * @param priceInPaise limit price in paise, must be positive
     * @param quantity     number of units, must be positive
     * @param sequence     arrival number; lower = earlier (time priority)
     */
    public Order(long id, Side side, long priceInPaise, long quantity, long sequence) {
        this(id, ANONYMOUS, side, priceInPaise, quantity, sequence);
    }

    /**
     * Creates a LIMIT order (time in force GTC), plus the name of the trader who placed it.
     *
     * @param trader who placed it, must not be null or blank
     */
    public Order(long id, String trader, Side side, long priceInPaise, long quantity, long sequence) {
        this(id, trader, OrderType.LIMIT, TimeInForce.GTC, side, priceInPaise, quantity, sequence);
    }

    /** Creates a LIMIT order with an explicit time in force (GTC, IOC or FOK). */
    public static Order limit(long id, String trader, Side side, long priceInPaise,
                              long quantity, long sequence, TimeInForce timeInForce) {
        return new Order(id, trader, OrderType.LIMIT, timeInForce, side, priceInPaise, quantity, sequence);
    }

    /**
     * Creates a MARKET order: no price, trades immediately against what is on the book.
     * A market order is always IOC: what it cannot fill right now is cancelled.
     */
    public static Order market(long id, String trader, Side side, long quantity, long sequence) {
        return new Order(id, trader, OrderType.MARKET, TimeInForce.IOC, side, NO_PRICE, quantity, sequence);
    }

    private Order(long id, String trader, OrderType type, TimeInForce timeInForce, Side side,
                  long priceInPaise, long quantity, long sequence) {
        if (trader == null || trader.isBlank()) {
            throw new IllegalArgumentException("trader must not be null or blank");
        }
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        if (timeInForce == null) {
            throw new IllegalArgumentException("time in force must not be null");
        }
        if (type == OrderType.LIMIT && priceInPaise <= 0) {
            throw new IllegalArgumentException("price must be positive, got " + priceInPaise);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        this.id = id;
        this.trader = trader;
        this.type = type;
        this.timeInForce = timeInForce;
        this.side = side;
        this.priceInPaise = priceInPaise;
        this.originalQuantity = quantity;
        this.remainingQuantity = quantity;
        this.sequence = sequence;
    }

    /**
     * Reduce the unfilled quantity after a trade.
     *
     * @throws IllegalArgumentException if qty is not positive or exceeds what is left
     */
    public void fill(long qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive, got " + qty);
        }
        if (qty > remainingQuantity) {
            throw new IllegalArgumentException(
                    "cannot fill " + qty + " when only " + remainingQuantity + " remain");
        }
        remainingQuantity -= qty;
    }

    /**
     * Make this order smaller in place. The original quantity shrinks by the same amount,
     * so "original minus remaining" (what was already traded) stays correct.
     * Package-private: callers go through {@code MatchingEngine.modify}.
     *
     * @param newRemaining the new unfilled quantity: positive and LESS than the current one
     */
    void reduceQuantityTo(long newRemaining) {
        if (newRemaining <= 0 || newRemaining >= remainingQuantity) {
            throw new IllegalArgumentException("new quantity must be between 1 and "
                    + (remainingQuantity - 1) + ", got " + newRemaining);
        }
        originalQuantity -= remainingQuantity - newRemaining;
        remainingQuantity = newRemaining;
    }

    public boolean isFullyFilled() {
        return remainingQuantity == 0;
    }

    public long getId() {
        return id;
    }

    public String getTrader() {
        return trader;
    }

    public OrderType getType() {
        return type;
    }

    public TimeInForce getTimeInForce() {
        return timeInForce;
    }

    public Side getSide() {
        return side;
    }

    /**
     * The limit price in paise.
     *
     * @throws IllegalStateException for a MARKET order, which has no price
     */
    public long getPriceInPaise() {
        if (type == OrderType.MARKET) {
            throw new IllegalStateException("a market order has no price");
        }
        return priceInPaise;
    }

    public long getOriginalQuantity() {
        return originalQuantity;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }

    public long getSequence() {
        return sequence;
    }

    @Override
    public String toString() {
        String price = type == OrderType.MARKET ? "MARKET" : priceInPaise + "p";
        return "Order{id=" + id + ", " + trader + " " + side + " " + remainingQuantity + "/"
                + originalQuantity + " @ " + price + " " + timeInForce + ", seq=" + sequence + "}";
    }
}
