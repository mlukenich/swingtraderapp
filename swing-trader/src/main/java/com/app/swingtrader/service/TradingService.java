package com.app.swingtrader.service;

import com.app.swingtrader.repository.PositionRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    @Value("${trading.notional-trade-amount}")
    private double notionalTradeAmount;

    @Value("${alpaca.api.base-url}")
    private String baseUrl;

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
        // 1. Get all OPEN positions from our local database.
        List<com.app.swingtrader.model.Position> openPositions = positionRepository.findAllByStatus(com.app.swingtrader.model.PositionStatus.OPEN);
        System.out.println("Reviewing " + openPositions.size() + " open position(s)...");

        // Strategy parameters (can be moved to properties)
        int shortMaPeriod = 10;
        int longMaPeriod = 50;
        int rsiPeriod = 14;
        double takeProfitPercentage = 0.10; // 10%
        double atrMultiplier = 2.0;
        int atrPeriod = 14;

        for (com.app.swingtrader.model.Position position : openPositions) {
            String symbol = position.getSymbol();
            System.out.println("Checking position: " + symbol);

            // --- Portfolio Reconciliation Logic ---
            // If quantity is 0, this is a new position that needs to be updated with real data.
            if (position.getQuantity() == 0.0) {
                AlpacaPosition alpacaPosition = getAlpacaPosition(symbol);
                if (alpacaPosition != null) {
                    System.out.println("Reconciling new position for " + symbol);
                    position.setQuantity(Double.parseDouble(alpacaPosition.getQty()));
                    position.setEntryPrice(Double.parseDouble(alpacaPosition.getAvgEntryPrice()));
                    positionRepository.save(position);
                    System.out.println("Position for " + symbol + " updated with filled order details.");
                } else {
                    // Order may not have filled yet, or failed. We'll check again tomorrow.
                    System.out.println("Position for " + symbol + " not yet found on Alpaca. Will check again on next scan.");
                    continue; // Skip further analysis for this symbol today
                }
            }

            // 2. For each position, fetch the latest data.
            List<AlpacaStockBar> bars = fetchHistoricalBars(symbol, 60);
            if (bars.size() < longMaPeriod + 1) {
                System.out.println("Not enough historical data to review " + symbol);
                continue;
            }

            double currentPrice = bars.get(bars.size() - 1).getClose();
            double highPrice = bars.get(bars.size() - 1).getHigh();
            double lowPrice = bars.get(bars.size() - 1).getLow();

            boolean shouldSell = false;
            String sellReason = "";

            // A. Check for Take-Profit
            double takeProfitPrice = position.getEntryPrice() * (1 + takeProfitPercentage);
            if (highPrice >= takeProfitPrice) {
                shouldSell = true;
                sellReason = "Take-Profit target hit at $" + takeProfitPrice;
            }

            // B. Check for ATR Stop-Loss
            if (!shouldSell) {
                double atr = calculateAtr(bars, atrPeriod);
                double stopLossPrice = position.getEntryPrice() - (atr * atrMultiplier);
                if (lowPrice <= stopLossPrice) {
                    shouldSell = true;
                    sellReason = "ATR Stop-Loss triggered at $" + stopLossPrice;
                }
            }

            // C. Check for Indicator-based Sell Signal
            if (!shouldSell) {
                double rsi = calculateRsi(bars, rsiPeriod);
                double shortMA = calculateMovingAverage(bars, shortMaPeriod);
                double longMA = calculateMovingAverage(bars, longMaPeriod);
                List<AlpacaStockBar> previousBars = bars.subList(0, bars.size() - 1);
                double prevShortMA = calculateMovingAverage(previousBars, shortMaPeriod);
                double prevLongMA = calculateMovingAverage(previousBars, longMaPeriod);

                boolean isBearishCrossover = shortMA < longMA && prevShortMA >= prevLongMA;
                boolean isRsiConfirmed = rsi < 50;

                if (isBearishCrossover && isRsiConfirmed) {
                    shouldSell = true;
                    sellReason = "Bearish MA Crossover and RSI confirmation.";
                }
            }

            // 4. If an exit condition is met, sell the position.
            if (shouldSell) {
                System.out.println("!!! SELL SIGNAL DETECTED FOR " + symbol + " !!! Reason: " + sellReason);

                AlpacaPosition alpacaPosition = getAlpacaPosition(symbol);

                if (alpacaPosition != null) {
                    // Place a sell order for the exact quantity we own
                    placeOrder(symbol, Double.parseDouble(alpacaPosition.getQty()), null, "sell");

                    // Update our database to mark the position as closed
                    position.setStatus(com.app.swingtrader.model.PositionStatus.CLOSED);
                    positionRepository.save(position);
                    System.out.println("Position for " + symbol + " marked as CLOSED in database.");
                } else {
                    // This is a safety check. If we think we have a position but Alpaca doesn't,
                    // we should mark our local position as closed to sync up.
                    System.err.println("Local position for " + symbol + " found, but no matching position on Alpaca. Correcting local state.");
                    position.setStatus(com.app.swingtrader.model.PositionStatus.CLOSED);
                    positionRepository.save(position);
                }
            }
        }
    }

    private void scanForNewTrades() {
        List<String> symbolsToScan = Arrays.asList(watchlist.split(","));
        System.out.println("Scanning watchlist for new trades: " + symbolsToScan);

        int shortMaPeriod = 10;
        int longMaPeriod = 50;
        int rsiPeriod = 14;

        for (String symbol : symbolsToScan) {
            // Check if we already have an open position for this symbol
            if (positionRepository.findBySymbolAndStatus(symbol, com.app.swingtrader.model.PositionStatus.OPEN).isPresent()) {
                System.out.println("Already in a position for " + symbol + ", skipping scan.");
                continue;
            }

            // Fetch historical data (e.g., last 60 days to have enough data for a 50-day MA)
            List<AlpacaStockBar> bars = fetchHistoricalBars(symbol, 60);

            if (bars.size() < longMaPeriod + 1) {
                System.out.println("Not enough historical data for " + symbol + ", skipping.");
                continue;
            }

            // --- STRATEGY LOGIC ---
            double rsi = calculateRsi(bars, rsiPeriod);
            double shortMA = calculateMovingAverage(bars, shortMaPeriod);
            double longMA = calculateMovingAverage(bars, longMaPeriod);

            List<AlpacaStockBar> previousBars = bars.subList(0, bars.size() - 1);
            double prevShortMA = calculateMovingAverage(previousBars, shortMaPeriod);
            double prevLongMA = calculateMovingAverage(previousBars, longMaPeriod);

            System.out.printf("Analysis for %s: Short MA=%.2f, Long MA=%.2f, RSI=%.2f%n", symbol, shortMA, longMA, rsi);

            // --- Check for the BUY signal ---
            boolean isBullishCrossover = shortMA > longMA && prevShortMA <= prevLongMA;
            boolean isRsiConfirmed = rsi > 50;

            if (isBullishCrossover && isRsiConfirmed) {
                System.out.println("!!! BUY SIGNAL DETECTED FOR " + symbol + " !!!");

                // Place the buy order via Alpaca API
                placeOrder(symbol, null, notionalTradeAmount, "buy");

                // Save the new position to our database
                // Note: We don't know the exact quantity or entry price yet,
                // so we save a placeholder and update it later.
                double entryPrice = bars.get(bars.size() - 1).getClose(); // Use last close as approximate entry price
                com.app.swingtrader.model.Position newPosition = new com.app.swingtrader.model.Position(symbol, 0.0, entryPrice);
                positionRepository.save(newPosition);

                System.out.println("Saved new OPEN position for " + symbol + " to database.");
            }
        }
    }

    /**
     * Calculates the Simple Moving Average of the closing prices.
     */
    private double calculateMovingAverage(List<AlpacaStockBar> bars, int period) {
        if (bars.size() < period) {
            return 0.0;
        }
        List<AlpacaStockBar> recentBars = bars.subList(bars.size() - period, bars.size());
        return recentBars.stream()
                .mapToDouble(AlpacaStockBar::getClose)
                .average()
                .orElse(0.0);
    }

    /**
     * Calculates the Relative Strength Index (RSI).
     */
    private double calculateRsi(List<AlpacaStockBar> bars, int period) {
        if (bars.size() < period + 1) {
            return 0.0;
        }

        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();
        List<AlpacaStockBar> relevantBars = bars.subList(bars.size() - (period + 1), bars.size());

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

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /**
     * Places a new market order with the Alpaca API.
     */
    private void placeOrder(String symbol, Double qty, Double notional, String side) {
        System.out.printf("Placing %s order for %s...%n", side.toUpperCase(), symbol);
        String url = baseUrl + "/v2/orders";

        OrderRequest orderRequest = new OrderRequest();
        orderRequest.setSymbol(symbol);
        orderRequest.setSide(side);
        orderRequest.setType("market");
        orderRequest.setTimeInForce("day"); // 'day' is correct for equities

        if (qty != null) {
            orderRequest.setQty(String.valueOf(qty));
        }
        if (notional != null) {
            orderRequest.setNotional(String.valueOf(notional));
        }

        HttpEntity<OrderRequest> entity = new HttpEntity<>(orderRequest, apiHeaders);

        try {
            restTemplate.postForEntity(url, entity, String.class);
            System.out.println("Order for " + symbol + " placed successfully.");
        } catch (Exception e) {
            System.err.println("Error placing order for " + symbol + ": " + e.getMessage());
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

    /**
     * Calculates the Average True Range (ATR) from a list of bars.
     * @param bars The list of historical bars to analyze.
     * @param period The number of periods to include in the calculation.
     * @return The calculated ATR value.
     */
    private double calculateAtr(List<AlpacaStockBar> bars, int period) {
        if (bars.size() < period + 1) {
            return 0.0; // Not enough data
        }

        List<Double> trueRanges = new ArrayList<>();
        // Get the slice of bars needed for the calculation (period + 1 to get 'period' number of changes)
        List<AlpacaStockBar> relevantBars = bars.subList(bars.size() - (period + 1), bars.size());

        // Start from the second bar in our slice to compare with the one before it
        for (int i = 1; i < relevantBars.size(); i++) {
            AlpacaStockBar currentBar = relevantBars.get(i);
            AlpacaStockBar previousBar = relevantBars.get(i - 1);

            double highMinusLow = currentBar.getHigh() - currentBar.getLow();
            double highMinusPrevClose = Math.abs(currentBar.getHigh() - previousBar.getClose());
            double lowMinusPrevClose = Math.abs(currentBar.getLow() - previousBar.getClose());

            double trueRange = Math.max(highMinusLow, Math.max(highMinusPrevClose, lowMinusPrevClose));
            trueRanges.add(trueRange);
        }

        // Return the average of the true ranges
        return trueRanges.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    private AlpacaPosition getAlpacaPosition(String symbol) {
        String url = String.format("%s/v2/positions/%s", baseUrl, symbol);
        HttpEntity<String> entity = new HttpEntity<>(apiHeaders);
        try {
            ResponseEntity<AlpacaPosition> response = restTemplate.exchange(url, HttpMethod.GET, entity, AlpacaPosition.class);
            return response.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            // This is expected if there is no open position for the symbol on Alpaca
            return null;
        } catch (Exception e) {
            System.err.println("Error fetching Alpaca position for " + symbol + ": " + e.getMessage());
            return null;
        }
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

    @Data
    @NoArgsConstructor
    private static class OrderRequest {
        private String symbol;
        private String qty;
        private String notional;
        private String side;
        private String type;
        @JsonProperty("time_in_force")
        private String timeInForce;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AlpacaPosition {
        private String symbol;
        private String qty;
        @JsonProperty("avg_entry_price")
        private String avgEntryPrice;
        @JsonProperty("side")
        private String side;
    }
}