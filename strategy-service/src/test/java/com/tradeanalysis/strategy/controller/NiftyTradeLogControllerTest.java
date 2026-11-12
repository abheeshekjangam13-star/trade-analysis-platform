package com.tradeanalysis.strategy.controller;

import com.tradeanalysis.strategy.estimate.OptionEstimates;
import com.tradeanalysis.strategy.model.BacktestResult;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.repository.BacktestResultRepository;
import com.tradeanalysis.strategy.service.StrategyService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NiftyTradeLogControllerTest {

    private static NiftyTradeLog trade(Long backtestId, int lots) {
        NiftyTradeLog t = new NiftyTradeLog(7L, "NIFTY 50", LocalDate.of(2026, 11, 9), 1, "BULLISH", 100, 90,
                Instant.parse("2026-11-09T03:45:00Z"), Instant.parse("2026-11-09T04:00:00Z"), 25000, "CE", 25000, lots, 65, 24970);
        t.setBacktestResultId(backtestId);
        return t;
    }

    private static BacktestResult backtest(long id, double capital) {
        BacktestResult b = mock(BacktestResult.class);
        when(b.getId()).thenReturn(id);
        when(b.getInitialCapital()).thenReturn(capital);
        return b;
    }

    @Test
    void eachTradeCarriesTheCapitalItsBacktestRanWith() {
        StrategyService svc = mock(StrategyService.class);
        BacktestResultRepository repo = mock(BacktestResultRepository.class);
        // The same trade from two portfolio runs: ₹1L sized it at 2 lots, ₹5L at 8.
        NiftyTradeLog small = trade(11L, 2), big = trade(12L, 8), orphan = trade(null, 2);
        when(svc.getNiftyTradeLogsBetween(any(), any())).thenReturn(List.of(small, big, orphan));
        BacktestResult b11 = backtest(11L, 100_000), b12 = backtest(12L, 500_000);
        when(repo.findAllById(anyIterable())).thenReturn(List.of(b11, b12));

        List<NiftyTradeLog> out = new NiftyTradeLogController(svc, mock(OptionEstimates.class), repo).between(null, null);

        assertThat(out).extracting(NiftyTradeLog::getBacktestCapital).containsExactly(100_000.0, 500_000.0, null);
    }
}
