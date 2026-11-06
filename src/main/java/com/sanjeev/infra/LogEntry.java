package com.sanjeev.infra;

/**
 * One line of the command log: a command and the global sequence number it was given.
 * Replaying entries in sequence order rebuilds the exact same engine state.
 */
public record LogEntry(long sequence, Command command) { }
