# Matching Engine

A stock-exchange matching engine in Java 21. It takes buy and sell orders, matches them by
**price-time priority**, keeps an exact replayable record of everything that happened, and can be
used over a REST API and a live web page.

Built phase by phase as a learning project. A seeded fuzz simulation checks the order book after
every single operation, and the test suite (650+ tests) is the safety net for every change.

## What you can do with it

| Area | Supported |
|---|---|
| Order types | **Limit** (GTC, IOC, FOK) and **market** (always IOC) |
| Order changes | **Cancel**, and **modify** (a size reduction keeps queue position; a price change or size increase loses it) |
| Matching | Best price first, then earliest arrival; the trade happens at the **resting** order's price |
| Output | An event stream: accepted, rejected, trade executed, cancelled, modified |
| Concurrency | Many producer threads, **one** engine thread, no locks around the engine |
| Durability | Every command goes to a JSON-lines log **before** it is applied (write-ahead log) |
| Recovery | Rebuild the exact book from the log, or from a snapshot plus the log after it |
| REST API | Place, cancel and modify orders; read the book and recent trades; run a traffic simulation |
| Live view | A web page with the order book ladder, a trade tape and an order form, fed over WebSocket |

## How it fits together

```
 HTTP threads ──submit──> [ bounded queue ] ──> ENGINE THREAD (the only one that touches the book)
                                                  1. give the command the next sequence number
                                                  2. append it to the log + flush        (write-ahead)
                                                  3. apply it to the matching engine
                                                  4. publish the events  ──> trade feed, book feed ──> WebSocket ──> browser
                                                  5. answer the caller
```

- `com.sanjeev.engine`: the pure core (order book, matching, events). Single-threaded. No Spring, no file or network IO.
- `com.sanjeev.infra`: commands, the write-ahead log, snapshots, crash recovery, and the sequencer (the single-writer thread).
- `com.sanjeev.api`: Spring Boot REST API, the WebSocket market-data feed, and the static page. It talks to the engine only through the sequencer.
- `com.sanjeev.sim`: the seeded random simulation and the crash-recovery demo.

## Getting started

Requires **Java 21** and **Maven**.

### Run the tests

```bash
mvn test
```

This also writes a coverage report to `target/site/jacoco/index.html` (open it in a browser).

### Run the simulation

Seeded random traffic (default seed 42, 100,000 operations). It checks the book after every operation
and checks that no quantity appears or vanishes. The same seed always gives the same run.

```bash
mvn compile
java -cp target/classes com.sanjeev.sim.Simulation 42 100000
```

### Run the server and the live page

```bash
mvn spring-boot:run
```

Then open <http://localhost:8080/>. Press **Simulate traffic** to fill the book, or place two
crossing orders yourself. Each run writes its log and snapshots to its own folder under `data/`.

### REST API

| Call | What it does |
|---|---|
| `POST /api/orders` | Place an order. Body: `{"trader":"Anu","side":"BUY","type":"LIMIT","price":"40.50","quantity":10}`. The price is a **string** in rupees; `timeInForce` is optional (`GTC` default). |
| `DELETE /api/orders/{id}` | Cancel a resting order. |
| `PATCH /api/orders/{id}` | Change a resting order. Body: `{"price":"40.60","quantity":8}` (both required). |
| `GET /api/book?depth=10` | The top levels of each side. |
| `GET /api/trades?limit=50` | The most recent trades, newest first. |
| `POST /api/simulate?count=1000` | Run random traffic through the real exchange (1 to 10,000 operations, optional `seed`). |

Every error comes back in one JSON shape (`status`, `error`, `message`, `details`).

### Live feed (WebSocket)

Connect to `ws://localhost:8080/ws` with STOMP and subscribe to `/topic/book` (top 10 levels per side,
at most every 100 ms) or `/topic/trades` (every trade, in order). The feed is read-only.

### Other commands

```bash
mvn compile exec:java                       # crash-recovery demo: kill a run, rebuild the book from disk
mvn -Pjmh compile exec:exec@jmh             # benchmarks (see BENCHMARKS.md)
```

## Design decisions

- **Prices are `long` paise**, never `double`, so money arithmetic is exact. Rupee text exists only at the HTTP edge.
- **Price-time priority**: best price first, then earliest arrival, and trades happen at the **resting** order's price.
- **Sorted primitive arrays per side** (best price last) instead of a `TreeMap`: no boxing, and the best price is O(1). About 23% faster in the benchmark.
- **A FIFO queue per price level** gives time priority without sorting.
- **An id-to-order `HashMap` index** makes finding an order to cancel O(1).
- **Market, IOC and FOK orders never rest**; the order book itself refuses to hold anything else than a good-till-cancelled limit order.
- **FOK counts available quantity before trading**, so a failed FOK changes nothing.
- **Modify loses queue position unless it only shrinks**, so nobody can jump the queue.
- **One writer thread** (the LMAX idea): no lock contention, and one reproducible order of events.
- **Log the command before applying it**; the engine is deterministic (no clock, no randomness), so replaying the log rebuilds the exact same state.
- **Snapshots are only a shortcut**; the log is the truth, and a damaged snapshot falls back to an older one.
- **Fail-stop**: if the log cannot be written, the engine stops and every waiting caller is released with an error. Nothing unlogged is ever applied.
- **The engine thread never waits on a network client**: the book feed is a throttled "something changed" flag, and trades go through a bounded queue to a separate thread.
- **The browser never talks to the engine**: it sends REST requests (which queue like any other command) and listens to a read-only feed.
- **Strict JSON in**: unknown fields, `10.5` for a whole number, and a number where the price string belongs are all refused.

## Testing approach

- Unit tests for every behaviour, with names that read as sentences.
- `BookInvariants` runs after every operation in the engine tests and in the simulation.
- A seeded simulation: same seed, same run, so any failure is replayable. It checks quantity conservation and price-time priority on every trade, and it was **mutation-checked**: six deliberate engine bugs were each caught by it.
- Concurrency tests (many producer threads), crash-and-replay tests, and a differential test that replays the log into a fresh engine and gets an identical book hash.
- Integration tests with a real server, a real WebSocket and a real STOMP client.
- Coverage is measured with JaCoCo on every `mvn test`.

## Known limitations

- **Self-trades are allowed** (the same trader can trade with themself). Real exchanges prevent this.
- **Duplicate order ids** are only rejected while the original order is resting.
- **Callers choose arrival numbers** for orders they build themselves (the engine hands out its own with `nextSequence()`). The engine rejects clashes, but a cleaner design would number orders itself.
- **Not fsynced**: the log is flushed to the operating system, not forced onto the disk, so a power cut could lose the last few lines (a process crash loses nothing).
- **The server does not resume an old log**: every start opens a fresh run folder. Recovery is demonstrated and tested, but the server does not yet continue from a previous run.
- **No authentication and one symbol**: anyone who can reach the port can trade, and there is a single market.
- **`totalQuantity()` and a level's `remove` are O(k)** for k orders at one price. A true O(1) cancel would need a linked list.
- **The engine is single-threaded by design**; scaling out would mean one engine per symbol.
