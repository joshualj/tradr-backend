package com.tradrbackend.model;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "simfin_forward_filled_data",
        indexes = {
                @Index(name = "idx_ticker_date", columnList = "ticker, compositeStockId_date")
        })
public class SimFinModel {
    @EmbeddedId
    CompositeStockId compositeStockId;
    double adjClose;
    BigDecimal sharesOutstanding;
    BigDecimal latestNetIncomeCommon;
    double market_cap;
    BigDecimal peRatioTTM;
    LocalDate latestIncomeReportDate;
}
