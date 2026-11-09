package com.tradeanalysis.strategy.market;

import com.tradeanalysis.common.market.Underlying;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnderlyingTest {

    @Test
    void niftyIsTheDefaultAndBankNiftyNamesResolve() {
        assertThat(Underlying.of(null)).isEqualTo(Underlying.NIFTY);
        assertThat(Underlying.of("")).isEqualTo(Underlying.NIFTY);
        assertThat(Underlying.of("junk")).isEqualTo(Underlying.NIFTY);
        assertThat(Underlying.of("BANKNIFTY")).isEqualTo(Underlying.BANKNIFTY);
        assertThat(Underlying.of("Bank Nifty")).isEqualTo(Underlying.BANKNIFTY);
        assertThat(Underlying.of("banknifty")).isEqualTo(Underlying.BANKNIFTY);
    }

    @Test
    void strikesAreOneStepFromAtm() {
        assertThat(Underlying.NIFTY.strike(true, 25812)).isEqualTo(25750);      // ATM 25,800 − 50
        assertThat(Underlying.NIFTY.strike(false, 25812)).isEqualTo(25850);     // ATM 25,800 + 50
        assertThat(Underlying.BANKNIFTY.strike(true, 55230)).isEqualTo(55100);  // ATM 55,200 − 100
        assertThat(Underlying.BANKNIFTY.strike(false, 55230)).isEqualTo(55300); // ATM 55,200 + 100
    }

    @Test
    void lotsAndExpiryDifferByIndex() {
        assertThat(Underlying.NIFTY.defaultLotSize).isEqualTo(65);
        assertThat(Underlying.BANKNIFTY.defaultLotSize).isEqualTo(30);
        assertThat(Underlying.NIFTY.monthlyOnly).isFalse();
        assertThat(Underlying.BANKNIFTY.monthlyOnly).isTrue();
        assertThat(Underlying.BANKNIFTY.indexSymbol).isEqualTo("NIFTY BANK");
        assertThat(Underlying.BANKNIFTY.optionName).isEqualTo("BANKNIFTY");
    }
}
