package com.app.swingtrader.scheduler;

import com.app.swingtrader.service.TradingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TradingScheduler {

    private final TradingService tradingService;

    public TradingScheduler(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    /**
     * This method is scheduled to run based on the cron expression.
     * It triggers the daily scan in the TradingService.
     */
    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "America/New_York")
    public void triggerDailyScan() {
        tradingService.performDailyScan();
    }
}
