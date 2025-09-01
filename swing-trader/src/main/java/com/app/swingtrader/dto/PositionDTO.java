package com.app.swingtrader.dto;

import com.app.swingtrader.model.Position;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PositionDTO {
    private String symbol;
    private Double quantity;
    private Double entryPrice;
    private LocalDate entryDate;
    private Double currentPrice;
    private Double unrealizedPl;

    public PositionDTO(Position position, double currentPrice) {
        this.symbol = position.getSymbol();
        this.quantity = position.getQuantity();
        this.entryPrice = position.getEntryPrice();
        this.entryDate = position.getEntryDate();
        this.currentPrice = currentPrice;
        // Calculate the unrealized profit/loss
        if (position.getQuantity() != null && position.getQuantity() > 0) {
            this.unrealizedPl = (currentPrice - position.getEntryPrice()) * position.getQuantity();
        } else {
            this.unrealizedPl = 0.0;
        }
    }
}
