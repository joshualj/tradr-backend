package com.tradrbackend.repository;

import com.tradrbackend.model.CompositeStockId;
import com.tradrbackend.model.SimFinModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface SimFinRepository extends JpaRepository<SimFinModel, CompositeStockId> {

    /**
     * Finds the single SimFinModel record with the latest date for a given ticker.
     * JPA translates this method name into:
     * SELECT s FROM SimFinModel s WHERE s.compositeStockId.ticker = :ticker
     * ORDER BY s.compositeStockId.date DESC LIMIT 1
     * It then returns the specific field 'latestNetIncomeCommon' from that record.
     */
    @Query(value = "SELECT s.latest_net_income_common FROM simfin_forward_filled_data s " +
            "WHERE s.ticker = :ticker " +
            "ORDER BY s.date DESC " +
            "LIMIT 1",
            nativeQuery = true)
    Optional<BigDecimal> findTop1LatestNetIncomeCommonByCompositeStockId_TickerOrderByCompositeStockId_DateDesc(@Param("ticker") String ticker);
}