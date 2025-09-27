package com.tradrbackend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AlphaVantageData {
    LinkedHashMap<LocalDate, BigDecimal> historicalPrices;
    BigDecimal latestVolume;
}