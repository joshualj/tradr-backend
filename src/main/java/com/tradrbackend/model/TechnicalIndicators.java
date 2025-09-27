package com.tradrbackend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A data transfer object (DTO) to hold the calculated technical indicator values
 * and their corresponding signals.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TechnicalIndicators {

    // Indicator Values
    private Double sma50;
    private BigDecimal ema20; // New field for EMA_20
    private Double rsi;
    private Double macdLine;
    private Double macdSignal;
    private Double macdHistogram;
    private Double bbMiddle;
    private Double bbUpper;
    private Double bbLower;
    private Double percentageChangeFromMean; // Added this field to resolve the build failure
    private Double atr; // New field for Average True Range

    // Signals derived from indicators
    private String rsiSignal;
    private String macdSignalInterpretation; // Renamed to avoid collision
    private String bollingerBandSignal;

    private Double volume;
    private Double marketCap;
    private Double volatility;
    private Double sentiment;
    private double probability;
    private double latestClosePrice;

    // Create constructor with volatility, marketCap, latestVolume, and sentiment
    public TechnicalIndicators(Double volatility, Double marketCap, Double volume, Double sentiment) {
        this.volatility = volatility;
        this.marketCap = marketCap;
        this.volume = volume;
        this.sentiment = sentiment;
    }
}