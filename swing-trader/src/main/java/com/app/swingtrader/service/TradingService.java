package com.app.swingtrader.service;

import com.app.swingtrader.repository.PositionRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class TradingService {

    private final PositionRepository positionRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final HttpHeaders apiHeaders = new HttpHeaders();

    @Value("${alpaca.api.key}")
    private String apiKey;

    @Value("${alpaca.api.secret}")
    private String apiSecret;

    @Value("${alpaca.api.data-url}")
    private String dataUrl;

    @Value("${trading.watchlist}")
    private String watchlist;

    public TradingService(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    public void performDailyScan() {
        System.out.println("--- [" + java.time.LocalDate.now() + "] Starting Daily Trading Scan ---");

        // Set up authentication headers for this scan
        this.apiHeaders.set("APCA-API-KEY-ID", apiKey);
        this.apiHeaders.set("APCA-API-SECRET-KEY", apiSecret);

        reviewOpenPositions();
        scanForNewTrades();

        System.out.println("--- Daily Trading Scan Complete ---");
    }

    private void reviewOpenPositions() {
        // TODO: Implement logic to review existing open positions.
        // 1. Get all OPEN positions from the database.
        // 2. For each position, fetch the latest price data.
        // 3. Check if any take-profit, stop-loss, or indicator-based sell signals are met.
        // 4. If so, place a sell order and update the position status to CLOSED in the database.
        System.out.println("Reviewing open positions...");
    }

    private void scanForNewTrades() {
        // Get the list of symbols from the watchlist property
        List<String> symbolsToScan = Arrays.asList(watchlist.split(","));
        System.out.println("Scanning watchlist for new trades: " + symbolsToScan);

        // Define strategy parameters (we can move these to application.properties later)
        int shortMaPeriod = 10;
        int longMaPeriod = 50;
        int rsiPeriod = 14;

        for (String symbol : symbolsToScan) {
            // Check if we already have an open position for this symbol
            if (positionRepository.findBySymbolAndStatus(symbol, com.app.swingtrader.model.PositionStatus.OPEN).isPresent()) {
                System.out.println("Already in a position for " + symbol + ", skipping scan.");
                continue;
            }

            // Fetch historical data to analyze (e.g., last 60 days to have enough data for a 50-day MA)
            List<AlpacaStockBar> bars = fetchHistoricalBars(symbol, 60);

            if (bars.size() < longMaPeriod + 1) { // Ensure we have enough data for all indicators
                System.out.println("Not enough historical data for " + symbol + ", skipping.");
                continue;
            }

            // --- STRATEGY LOGIC ---
            // 1. Calculate indicators
            double rsi = calculateRsi(bars, rsiPeriod);
            double shortMA = calculateMovingAverage(bars, shortMaPeriod);
            double longMA = calculateMovingAverage(bars, longMaPeriod);

            // 2. We also need the previous day's MAs to detect a crossover
            List<AlpacaStockBar> previousBars = bars.subList(0, bars.size() - 1);
            double prevShortMA = calculateMovingAverage(previousBars, shortMaPeriod);
            double prevLongMA = calculateMovingAverage(previousBars, longMaPeriod);

            System.out.printf("Analysis for %s: Short MA=%.2f, Long MA=%.2f, RSI=%.2f%n", symbol, shortMA, longMA, rsi);

            // 3. Check for the BUY signal
            boolean isBullishCrossover = shortMA > longMA && prevShortMA <= prevLongMA;
            boolean isRsiConfirmed = rsi > 50;

            if (isBullishCrossover && isRsiConfirmed) {
                System.out.println("!!! BUY SIGNAL DETECTED FOR " + symbol + " !!!");
                // TODO: In our next step, we will place the buy order and save the position.
            }
        }
    }

    private List<AlpacaStockBar> fetchHistoricalBars(String symbol, int limit) {
        System.out.println("Fetching " + limit + " daily bars for " + symbol + "...");
        // This Alpaca endpoint fetches historical data for stocks
        String url = String.format("%s/v2/stocks/bars?symbols=%s&timeframe=1Day&limit=%d",
                dataUrl, symbol, limit);

        HttpEntity<String> entity = new HttpEntity<>(apiHeaders);

        try {
            ResponseEntity<AlpacaStockBarsResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, AlpacaStockBarsResponse.class);

            if (response.getBody() != null && response.getBody().getBars() != null) {
                return response.getBody().getBars();
            }
        } catch (Exception e) {
            System.err.println("Error fetching historical data for " + symbol + ": " + e.getMessage());
        }
        return List.of(); // Return an empty list on failure
    }

    private double calculateMovingAverage(List<AlpacaStockBar> bars, int period) {
        if (bars.size() < period) {
            return 0.0;
        }
        // Get the most recent 'period' bars
        List<AlpacaStockBar> recentBars = bars.subList(bars.size() - period, bars.size());

        // Calculate the average of the closing prices
        return recentBars.stream()
                .mapToDouble(AlpacaStockBar::getClose)
                .average()
                .orElse(0.0);
    }

    private double calculateRsi(List<AlpacaStockBar> bars, int period) {
        if (bars.size() < period + 1) {
            return 0.0;
        }

        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();

        // Get the relevant slice of bars for the calculation
        List<AlpacaStockBar> relevantBars = bars.subList(bars.size() - (period + 1), bars.size());

        // Calculate gains and losses for each period
        for (int i = 1; i < relevantBars.size(); i++) {
            double change = relevantBars.get(i).getClose() - relevantBars.get(i - 1).getClose();
            if (change > 0) {
                gains.add(change);
                losses.add(0.0);
            } else {
                gains.add(0.0);
                losses.add(Math.abs(change));
            }
        }

        double avgGain = gains.stream().mapToDouble(d -> d).average().orElse(0.0);
        double avgLoss = losses.stream().mapToDouble(d -> d).average().orElse(0.0);

        if (avgLoss == 0) {
            return 100.0; // Max bullishness
        }

        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    // --- Helper classes for parsing Alpaca's Stocks API response ---

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AlpacaStockBarsResponse {
        private List<AlpacaStockBar> bars;
        @JsonProperty("next_page_token")
        private String nextPageToken;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AlpacaStockBar {
        @JsonProperty("t") // Timestamp
        private String timestamp;
        @JsonProperty("o") // Open
        private double open;
        @JsonProperty("h") // High
        private double high;
        @JsonProperty("l") // Low
        private double low;
        @JsonProperty("c") // Close
        private double close;
        @JsonProperty("v") // Volume
        private long volume;
    }
}