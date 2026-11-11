package com.tradeanalysis.strategy.share;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/strategies")
@Tag(name = "Strategy sharing", description = "Admins share strategies with chosen users; accepting creates the user's own copy")
public class StrategyShareController {

    public record ShareRequest(List<Long> userIds, String note) {}

    private final StrategyShareService svc;

    public StrategyShareController(StrategyShareService svc) { this.svc = svc; }

    @PostMapping("/{id}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "ADMIN: share a strategy with chosen users (each gets an invitation to accept or decline)")
    public List<StrategyShareService.ShareView> share(@PathVariable Long id, @RequestBody ShareRequest body) {
        return svc.share(id, body == null ? null : body.userIds(), body == null ? null : body.note());
    }

    @GetMapping("/{id}/shares")
    @Operation(summary = "ADMIN: who a strategy was shared with and their answers")
    public List<StrategyShareService.ShareView> forStrategy(@PathVariable Long id) { return svc.forStrategy(id); }

    @GetMapping("/shares")
    @Operation(summary = "ADMIN: every strategy share and its answer, newest first")
    public List<StrategyShareService.ShareView> all() { return svc.all(); }

    @GetMapping("/shares/incoming")
    @Operation(summary = "Strategies shared with you (pending first)")
    public List<StrategyShareService.ShareView> mine() { return svc.mine(); }

    @PostMapping("/shares/{shareId}/accept")
    @Operation(summary = "Accept a shared strategy: creates your own copy (counts towards your plan's strategy limit)")
    public StrategyShareService.ShareView accept(@PathVariable Long shareId) { return svc.accept(shareId); }

    @PostMapping("/shares/{shareId}/decline")
    @Operation(summary = "Decline a shared strategy")
    public StrategyShareService.ShareView decline(@PathVariable Long shareId) { return svc.decline(shareId); }

    @DeleteMapping("/shares/{shareId}")
    @Operation(summary = "ADMIN: withdraw a share the user hasn't answered yet")
    public StrategyShareService.ShareView revoke(@PathVariable Long shareId) { return svc.revoke(shareId); }
}
