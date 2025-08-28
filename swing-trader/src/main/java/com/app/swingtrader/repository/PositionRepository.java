package com.app.swingtrader.repository;

import com.app.swingtrader.model.Position;
import com.app.swingtrader.model.PositionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {

    /**
     * Spring Data JPA will automatically create a query for this method.
     * It will find a position by its stock symbol and its current status.
     * This is useful for checking if we already have an open position for a stock.
     */
    Optional<Position> findBySymbolAndStatus(String symbol, PositionStatus status);

    /**
     * Finds all positions that are currently open.
     */
    List<Position> findAllByStatus(PositionStatus status);
}
