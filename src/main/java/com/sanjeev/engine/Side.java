package com.sanjeev.engine;

/** Which side of the market an order is on. */
public enum Side {
    /** A buyer: wants to pay at most the limit price. */
    BUY,
    /** A seller: wants to receive at least the limit price. */
    SELL;

    /** The side an order of this side can trade against. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
