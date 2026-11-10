package com.sanjeev.api;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Converts between rupees as people write them ("40.50") and paise as the engine stores them
 * (4050, a long). This is the ONLY place in the system where a decimal number exists: it is used
 * at the HTTP edge and nowhere inside.
 *
 * <p>The rules are strict, because a price is money and "close enough" is a bug:
 * <ul>
 *   <li>the price must arrive as a JSON STRING, never a JSON number (numbers are parsed as
 *       doubles by many tools, and a double cannot hold 0.10 exactly);</li>
 *   <li>only digits and at most one decimal point: no sign, spaces, commas or exponent;</li>
 *   <li>at most 2 decimal places (a paisa is the smallest unit), so "40.505" is refused, not
 *       rounded;</li>
 *   <li>greater than zero and not above {@link #MAX_PRICE_PAISE}.</li>
 * </ul>
 */
final class Money {

    /** The highest price accepted: Rs 100,000,000.00 (10 crore). */
    static final long MAX_PRICE_PAISE = 10_000_000_000L;

    private static final Pattern PLAIN_DECIMAL = Pattern.compile("\\d+(\\.\\d+)?");
    private static final int MAX_TEXT_LENGTH = 30; // no work for absurdly long inputs

    private Money() { }

    /**
     * @param rupees text such as "40.50", "40.5" or "40"
     * @return the amount in paise
     * @throws IllegalArgumentException with a message fit to show the caller if the text is not
     *         an acceptable price
     */
    static long rupeesToPaise(String rupees) {
        if (rupees == null || rupees.isBlank()) {
            throw new IllegalArgumentException("price must not be empty");
        }
        if (rupees.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("price is too long");
        }
        if (!PLAIN_DECIMAL.matcher(rupees).matches()) {
            throw new IllegalArgumentException("price must be a plain positive decimal such as 40.50 "
                    + "(digits and one optional decimal point; no sign, spaces, commas or exponent)");
        }
        BigDecimal value = new BigDecimal(rupees); // exact: BigDecimal reads the digits, not a binary fraction
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("price may have at most 2 decimal places (paise)");
        }
        long paise;
        try {
            paise = value.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("price is too large");
        }
        if (paise <= 0) {
            throw new IllegalArgumentException("price must be greater than zero");
        }
        if (paise > MAX_PRICE_PAISE) {
            throw new IllegalArgumentException("price must not exceed " + paiseToRupees(MAX_PRICE_PAISE));
        }
        return paise;
    }

    /** Paise to rupees with exactly 2 decimals: 4050 becomes "40.50", 5 becomes "0.05". */
    static String paiseToRupees(long paise) {
        return BigDecimal.valueOf(paise, 2).toPlainString();
    }
}
