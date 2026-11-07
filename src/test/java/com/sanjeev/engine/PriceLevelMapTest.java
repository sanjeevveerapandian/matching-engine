package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PriceLevelMapTest {

    private static PriceLevelMap bids() {
        return new PriceLevelMap(true);
    }

    private static PriceLevelMap asks() {
        return new PriceLevelMap(false);
    }

    private static List<Long> pricesBestFirst(PriceLevelMap map) {
        List<Long> prices = new ArrayList<>();
        for (PriceLevel level : map.bestFirst()) {
            prices.add(level.getPriceInPaise());
        }
        return prices;
    }

    // ---------------------------------------------------------------- the basics

    @Test
    void anEmptyMapHasNoBestLevelAndNothingToFind() {
        PriceLevelMap map = bids();

        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
        assertNull(map.best());
        assertNull(map.get(3000));
        assertFalse(map.bestFirst().iterator().hasNext());
    }

    @Test
    void forBidsTheBestPriceIsTheHighestAndForAsksItIsTheLowest() {
        PriceLevelMap bids = bids();
        PriceLevelMap asks = asks();
        for (long price : new long[] {2900, 3100, 3000}) {
            bids.getOrCreate(price);
            asks.getOrCreate(price);
        }

        assertEquals(3100, bids.bestPrice());
        assertEquals(3100, bids.best().getPriceInPaise());
        assertEquals(2900, asks.bestPrice());
        assertEquals(2900, asks.best().getPriceInPaise());
    }

    @Test
    void levelsAreListedBestFirstOnBothSides() {
        PriceLevelMap bids = bids();
        PriceLevelMap asks = asks();
        for (long price : new long[] {3300, 3100, 3200, 3000}) {
            bids.getOrCreate(price);
            asks.getOrCreate(price);
        }

        assertEquals(List.of(3300L, 3200L, 3100L, 3000L), pricesBestFirst(bids));
        assertEquals(List.of(3000L, 3100L, 3200L, 3300L), pricesBestFirst(asks));
    }

    @Test
    void worstFirstIsExactlyTheReverseOfBestFirst() {
        for (PriceLevelMap map : List.of(bids(), asks())) {
            for (long price : new long[] {3300, 3100, 3200, 3000}) {
                map.getOrCreate(price);
            }
            List<Long> worst = new ArrayList<>();
            for (PriceLevel level : map.worstFirst()) {
                worst.add(level.getPriceInPaise());
            }
            List<Long> reversed = new ArrayList<>(pricesBestFirst(map));
            java.util.Collections.reverse(reversed);

            assertEquals(reversed, worst);
        }
    }

    @Test
    void getOrCreateMakesALevelOnceAndThenReturnsTheSameOne() {
        PriceLevelMap map = asks();

        PriceLevel first = map.getOrCreate(3000);
        PriceLevel second = map.getOrCreate(3000);

        assertSame(first, second);
        assertEquals(3000, first.getPriceInPaise());
        assertEquals(1, map.size());
        assertSame(first, map.get(3000));
    }

    @Test
    void removingTheBestLevelMakesTheNextOneBest() {
        PriceLevelMap map = bids();
        map.getOrCreate(3000);
        map.getOrCreate(3100);

        PriceLevel removed = map.remove(3100);

        assertEquals(3100, removed.getPriceInPaise());
        assertEquals(3000, map.bestPrice());
        assertEquals(1, map.size());
    }

    @Test
    void removingAMiddleLevelKeepsTheOthersInOrder() {
        PriceLevelMap map = asks();
        for (long price : new long[] {3000, 3100, 3200, 3300}) {
            map.getOrCreate(price);
        }

        map.remove(3100);

        assertEquals(List.of(3000L, 3200L, 3300L), pricesBestFirst(map));
    }

    @Test
    void removingEveryLevelEmptiesTheMapAndRemovingAMissingOneReturnsNull() {
        PriceLevelMap map = bids();
        map.getOrCreate(3000);

        assertNull(map.remove(9999));
        assertEquals(3000, map.remove(3000).getPriceInPaise());
        assertTrue(map.isEmpty());
        assertNull(map.best());
        assertNull(map.remove(3000));
    }

    @Test
    void putReplacesAnExistingLevelAndReturnsTheOldOne() {
        PriceLevelMap map = bids();
        PriceLevel old = map.getOrCreate(3000);
        PriceLevel replacement = new PriceLevel(3000);

        assertSame(old, map.put(3000, replacement));
        assertSame(replacement, map.get(3000));
        assertEquals(1, map.size());
        assertNull(map.put(3100, new PriceLevel(3100)));
        assertEquals(2, map.size());
    }

    @Test
    void itKeepsWorkingWhenItGrowsFarBeyondItsStartingSize() {
        PriceLevelMap map = asks();
        Random random = new Random(3);
        List<Long> prices = new ArrayList<>();
        for (long p = 1; p <= 5_000; p++) {
            prices.add(p);
        }
        java.util.Collections.shuffle(prices, random);
        for (long price : prices) {
            map.getOrCreate(price);
        }

        assertEquals(5_000, map.size());
        assertEquals(1, map.bestPrice());
        long expected = 1;
        for (PriceLevel level : map.bestFirst()) {
            assertEquals(expected++, level.getPriceInPaise());
        }
        for (long p = 1; p <= 5_000; p++) {
            assertEquals(p, map.get(p).getPriceInPaise());
        }
    }

    // ---------------------------------------------------------------- the strong test: same answers as TreeMap

    private static void checkAgainstTreeMap(boolean bestIsHighest, long seed, int priceSpan) {
        PriceLevelMap map = new PriceLevelMap(bestIsHighest);
        TreeMap<Long, PriceLevel> reference =
                bestIsHighest ? new TreeMap<>(Comparator.reverseOrder()) : new TreeMap<>();
        Random random = new Random(seed);

        for (int i = 0; i < 100_000; i++) {
            long price = 2900 + random.nextInt(priceSpan);
            switch (random.nextInt(5)) {
                case 0, 1 -> {
                    PriceLevel mine = map.getOrCreate(price);
                    PriceLevel theirs = reference.computeIfAbsent(price, PriceLevel::new);
                    assertEquals(theirs.getPriceInPaise(), mine.getPriceInPaise());
                }
                case 2 -> assertEquals(reference.remove(price) != null, map.remove(price) != null);
                case 3 -> assertEquals(reference.get(price) != null, map.get(price) != null);
                default -> {
                    if (reference.isEmpty()) {
                        assertNull(map.best());
                    } else {
                        assertEquals(reference.firstKey().longValue(), map.bestPrice());
                        assertEquals(reference.firstKey().longValue(), map.best().getPriceInPaise());
                    }
                }
            }
            assertEquals(reference.size(), map.size());
        }
        assertEquals(new ArrayList<>(reference.keySet()), new ArrayList<>(map.keySet()));
    }

    @Test
    void afterAHundredThousandRandomOperationsBidsAnswerExactlyLikeATreeMap() {
        checkAgainstTreeMap(true, 11, 300);
    }

    @Test
    void afterAHundredThousandRandomOperationsAsksAnswerExactlyLikeATreeMap() {
        checkAgainstTreeMap(false, 12, 300);
    }

    @Test
    void theSameHoldsWithVeryFewDistinctPricesWhereLevelsAreCreatedAndRemovedConstantly() {
        checkAgainstTreeMap(true, 13, 5);
        checkAgainstTreeMap(false, 14, 5);
    }

    @Test
    void theSameHoldsWithAVeryWidePriceRange() {
        checkAgainstTreeMap(true, 15, 20_000);
        checkAgainstTreeMap(false, 16, 20_000);
    }

    // ---------------------------------------------------------------- the Map API used by invariants and tests

    @Test
    void theMapApiFindsLevelsByBoxedPriceAndIgnoresKeysOfTheWrongType() {
        PriceLevelMap map = bids();
        PriceLevel level = map.getOrCreate(3000);
        Object boxed = 3000L;

        assertSame(level, map.get(boxed));
        assertTrue(map.containsKey(boxed));
        assertNull(map.get("3000"));
        assertNull(map.get(null));
        assertFalse(map.containsKey(3001L));
        assertNull(map.remove("3000"));
        assertEquals(1, map.size());
    }

    @Test
    void theMapApiCanPutAndRemoveByBoxedPrice() {
        Map<Long, PriceLevel> map = asks();
        PriceLevel level = new PriceLevel(3000);

        assertNull(map.put(3000L, level));
        assertSame(level, map.get(3000L));
        assertSame(level, map.remove(3000L));
        assertTrue(map.isEmpty());
    }

    @Test
    void theMapApiRefusesNullKeysAndNullLevels() {
        Map<Long, PriceLevel> map = bids();

        assertThrows(NullPointerException.class, () -> map.put(null, new PriceLevel(3000)));
        assertThrows(NullPointerException.class, () -> map.put(3000L, null));
    }

    @Test
    void entriesAndValuesComeBestPriceFirstLikeTheTreeMapThisReplaced() {
        PriceLevelMap bids = bids();
        PriceLevelMap asks = asks();
        for (long price : new long[] {3100, 3000, 3200}) {
            bids.getOrCreate(price);
            asks.getOrCreate(price);
        }

        assertEquals(List.of(3200L, 3100L, 3000L), new ArrayList<>(bids.keySet()));
        assertEquals(List.of(3000L, 3100L, 3200L), new ArrayList<>(asks.keySet()));
        List<Long> valuePrices = new ArrayList<>();
        for (PriceLevel level : bids.values()) {
            valuePrices.add(level.getPriceInPaise());
        }
        assertEquals(List.of(3200L, 3100L, 3000L), valuePrices);
        assertEquals(3, bids.entrySet().size());
    }

    @Test
    void anEntryCannotBeModifiedThroughTheMapView() {
        PriceLevelMap map = bids();
        map.getOrCreate(3000);
        Map.Entry<Long, PriceLevel> entry = map.entrySet().iterator().next();

        assertThrows(UnsupportedOperationException.class, () -> entry.setValue(new PriceLevel(3000)));
    }

    @Test
    void aLevelMayBeFiledUnderAPriceThatDoesNotMatchItSoInvariantTestsCanBreakTheBookOnPurpose() {
        PriceLevelMap map = asks();
        PriceLevel level = new PriceLevel(3000);

        map.put(3100L, level);

        assertSame(level, map.get(3100));
        assertEquals(3100, map.bestPrice()); // keyed by where it was filed, not by what it says
        assertNull(map.get(3000));
    }

    @Test
    void zeroAndNegativeKeysAreStillSortedCorrectlyThroughTheMapApi() {
        Map<Long, PriceLevel> bids = bids();
        Map<Long, PriceLevel> asks = asks();
        for (long key : new long[] {0, -5, 5}) {
            bids.put(key, new PriceLevel(1));
            asks.put(key, new PriceLevel(1));
        }

        assertEquals(List.of(5L, 0L, -5L), new ArrayList<>(bids.keySet()));
        assertEquals(List.of(-5L, 0L, 5L), new ArrayList<>(asks.keySet()));
    }

    @Test
    void thePriceThatCannotBeNegatedIsRejectedInsteadOfSilentlyMisordered() {
        PriceLevelMap asks = asks();

        assertThrows(IllegalArgumentException.class, () -> asks.get(Long.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> asks.getOrCreate(Long.MIN_VALUE));
    }

    @Test
    void iteratorsReportTheEndProperly() {
        PriceLevelMap map = bids();
        map.getOrCreate(3000);
        Iterator<PriceLevel> levels = map.bestFirst().iterator();
        Iterator<PriceLevel> worst = map.worstFirst().iterator();
        Iterator<Map.Entry<Long, PriceLevel>> entries = map.entrySet().iterator();
        levels.next();
        worst.next();
        entries.next();

        assertThrows(NoSuchElementException.class, levels::next);
        assertThrows(NoSuchElementException.class, worst::next);
        assertThrows(NoSuchElementException.class, entries::next);
        assertNotNull(map.best()); // iterating never removes anything
    }
}
