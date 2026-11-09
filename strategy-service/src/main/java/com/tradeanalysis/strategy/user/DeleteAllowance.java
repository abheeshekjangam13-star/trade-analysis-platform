package com.tradeanalysis.strategy.user;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deletes are soft (rows are hidden, not removed). Plans with a delete limit (Free: 4) may delete that many strategies
 * and portfolios in all; strategies and portfolios share one allowance. Admins and paid plans: no limit.
 */
@Component
public class DeleteAllowance {

    private final JdbcTemplate jdbc;

    public DeleteAllowance(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** How many strategies and portfolios this user has deleted. */
    public int used(Long userId) {
        if (userId == null) return 0;
        Integer n = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM strategy WHERE deleted_by_user_id = ?) "
                + "+ (SELECT COUNT(*) FROM portfolio WHERE deleted_by_user_id = ?)", Integer.class, userId, userId);
        return n == null ? 0 : n;
    }

    /** How many strategies this user owns now (deleted ones don't count). */
    public int strategiesOwned(Long userId) {
        if (userId == null) return 0;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM strategy WHERE created_by_user_id = ? AND deleted_at IS NULL",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    /** Refuses the delete when the caller's plan allowance is used up (admins and jobs: no limit). */
    public void check() {
        Long me = CurrentUser.id().orElse(null);
        CurrentUser.limitedPlan().ifPresent(plan -> {
            if (plan.maxDeletes() == Plan.UNLIMITED) return;
            if (used(me) >= plan.maxDeletes()) {
                Plan next = plan.next();
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You've used all " + plan.maxDeletes() + " deletes of your " + plan + " plan."
                                + (next == null ? "" : " Upgrade to " + next + " (₹" + next.pricePerMonth()
                                + "/month) to delete without a limit."));
            }
        });
    }
}
