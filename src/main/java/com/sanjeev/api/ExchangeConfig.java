package com.sanjeev.api;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.infra.EventLog;
import com.sanjeev.infra.EventPublisher;
import com.sanjeev.infra.Sequencer;
import com.sanjeev.infra.SnapshotStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the exchange: an engine, a command log, snapshots, a sequencer thread, and the service
 * the controllers use. This is the one place where the Spring-free engine and infrastructure are
 * wired up.
 *
 * <p>Each application run gets its own folder under {@code matching.data-dir}, because a log file
 * can only be started fresh (see EventLog) and the engine's state is not yet recovered at start-up.
 * Old runs' logs stay on disk and can be replayed with {@code Recovery}.
 */
@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
class ExchangeConfig {

    private static final DateTimeFormatter RUN_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /** Spring closes it (stopping the engine thread and closing the log) when the application stops. */
    @Bean(destroyMethod = "close")
    ExchangeService exchangeService(MatchingProperties properties) throws IOException {
        Path runDir = properties.dataDir().resolve("run-" + LocalDateTime.now().format(RUN_NAME));
        Files.createDirectories(runDir);

        MatchingEngine engine = new MatchingEngine();
        RecentTrades recentTrades = new RecentTrades(properties.recentTrades());
        EventPublisher publisher = (sequence, events) -> {
            for (Event event : events) { // runs on the engine thread, so no locking is needed
                if (event instanceof Event.TradeExecuted executed) {
                    recentTrades.add(executed.trade());
                }
            }
        };
        Sequencer sequencer = new Sequencer(engine, new EventLog(runDir.resolve("commands.log")), publisher,
                properties.queueCapacity(), new SnapshotStore(runDir.resolve("snapshots")),
                properties.snapshotEvery());
        sequencer.start();
        return new SequencedExchangeService(sequencer, recentTrades, properties.requestTimeout());
    }
}
