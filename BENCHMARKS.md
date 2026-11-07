# Benchmarks

How fast is the **pure matching engine**, and what did one optimisation change?

**Short version.** On this laptop, with a synthetic but fairly realistic order mix, the engine
processes about **4.8 million operations per second** (about **300 ns per operation** on average).
Replacing the `TreeMap` that holds price levels with a primitive, boxing-free structure made
throughput about **23% higher** and cut allocation by about 12%. Read the
[limits](#what-this-benchmark-does-not-prove) before quoting any of these numbers.

## The machine

| | |
|---|---|
| Computer | Dell Vostro 5625 (laptop), plugged in, power plan "Balanced" |
| CPU | AMD Ryzen 5 5625U, 6 cores / 12 threads, 2.3 GHz base clock (Windows' figure; it boosts higher) |
| RAM | 15.4 GB total (about 2.9 GB free when measuring) |
| OS | Windows 11 Home (10.0.26300) |
| Java | HotSpot 64-bit Server VM 21.0.12.1 LTS |
| JMH | 1.37, run with its default (experimental "compiler") Blackholes |
| JVM flags | `-Xms1g -Xmx1g`, default garbage collector |
| Not controlled | Browsers, Zoom and a VPN were open; the CPU was about 11% busy before starting; no core pinning |

This is an ordinary busy laptop, not an isolated benchmark machine. That is why every comparison
below includes a control run.

## What is measured

The input is a seeded stream (`seed = 42`) from `OrderGenerator`:

- 60% limit orders (85% GTC, 10% IOC, 5% FOK), 10% market orders, 20% cancels, 10% modifies.
- Cancels and modifies only ever target orders that are really resting, so none of them is a cheap
  "unknown id" rejection. (My first workload did that: 29% of operations were rejections, which
  would have flattered the engine. I replaced it.)
- 80% of limit orders are *passive*: a buy goes below the mid price, a sell above it. With 0%
  passive the book held only 4 orders, because everything crossed or was cancelled at once. With
  80% the book holds about **2,000 resting orders at the start**, growing to about 3,000 during a
  10,000-operation batch, with about 0.3 trades per operation. I picked 80% by looking at book
  depth only, never at speed. (Depth at 0 / 60 / 80 / 90% passive: 4 / 404 / 1,964 / 3,046 orders.)
- Prices stay within 3000 paise plus or minus 5%.

Each benchmark starts every batch from the **same pre-built book** (20,000 operations of
history, restored from a snapshot). Building and restoring that book is **not timed**.

**Timed:** per operation, build the `Order`, run it through `MatchingEngine.process*`, and produce
the event list. **Not in the benchmark at all:** the sequencer thread, the queue, the JSON log,
`flush()`, snapshots, and anything that touches the network.

| | Throughput benchmark | Latency benchmark |
|---|---|---|
| JMH mode | `Throughput` | `SampleTime` |
| One invocation | a batch of 10,000 operations | exactly 1 operation |
| Reset | fresh engine before every batch (untimed) | fresh engine every 50,000 operations (untimed) |
| Warm-up | 5 iterations x 2 s | 5 iterations x 2 s |
| Measurement | 10 iterations x 2 s | 10 iterations x 2 s |
| Forks (fresh JVMs) | 3 | 3 |

**Why warm-up matters.** The JVM starts by interpreting code slowly and compiles hot methods
(the JIT) as it learns which ones matter. Early iterations measure the JIT warming up, not the
engine, so JMH runs warm-up iterations and discards them. **Why forks matter.** The JIT's
decisions depend on what happened earlier in that JVM, so one JVM can be lucky or unlucky;
three fresh JVMs expose that spread.

## Baseline

| Metric | Run 1 | Run 2 (control, same code, later) |
|---|---|---|
| Throughput | 4,849,313 +/- 117,794 ops/s | 4,782,590 +/- 362,972 ops/s |
| Mean latency | 320.5 ns | 287.4 ns |
| p50 | 200 ns | 200 ns |
| p90 | 500 ns | 400 ns |
| p95 | 600 ns | 500 ns |
| p99 | 900 ns | 900 ns |
| p99.9 | 5,200 ns | 4,496 ns |
| Allocation | 480.3 bytes per operation (about 2.0 GB/s) | not measured |

(`+/-` is JMH's 99.9% confidence interval over 30 iterations.) The two identical-code runs agree
to within 1.4% on throughput, but their mean latencies differ by 10%. That gap is the machine's
noise, and it sets how much latency difference I can honestly claim.

## Where the time and the allocations go (the profile)

Java Flight Recorder over about 100 million operations of the same workload (`ProfileRun`).
A profiler says *where*; JMH says *how much*. Inlining blurs exact attribution, so these are
rough shares.

**Allocation by class:** `java.lang.Long` 34.5% (the biggest), `Order` 14.7%, `Object[]` 13.9%,
`ArrayList` 8.9%, `Trade` 6.2%, `Event.OrderAccepted` 6.0%, `HashMap$Node` 4.4%. Almost all the
`Long` objects come from `Long.valueOf` inside `OrderBook`: ids boxed for the `HashMap`, and prices
boxed for the `TreeMap`. (The profile loop includes the engine reset, which the benchmark does not
time, so the share inside the timed region is somewhat smaller.)

**CPU time:** the `TreeMap` that holds the price levels (`getEntryUsingComparator`,
`computeIfAbsent`, `getEntry` and a few smaller frames) is about **27%**, the largest single cost.
The `HashMap` id index is about 6% (not counting resizing, which is the untimed reset).
`ArrayDeque.remove` (cancel scanning one price level) is about 5%.

## The one optimisation, and why

**Chosen: avoid boxing in the price-level map.** `TreeMap<Long, PriceLevel>` was replaced by
`PriceLevelMap`: two parallel sorted arrays (`long[]` prices, `PriceLevel[]` levels) with the best
price stored last, so lookups are a binary search over primitives and inserts and removals near the
best price shift almost nothing.

Why this one, against the three allowed options:

- **O(1) cached `totalQuantity`:** rejected. It does not appear in the profile (only FOK orders and
  printing use it), and it is unsafe, because `Order.fill` is public and an existing test fills an
  order directly and expects the level total to follow.
- **Reduce allocations** (list and event growth): about a quarter of the bytes, but allocation is
  cheap and those sites barely show in CPU time. Plausible, smaller payoff.
- **Avoid boxing:** `Long` is the largest allocated class and the price-level `TreeMap` is the
  largest CPU cost, so it was the strongest evidence-backed choice. I changed only the price levels,
  not the id `HashMap` (which would have been a second optimisation).

**The trade-off.** `TreeMap` inserts in O(log P). The sorted arrays insert a *new* level in O(P)
worst case, because elements shift. That is cheap for a few hundred price levels per side (what this
workload has) and would be a poor choice for millions of levels. Lookups remain O(log P).

## Before and after

Three runs, identical settings and workload: baseline run 1, the optimised code, and baseline run 2
(the original code again, in a scratch copy, as a drift control).

**Throughput (operations per second):**

| Run | Score | Per-fork means |
|---|---|---|
| Baseline, run 1 | 4,849,313 +/- 117,794 | 4.87 M, 4.91 M, 4.77 M |
| **After** | **5,908,741 +/- 298,680** | 5.67 M, 5.66 M, 6.40 M |
| Baseline, run 2 (control) | 4,782,590 +/- 362,972 | 4.41 M, 5.00 M, 4.94 M |

- After vs run 1: **+21.8%**. After vs run 2: **+23.5%**. After vs the mean of the two baselines
  (4,815,952): **+22.7%**.
- The slowest "after" fork (5.66 M) is faster than the fastest of the six "before" forks (5.00 M),
  so the improvement is larger than the noise.

**Latency per operation (nanoseconds, lower is better):**

| Metric | Baseline 1 | Baseline 2 | After | Change vs mean of baselines |
|---|---|---|---|---|
| Mean | 320.5 | 287.4 | 249.5 | **-17.9%** |
| p50 | 200 | 200 | 200 | no difference visible |
| p90 | 500 | 400 | 400 | within noise |
| p95 | 600 | 500 | 500 | within noise |
| p99 | 900 | 900 | 800 | within noise (one clock tick) |
| p99.9 | 5,200 | 4,496 | 3,100 | -36%, suggestive only |

Be careful with this table. The clock ticks in about 100 ns steps on Windows, so anything under a
microsecond is coarse. The two baseline runs differ from each other by 10% in the mean and by 14%
at p99.9, so differences of that size are **not** evidence of anything. Only the throughput gain and
the mean-latency gain (the "after" mean is below both baselines) are clearly larger than the noise.

**Allocation:** 480.3 -> 421.8 bytes per operation (**-12.2%**).

## What this benchmark does NOT prove

- **It is not the whole system.** It excludes the sequencer thread, the queue, JSON
  serialisation, the log write and its `flush()` after every command, snapshots, and any network.
  An end-to-end exchange would be much slower, because logging dominates.
- **The workload is synthetic.** It comes from my own generator, not real market data: one
  instrument, a narrow price band, about 300 price levels per side, and a fixed operation mix. The
  optimisation's win depends on that: with a very wide spread of prices (millions of levels) the
  O(P) inserts could make it slower.
- **It is highly repeatable, which flatters the engine.** The same 10,000 operations replay on
  every batch, so data stays in the CPU cache and branches are predictable. Real traffic is messier.
- **It is one machine.** A busy Windows laptop with browsers open, no pinned cores, and
  boosting clocks. Another machine could rank things differently. The ratio is more portable than
  the absolute numbers.
- **Latency here is "service time", not "response time".** Each operation is timed on its own with
  nothing waiting behind it. Real systems also have queueing delay when orders arrive faster than
  they are processed (the "coordinated omission" problem), which this does not model.
- **Tail latency is the least reliable number.** p99.9 and beyond are dominated by garbage
  collection, the OS scheduler and the timer, and moved by up to 14% between two identical runs.
- **A percentage is not a promise.** "+22.7%" means this benchmark, on this machine, on this
  workload. It does not mean the engine is "23% faster" in general.
- **I did not check the generated machine code**, so I can't rule out that the JIT treated
  some part of the benchmark specially. JMH's own warning about its experimental Blackholes applies.

## Reproduce

```bash
mvn -Pjmh compile exec:exec@jmh                                      # both benchmarks, full settings
mvn -Pjmh compile exec:exec@jmh "-Djmh.args=ThroughputBenchmark -prof gc"   # allocation per operation
mvn -Pjmh compile exec:exec@sanity                                   # prints what the workload does
mvn -Pjmh compile exec:exec@profile                                  # Flight Recorder run
```

For a cleaner measurement: plug in, choose the "Best performance" power plan, close other
programs, and run each configuration more than once.

Raw results are in `benchmarks/results/`: `baseline.json`, `after.json`, `baseline-rerun.json`
(JMH output with every iteration) and the matching console logs, plus the two GC profiles.
