package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.strategy.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The Trading page's live view: what each of your strategies is doing today, and your own stop button. */
@RestController
@RequestMapping("/api/trading")
public class CockpitController {

    private final StrategyStatusService status;
    private final TradingService trading;

    public CockpitController(StrategyStatusService status, TradingService trading) {
        this.status = status; this.trading = trading;
    }

    @GetMapping("/strategy-status")
    @Operation(summary = "Your PAPER/LIVE strategies today: in a trade (with its stop), done (with P&L), waiting (with the "
            + "levels that would trigger it and the distance from the index), or not trading today (with the reason)")
    public List<StrategyStatusService.Status> strategyStatus() {
        return status.forUser(CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first")));
    }

    @GetMapping("/today")
    @Operation(summary = "The Trading page's live view: today's Nifty 50 5-minute candles and your PAPER/LIVE strategies' status")
    public StrategyStatusService.Today today() {
        return status.today(CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first")));
    }

    @PostMapping("/strategies/{id}/stop")
    @Operation(summary = "Stop one of your strategies: it goes OFF for you and your open position in it is closed now")
    public TradingService.StopResult stopStrategy(@PathVariable Long id) { return trading.stopStrategy(id); }

    @PostMapping("/stop-mine")
    @Operation(summary = "Your own emergency stop: all your strategies OFF and your open strategy positions closed now")
    public TradingService.StopResult stopMine() { return trading.stopMine(); }
}
