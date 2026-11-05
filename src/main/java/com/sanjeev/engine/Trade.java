package com.sanjeev.engine;

/**
 * A completed trade: who bought, who sold, at what price, and how much.
 *
 * <p>A record is a small immutable data holder: once a trade happened, it never changes.
 *
 * @param tradeId     unique, increasing id given by the engine
 * @param buyOrderId  id of the buy order
 * @param sellOrderId id of the sell order
 * @param buyer       trader who bought
 * @param seller      trader who sold
 * @param price       price in paise: always the RESTING order's price
 * @param quantity    units traded
 * @param sequence    sequence number of the incoming order that caused this trade
 */
public record Trade(
        long tradeId,
        long buyOrderId,
        long sellOrderId,
        String buyer,
        String seller,
        long price,
        long quantity,
        long sequence) { }
