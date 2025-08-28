package com.app.swingtrader.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Represents an open or closed trading position in the database.
 * @Entity tells JPA that this class is a database table.
 */
@Entity
@Table(name = "positions") // Explicitly names the database table
@Data // Lombok: Generates getters, setters, toString, etc.
@NoArgsConstructor // Lombok: Generates a no-argument constructor, which JPA requires.
public class Position {

    /**
     * @Id marks this as the primary key.
     * @GeneratedValue tells the database to automatically generate this value.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private Double quantity;
    private Double entryPrice;
    private LocalDate entryDate;
    private PositionStatus status;

    // A convenience constructor for creating new positions
    public Position(String symbol, Double quantity, Double entryPrice) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.entryPrice = entryPrice;
        this.entryDate = LocalDate.now();
        this.status = PositionStatus.OPEN;
    }
}
