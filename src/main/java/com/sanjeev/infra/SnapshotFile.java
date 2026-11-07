package com.sanjeev.infra;

import com.sanjeev.engine.EngineSnapshot;

/**
 * The contents of one snapshot file.
 *
 * @param lastCommandSequence the sequence number of the last command included in this state
 * @param stateHash           {@code OrderBook.stateHash()} when it was taken; checked on load,
 *                            so a damaged snapshot is noticed instead of silently trusted
 * @param engine              the engine state itself
 */
public record SnapshotFile(long lastCommandSequence, String stateHash, EngineSnapshot engine) { }
