package com.tradeanalysis.strategy.portfolio;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/portfolios")
@Tag(name = "Portfolios", description = "Groups of strategies and their combined, stored reports")
public class PortfolioController {

    public record PortfolioRequest(String name, String description, List<Long> strategyIds) {}

    private final PortfolioService svc;

    public PortfolioController(PortfolioService svc) { this.svc = svc; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a portfolio from existing strategies")
    public Portfolio create(@RequestBody PortfolioRequest body) {
        return svc.create(body.name(), body.description(), body.strategyIds());
    }

    @GetMapping
    @Operation(summary = "List portfolios")
    public List<Portfolio> list() { return svc.list(); }

    @GetMapping("/{id}")
    @Operation(summary = "Get a portfolio")
    public Portfolio get(@PathVariable Long id) { return svc.get(id); }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a portfolio: name, description and/or strategyIds (omitted = unchanged). Changing the "
            + "strategies deletes all its stored reports (and the backtests they ran): run a fresh report afterwards")
    public PortfolioService.UpdateResult update(@PathVariable Long id, @RequestBody PortfolioRequest body) {
        if (body == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to change");
        return svc.update(id, body.name(), body.description(), body.strategyIds());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a portfolio with all its stored reports (and the backtests they ran); its strategies stay")
    public void delete(@PathVariable Long id) { svc.delete(id); }

    @PostMapping("/{id}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Backtest every strategy for each window and store the combined report per window")
    public List<PortfolioReport> run(@PathVariable Long id,
                                     @RequestParam(defaultValue = "30,90,180,360") List<Integer> windows,
                                     @RequestParam(defaultValue = "100000") double capital,
                                     @RequestParam(defaultValue = "") String symbol) {
        return svc.run(id, windows, capital, symbol);
    }

    @GetMapping("/{id}/reports")
    @Operation(summary = "Stored reports of a portfolio, newest first")
    public List<PortfolioReport> reports(@PathVariable Long id) { return svc.reports(id); }
}
