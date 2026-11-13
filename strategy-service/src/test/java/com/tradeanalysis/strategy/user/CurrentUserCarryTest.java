package com.tradeanalysis.strategy.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserCarryTest {

    @AfterEach
    void clear() { CurrentUser.clear(); }

    @Test
    void aPoolThreadRunsAsTheCallerAndIsClearedAfter() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertThat(pool.submit(CurrentUser::unrestricted).get()).isTrue();   // a bare pool thread has no caller

            CurrentUser.set(42L, false, Plan.FREE);
            record Seen(Long id, boolean unrestricted, Plan plan) {}
            Seen seen = pool.submit(CurrentUser.carry(() ->
                    new Seen(CurrentUser.id().orElse(null), CurrentUser.unrestricted(), CurrentUser.limitedPlan().orElse(null)))).get();
            assertThat(seen).isEqualTo(new Seen(42L, false, Plan.FREE));

            assertThat(pool.submit(() -> CurrentUser.id().isEmpty()).get()).isTrue();   // the thread is left as it was
        } finally { pool.shutdownNow(); }
    }
}
