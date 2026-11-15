package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.market.Underlying;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AngelScripMasterTest {

    private static final String JSON = """
            [{"token":"43912","symbol":"NIFTY25NOV2524000CE","name":"NIFTY","expiry":"25NOV2025","strike":"2400000.000000",
              "lotsize":"75","instrumenttype":"OPTIDX","exch_seg":"NFO","tick_size":"5.000000"},
             {"token":"43913","symbol":"NIFTY25NOV2524000PE","name":"NIFTY","expiry":"25NOV2025","strike":"2400000.000000",
              "lotsize":"75","instrumenttype":"OPTIDX","exch_seg":"NFO","tick_size":"5.000000"},
             {"token":"52001","symbol":"BANKNIFTY25NOV2552000CE","name":"BANKNIFTY","expiry":"25NOV2025","strike":"5200000.000000",
              "lotsize":"35","instrumenttype":"OPTIDX","exch_seg":"NFO","tick_size":"5.000000"},
             {"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE","expiry":"","strike":"-1.000000","lotsize":"1",
              "instrumenttype":"","exch_seg":"NSE","tick_size":"5.000000"},
             {"token":"60001","symbol":"FINNIFTY25NOV2524000CE","name":"FINNIFTY","expiry":"25NOV2025","strike":"2400000.000000",
              "lotsize":"65","instrumenttype":"OPTIDX","exch_seg":"NFO","tick_size":"5.000000"}]""";

    private final NiftyOptions options = mock(NiftyOptions.class);
    private final AngelScripMaster master = new AngelScripMaster(RestClient.builder(), options,
            Clock.fixed(Instant.parse("2025-11-20T04:00:00Z"), ZoneId.of("UTC")), "https://angel.test/scrip.json");

    private static final KiteApi.OptionInstrument KITE_CE =
            new KiteApi.OptionInstrument("NIFTY25N2524000CE", LocalDate.of(2025, 11, 25), 24000, "CE", 75, 0.05);

    @Test
    void keepsOnlyTheIndexOptionsStrategiesTrade() throws Exception {
        List<AngelScripMaster.Contract> list = AngelScripMaster.parse(new ByteArrayInputStream(JSON.getBytes(StandardCharsets.UTF_8)));
        assertThat(list).extracting(AngelScripMaster.Contract::token).containsExactly("43912", "43913", "52001");
        assertThat(list.get(0)).isEqualTo(new AngelScripMaster.Contract("43912", "NIFTY25NOV2524000CE", "NIFTY",
                LocalDate.of(2025, 11, 25), 24000, "CE", 75));
    }

    @Test
    void translatesAKiteSymbolToAngelsAndBack() throws Exception {
        master.load(AngelScripMaster.parse(new ByteArrayInputStream(JSON.getBytes(StandardCharsets.UTF_8))));
        when(options.find(Underlying.NIFTY, "NIFTY25N2524000CE")).thenReturn(Optional.of(KITE_CE));
        when(options.all(Underlying.NIFTY)).thenReturn(List.of(KITE_CE));

        AngelScripMaster.Contract c = master.toAngel("NFO", "NIFTY25N2524000CE");
        assertThat(c.symbol()).isEqualTo("NIFTY25NOV2524000CE");
        assertThat(c.token()).isEqualTo("43912");
        assertThat(master.toKite("43912", "NIFTY25NOV2524000CE")).isEqualTo("NIFTY25N2524000CE");
        assertThat(master.toKite("99999", "SOMETHING")).isEqualTo("SOMETHING");   // unknown: Angel's name
    }

    @Test
    void refusesWhatAngelOrdersHereDoNotSupport() throws Exception {
        master.load(AngelScripMaster.parse(new ByteArrayInputStream(JSON.getBytes(StandardCharsets.UTF_8))));
        when(options.find(Underlying.NIFTY, "NIFTY25N2524500CE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> master.toAngel("NSE", "RELIANCE")).hasMessageContaining("NFO index options only");
        assertThatThrownBy(() -> master.toAngel("NFO", "NIFTY25N2524500CE")).hasMessageContaining("Unknown contract");
    }
}
