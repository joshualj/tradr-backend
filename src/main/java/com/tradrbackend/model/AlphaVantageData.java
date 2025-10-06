package com.tradrbackend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AlphaVantageData {
    LinkedHashMap<LocalDate, BigDecimal> historicalPrices;
    BigDecimal latestVolume;
    List<BigDecimal> volumes20Day;
}