package com.tradeanalysis.marketdata.service;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.exception.NotFoundException;
import com.tradeanalysis.marketdata.client.YahooFinanceClient;
import com.tradeanalysis.marketdata.model.CandleEntity;
import com.tradeanalysis.marketdata.repository.CandleRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarketDataService {

    private final CandleRepository repo;
    private final YahooFinanceClient client;

    // Symbols that have been requested at least once — auto-refreshed.
    private final Set<String> watchedSymbols = ConcurrentHashMap.newKeySet();

    public MarketDataService(CandleRepository repo, YahooFinanceClient client) {
        this.repo = repo;
        this.client = client;
    }

    /** Returns a PriceSeries, fetching from upstream if the DB is empty. */
    @Transactional
    public PriceSeries getSeries(String symbol, Interval interval, int limit) {
        watchedSymbols.add(symbol.toUpperCase());
        String sym = symbol.toUpperCase();
        List<CandleEntity> entities = repo.findBySymbolAndIntervalCodeOrderByTimestampAsc(sym, interval.name());

        if (entities.isEmpty()) {
            // cold-start: fetch now
            List<Candle> fetched = client.fetch(sym, interval, limit);
            if (fetched.isEmpty()) throw new NotFoundException("No data found for symbol: " + sym);
            persist(sym, interval, fetched);
            entities = repo.findBySymbolAndIntervalCodeOrderByTimestampAsc(sym, interval.name());
        }

        List<Candle> candles = entities.stream().map(this::toDto)
                .limit(limit).toList();
        return new PriceSeries(sym, interval, candles);
    }

    @Transactional(readOnly = true)
    public PriceSeries getRange(String symbol, Interval interval, Instant from, Instant to) {
        String sym = symbol.toUpperCase();
        List<Candle> candles = repo.findInRange(sym, interval.name(), from, to)
                .stream().map(this::toDto).toList();
        if (candles.isEmpty()) throw new NotFoundException("No data in range for symbol: " + sym);
        return new PriceSeries(sym, interval, candles);
    }

    @Transactional(readOnly = true)
    public List<String> listSymbols() {
        return repo.findDistinctSymbols();
    }

    /** Refresh all watched symbols every 5 minutes. */
    @Scheduled(fixedDelayString = "${market-data.refresh-ms:300000}")
    @Transactional
    public void refresh() {
        for (String sym : watchedSymbols) {
            List<Candle> fetched = client.fetch(sym, Interval.DAY_1, 252);
            if (!fetched.isEmpty()) persist(sym, Interval.DAY_1, fetched);
        }
    }

    private void persist(String symbol, Interval interval, List<Candle> candles) {
        for (Candle c : candles) {
            boolean exists = repo
                    .findInRange(symbol, interval.name(), c.timestamp(), c.timestamp())
                    .stream().findFirst().isPresent();
            if (!exists) {
                repo.save(new CandleEntity(symbol, interval.name(),
                        c.timestamp(), c.open(), c.high(), c.low(), c.close(), c.volume()));
            }
        }
    }

    private Candle toDto(CandleEntity e) {
        return new Candle(e.getTimestamp(), e.getOpen(), e.getHigh(), e.getLow(), e.getClose(), e.getVolume());
    }
}
