package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.strategy.compliance.ComplianceAudit;
import com.tradeanalysis.strategy.model.Strategy;
import com.tradeanalysis.strategy.service.StrategyService;
import com.tradeanalysis.strategy.user.TestCaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Every strategy mode change is in the audit trail, saved in the same transaction: one way, all at once, or a stop. */
class TradingServiceAuditTest {

    static final long ME = 7L;

    StrategyLiveConfigRepository configs = mock(StrategyLiveConfigRepository.class);
    StrategyService strategies = mock(StrategyService.class);
    LivePositionRepository positions = mock(LivePositionRepository.class);
    ComplianceAudit audit = mock(ComplianceAudit.class);
    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    TradingService trading;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(configs.findByStrategyIdAndUserId(anyLong(), eq(ME))).thenReturn(Optional.empty());
        when(configs.save(any())).thenAnswer(i -> i.getArgument(0));
        List<Strategy> list = new ArrayList<>();
        for (long id : new long[]{104, 137}) {
            Strategy s = mock(Strategy.class);
            when(s.getId()).thenReturn(id);
            when(s.getType()).thenReturn("NIFTY_OPENING_BREAKOUT");
            when(s.getParameters()).thenReturn(Map.of());
            when(strategies.get(id)).thenReturn(s);
            list.add(s);
        }
        when(strategies.list()).thenReturn(list);
        trading = new TradingService(configs, mock(TradingControlRepository.class), mock(TradeOrderRepository.class), positions,
                mock(OrderService.class), strategies, mock(com.tradeanalysis.strategy.user.AppUserRepository.class),
                mock(ObjectProvider.class), mock(BrokerAccounts.class), mock(com.tradeanalysis.strategy.repository.StrategyRepository.class),
                mock(LiveOrderBilling.class), mock(com.tradeanalysis.strategy.compliance.Compliance.class), audit, txManager);
        TestCaller.signIn(ME, true);
    }

    @AfterEach
    void tearDown() { TestCaller.signOut(); }

    @Test
    void aSingleSwitchIsRecordedWithTheSave() {
        trading.setMode(137L, TradingMode.PAPER, "MIS");

        InOrder order = inOrder(configs, audit, txManager);
        order.verify(configs).save(any());
        order.verify(audit).recordOrFail(ME, TradingService.MODE_ACTION, "strategy 137 PAPER MIS");
        order.verify(txManager).commit(any());
    }

    @Test
    void aPortfolioSwitchRecordsEveryStrategyItChanged() {
        trading.setModes(List.of(104L, 137L), TradingMode.PAPER, "MIS", "portfolio 12");

        verify(audit).recordOrFail(ME, TradingService.MODE_ACTION, "strategy 104 PAPER MIS (portfolio 12)");
        verify(audit).recordOrFail(ME, TradingService.MODE_ACTION, "strategy 137 PAPER MIS (portfolio 12)");
    }

    @Test
    void stopAllRecordsEachStrategyItTurnsOff() {
        StrategyLiveConfig live = new StrategyLiveConfig(137L, ME);
        live.set(TradingMode.LIVE, "MIS");
        StrategyLiveConfig off = new StrategyLiveConfig(104L, ME);
        when(configs.findByUserId(ME)).thenReturn(List.of(live, off));
        when(positions.findByStatus(any())).thenReturn(List.of());

        trading.stopMine();

        verify(audit).recordOrFail(ME, TradingService.MODE_ACTION, "strategy 137 OFF MIS (stop all)");
        verify(audit, times(1)).recordOrFail(anyLong(), anyString(), anyString());   // the OFF one is left alone
    }

    @Test
    void theKillSwitchRecordsWhoseStrategyItStopped() {
        StrategyLiveConfig usha = new StrategyLiveConfig(137L, 42L);
        usha.set(TradingMode.LIVE, "MIS");
        when(configs.findByModeNot(TradingMode.OFF)).thenReturn(List.of(usha));
        TradingControlRepository controls = mock(TradingControlRepository.class);
        when(positions.findByStatus(any())).thenReturn(List.of());
        // engageKillSwitch reads the controls first: give it a fresh service with a control row.
        TradingControl control = mock(TradingControl.class);
        when(controls.get()).thenReturn(control);
        TradingService t = new TradingService(configs, controls, mock(TradeOrderRepository.class), positions,
                mock(OrderService.class), strategies, mock(com.tradeanalysis.strategy.user.AppUserRepository.class),
                mockProvider(), mock(BrokerAccounts.class), mock(com.tradeanalysis.strategy.repository.StrategyRepository.class),
                mock(LiveOrderBilling.class), mock(com.tradeanalysis.strategy.compliance.Compliance.class), audit, txManager);

        t.engageKillSwitch();

        verify(audit).recordOrFail(ME, TradingService.MODE_ACTION, "strategy 137 OFF MIS user 42 (kill switch)");
    }

    @Test
    void whenTheRecordCantBeWrittenTheChangeIsNotKept() {
        doThrow(new RuntimeException("audit table unavailable")).when(audit).recordOrFail(anyLong(), anyString(), anyString());

        assertThatThrownBy(() -> trading.setMode(137L, TradingMode.PAPER, "MIS")).hasMessageContaining("audit table unavailable");

        verify(txManager).rollback(any(TransactionStatus.class));
        verify(txManager, never()).commit(any());
    }

    @Test
    void aPortfolioSwitchSkipsAStrategyWhoseRecordFailsAndKeepsNoChangeForIt() {
        doThrow(new RuntimeException("audit table unavailable")).when(audit)
                .recordOrFail(ME, TradingService.MODE_ACTION, "strategy 104 PAPER MIS (portfolio 12)");

        TradingService.BulkResult r = trading.setModes(List.of(104L, 137L), TradingMode.PAPER, "MIS", "portfolio 12");

        assertThat(r.changed()).hasSize(1);
        assertThat(r.skipped()).singleElement().satisfies(m -> assertThat(m.get("strategyId")).isEqualTo(104L));
        verify(txManager, times(1)).rollback(any(TransactionStatus.class));
        verify(txManager, times(1)).commit(any());
    }

    @SuppressWarnings("unchecked")
    static ObjectProvider<LiveStrategyRunner> mockProvider() { return mock(ObjectProvider.class); }
}
