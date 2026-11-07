package com.sanjeev.engine;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * The price levels of ONE side of the book, sorted by price, with no boxing.
 *
 * <p>It replaces {@code TreeMap<Long, PriceLevel>}. That map had to box every price into a
 * {@code Long} for each lookup, then chase pointers between boxed objects through a comparator.
 * This keeps the prices in a plain {@code long[]} and the levels in a parallel array, both sorted,
 * so a lookup is a binary search over contiguous primitives.
 *
 * <p>The array is sorted so that the BEST price is the LAST element: highest price for bids,
 * lowest for asks (asks are stored negated, which flips their order). Orders mostly arrive near
 * the best price, so inserting and removing near that end shifts almost nothing.
 *
 * <p>Costs: lookup O(log P). Insert and remove O(P) in the worst case, because elements shift
 * (a {@code TreeMap} is O(log P)). With a few hundred price levels per side that is cheap; with
 * millions of levels it would not be, and a {@code TreeMap} would be the better choice.
 *
 * <p>It is also a {@code Map<Long, PriceLevel>} (iterating best price first, like the
 * {@code TreeMap} it replaces) so that {@code BookInvariants} and the tests can inspect and
 * deliberately corrupt it. The engine itself only uses the primitive methods, which never box.
 * Not thread-safe: the engine is single-threaded.
 */
final class PriceLevelMap extends AbstractMap<Long, PriceLevel> {

    private static final int INITIAL_CAPACITY = 16;

    private final boolean bestIsHighest;
    private long[] sortKeys = new long[INITIAL_CAPACITY];       // ascending; best is the LAST one
    private PriceLevel[] levels = new PriceLevel[INITIAL_CAPACITY];
    private int size;

    /** @param bestIsHighest true for bids (highest price is best), false for asks (lowest is best) */
    PriceLevelMap(boolean bestIsHighest) {
        this.bestIsHighest = bestIsHighest;
    }

    // ------------------------------------------------------------------ the fast, primitive API

    /** The level at exactly this price, or null. O(log P), no boxing. */
    PriceLevel get(long price) {
        int index = search(sortKey(price));
        return index >= 0 ? levels[index] : null;
    }

    /** The level at this price, creating it if it does not exist yet. */
    PriceLevel getOrCreate(long price) {
        long key = sortKey(price);
        int index = search(key);
        if (index >= 0) {
            return levels[index];
        }
        PriceLevel level = new PriceLevel(price);
        insertAt(-index - 1, key, level);
        return level;
    }

    /** Put a level at this price, replacing any existing one. Returns the replaced level, or null. */
    PriceLevel put(long price, PriceLevel level) {
        long key = sortKey(price);
        int index = search(key);
        if (index >= 0) {
            PriceLevel old = levels[index];
            levels[index] = level;
            return old;
        }
        insertAt(-index - 1, key, level);
        return null;
    }

    /** Remove the level at this price. Returns it, or null if there was none. */
    PriceLevel remove(long price) {
        int index = search(sortKey(price));
        if (index < 0) {
            return null;
        }
        PriceLevel old = levels[index];
        int moved = size - index - 1;
        System.arraycopy(sortKeys, index + 1, sortKeys, index, moved);
        System.arraycopy(levels, index + 1, levels, index, moved);
        size--;
        levels[size] = null; // do not keep a stale reference alive
        return old;
    }

    /** The best level (the engine's next one to trade against), or null if there are none. O(1). */
    PriceLevel best() {
        return size == 0 ? null : levels[size - 1];
    }

    /** The best price. Only call when the map is not empty. O(1). */
    long bestPrice() {
        return price(sortKeys[size - 1]);
    }

    @Override
    public int size() {
        return size;
    }

    /** The levels, best price first. Read-only. */
    Collection<PriceLevel> bestFirst() {
        return new AbstractCollection<>() {
            @Override
            public Iterator<PriceLevel> iterator() {
                return new Iterator<>() {
                    private int next = size - 1;

                    @Override
                    public boolean hasNext() {
                        return next >= 0;
                    }

                    @Override
                    public PriceLevel next() {
                        if (next < 0) {
                            throw new NoSuchElementException();
                        }
                        return levels[next--];
                    }
                };
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    /** The levels, worst price first (the order an exchange ladder prints the ask side). Read-only. */
    Iterable<PriceLevel> worstFirst() {
        return () -> new Iterator<>() {
            private int next = 0;

            @Override
            public boolean hasNext() {
                return next < size;
            }

            @Override
            public PriceLevel next() {
                if (next >= size) {
                    throw new NoSuchElementException();
                }
                return levels[next++];
            }
        };
    }

    // ------------------------------------------------------------------ the Map API (tests and invariants)

    @Override
    public PriceLevel get(Object key) {
        return key instanceof Long price ? get(price.longValue()) : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public PriceLevel put(Long key, PriceLevel value) {
        if (key == null || value == null) {
            throw new NullPointerException("price and level must not be null");
        }
        return put(key.longValue(), value);
    }

    @Override
    public PriceLevel remove(Object key) {
        return key instanceof Long price ? remove(price.longValue()) : null;
    }

    /** Best price first, like the TreeMap this replaced. */
    @Override
    public Collection<PriceLevel> values() {
        return bestFirst();
    }

    /** Entries, best price first. The entries are snapshots and cannot be modified. */
    @Override
    public Set<Map.Entry<Long, PriceLevel>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<Long, PriceLevel>> iterator() {
                return new Iterator<>() {
                    private int next = size - 1;

                    @Override
                    public boolean hasNext() {
                        return next >= 0;
                    }

                    @Override
                    public Map.Entry<Long, PriceLevel> next() {
                        if (next < 0) {
                            throw new NoSuchElementException();
                        }
                        int index = next--;
                        return new SimpleImmutableEntry<>(price(sortKeys[index]), levels[index]);
                    }
                };
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    // ------------------------------------------------------------------ internals

    /** Prices are stored negated for asks, so "best is last" holds for both sides. */
    private long sortKey(long price) {
        if (price == Long.MIN_VALUE) {
            throw new IllegalArgumentException("price out of range: " + price); // cannot be negated
        }
        return bestIsHighest ? price : -price;
    }

    private long price(long sortKey) {
        return bestIsHighest ? sortKey : -sortKey;
    }

    /** Index of the key, or {@code -(insertion point) - 1} if absent (like Arrays.binarySearch). */
    private int search(long sortKey) {
        return Arrays.binarySearch(sortKeys, 0, size, sortKey);
    }

    private void insertAt(int index, long key, PriceLevel level) {
        if (size == sortKeys.length) {
            sortKeys = Arrays.copyOf(sortKeys, size * 2);
            levels = Arrays.copyOf(levels, size * 2);
        }
        int moved = size - index;
        System.arraycopy(sortKeys, index, sortKeys, index + 1, moved);
        System.arraycopy(levels, index, levels, index + 1, moved);
        sortKeys[index] = key;
        levels[index] = level;
        size++;
    }
}
