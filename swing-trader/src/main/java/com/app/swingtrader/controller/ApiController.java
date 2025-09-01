package com.app.swingtrader.controller;

import com.app.swingtrader.model.Position;
import com.app.swingtrader.model.PositionStatus;
import com.app.swingtrader.repository.PositionRepository;
import com.app.swingtrader.service.TradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.swingtrader.dto.PositionDTO;
import com.app.swingtrader.service.TradingService.AlpacaStockBar;
import java.util.ArrayList;

import java.util.List;

@RestController
@RequestMapping("/api") // All endpoints in this controller will start with /api
public class ApiController {

    private final PositionRepository positionRepository;
    private final TradingService tradingService;

    public ApiController(PositionRepository positionRepository, TradingService tradingService) {
        this.positionRepository = positionRepository;
        this.tradingService = tradingService;
    }

    @GetMapping("/activity")
    public List<String> getActivityLog() {
        return tradingService.getActivityLog();
    }

    @GetMapping("/runscan")
    public String runScan() {
        // Run the service method in a new thread so the web request returns immediately
        new Thread(tradingService::performDailyScan).start();
        return "Manual scan triggered. Check your application console for logs.";
    }

    @GetMapping("/positions")
    public List<PositionDTO> getOpenPositions() {
        List<Position> positions = positionRepository.findAllByStatus(PositionStatus.OPEN);
        List<PositionDTO> positionDTOs = new ArrayList<>();

        for (Position position : positions) {
            // UPDATED: Call the existing fetchHistoricalBars method to get the latest price
            // We only need 1 bar to get the most recent closing price.
            List<TradingService.AlpacaStockBar> bars = tradingService.fetchHistoricalBars(position.getSymbol(), 1);

            double currentPrice = position.getEntryPrice(); // Default to entry price if fetch fails
            if (bars != null && !bars.isEmpty()) {
                currentPrice = bars.get(0).getClose();
            }

            positionDTOs.add(new PositionDTO(position, currentPrice));
        }
        return positionDTOs;
    }
}