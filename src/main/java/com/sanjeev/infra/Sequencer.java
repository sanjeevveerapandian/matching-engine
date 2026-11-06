package com.sanjeev.infra;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Many threads submit commands; ONE thread runs the engine.
 *
 * <pre>
 *   producer threads ---submit---> [ bounded queue ] ---take---> ENGINE THREAD
 *                                                                  1. give it the next sequence number
 *                                                                  2. append it to the log + flush   (write-ahead)
 *                                                                  3. apply it to the engine
 *                                                                  4. publish the events
 * </pre>
 *
 * <p>The engine, the book and the sequence counter are touched ONLY by the engine thread, so
 * they need no locks at all. All the concurrency in the system is the queue.
 *
 * <p>Sequence numbers are assigned by the engine thread as it takes commands, so they are
 * unique and gap-free by construction (1, 2, 3, ...), and they match the order in the log.
 * Commands from one producer keep that producer's order.
 *
 * <p>Fail-stop: if the log cannot be written, or anything throws while applying or publishing,
 * the engine thread stops and {@link #failure()} says why. We never apply a command that is
 * not safely in the log, and we never carry on with a possibly corrupt book.
 */
public final class Sequencer {

    /** Marks the end of the queue. Compared by identity (==); no client can create this object. */
    private static final Command STOP = new Command.CancelOrder(Long.MIN_VALUE);
    private static final long WAIT_MS = 50;

    private final BlockingQueue<Command> queue;
    private final MatchingEngine engine;
    private final CommandProcessor processor;
    private final EventLog log;
    private final EventPublisher publisher;
    private final Thread engineThread = new Thread(this::runLoop, "sequencer-engine");

    /** Producers hold the read lock while enqueueing; shutdown takes the write lock once. */
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();
    private boolean closed; // guarded by gate

    private long lastSequence;          // engine thread only
    private volatile Throwable failure; // set by the engine thread, read by producers

    /**
     * @param queueCapacity how many commands may wait; when full, producers wait (back-pressure)
     */
    public Sequencer(MatchingEngine engine, EventLog log, EventPublisher publisher, int queueCapacity) {
        this.engine = engine;
        this.processor = new CommandProcessor(engine);
        this.log = log;
        this.publisher = publisher;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    /** Start the engine thread. Call once. */
    public void start() {
        engineThread.start();
    }

    /**
     * Hand a command to the engine thread. Safe to call from any number of threads. Blocks while
     * the queue is full. Once this returns normally, the command WILL be logged and applied
     * (unless the sequencer fails).
     *
     * @throws IllegalStateException if the sequencer is shut down or has failed
     */
    public void submit(Command command) throws InterruptedException {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        gate.readLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("sequencer is shut down");
            }
            throwIfFailed();
            while (!queue.offer(command, WAIT_MS, TimeUnit.MILLISECONDS)) {
                throwIfFailed(); // do not wait forever on a dead engine thread
            }
        } finally {
            gate.readLock().unlock();
        }
    }

    /**
     * Stop accepting commands, process everything already accepted, then stop the engine thread
     * and close the log. Returns when the engine thread has finished.
     */
    public void shutdown() throws InterruptedException {
        gate.writeLock().lock(); // waits for producers mid-submit; after this none can enqueue
        try {
            closed = true;
        } finally {
            gate.writeLock().unlock();
        }
        // every accepted command is already ahead of STOP in the queue
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
     * belongs to that thread alone, so reading it from here would be a data race.
     */
    public MatchingEngine engineAfterShutdown() {
        if (engineThread.isAlive()) {
            throw new IllegalStateException("the engine thread is still running");
        }
        return engine;
    }

    // ------------------------------------------------------------------ the engine thread

    private void runLoop() {
        try {
            while (true) {
                Command command = queue.take();
                if (command == STOP) {
                    return;
                }
                long sequence = ++lastSequence;
                log.append(sequence, command);                           // 1. write-ahead
                List<Event> events = processor.apply(sequence, command); // 2. apply
                publisher.publish(sequence, events);                     // 3. publish
            }
        } catch (InterruptedException e) {
            failure = new IllegalStateException("engine thread was interrupted", e);
        } catch (IOException e) {
            failure = e; // could not write the log: nothing after this was applied
        } catch (Throwable t) {
            failure = t;
        } finally {
            try {
                log.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
    }

    private void throwIfFailed() {
        Throwable f = failure;
        if (f != null) {
            throw new IllegalStateException("sequencer stopped after a failure: " + f, f);
        }
    }
}
