package com.app.swingtrader.controller;

import com.app.swingtrader.model.Position;
import com.app.swingtrader.model.PositionStatus;
import com.app.swingtrader.repository.PositionRepository;
import com.app.swingtrader.service.TradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/positions")
    public List<Position> getOpenPositions() {
        return positionRepository.findAllByStatus(PositionStatus.OPEN);
    }

    @GetMapping("/activity")
    public List<String> getActivityLog() {
        return tradingService.getActivityLog();
    }
}