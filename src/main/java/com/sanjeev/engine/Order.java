package com.sanjeev.engine;

/**
 * A limit order: "I want to {side} {quantity} units at {priceInPaise} or better."
 *
 * <p>Price is a long in paise (Rs 1 = 100 paise). Never a double: floating point
 * cannot represent values like 0.10 exactly, and money must be exact.
 *
 * <p>Only {@code remainingQuantity} changes over the life of an order (when it is
 * partly filled). Everything else is fixed, so the fields are final.
 */
public final class Order {

    /** Name used when the caller does not say who placed the order. */
    public static final String ANONYMOUS = "anonymous";

    private final long id;
    private final String trader;
    private final Side side;
    private final long priceInPaise;
    private final long originalQuantity;
    private final long sequence;
    private long remainingQuantity;

    /**
     * @param id           unique order id
     * @param side         BUY or SELL
     * @param priceInPaise limit price in paise, must be positive
     * @param quantity     number of units, must be positive
     * @param sequence     arrival number given by the engine; lower = earlier (time priority)
     */
    public Order(long id, Side side, long priceInPaise, long quantity, long sequence) {
        this(id, ANONYMOUS, side, priceInPaise, quantity, sequence);
    }

    /**
     * Same as above, plus the name of the trader who placed the order (shown on trades).
     *
     * @param trader who placed it, must not be null or blank
     */
    public Order(long id, String trader, Side side, long priceInPaise, long quantity, long sequence) {
        if (trader == null || trader.isBlank()) {
            throw new IllegalArgumentException("trader must not be null or blank");
        }
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        if (priceInPaise <= 0) {
            throw new IllegalArgumentException("price must be positive, got " + priceInPaise);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        this.id = id;
        this.trader = trader;
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

    public boolean isFullyFilled() {
        return remainingQuantity == 0;
    }

    public long getId() {
        return id;
    }

    public String getTrader() {
        return trader;
    }

    public Side getSide() {
        return side;
    }

    public long getPriceInPaise() {
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
        return "Order{id=" + id + ", " + trader + " " + side + " " + remainingQuantity + "/" + originalQuantity
                + " @ " + priceInPaise + "p, seq=" + sequence + "}";
    }
}
