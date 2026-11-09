package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.market.Underlying;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NiftyOptionsTest {

    static final LocalDate TUE = LocalDate.of(2026, 11, 3);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T04:00:00Z"), ZoneId.of("Asia/Kolkata"));

    final KiteApi kite = mock(KiteApi.class);
    final NiftyOptions options = new NiftyOptions(kite, CLOCK);

    @Test
    void loadsEachIndexSeparatelyAndResolvesItsOwnContracts() {
        when(kite.optionsOf(Underlying.NIFTY)).thenReturn(List.of(
                new KiteApi.OptionInstrument("NIFTY26N0324150CE", TUE, 24150, "CE", 65, 0.05)));
        when(kite.optionsOf(Underlying.BANKNIFTY)).thenReturn(List.of(
                new KiteApi.OptionInstrument("BANKNIFTY26N0352000CE", TUE, 52000, "CE", 30, 0.05)));

        assertThat(options.resolve(Underlying.BANKNIFTY, "CE", 52000, TUE).tradingsymbol()).isEqualTo("BANKNIFTY26N0352000CE");
        assertThat(options.resolve(Underlying.NIFTY, "CE", 24150, TUE).lotSize()).isEqualTo(65);
        assertThat(options.find(Underlying.BANKNIFTY, "NIFTY26N0324150CE")).isEmpty();   // NIFTY's contract is not Bank Nifty's
        assertThat(options.chain(Underlying.BANKNIFTY, TUE)).hasSize(1);

        verify(kite, times(1)).optionsOf(Underlying.BANKNIFTY);   // cached for the day
        verify(kite, times(1)).optionsOf(Underlying.NIFTY);
    }

    @Test
    void namesTheIndexWhenItHasNoContract() {
        when(kite.optionsOf(any())).thenReturn(List.of());
        assertThatThrownBy(() -> options.resolve(Underlying.BANKNIFTY, "PE", 52000, TUE))
                .hasMessageContaining("No BANKNIFTY 52000 PE contract");
    }
}
