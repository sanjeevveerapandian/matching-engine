# Matching Engine

A stock exchange matching engine I built in Java 21 to understand how exchanges actually work: how orders match, how you keep the order book correct, and how you get it back after a crash.

It handles one symbol, matches by price-time priority, logs every command to disk before applying it, and has a small live page where you can watch the book move.

**Live demo:** https://matching-engine-hwru.onrender.com
(Free Render instance. It sleeps when idle, so the first load can take up to a minute, and it starts empty after every restart. Hit **Simulate traffic** to fill the book.)

![Live order book after a simulated burst of 1000 orders](docs/images/live-demo.png)

## How it works

![Architecture sketch](docs/images/architecture.png)

The main idea is borrowed from LMAX: **only one thread ever touches the order book.**

HTTP requests don't call the engine. They drop a command on a bounded queue and wait. The sequencer thread takes commands one at a time, writes each one to the log, applies it to the engine, then publishes the resulting events. Because nothing else touches the book, the engine has no locks at all, and every run produces one exact order of events.

The browser never talks to the engine either. It sends REST requests and listens to a read-only WebSocket feed. Book updates are throttled to one every 100 ms so a slow browser can't slow down matching.

Code is split into four packages: `engine` (pure matching, no I/O, no Spring), `infra` (commands, log, snapshots, recovery, sequencer), `api` (REST, WebSocket feed, health check) and `sim` (seeded simulation and a crash demo).

## What it supports

- Limit orders (GTC, IOC, FOK) and market orders
- Cancel and modify. Reducing size keeps your place in the queue; changing price or increasing size loses it
- Trades happen at the resting order's price
- Write-ahead command log, snapshots, and recovery that rebuilds the exact same book
- REST API, WebSocket feed, health check, Docker image

## A few decisions worth explaining

**Prices are `long` paise, never `double`.** 0.10 can't be stored exactly as a double, and money has to be exact. The rupee string `"40.50"` only exists at the HTTP boundary.

**Log the command first, then apply it.** If the process dies mid-way, the log still has everything that was applied. If the log can't be written, the engine stops instead of applying something that isn't on disk.

**No clock and no randomness inside the engine.** Same commands in, same book out. That's what makes replay work, and a test checks it by comparing a SHA-256 fingerprint of the book.

**I replaced the `TreeMap`.** Price levels started as a `TreeMap<Long, PriceLevel>`. Profiling showed it was the biggest CPU cost (around 27%) and `Long` boxing was the biggest allocation, so I switched to sorted primitive arrays with an `ArrayDeque` per level. Adding a brand new price level is now O(P) instead of O(log P), which is fine for a few hundred levels and bad for millions.

## Benchmarks

JMH, engine only, on my laptop (Ryzen 5 5625U, Java 21, 1 GB heap). Synthetic mix: 60% limit, 10% market, 20% cancel, 10% modify, about 2,000 resting orders.

| | TreeMap (before) | Primitive arrays (after) |
|---|---|---|
| Throughput | ~4.8M ops/s | ~5.9M ops/s |
| Mean latency | ~300 ns | ~250 ns |
| Allocation per op | 480 bytes | 422 bytes |

About 23% faster after the change. These numbers don't include the queue, the JSON log or the network, so a full round trip is much slower. I haven't measured end to end yet. Full method, raw results and caveats are in [BENCHMARKS.md](BENCHMARKS.md).

## Testing

670 tests, 95.7% line coverage. The parts I'm most happy with:

- A **book invariant checker** runs after every operation in tests: no crossed book, no empty levels, index and book always agree.
- A **seeded simulation** runs 100,000 random operations and checks price-time priority and quantity conservation on every trade. It caught a real sequence-number bug on its first run. To check the simulation itself, I planted six deliberate bugs in the engine and it caught all six.
- **Crash tests** kill the sequencer, rebuild from the log (and from snapshots), and compare fingerprints. A half-written last log line is skipped; corruption anywhere else is refused.

## Run it

```bash
mvn test                # tests + coverage report in target/site/jacoco
mvn spring-boot:run     # then open http://localhost:8080
```

Or with Docker:

```bash
docker build -t matching-engine .
docker run --rm -p 8080:8080 matching-engine
```

Run just the simulation (seed 42, 100k ops):

```bash
mvn compile
java -cp target/classes com.sanjeev.sim.Simulation 42 100000
```

## API

| Call | What it does |
|---|---|
| `POST /api/orders` | Place an order, e.g. `{"trader":"Anu","side":"BUY","type":"LIMIT","price":"40.50","quantity":10}` |
| `DELETE /api/orders/{id}` | Cancel |
| `PATCH /api/orders/{id}` | Modify price or quantity |
| `GET /api/book?depth=10` | Top of book |
| `GET /api/trades?limit=50` | Recent trades |
| `POST /api/simulate?count=1000` | Push random traffic through the real exchange |

WebSocket (STOMP) at `/ws`, topics `/topic/book` and `/topic/trades`.

## What's missing

- One symbol, one process
- No auth or rate limiting, so anyone can trade on the demo
- Recovery is built and tested but not yet run on startup; each start begins a fresh log
- Self-trades are allowed (real exchanges block them)
- The log is flushed, not fsynced, so a power cut could lose the last few lines

Next on my list: one engine per symbol, a binary protocol instead of JSON, and trying a Disruptor-style ring buffer in place of the queue (and measuring it before claiming anything).

Deployment steps are in [docs/DEPLOY.md](docs/DEPLOY.md).
