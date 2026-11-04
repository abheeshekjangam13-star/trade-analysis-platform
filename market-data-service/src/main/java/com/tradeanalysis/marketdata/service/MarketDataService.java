package com.tradeanalysis.marketdata.service;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.exception.NotFoundException;
import com.tradeanalysis.marketdata.client.KiteConnectClient;
import com.tradeanalysis.marketdata.model.CandleEntity;
import com.tradeanalysis.marketdata.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final CandleRepository repo;
    private final KiteConnectClient client;

    // Symbols that have been requested at least once — auto-refreshed.
    private final Set<String> watchedSymbols = ConcurrentHashMap.newKeySet();

    public MarketDataService(CandleRepository repo, KiteConnectClient client) {
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

        // Most recent `limit` candles, still oldest-first.
        List<Candle> candles = entities.stream().skip(Math.max(0, entities.size() - limit))
                .map(this::toDto).toList();
        return new PriceSeries(sym, interval, candles);
    }

    /** Returns candles in [from, to], backfilling from Kite any part of the range not yet stored. */
    @Transactional
    public PriceSeries getRange(String symbol, Interval interval, Instant from, Instant to) {
        String sym = symbol.toUpperCase();
        backfill(sym, interval, from, to);
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

    private void backfill(String symbol, Interval interval, Instant from, Instant to) {
        List<CandleEntity> stored = repo.findInRange(symbol, interval.name(), from, to);

        // Intraday bars not on an interval boundary (e.g. 11:15:58) came from an unaligned Kite
        // request; they never match the 09:15 reference bar and must be replaced.
        if (isIntraday(interval)) {
            long step = interval.duration().getSeconds();
            List<CandleEntity> misaligned = stored.stream()
                    .filter(e -> e.getTimestamp().getEpochSecond() % step != 0).toList();
            if (!misaligned.isEmpty()) {
                log.warn("Deleting {} misaligned {} candles for {}", misaligned.size(), interval, symbol);
                repo.deleteAll(misaligned);
                stored = repo.findInRange(symbol, interval.name(), from, to);
            }
        }

        if (stored.isEmpty()) {
            persist(symbol, interval, client.fetchRange(symbol, interval, from, to));
            return;
        }
        Instant first = stored.get(0).getTimestamp();
        Instant last  = stored.get(stored.size() - 1).getTimestamp();
        if (first.isAfter(from.plus(Duration.ofDays(4)))) { // tolerate weekends/holidays at range start
            persist(symbol, interval, client.fetchRange(symbol, interval, from, first));
        }
        if (last.plus(interval.duration()).isBefore(to)) {
            // Re-fetch the last stored bar too: it may have been saved while still forming.
            persist(symbol, interval, client.fetchRange(symbol, interval, last, to));
        }
    }

    private boolean isIntraday(Interval interval) {
        return interval != Interval.DAY_1 && interval != Interval.WEEK_1;
    }

    private void persist(String symbol, Interval interval, List<Candle> candles) {
        if (candles.isEmpty()) return;
        Instant min = candles.stream().map(Candle::timestamp).min(Instant::compareTo).get();
        Instant max = candles.stream().map(Candle::timestamp).max(Instant::compareTo).get();
        Map<Instant, CandleEntity> existing = new HashMap<>();
        repo.findInRange(symbol, interval.name(), min, max)
                .forEach(e -> existing.put(e.getTimestamp(), e));

        List<CandleEntity> toSave = new ArrayList<>();
        for (Candle c : candles) {
            CandleEntity e = existing.get(c.timestamp());
            if (e == null) {
                e = new CandleEntity(symbol, interval.name(),
                        c.timestamp(), c.open(), c.high(), c.low(), c.close(), c.volume());
                existing.put(c.timestamp(), e);
                toSave.add(e);
            } else if (e.update(c.open(), c.high(), c.low(), c.close(), c.volume())) {
                toSave.add(e);
            }
        }
        repo.saveAll(toSave);
    }

    private Candle toDto(CandleEntity e) {
        return new Candle(e.getTimestamp(), e.getOpen(), e.getHigh(), e.getLow(), e.getClose(), e.getVolume());
    }
}
