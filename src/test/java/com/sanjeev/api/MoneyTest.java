package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MoneyTest {

    // ---------------------------------------------------------------- rupees text -> paise

    @ParameterizedTest(name = "\"{0}\" rupees is {1} paise")
    @CsvSource({
            "40, 4000",
            "40.5, 4050",
            "40.50, 4050",
            "40.500, 4050",          // trailing zeros are not extra precision
            "0.01, 1",
            "0.1, 10",
            "007, 700",
            "1.10, 110",
            "19.99, 1999",           // 19.99 * 100 is 1998.9999999999998 in double arithmetic
            "1.15, 115",             // 1.15 * 100 is 114.99999999999999 in double arithmetic
            "0.07, 7",               // 0.07 * 100 is 7.000000000000001 in double arithmetic
            "100000000, 10000000000",
            "100000000.00, 10000000000"
    })
    void aPlainDecimalBecomesExactlyThatManyPaise(String rupees, long expectedPaise) {
        assertEquals(expectedPaise, Money.rupeesToPaise(rupees));
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {
            "abc", "-1", "+5", "1e2", "1E2", "4,000", " 40", "40 ", "4 0", "40.", ".5", "--1", "0x10",
            "٤٠"            // Arabic-Indic digits: only plain 0-9 are digits here
    })
    void textThatIsNotAPlainPositiveDecimalIsRefused(String rupees) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Money.rupeesToPaise(rupees));
        assertTrue(e.getMessage().contains("plain positive decimal"), e.getMessage());
    }

    @ParameterizedTest(name = "\"{0}\" has too many decimals")
    @ValueSource(strings = {"40.505", "40.001", "0.001", "1.234"})
    void moreThanTwoDecimalPlacesIsRefusedNotRounded(String rupees) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Money.rupeesToPaise(rupees));
        assertTrue(e.getMessage().contains("at most 2 decimal places"), e.getMessage());
    }

    @ParameterizedTest(name = "\"{0}\" is not above zero")
    @ValueSource(strings = {"0", "0.0", "0.00", "000"})
    void zeroIsRefused(String rupees) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Money.rupeesToPaise(rupees));
        assertTrue(e.getMessage().contains("greater than zero"), e.getMessage());
    }

    @Test
    void aPriceAboveTheMaximumIsRefusedAndTheMaximumItselfIsAccepted() {
        assertEquals(Money.MAX_PRICE_PAISE, Money.rupeesToPaise("100000000.00"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Money.rupeesToPaise("100000000.01"));
        assertTrue(e.getMessage().contains("must not exceed 100000000.00"), e.getMessage());
    }

    @Test
    void numbersTooBigForALongAreRefusedWithAClearMessageNotAnArithmeticError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Money.rupeesToPaise("99999999999999999999999999"));
        assertTrue(e.getMessage().contains("too large") || e.getMessage().contains("must not exceed"), e.getMessage());
    }

    @Test
    void absurdlyLongTextIsRefusedBeforeAnyNumberWorkIsDone() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Money.rupeesToPaise("1".repeat(31)));
        assertTrue(e.getMessage().contains("too long"), e.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void missingOrBlankTextIsRefused(String rupees) {
        assertThrows(IllegalArgumentException.class, () -> Money.rupeesToPaise(rupees));
    }

    // ---------------------------------------------------------------- paise -> rupees text

    @ParameterizedTest(name = "{0} paise is \"{1}\"")
    @CsvSource({"4050, 40.50", "5, 0.05", "0, 0.00", "100, 1.00", "10000000000, 100000000.00",
            "123456789, 1234567.89", "1, 0.01", "99, 0.99"})
    void paiseAlwaysShowExactlyTwoDecimals(long paise, String expected) {
        assertEquals(expected, Money.paiseToRupees(paise));
    }

    @Test
    void convertingToTextAndBackNeverChangesTheAmount() {
        Random random = new Random(7);
        for (int i = 0; i < 100_000; i++) {
            long paise = 1 + (long) (random.nextDouble() * Money.MAX_PRICE_PAISE);
            assertEquals(paise, Money.rupeesToPaise(Money.paiseToRupees(paise)), "round trip of " + paise);
        }
    }
}
