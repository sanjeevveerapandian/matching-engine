# Matching Engine: rules for Claude

## What this project is
A stock exchange matching engine in Java 21 + Maven, built in phases as a portfolio project.
I am Sanjeev, a fresher preparing for SDE-1 interviews. I must understand every line.

## Learning mode (most important)
- After creating or changing each file, stop and explain in simple English:
  what it does, why it is designed this way, what the alternatives were, and
  one interview question about it with a good answer.
- Use the vegetable market example (Ravi sells tomatoes, Anu buys) when it helps.
- Explain WHY, not just what. Keep it short.

## Engine rules (never break these)
- Prices are long in paise. Never use double or float for money.
- Price-time priority: best price first, then earliest sequence number.
- A trade always happens at the RESTING order's price.
- The core engine (package com.sanjeev.engine) is single-threaded and has
  no Spring, no file IO, no network code. Keep it pure.

## Testing rules
- Every new behaviour gets a JUnit 5 test. Test names describe behaviour in plain English.
- Run mvn test before every commit. All tests must pass.
- Never delete or weaken an existing test to make it pass. If a test seems wrong, ask me.

## Git rules
- One commit at the end of each phase, with a message in this exact format:

  Phase N: short description

  Do NOT add any Co-Authored-By or other Claude attribution line.

- Never push. I push myself.
- Never create or edit NOTES.md. I write it myself.

## End of every phase
- Stop. Do not start the next phase.
- Give me: a 5-line summary, a list of files changed, the command to run it,
  and 3 self-test questions (no answers).
