package com.sanjeev.infra;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Many threads submit work; ONE thread runs the engine.
 *
 * <pre>
 *   producer threads ---submit---> [ bounded queue ] ---take---> ENGINE THREAD
 *                                                                  1. give it the next sequence number
 *                                                                  2. append it to the log + flush   (write-ahead)
 *                                                                  3. apply it to the engine
 *                                                                  4. publish the events
 *                                                                  5. complete the caller's future
 * </pre>
 *
 * <p>The engine, the book and the sequence counter are touched ONLY by the engine thread, so
 * they need no locks at all. All the concurrency in the system is the queue.
 *
 * <p>Two kinds of work go through the queue:
 * <ul>
 *   <li>COMMANDS ({@link #submit}, {@link #submitForResult}): numbered, logged, applied.</li>
 *   <li>QUERIES ({@link #query}): read-only questions about the engine. They run on the engine
 *       thread too (the only safe place to read it), in queue order, but they are NOT numbered and
 *       NOT logged: reading changes nothing, so there is nothing to replay.</li>
 * </ul>
 *
 * <p>Sequence numbers are assigned by the engine thread as it takes commands, so they are
 * unique and gap-free by construction (1, 2, 3, ...), and they match the order in the log.
 * Commands from one producer keep that producer's order.
 *
 * <p>Fail-stop: if the log cannot be written, or anything throws while applying or publishing,
 * the engine thread stops and {@link #failure()} says why. We never apply a command that is
 * not safely in the log, and we never carry on with a possibly corrupt book. Work that is
 * waiting, and the command that was running, get their futures completed exceptionally so that
 * no caller waits forever.
 */
public final class Sequencer {

    /** What one command did: the number it was given and the events it produced. */
    public record Applied(long sequence, List<Event> events) { }

    /** One item of work on the queue. */
    private sealed interface Work permits CommandWork, QueryWork, Stop { }

    /** A command; {@code result} is null when the caller does not want to wait for it. */
    private record CommandWork(Command command, CompletableFuture<Applied> result) implements Work { }

    private record QueryWork<T>(Function<MatchingEngine, T> reader, CompletableFuture<T> result)
            implements Work { }

    /** Marks the end of the queue. */
    private record Stop() implements Work { }

    private static final Work STOP = new Stop();
    private static final long WAIT_MS = 50;

    private final BlockingQueue<Work> queue;
    private final MatchingEngine engine;
    private final CommandProcessor processor;
    private final EventLog log;
    private final EventPublisher publisher;
    private final Thread engineThread = new Thread(this::runLoop, "sequencer-engine");

    /** Producers hold the read lock while enqueueing; shutdown takes the write lock once. */
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();
    private boolean closed; // guarded by gate

    /** How often (in commands) a snapshot is taken unless you say otherwise. */
    public static final long DEFAULT_SNAPSHOT_EVERY = 10_000;

    private final SnapshotStore snapshots;       // null = no snapshots
    private final long snapshotEvery;

    private long lastSequence;                   // engine thread only
    private volatile Throwable failure;          // set by the engine thread, read by producers
    private volatile IOException snapshotFailure; // the latest snapshot that could not be written

    /**
     * A sequencer that takes no snapshots.
     *
     * @param queueCapacity how many items may wait; when full, producers wait (back-pressure)
     */
    public Sequencer(MatchingEngine engine, EventLog log, EventPublisher publisher, int queueCapacity) {
        this(engine, log, publisher, queueCapacity, null, DEFAULT_SNAPSHOT_EVERY);
    }

    /** A sequencer that snapshots every {@link #DEFAULT_SNAPSHOT_EVERY} commands. */
    public Sequencer(MatchingEngine engine, EventLog log, EventPublisher publisher, int queueCapacity,
                     SnapshotStore snapshots) {
        this(engine, log, publisher, queueCapacity, snapshots, DEFAULT_SNAPSHOT_EVERY);
    }

    /**
     * @param snapshots     where snapshots go, or null for none
     * @param snapshotEvery take a snapshot after every this-many commands (sequence 10000, 20000,
     *                      ... when 10000); must be positive
     */
    public Sequencer(MatchingEngine engine, EventLog log, EventPublisher publisher, int queueCapacity,
                     SnapshotStore snapshots, long snapshotEvery) {
        if (snapshotEvery <= 0) {
            throw new IllegalArgumentException("snapshotEvery must be positive, got " + snapshotEvery);
        }
        this.engine = engine;
        this.processor = new CommandProcessor(engine);
        this.log = log;
        this.publisher = publisher;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.snapshots = snapshots;
        this.snapshotEvery = snapshotEvery;
    }

    /** Start the engine thread. Call once. */
    public void start() {
        engineThread.start();
    }

    /**
     * Hand a command to the engine thread and do not wait for the outcome. Safe to call from any
     * number of threads. Blocks while the queue is full. Once this returns normally, the command
     * WILL be logged and applied (unless the sequencer fails).
     *
     * @throws IllegalStateException if the sequencer is shut down or has failed
     */
    public void submit(Command command) throws InterruptedException {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        enqueue(new CommandWork(command, null));
    }

    /**
     * Like {@link #submit}, but returns a future that the engine thread completes, after the command
     * has been logged, applied and published, with its sequence number and events. If the sequencer
     * fails first, the future completes exceptionally instead. Blocks only while the queue is full.
     *
     * @throws IllegalStateException if the sequencer is shut down or has failed
     */
    public CompletableFuture<Applied> submitForResult(Command command) throws InterruptedException {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        CompletableFuture<Applied> result = new CompletableFuture<>();
        enqueue(new CommandWork(command, result));
        return result;
    }

    /**
     * Ask a read-only question about the engine. {@code reader} runs ON THE ENGINE THREAD, in queue
     * order (so it sees the effect of every command submitted before it), and must return an
     * immutable copy of whatever it reads: never the engine's own mutable objects. The query is not
     * numbered or logged. If {@code reader} throws, only this future fails; the engine carries on.
     *
     * @throws IllegalStateException if the sequencer is shut down or has failed
     */
    public <T> CompletableFuture<T> query(Function<MatchingEngine, T> reader) throws InterruptedException {
        if (reader == null) {
            throw new IllegalArgumentException("reader must not be null");
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        enqueue(new QueryWork<>(reader, result));
        return result;
    }

    private void enqueue(Work work) throws InterruptedException {
        gate.readLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("sequencer is shut down");
            }
            throwIfFailed();
            while (!queue.offer(work, WAIT_MS, TimeUnit.MILLISECONDS)) {
                throwIfFailed(); // do not wait forever on a dead engine thread
            }
        } finally {
            gate.readLock().unlock();
        }
    }

    /**
     * Stop accepting work, process everything already accepted, then stop the engine thread
     * and close the log. Returns when the engine thread has finished.
     */
    public void shutdown() throws InterruptedException {
        gate.writeLock().lock(); // waits for producers mid-submit; after this none can enqueue
        try {
            closed = true;
        } finally {
            gate.writeLock().unlock();
        }
        // every accepted item is already ahead of STOP in the queue
        while (engineThread.isAlive() && !queue.offer(STOP, WAIT_MS, TimeUnit.MILLISECONDS)) {
            // queue full: the engine thread is still draining it
        }
        engineThread.join();
    }

    /** Why the engine thread stopped abnormally, if it did. */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    /**
     * The engine, for inspection AFTER the engine thread has finished. While it runs, the engine
     * belongs to that thread alone, so reading it from here would be a data race. (To read it
     * while running, use {@link #query}.)
     */
    public MatchingEngine engineAfterShutdown() {
        if (engineThread.isAlive()) {
            throw new IllegalStateException("the engine thread is still running");
        }
        return engine;
    }

    // ------------------------------------------------------------------ the engine thread

    private void runLoop() {
        Work current = null; // what we are working on, so a failure can complete its future
        try {
            while (true) {
                current = queue.take();
                switch (current) {
                    case Stop stop -> {
                        return;
                    }
                    case CommandWork work -> runCommand(work);
                    case QueryWork<?> query -> runQuery(engine, query);
                }
                current = null;
            }
        } catch (InterruptedException e) {
            failure = new IllegalStateException("engine thread was interrupted", e);
        } catch (IOException e) {
            failure = e; // could not write the log: nothing after this was applied
        } catch (Throwable t) {
            failure = t;
        } finally {
            Throwable cause = failure;
            if (cause != null) {
                failPending(current, cause);
            }
            try {
                log.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
    }

    private void runCommand(CommandWork work) throws IOException {
        long sequence = ++lastSequence;
        log.append(sequence, work.command());                           // 1. write-ahead
        List<Event> events = processor.apply(sequence, work.command()); // 2. apply
        publisher.publish(sequence, events);                            // 3. publish
        if (work.result() != null) {
            work.result().complete(new Applied(sequence, events));      // 4. answer the caller
        }
        if (snapshots != null && sequence % snapshotEvery == 0) {
            takeSnapshot(sequence);                                     // 5. now and then, a snapshot
        }
    }

    private static <T> void runQuery(MatchingEngine engine, QueryWork<T> query) {
        try {
            query.result().complete(query.reader().apply(engine));
        } catch (RuntimeException e) {
            query.result().completeExceptionally(e); // a bad reader fails only its own query
        }
    }

    /** After a fatal failure: nobody who is waiting may wait forever. */
    private void failPending(Work current, Throwable cause) {
        Throwable reason = new IllegalStateException("sequencer stopped after a failure: " + cause, cause);
        failWork(current, reason);
        Work waiting;
        while ((waiting = queue.poll()) != null) {
            failWork(waiting, reason);
        }
    }

    private static void failWork(Work work, Throwable reason) {
        switch (work) {
            case CommandWork command when command.result() != null ->
                    command.result().completeExceptionally(reason); // no effect if already completed
            case QueryWork<?> query -> query.result().completeExceptionally(reason);
            case null, default -> { }
        }
    }

    /**
     * A snapshot is only a shortcut for recovery (the log is the truth), so failing to write one
     * is NOT fatal: we remember the error and keep going. Taken on the engine thread, so it
     * pauses processing for the time it takes to write the file.
     */
    private void takeSnapshot(long sequence) {
        try {
            snapshots.write(sequence, engine);
        } catch (IOException e) {
            snapshotFailure = e;
        }
    }

    /** The most recent snapshot error, if any snapshot could not be written. */
    public Optional<IOException> lastSnapshotFailure() {
        return Optional.ofNullable(snapshotFailure);
    }

    private void throwIfFailed() {
        Throwable f = failure;
        if (f != null) {
            throw new IllegalStateException("sequencer stopped after a failure: " + f, f);
        }
    }
}
