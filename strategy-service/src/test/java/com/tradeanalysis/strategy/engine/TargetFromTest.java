package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.indicator.Params;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetFromTest {

    static Params p(String... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new Params(m);
    }

    @Test
    void theTargetIsMeasuredFromTheEntryUnlessSetToTheLine() {
        assertThat(TargetFrom.of(p())).isEqualTo(TargetFrom.ENTRY);
        assertThat(TargetFrom.of(p("targetFrom", "line"))).isEqualTo(TargetFrom.LINE);
        // #174 on a 20-point late entry: line 25,975, bought 25,995, 60-point target.
        assertThat(TargetFrom.ENTRY.target(true, 25995, 25975, 60)).isEqualTo(26055);
        assertThat(TargetFrom.LINE.target(true, 25995, 25975, 60)).isEqualTo(26035);
        assertThat(TargetFrom.LINE.target(false, 25731, 25754, 100)).isEqualTo(25654);   // #243's PE: 25,754 − 100
        assertThatThrownBy(() -> TargetFrom.of(p("targetFrom", "PIVOT"))).hasMessageContaining("ENTRY or LINE");
    }

    @Test
    void thereIsNoRoomOnlyWhenTheEntryIsAtOrPastATargetFromTheLine() {
        assertThat(TargetFrom.LINE.noRoom(true, 26035, 25975, 60)).isTrue();
        assertThat(TargetFrom.LINE.noRoom(true, 26034, 25975, 60)).isFalse();
        assertThat(TargetFrom.LINE.noRoom(false, 25690, 25754, 60)).isTrue();
        assertThat(TargetFrom.ENTRY.noRoom(true, 26100, 25975, 60)).isFalse();
        assertThat(TargetFrom.LINE.noRoom(true, 26100, 25975, 0)).isFalse();             // no target
    }

    @Test
    void strategiesWithoutAFixedLineRefuseIt() {
        assertThatThrownBy(() -> EmaCrossEngine.validateParams(p("referenceMode", "EMA_CROSS", "targetPoints", "50", "targetFrom", "LINE")))
                .hasMessageContaining("Target from the line needs a strategy that buys at a fixed line");
        assertThatThrownBy(() -> GapFillEngine.validateParams(p("referenceMode", "GAP_FILL", "targetFrom", "LINE")))
                .hasMessageContaining("gap-fill strategy has none");
        assertThatThrownBy(() -> NiftyOpeningBreakoutEngine.validateParams(p("targetR", "2", "targetFrom", "LINE")))
                .hasMessageContaining("target in points, not targetR");
    }
}
