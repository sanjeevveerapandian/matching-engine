package com.sanjeev.infra;

import com.sanjeev.engine.Event;
import java.util.List;

/**
 * Receives the events produced by each command. Called on the engine thread, in sequence
 * order, so a slow publisher slows the whole engine: keep implementations quick.
 */
@FunctionalInterface
public interface EventPublisher {

    /**
     * @param sequence the global sequence number of the command that produced these events
     * @param events   what happened, in the order it happened (never null, may be empty)
     */
    void publish(long sequence, List<Event> events);
}
