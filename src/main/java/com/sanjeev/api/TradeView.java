package com.sanjeev.api;

import com.sanjeev.engine.Trade;

/** A trade as shown to callers: the price is rupees text ("40.50"), not paise. */
public record TradeView(long tradeId, long buyOrderId, long sellOrderId, String buyer, String seller,
                        String price, long quantity, long sequence) {

    static TradeView from(Trade trade) {
        return new TradeView(trade.tradeId(), trade.buyOrderId(), trade.sellOrderId(), trade.buyer(),
                trade.seller(), Money.paiseToRupees(trade.price()), trade.quantity(), trade.sequence());
    }
}
