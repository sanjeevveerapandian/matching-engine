package com.sanjeev.engine;

/** How long an order is allowed to live if it cannot trade completely right now. */
public enum TimeInForce {
    /** Good Till Cancelled: trade what you can now, rest the remainder on the book. */
    GTC,
    /** Immediate Or Cancel: trade what you can now, cancel the remainder. */
    IOC,
    /** Fill Or Kill: trade the WHOLE quantity right now, or do nothing at all. */
    FOK
}
