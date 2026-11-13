package com.tradeanalysis.strategy.service;

import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.repository.NiftyTradeLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A trade written by the batched insert reads back through JPA with every field it had. */
@SpringBootTest(properties = {"trading.runner.cron=-", "trading.exit-watch.initial-delay-ms=3600000"})
@ActiveProfiles("it")
class BacktestRowsTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired NiftyTradeLogRepository trades;

    @Test
    void everyTradeFieldSurvivesTheBatchedInsert() {
        long backtestId = 987_654L;
        NiftyTradeLog closed = new NiftyTradeLog(7L, "NIFTY 50", LocalDate.of(2026, 11, 5), 1, "BULLISH", 25_120.5, 25_080.25,
                Instant.parse("2026-11-05T03:45:00Z"), Instant.parse("2026-11-05T03:52:00Z"), 25_125.75, "CE", 25_100, 2, 65, 25_100.375);
        closed.setContext(0.42, 120.5);
        closed.close(Instant.parse("2026-11-05T06:10:00Z"), 25_190.0, "TARGET_HIT", 25_150.0);
        closed.setBacktestResultId(backtestId);
        NiftyTradeLog skipped = new NiftyTradeLog(7L, "NIFTY 50", LocalDate.of(2026, 11, 6), 2, "BEARISH", 1, 1,
                null, Instant.parse("2026-11-06T04:00:00Z"), 1, "PE", 25_000, 1, 65, 1);
        skipped.markSkipped("CAPITAL");
        skipped.setBacktestResultId(backtestId);

        new BacktestRows(jdbc).insertTrades(List.of(closed, skipped));

        List<NiftyTradeLog> back = trades.findByBacktestResultId(backtestId).stream()
                .sorted(java.util.Comparator.comparing(NiftyTradeLog::getTradeDate)).toList();
        assertThat(back).hasSize(2);
        assertThat(back.get(0).getId()).isNotNull();
        assertThat(back.get(0)).usingRecursiveComparison().ignoringFields("id", "createdAt").isEqualTo(closed);
        assertThat(back.get(1)).usingRecursiveComparison().ignoringFields("id", "createdAt").isEqualTo(skipped);
        assertThat(back.get(1).getReferenceTimestamp()).isNull();
        assertThat(back.get(1).getExitTimestamp()).isNull();
        assertThat(back.get(0).getCreatedAt()).isNotNull();
    }
}
