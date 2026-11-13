# Matching Engine

This is the part of a stock exchange that matches buy and sell orders. I wanted to know how it actually works, so I built one in Java.

Live demo: https://matching-engine-hwru.onrender.com
(It's on Render's free plan, so the first load can take about a minute. Click **Simulate traffic** to fill the order book.)

![Live demo](docs/images/live-demo.png)

## How it works

![Architecture](docs/images/architecture.png)

When you place an order, it goes like this:

1. The REST API receives it and puts it in a queue.
2. One thread, the sequencer, picks orders from the queue one by one.
3. It first writes the order to a log file on disk.
4. Then it gives the order to the matching engine, which finds a match in the order book.
5. The resulting trades go out to the browser over a WebSocket.

The key point is that **only one thread ever touches the order book**. So there are no locks and no race conditions. The idea comes from LMAX, an exchange that works the same way.

Matching follows the normal exchange rule: best price first, and if two orders have the same price, the one that came first wins.

Because every order is written to the log before it's applied, the engine can crash and come back with the exact same order book by replaying the log.

## Things I learned building it

- **Don't use `double` for money.** 0.1 + 0.2 is not 0.3 in floating point. I store prices as whole paise in a `long`.
- **Profile before you optimise.** I used a `TreeMap` for price levels, and profiling showed it was the slowest part. Replacing it with plain sorted arrays made the engine about 23% faster.
- **Check your benchmark, not just your code.** My first benchmark looked great, but almost a third of the orders were being rejected straight away, which made it look faster than it was. I fixed the test data and the numbers became honest.
- **Random testing finds real bugs.** I wrote a simulation that fires 100,000 random orders and checks the book after each one. It found a bug in sequence numbers on its very first run.

Benchmark details are in [BENCHMARKS.md](BENCHMARKS.md).

## Run it

You need Java 21 and Maven.

```bash
mvn test              # run the tests
mvn spring-boot:run   # start it, then open http://localhost:8080
```

Or with Docker:

```bash
docker build -t matching-engine .
docker run -p 8080:8080 matching-engine
```

Main API calls:

- `POST /api/orders` place an order
- `DELETE /api/orders/{id}` cancel it
- `GET /api/book` see the order book
- `GET /api/trades` see recent trades

## What's not done yet

- Only one stock symbol
- No login, so anyone can place orders on the demo
- Recovery from the log works in tests, but the server doesn't use it on startup yet

Next I want to support multiple symbols and try a ring buffer instead of the queue.
