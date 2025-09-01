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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStreamWriter;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.FileReader;
import java.io.Reader;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class TradingService {

    private final PositionRepository positionRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final HttpHeaders apiHeaders = new HttpHeaders();

    private static final Logger logger = LoggerFactory.getLogger(TradingService.class);

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

    @Value("${ml.data.path}")
    private String mlDataPath;

    public TradingService(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    public void performDailyScan() {
        logger.info("--- [" + java.time.LocalDate.now() + "] Starting Daily Trading Scan ---");
        addLogEntry("--- [" + java.time.LocalDate.now() + "] Starting Daily Trading Scan ---");
        // Set up authentication headers for this scan
        this.apiHeaders.set("APCA-API-KEY-ID", apiKey);
        this.apiHeaders.set("APCA-API-SECRET-KEY", apiSecret);

        reviewOpenPositions();
        scanForNewTrades();

        logger.info("--- Daily Trading Scan Complete ---");
        addLogEntry("--- Daily Trading Scan Complete ---");
    }

    private void reviewOpenPositions() {
        // 1. Get all OPEN positions from our local database.
        List<com.app.swingtrader.model.Position> openPositions = positionRepository.findAllByStatus(com.app.swingtrader.model.PositionStatus.OPEN);
        logger.info("Reviewing " + openPositions.size() + " open position(s)...");

        // Strategy parameters (can be moved to properties)
        int shortMaPeriod = 10;
        int longMaPeriod = 50;
        int rsiPeriod = 14;
        double takeProfitPercentage = 0.10; // 10%
        double atrMultiplier = 2.0;
        int atrPeriod = 14;

        for (com.app.swingtrader.model.Position position : openPositions) {
            String symbol = position.getSymbol();
            logger.info("Checking position: " + symbol);

            // --- Portfolio Reconciliation Logic ---
            // If quantity is 0, this is a new position that needs to be updated with real data.
            if (position.getQuantity() == 0.0) {
                AlpacaPosition alpacaPosition = getAlpacaPosition(symbol);
                if (alpacaPosition != null) {
                    logger.info("Reconciling new position for " + symbol);
                    position.setQuantity(Double.parseDouble(alpacaPosition.getQty()));
                    position.setEntryPrice(Double.parseDouble(alpacaPosition.getAvgEntryPrice()));
                    positionRepository.save(position);
                    logger.info("Position for " + symbol + " updated with filled order details.");
                } else {
                    // Order may not have filled yet, or failed. We'll check again tomorrow.
                    logger.info("Position for " + symbol + " not yet found on Alpaca. Will check again on next scan.");
                    continue; // Skip further analysis for this symbol today
                }
            }

            // 2. For each position, fetch the latest data.
            List<AlpacaStockBar> bars = fetchHistoricalBars(symbol, 60);
            if (bars.size() < longMaPeriod + 1) {
                logger.info("Not enough historical data to review " + symbol);
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
                logger.info("!!! SELL SIGNAL DETECTED FOR " + symbol + " !!! Reason: " + sellReason);
                addLogEntry("!!! SELL SIGNAL DETECTED FOR " + symbol + " !!! Reason: " + sellReason);
                AlpacaPosition alpacaPosition = getAlpacaPosition(symbol);

                if (alpacaPosition != null) {
                    // Place a sell order for the exact quantity we own
                    placeOrder(symbol, Double.parseDouble(alpacaPosition.getQty()), null, "sell");

                    // Update our database to mark the position as closed
                    position.setStatus(com.app.swingtrader.model.PositionStatus.CLOSED);
                    positionRepository.save(position);
                    logger.info("Position for " + symbol + " marked as CLOSED in database.");
                } else {
                    // This is a safety check. If we think we have a position but Alpaca doesn't,
                    // we should mark our local position as closed to sync up.
                    logger.error("Local position for " + symbol + " found, but no matching position on Alpaca. Correcting local state.");
                    position.setStatus(com.app.swingtrader.model.PositionStatus.CLOSED);
                    positionRepository.save(position);
                }
            }
        }
    }

    private void scanForNewTrades() {
        List<String> symbolsToScan = Arrays.asList(watchlist.split(","));
        logger.info("Scanning watchlist for new trades: " + symbolsToScan);
        addLogEntry("Scanning watchlist for new trades: " + symbolsToScan);

        int shortMaPeriod = 10;
        int longMaPeriod = 50;
        int rsiPeriod = 14;

        for (String symbol : symbolsToScan) {
            // Check if we already have an open position for this symbol
            if (positionRepository.findBySymbolAndStatus(symbol, com.app.swingtrader.model.PositionStatus.OPEN).isPresent()) {
                logger.info("Already in a position for " + symbol + ", skipping scan.");
                continue;
            }

            // Fetch historical data (e.g., last 60 days to have enough data for a 50-day MA)
            List<AlpacaStockBar> bars = fetchHistoricalBars(symbol, 60);

            if (bars == null || bars.size() < longMaPeriod + 1) {
                logger.info("Not enough historical data for " + symbol + ", skipping.");
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
                logger.info("!!! BUY SIGNAL DETECTED FOR " + symbol + " !!!");
                addLogEntry("!!! BUY SIGNAL DETECTED FOR " + symbol + " !!!");
                // --- Get Prediction from Machine Learning Model ---
                logger.info("Getting prediction from ML model for {}", symbol);
                int prediction = getPredictionFromModel(bars);

                if (prediction == 1) {
                    String logMsg = "!!! ML MODEL PREDICTED BUY SIGNAL FOR " + symbol + " !!!";
                    logger.info(logMsg);
                    addLogEntry(logMsg);

                    placeOrder(symbol, null, notionalTradeAmount, "buy");

                    double entryPrice = bars.get(bars.size() - 1).getClose();
                    com.app.swingtrader.model.Position newPosition = new com.app.swingtrader.model.Position(symbol, 0.0, entryPrice);
                    positionRepository.save(newPosition);

                    logger.info("Saved new OPEN position for {} to database.", symbol);
                } else {
                    logger.info("ML model predicted HOLD for {}. No action taken.", symbol);
                }
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
            logger.info("Order for " + symbol + " placed successfully.");
            addLogEntry("Order for " + symbol + " placed successfully.");
        } catch (Exception e) {
            logger.error("Error placing order for " + symbol + ": " + e.getMessage());
            addLogEntry("Error placing order for " + symbol + ": " + e.getMessage());
        }
    }

    public List<AlpacaStockBar> fetchHistoricalBars(String symbol, int limit) {
        logger.info("Reading historical bars for {} from local CSV file...", symbol);
        List<AlpacaStockBar> bars = new ArrayList<>();
        String filePath = mlDataPath + "/" + symbol + ".csv";

        try (Reader reader = new FileReader(filePath);
             // Use withHeader() to parse the first row as the header map
             CSVParser csvParser = CSVParser.parse(reader, CSVFormat.DEFAULT.withHeader())) {

            for (CSVRecord csvRecord : csvParser) {
                // --- THIS IS THE CRITICAL FIX ---
                // The yfinance CSV file has two junk rows after the main header.
                // We check the first column and skip the rows if they contain "Ticker" or "Date".
                if (csvRecord.get(0).equals("Ticker") || csvRecord.get(0).equals("Date")) {
                    continue; // Skip this junk row
                }

                // The first column header from the yfinance file is "Price", which contains the date.
                String dateString = csvRecord.get("Price");

                AlpacaStockBar bar = new AlpacaStockBar(
                        Instant.parse(dateString + "T00:00:00Z").atZone(ZoneOffset.UTC).toInstant().toString(),
                        Double.parseDouble(csvRecord.get("Open")),
                        Double.parseDouble(csvRecord.get("High")),
                        Double.parseDouble(csvRecord.get("Low")),
                        Double.parseDouble(csvRecord.get("Close")),
                        Long.parseLong(csvRecord.get("Volume"))
                );
                bars.add(bar);
            }

            if (bars.size() > limit) {
                return bars.subList(bars.size() - limit, bars.size());
            } else {
                return bars;
            }

        } catch (Exception e) {
            logger.error("Error reading CSV file for {}: {}", symbol, e.getMessage());
            return List.of();
        }
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
            logger.error("Error fetching Alpaca position for " + symbol + ": " + e.getMessage());
            addLogEntry("Error fetching Alpaca position for " + symbol + ": " + e.getMessage());
            return null;
        }
    }

    // Add this with your other state variables
    private final List<String> activityLog = new CopyOnWriteArrayList<>();

    // Add this helper method anywhere in the class
    private void addLogEntry(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        activityLog.add(0, String.format("[%s] %s", timestamp, message));
        // Keep the log from growing indefinitely
        while (activityLog.size() > 50) {
            activityLog.remove(activityLog.size() - 1);
        }
    }

    private int getPredictionFromModel(List<AlpacaStockBar> bars) {
        try {
            // Define the path to the Python project and script
            java.io.File pythonProjectDir = new java.io.File("../swing-trader-ml/");
            String scriptPath = "src/models/predict_model.py";

            List<String> command = List.of("py", scriptPath);

            // Execute the command
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(pythonProjectDir);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Convert the bar history to a JSON string
            ObjectMapper objectMapper = new ObjectMapper();
            String barsJson = objectMapper.writeValueAsString(bars);

            // Write the JSON data to the script's standard input
            try (var writer = new OutputStreamWriter(process.getOutputStream())) {
                writer.write(barsJson);
            }

            // Read the prediction output from the script
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String output = reader.readLine();
                logger.info("Model prediction output: {}", output);
                if (output != null && !output.isEmpty()) {
                    return Integer.parseInt(output.trim());
                }
            }

        } catch (Exception e) {
            logger.error("Failed to get prediction from model", e);
        }
        return 0; // Default to "don't buy" on any error
    }

    // You will also need this helper method to calculate all indicators at once
    private Map<String, Double> calculateAllIndicators(List<AlpacaStockBar> bars) {
        // This is a placeholder for a more robust feature calculation service
        // For now, it's a simplified example
        Map<String, Double> indicators = new HashMap<>();
        // In a real app, you would calculate each indicator here
        // For now, we'll return an empty map. We'll fill this in later.
        return indicators;
    }

    // Also add this getter so the controller can access the log
    public List<String> getActivityLog() {
        return this.activityLog;
    }

    // --- Helper classes for parsing Alpaca's Stocks API response ---

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AlpacaStockBarsResponse {
        private Map<String, List<AlpacaStockBar>> bars; // <-- Changed to a Map
        @JsonProperty("next_page_token")
        private String nextPageToken;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlpacaStockBar {
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

        public AlpacaStockBar(String timestamp, double open, double high, double low, double close, long volume) {
            this.timestamp = timestamp;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
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