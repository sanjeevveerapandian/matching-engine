package com.sanjeev.engine;

/** How an order wants to be traded. */
public enum OrderType {
    /** "Trade at this price or better; if not possible now, wait on the book." */
    LIMIT,
    /** "Trade right now at whatever price is available; never wait." */
    MARKET
}
