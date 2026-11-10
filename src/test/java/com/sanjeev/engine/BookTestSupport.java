package com.sanjeev.engine;

/** Test helper: small questions about a book that several test classes ask. */
final class BookTestSupport {

    private BookTestSupport() { }

    /** Total quantity waiting at exactly this price on this side (0 if there is no such level). */
    static long quantityAt(OrderBook book, Side side, long price) {
        return book.depth(side, 100).stream()
                .filter(level -> level.priceInPaise() == price)
                .mapToLong(OrderBook.LevelSummary::totalQuantity)
                .sum();
    }
}
