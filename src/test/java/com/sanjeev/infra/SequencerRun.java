package com.sanjeev.infra;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test helper: pushes a list of commands through a real {@link Sequencer} (log file, optional
 * snapshots) from the calling thread, waits for it to finish, and keeps what happened so a
 * test can compare it with what recovery produces.
 */
final class SequencerRun {

    final MatchingEngine engine;
    /** Index i holds the events of the command with sequence number i + 1. */
    final List<List<Event>> eventsBySequence = new ArrayList<>();

    private SequencerRun(MatchingEngine engine) {
        this.engine = engine;
    }

    /**
     * @param store the snapshot store, or null for no snapshots
     * @param every snapshot interval in commands (ignored when {@code store} is null)
     */
    static SequencerRun run(Path logFile, List<Command> commands, SnapshotStore store, long every)
            throws Exception {
        List<List<Event>> collected = new ArrayList<>(); // written by the engine thread only
        MatchingEngine engine = new MatchingEngine();
        Sequencer sequencer = new Sequencer(engine, new EventLog(logFile),
                (sequence, events) -> collected.add(events), 1024, store, every);
        sequencer.start();
        for (Command command : commands) {
            sequencer.submit(command);
        }
        sequencer.shutdown();
        if (sequencer.failure().isPresent()) {
            throw new IllegalStateException("the sequencer failed", sequencer.failure().get());
        }
        SequencerRun run = new SequencerRun(sequencer.engineAfterShutdown());
        run.eventsBySequence.addAll(collected); // safe: the engine thread has finished
        return run;
    }

    /** The events of commands {@code fromSequence + 1} onwards, as one flat list. */
    List<Event> eventsAfter(long fromSequence) {
        List<Event> flat = new ArrayList<>();
        for (int i = (int) fromSequence; i < eventsBySequence.size(); i++) {
            flat.addAll(eventsBySequence.get(i));
        }
        return flat;
    }

    /** A brand-new engine fed the first {@code count} commands directly, with no files involved. */
    static MatchingEngine stateAfter(List<Command> commands, int count) {
        MatchingEngine engine = new MatchingEngine();
        CommandProcessor processor = new CommandProcessor(engine);
        for (int i = 0; i < count; i++) {
            processor.apply(i + 1L, commands.get(i));
        }
        return engine;
    }
}
