package com.tradrbackend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

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

    private Double latestVolume;
    private List<BigDecimal> volume20DayAvg;
    private Double marketCap;
    private Double volatility;
    private Double sentiment;
    private double probability;
    private double latestClosePrice;
    private BigDecimal latestNetIncome;
    private double peRatioTtm;
    private double sharesOutstanding;
    private double sp500PeProxy;

    // Create constructor with volatility, marketCap, latestVolume, and sentiment
    public TechnicalIndicators(Double volatility, Double marketCap, Double latestVolume, List<BigDecimal> volume20DayAvg,
                               Double sentiment, BigDecimal latestNetIncome, double peRatioTtm,
                               double sharesOutstanding, double sp500PeProxy) {
        this.volatility = volatility;
        this.marketCap = marketCap;
        this.latestVolume = latestVolume;
        this.volume20DayAvg = volume20DayAvg;
        this.sentiment = sentiment;
        this.latestNetIncome = latestNetIncome;
        this.peRatioTtm = peRatioTtm;
        this.sharesOutstanding = sharesOutstanding;
        this.sp500PeProxy = sp500PeProxy;
    }
}