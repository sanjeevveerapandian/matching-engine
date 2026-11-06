# Matching Engine

A stock-exchange matching engine in Java 21. It takes buy and sell orders, matches them
by **price-time priority**, and keeps an exact, replayable record of everything that happened.

Built phase by phase as a learning project, with a seeded fuzz simulation that checks the
order book after every single operation.

## Features

| Area | Supported |
|---|---|
| Order types | Limit (GTC, IOC, FOK) and market (always IOC) |
| Order changes | Cancel, and modify (a size reduction keeps queue position; a price change or size increase loses it) |
| Matching | Best price first, then earliest arrival; trades happen at the **resting** order's price |
| Output | An event stream: accepted, rejected, trade executed, cancelled, modified |
| Concurrency | Many producer threads, ONE engine thread, no locks around the engine |
| Durability | Every command is written to a JSON-lines log before it is applied (write-ahead log) |
| Safety nets | A book invariant checker and a seeded 100,000-operation simulation |

## How it fits together

```
 producer threads --submit--> [ bounded queue ] --> ENGINE THREAD
                                                     1. assign sequence number
                                                     2. append command to log + flush
                                                     3. apply to the matching engine
                                                     4. publish events
```

- `com.sanjeev.engine`: the pure core (order book, matching, events). Single-threaded, no IO, no libraries.
- `com.sanjeev.infra`: commands, the write-ahead log (Jackson), and the sequencer.
- `com.sanjeev.sim`: the seeded random simulation.

## Design decisions

- **Prices are `long` paise**, never `double`, so money arithmetic is exact.
- **`TreeMap` per side** (bids reverse-ordered), so the best price is always the first key.
- **A FIFO queue per price level** gives time priority without any sorting.
- **An id-to-order `HashMap` index** makes finding an order to cancel O(1).
- **A market order never rests**; IOC and FOK never rest either.
- **FOK counts available quantity before trading**, so a failed FOK changes nothing.
- **Modify loses queue position unless it only shrinks**, so nobody can jump the queue.
- **One writer thread** (the LMAX idea): no lock contention, and a reproducible order of events.
- **Log the command before applying it**, so replaying the log rebuilds the exact same state.
- **Events live with the engine**; infrastructure depends on the engine, never the reverse.

## Getting started

Requires Java 21 and Maven.

```bash
mvn test
```

Run the simulation (seed and operation count are optional, defaults are 42 and 100000):

```bash
mvn compile
java -cp target/classes com.sanjeev.sim.Simulation 42 100000
```

## Testing approach

- Unit tests for every behaviour, with names that read as sentences.
- An invariant checker (`BookInvariants`) that runs after every operation in the engine tests.
- A seeded simulation: same seed, same run, so any failure is replayable. It found a real
  sequence-number bug on its first run.
- Concurrency tests with four producer threads, and a test that replays the log into a
  fresh engine and gets identical events and an identical book.

## Known limitations

- Self-trades are allowed (the same trader can trade with themself).
- Duplicate order ids are only rejected while the original order is resting.
- The log is flushed to the OS, not fsynced, so a power cut could lose the last few lines.
- No snapshots or automatic restart recovery; replay is demonstrated in the tests.
- No network API; it is a library plus a simulation.
