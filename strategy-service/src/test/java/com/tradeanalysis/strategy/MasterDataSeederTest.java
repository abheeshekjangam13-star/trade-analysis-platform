package com.tradeanalysis.strategy;

import com.tradeanalysis.strategy.masterdata.MasterDataSeeder;
import com.tradeanalysis.strategy.model.Strategy;
import com.tradeanalysis.strategy.repository.StrategyRepository;
import com.tradeanalysis.strategy.service.StrategyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("it")
class MasterDataSeederTest {

    @Autowired MasterDataSeeder seeder;
    @Autowired StrategyRepository strategies;
    @Autowired StrategyService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void masterStrategiesAreRestoredWithTheirIdsAndParametersAndNewIdsFollowThem() throws Exception {
        // Seeded at startup on the empty test database.
        Strategy pe = strategies.findById(55L).orElseThrow();
        Strategy ce = strategies.findById(56L).orElseThrow();
        assertThat(pe.getParameters()).containsEntry("directions", "PE").containsEntry("breakevenLockPoints", "5")
                .containsEntry("failedBreakoutPoints", "10").containsEntry("maxStopPoints", "40").containsEntry("skipDays", "THU")
                .hasSize(17);
        assertThat(ce.getParameters()).containsEntry("directions", "CE").containsEntry("targetPoints", "80")
                .containsEntry("skipDays", "THU").hasSize(14);

        // Existing rows are left alone.
        assertThat(seeder.seed()).isZero();

        // Lost from the database → restored on the next run, same id and parameters.
        jdbc.update("DELETE FROM strategy_param WHERE strategy_id = 55");
        jdbc.update("DELETE FROM strategy WHERE id = 55");
        assertThat(seeder.seed()).isEqualTo(1);
        assertThat(strategies.findById(55L).orElseThrow().getParameters()).hasSize(17);

        // New strategies get ids after the master ones.
        Strategy created = service.create("after master data " + System.nanoTime(), null, "SMA_CROSSOVER", Map.of());
        assertThat(created.getId()).isGreaterThan(56L);
    }
}
