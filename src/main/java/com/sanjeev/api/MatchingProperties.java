package com.sanjeev.api;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings, read from {@code application.properties} (prefix {@code matching.}) or the command line.
 *
 * @param dataDir        where each run keeps its command log and snapshots (a new sub-folder per run)
 * @param snapshotEvery  take a snapshot after every this-many commands
 * @param queueCapacity  how many requests may wait for the engine thread before callers wait
 * @param requestTimeout how long an HTTP request waits for the engine thread before answering 503
 * @param recentTrades   how many trades {@code GET /api/trades} can look back over
 * @param bookPublishInterval at most one book update is pushed to WebSocket viewers per this long
 * @param bookDepth      price levels per side in a pushed book update
 * @param tradeFeedCapacity how many trades may wait to be pushed before new ones are dropped (the
 *                       engine thread never waits for slow viewers)
 */
@ConfigurationProperties(prefix = "matching")
public record MatchingProperties(
        @DefaultValue("data") Path dataDir,
        @DefaultValue("10000") long snapshotEvery,
        @DefaultValue("1024") int queueCapacity,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue("1000") int recentTrades,
        @DefaultValue("100ms") Duration bookPublishInterval,
        @DefaultValue("10") int bookDepth,
        @DefaultValue("10000") int tradeFeedCapacity) { }
