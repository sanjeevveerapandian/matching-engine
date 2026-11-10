package com.sanjeev.api;

import com.sanjeev.engine.Trade;

/**
 * What the engine thread tells the outside world about the market. Called ON THE ENGINE THREAD, so
 * every implementation must return immediately and never wait for anything: no network, no locks
 * that a slow client could hold. An implementation just leaves a note for another thread.
 */
interface MarketDataListener {

    /** A trade happened. */
    void onTrade(Trade trade);

    /** Something that may have changed the book happened. */
    void onBookChanged();
}
