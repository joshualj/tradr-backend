package com.tradrbackend.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.tradrbackend.model.TechnicalIndicators;
import org.springframework.stereotype.Service;

import com.tradrbackend.model.HistoricalPrice;
import com.tradrbackend.response.StockAnalysisResponse;

@Service // Marks this as a Spring service component
public class StockAnalyzerService {

    // You might want to make this configurable or a constant
    private static final double SIGNIFICANCE_THRESHOLD_PERCENT = 5.0; // 5% difference

    private static final int RSI_PERIOD = 14;
    private static final int MACD_FAST_PERIOD = 12;
    private static final int MACD_SLOW_PERIOD = 26;
    private static final int MACD_SIGNAL_PERIOD = 9;
    private static final int BOLLINGER_BAND_PERIOD = 20;
    private static final int BOLLINGER_BAND_STD_DEV = 2; // Standard deviations for Bollinger Bands
    private final RandomForestPredictionService randomForestPredictionService;
    private final RegressionPredictionService regressionPredictionService;

    public StockAnalyzerService(
            RandomForestPredictionService randomForestPredictionService,
            RegressionPredictionService regressionPredictionService
    ) {
        this.randomForestPredictionService = randomForestPredictionService;
        this.regressionPredictionService = regressionPredictionService;
    }

    /**
     * Performs comprehensive stock analysis including statistical significance,
     * technical indicator calculations, and signal scoring.
     *
     * @param historicalData A map of LocalDate to BigDecimal (adjusted close price), sorted oldest to newest.
     * @param duration The user-specified duration value for statistical analysis.
     * @param unit The user-specified duration unit for statistical analysis (e.g., "day", "month").
     * @return A StockAnalysisResponse object containing all analysis results.
     */
    public StockAnalysisResponse performStockAnalysis(Map<LocalDate, BigDecimal> historicalData,
                                                      StockAnalysisResponse response,
                                                      TechnicalIndicators indicators,
                                                      int duration,
                                                      String unit,
                                                      boolean useRegressionCoefficientModel
    ) throws IOException, InterruptedException {
        List<HistoricalPrice> historicalPriceList = getHistoricalPrices(historicalData);
        response.setHistoricalPrices(historicalPriceList); // Set the historical prices in the response
        // Use a list of only the *prices* (BigDecimals) for calculations, maintaining original order
        List<BigDecimal> prices = historicalPriceList.stream()
                                    .map(hp -> BigDecimal.valueOf(hp.getClose()))
                                    .collect(Collectors.toList());
        List<LocalDate> dates = historicalPriceList.stream()
                                   .map(HistoricalPrice::getDate)
                                   .toList();

        if (!isPricesValid(prices, response)) {
            return response;
        }

        LocalDate endDate = dates.get(dates.size() - 1);
        LocalDate startDateForStats = getStartDateForStats(endDate, duration, unit, response);
        if (!isStartDateValid(response, startDateForStats, unit)) {
            return response;
        }

        // Set latest price and received ticker early
        BigDecimal latestPriceBd = prices.get(prices.size() - 1);
        response.setLatestPrice(latestPriceBd.doubleValue());
        indicators.setLatestClosePrice(latestPriceBd.doubleValue());

        calculateAndSetIndicators(historicalData, response, indicators, startDateForStats, endDate, duration, unit,
                prices, latestPriceBd);
        performPredictionAndScoring(response, indicators, useRegressionCoefficientModel);
        response.setIndicators(indicators); // Set the new object in the response

        return response;
    }

    // currently useRegressionCoefficientModel is hard-coded as 'false'
    private void performPredictionAndScoring(StockAnalysisResponse response,
                                             TechnicalIndicators indicators,
                                             boolean useRegressionCoefficientModel
                                             ) throws IOException, InterruptedException {
        if (useRegressionCoefficientModel) {
            regressionPredictionService.makePrediction(indicators, response);
        } else {
            RandomForestPredictionService.RandomForestPredictionResponse predictionResponse = randomForestPredictionService.makePrediction(indicators);
            indicators.setProbability(predictionResponse.getProbability());
            response.setSignalScore(predictionResponse.getPrediction());
        }
    }

    private List<HistoricalPrice> getHistoricalPrices(Map<LocalDate, BigDecimal> historicalData) {
        // Convert the full map of historical data into a List of HistoricalPrice DTOs for the response
        return historicalData.entrySet().stream()
                .map(entry -> new HistoricalPrice(entry.getKey(), entry.getValue().doubleValue()))
                // Sort by the 'date' field of the HistoricalPrice object
                .sorted(Comparator.comparing(HistoricalPrice::getDate))
                .collect(Collectors.toList());
    }

    private LocalDate getStartDateForStats(LocalDate endDate, int duration, String unit, StockAnalysisResponse response) {
        return switch (unit.toLowerCase()) {
            case "day" -> endDate.minusDays(duration - 1);
            case "week" -> endDate.minusWeeks(duration - 1);
            case "month" -> endDate.minusMonths(duration - 1);
            case "year" -> endDate.minusYears(duration - 1);
            default -> null;
        };
    }

    private boolean isPricesValid(List<BigDecimal> prices, StockAnalysisResponse response) {
        if (prices.size() < 2) {
            response.setMessage("Not enough data for meaningful analysis (less than 2 data points).");
            response.setError("Insufficient data");
            response.setStatisticallySignificant(false);
            return false;
        }
        return true;
    }

    private boolean isStartDateValid(StockAnalysisResponse response, LocalDate startDateForStats, String unit) {
        if (startDateForStats == null) {
            response.setMessage("Invalid duration unit for statistical analysis: " + unit);
            response.setError("Invalid Unit");
            response.setStatisticallySignificant(false);
            return false;
        }
        return true;
    }

    private void calculateAndSetIndicators(Map<LocalDate, BigDecimal> historicalData,
                                             StockAnalysisResponse response,
                                             TechnicalIndicators indicators,
                                             LocalDate startDateForStats,
                                             LocalDate endDate,
                                             int duration,
                                             String unit,
                                             List<BigDecimal> prices,
                                             BigDecimal latestPriceBd) {
        setBasicStats(historicalData, response, startDateForStats, endDate, duration, unit, latestPriceBd, indicators);
        setIndicators(indicators, prices, latestPriceBd);
    }

    private void setBasicStats(Map<LocalDate, BigDecimal> historicalData,
                               StockAnalysisResponse response,
                               LocalDate startDateForStats,
                               LocalDate endDate,
                               int duration,
                               String unit,
                               BigDecimal latestPriceBd,
                               TechnicalIndicators indicators) {

        List<BigDecimal> pricesForStatisticalAnalysis = historicalData.entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(startDateForStats) && !entry.getKey().isAfter(endDate))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());

        BigDecimal percentageChangeFromMean = BigDecimal.ZERO;

        if (pricesForStatisticalAnalysis.size() < 2) {
            response.setMessage("Not enough data for statistical analysis over the specified period (" + duration + " " + unit + ").");
            response.setStatisticallySignificant(false);
            response.setPValue(null);
        } else {
            BigDecimal meanPrice = calculateMean(pricesForStatisticalAnalysis);
            BigDecimal stdDev = calculateStandardDeviation(pricesForStatisticalAnalysis, meanPrice);


            if (meanPrice.compareTo(BigDecimal.ZERO) != 0) {
                percentageChangeFromMean = latestPriceBd.subtract(meanPrice)
                        .divide(meanPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100));
            }

            double percentChangeFromMeanDouble = percentageChangeFromMean.doubleValue();
            indicators.setPercentageChangeFromMean(percentChangeFromMeanDouble);

            // Define "statistical significance" based on a combination of factors:
            // 1. A significant percentage change from the mean over the period (e.g., > 5%)
            // 2. The latest price being a certain number of standard deviations away from the mean (e.g., > 1.5 std dev)
            boolean isSignificantByPercent = Math.abs(percentChangeFromMeanDouble) > SIGNIFICANCE_THRESHOLD_PERCENT; // Example: 5% change

            boolean isSignificantByStdDev = false;
            if (stdDev.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal zScore = latestPriceBd.subtract(meanPrice).divide(stdDev, 2, RoundingMode.HALF_UP);
                isSignificantByStdDev = Math.abs(zScore.doubleValue()) > 1.5; // Example: 1.5 standard deviations away
            }

            boolean finalIsSignificant = isSignificantByPercent || isSignificantByStdDev;

            String statisticalMessage = String.format("Latest price ($%.2f) vs. mean ($%.2f) over %d %s(s): %.2f%% change. Std Dev: %.2f.",
                    latestPriceBd.doubleValue(), meanPrice.doubleValue(), duration, unit, percentChangeFromMeanDouble, stdDev.doubleValue());

            // Add a note if the actual data used is less than the requested period
            long requestedDays = convertDurationToDays(duration, unit);
            if (pricesForStatisticalAnalysis.size() < requestedDays) {
                statisticalMessage += " (Analysis based on " + pricesForStatisticalAnalysis.size() + " available data points, less than requested period due to API limits).";
            }

            response.setMessage(statisticalMessage);
            response.setStatisticallySignificant(finalIsSignificant);
            response.setPValue(null); // Still no p-value calculated in this simplified example
        }
    }

    private void setIndicators(TechnicalIndicators indicators, List<BigDecimal> prices, BigDecimal latestPriceBd
    ) {
        setAtr(indicators, prices);
        setSma(indicators, prices);
        setEma(indicators, prices);
        setRsi(indicators, prices);
        setMacd(indicators, prices);
        setBollingerBand(indicators, prices, latestPriceBd);
    }

    /**
     * Calculates the Average True Range (ATR) for a list of prices.
     * ATR is a measure of market volatility. We'll use a simplified calculation
     * for demonstration, assuming the 'prices' list contains the closing prices.
     * A more robust calculation would require high and low prices as well.
     *
     * @param indicators The TechnicalIndicators object to update.
     * @param prices The list of closing prices.
     */
    private void setAtr(TechnicalIndicators indicators, List<BigDecimal> prices) {
        final int atrPeriod = 14;

        if (prices.size() < atrPeriod) {
            indicators.setAtr(null); // Not enough data to calculate ATR
            return;
        }

        // For a full ATR calculation, you'd need high, low, and close prices.
        // For this example, we'll calculate the True Range based on the difference
        // between consecutive closing prices. This is a simplified approach.
        List<Double> trueRanges = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal currentPrice = prices.get(i);
            BigDecimal previousPrice = prices.get(i - 1);
            Double trueRange = currentPrice.subtract(previousPrice).abs().doubleValue();
            trueRanges.add(trueRange);
        }

        // Calculate the average of the last 14 true ranges
        double sum = IntStream.range(trueRanges.size() - atrPeriod, trueRanges.size())
                .mapToDouble(trueRanges::get)
                .sum();
        double atrValue = sum / atrPeriod;
        indicators.setAtr(atrValue);
    }

    private void setSma(TechnicalIndicators indicators, List<BigDecimal> prices) {
        if (prices.size() >= 50) {
            BigDecimal sma50 = calculateSma(prices, 50);
            indicators.setSma50(sma50.doubleValue());
        }
    }

    private void setEma(TechnicalIndicators indicators, List<BigDecimal> prices) {
        final int EMA_PERIOD = 20;
        if (prices.size() >= EMA_PERIOD) {
            BigDecimal ema20 = calculateEma(prices, EMA_PERIOD);
            indicators.setEma20(ema20);
        }
    }

    private BigDecimal calculateEma(List<BigDecimal> prices, int period) {
        if (prices == null || prices.size() < period) {
            return null;
        }

        // 1. Initial SMA for the first EMA calculation
        BigDecimal sma = calculateSma(prices, period);
        if (sma == null) {
            return null;
        }

        BigDecimal ema = sma;

        // 2. Calculate the multiplier
        BigDecimal multiplier = new BigDecimal("2").divide(
                new BigDecimal(period + 1), 4, RoundingMode.HALF_UP
        );

        // 3. Loop through the remaining prices and calculate the EMA
        List<BigDecimal> remainingPrices = prices.subList(period, prices.size());
        for (BigDecimal price : remainingPrices) {
            ema = price.subtract(ema).multiply(multiplier).add(ema);
        }

        return ema.setScale(4, RoundingMode.HALF_UP);
    }

    private void setRsi(TechnicalIndicators indicators, List<BigDecimal> prices) {
        if (prices.size() >= RSI_PERIOD) {
            Double rsi = calculateRSI(prices, RSI_PERIOD);
            indicators.setRsi(rsi);
            indicators.setRsiSignal(getRSISignal(rsi));
        }
    }

    private void setMacd(TechnicalIndicators indicators, List<BigDecimal> prices) {
        if (prices.size() >= MACD_SLOW_PERIOD + MACD_SIGNAL_PERIOD) {
            Map<String, BigDecimal> macdResult = calculateMACD(prices, MACD_FAST_PERIOD, MACD_SLOW_PERIOD, MACD_SIGNAL_PERIOD);
            indicators.setMacdLine(macdResult.get("MACD_Line").doubleValue());
            indicators.setMacdSignal(macdResult.get("MACD_Signal").doubleValue());
            indicators.setMacdHistogram(macdResult.get("MACD_Histogram").doubleValue());
            indicators.setMacdSignalInterpretation(getMACDSignal(macdResult.get("MACD_Line"), macdResult.get("MACD_Signal"), macdResult.get("MACD_Histogram")));
        }
    }

    private void setBollingerBand(TechnicalIndicators indicators, List<BigDecimal> prices, BigDecimal latestPriceBd) {
        if (prices.size() >= BOLLINGER_BAND_PERIOD) {
            Map<String, BigDecimal> bbResult = calculateBollingerBands(prices, BOLLINGER_BAND_PERIOD, BOLLINGER_BAND_STD_DEV);
            indicators.setBbMiddle(bbResult.get("BB_Middle").doubleValue());
            indicators.setBbUpper(bbResult.get("BB_Upper").doubleValue());
            indicators.setBbLower(bbResult.get("BB_Lower").doubleValue());
            indicators.setBollingerBandSignal(getBollingerBandSignal(latestPriceBd.doubleValue(), bbResult.get("BB_Upper"), bbResult.get("BB_Lower")));
        }
    }

    // --- Statistical Calculation Helper Methods ---

    /**
     * Calculates the mean (average) of a list of BigDecimal prices.
     * @param prices The list of prices.
     * @return The mean price.
     */
    private BigDecimal calculateMean(List<BigDecimal> prices) {
        if (prices == null || prices.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(prices.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates the standard deviation of a list of BigDecimal prices.
     * @param prices The list of prices.
     * @param mean The mean of the prices.
     * @return The standard deviation.
     */
    private BigDecimal calculateStandardDeviation(List<BigDecimal> prices, BigDecimal mean) {
        if (prices == null || prices.size() < 2) { // Need at least 2 data points for std dev
            return BigDecimal.ZERO;
        }
        BigDecimal sumOfSquares = BigDecimal.ZERO;
        for (BigDecimal price : prices) {
            BigDecimal diff = price.subtract(mean);
            sumOfSquares = sumOfSquares.add(diff.multiply(diff));
        }
        // Use n-1 for sample standard deviation
        BigDecimal variance = sumOfSquares.divide(new BigDecimal(prices.size() - 1), 4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).setScale(2, RoundingMode.HALF_UP);
    }


    /**
     * Helper to convert duration and unit to approximate number of days.
     * Used for comparison with available data points.
     * @param duration The duration value.
     * @param unit The duration unit (day, week, month, year).
     * @return Approximate number of days.
     */
    private long convertDurationToDays(int duration, String unit) {
        switch (unit.toLowerCase()) {
            case "day": return duration;
            case "week": return (long) duration * 7;
            case "month": return (long) duration * 30; // Approx 30 days per month
            case "year": return (long) duration * 365; // Approx 365 days per year
            default: return 0;
        }
    }

    // --- Technical Indicator Calculation Helper Methods ---

    /**
     * Calculates Simple Moving Average (SMA).
     * @param prices List of prices (assumed to be ordered oldest to newest).
     * @param period The period for the SMA.
     * @return The SMA value.
     */
    private BigDecimal calculateSma(List<BigDecimal> prices, int period) {
        if (prices.size() < period) {
            return BigDecimal.ZERO; // Not enough data
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = prices.size() - period; i < prices.size(); i++) {
            sum = sum.add(prices.get(i));
        }
        return sum.divide(new BigDecimal(period), 2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates Exponential Moving Average (EMA).
     * @param prices List of prices (assumed to be ordered oldest to newest).
     * @param period The period for the EMA.
     * @return The EMA value.
     */
    private BigDecimal calculateEMA(List<BigDecimal> prices, int period) {
        if (prices.size() < period) {
            return BigDecimal.ZERO;
        }
        BigDecimal multiplier = new BigDecimal(2).divide(new BigDecimal(period + 1), 4, RoundingMode.HALF_UP);
        BigDecimal ema = calculateSma(prices.subList(0, period), period); // Initial SMA for first EMA

        for (int i = period; i < prices.size(); i++) {
            ema = prices.get(i).subtract(ema).multiply(multiplier).add(ema);
        }
        return ema.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates Relative Strength Index (RSI).
     * @param prices List of prices (assumed to be ordered oldest to newest).
     * @param period The period for the RSI.
     * @return The RSI value (0-100).
     */
    private Double calculateRSI(List<BigDecimal> prices, int period) {
        if (prices.size() < period + 1) { // Need at least period + 1 prices to calculate first change
            return null;
        }

        List<BigDecimal> changes = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            changes.add(prices.get(i).subtract(prices.get(i - 1)));
        }

        BigDecimal avgGain = BigDecimal.ZERO;
        BigDecimal avgLoss = BigDecimal.ZERO;

        // Calculate initial average gain/loss over the first 'period' changes
        for (int i = 0; i < period; i++) {
            BigDecimal change = changes.get(i);
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                avgGain = avgGain.add(change);
            } else {
                avgLoss = avgLoss.add(change.abs());
            }
        }
        avgGain = avgGain.divide(new BigDecimal(period), 4, RoundingMode.HALF_UP);
        avgLoss = avgLoss.divide(new BigDecimal(period), 4, RoundingMode.HALF_UP);

        // Calculate subsequent average gain/loss
        for (int i = period; i < changes.size(); i++) {
            BigDecimal change = changes.get(i);
            BigDecimal currentGain = BigDecimal.ZERO;
            BigDecimal currentLoss = BigDecimal.ZERO;

            if (change.compareTo(BigDecimal.ZERO) > 0) {
                currentGain = change;
            } else {
                currentLoss = change.abs();
            }

            avgGain = currentGain.add(avgGain.multiply(new BigDecimal(period - 1))).divide(new BigDecimal(period), 4, RoundingMode.HALF_UP);
            avgLoss = currentLoss.add(avgLoss.multiply(new BigDecimal(period - 1))).divide(new BigDecimal(period), 4, RoundingMode.HALF_UP);
        }

        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return 100.0; // No losses, RSI is 100
        }
        if (avgGain.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0; // No gains, RSI is 0
        }

        BigDecimal rs = avgGain.divide(avgLoss, 4, RoundingMode.HALF_UP);
        BigDecimal rsi = new BigDecimal(100).subtract(new BigDecimal(100).divide(BigDecimal.ONE.add(rs), 2, RoundingMode.HALF_UP));

        return rsi.doubleValue();
    }

    /**
     * Calculates MACD Line, Signal Line, and Histogram.
     * @param prices List of prices (assumed to be ordered oldest to newest).
     * @param fastPeriod The period for the fast EMA.
     * @param slowPeriod The period for the slow EMA.
     * @param signalPeriod The period for the signal line EMA.
     * @return A map containing MACD_Line, MACD_Signal, and MACD_Histogram.
     */
    private Map<String, BigDecimal> calculateMACD(List<BigDecimal> prices, int fastPeriod, int slowPeriod, int signalPeriod) {
        if (prices.size() < slowPeriod + signalPeriod) { // Need enough data for longest EMA + signal
            return Collections.emptyMap();
        }

        List<BigDecimal> fastEMAs = new ArrayList<>();
        List<BigDecimal> slowEMAs = new ArrayList<>();
        
        // Calculate EMAs for each point in the series
        for (int i = 0; i < prices.size(); i++) {
            List<BigDecimal> subList = prices.subList(0, i + 1);
            if (subList.size() >= fastPeriod) {
                fastEMAs.add(calculateEMA(subList, fastPeriod));
            } else {
                fastEMAs.add(BigDecimal.ZERO); // Placeholder if not enough data yet
            }
            if (subList.size() >= slowPeriod) {
                slowEMAs.add(calculateEMA(subList, slowPeriod));
            } else {
                slowEMAs.add(BigDecimal.ZERO); // Placeholder if not enough data yet
            }
        }

        List<BigDecimal> macdLines = new ArrayList<>();
        for (int i = 0; i < fastEMAs.size(); i++) {
            if (fastEMAs.get(i).compareTo(BigDecimal.ZERO) != 0 && slowEMAs.get(i).compareTo(BigDecimal.ZERO) != 0) {
                macdLines.add(fastEMAs.get(i).subtract(slowEMAs.get(i)).setScale(2, RoundingMode.HALF_UP));
            } else {
                macdLines.add(BigDecimal.ZERO); // Placeholder
            }
        }

        // Calculate Signal Line (EMA of MACD Line)
        BigDecimal macdSignal = BigDecimal.ZERO;
        if (macdLines.size() >= signalPeriod) {
            macdSignal = calculateEMA(macdLines.subList(macdLines.size() - signalPeriod, macdLines.size()), signalPeriod);
        } else if (!macdLines.isEmpty()) {
             // If not enough for full signal period, use the last calculated MACD line as a rough estimate
             // Or, more accurately, calculate EMA over available macdLines if less than signalPeriod
             macdSignal = calculateEMA(macdLines, macdLines.size());
        }

        BigDecimal macdLine = macdLines.isEmpty() ? BigDecimal.ZERO : macdLines.get(macdLines.size() - 1);
        BigDecimal macdHistogram = macdLine.subtract(macdSignal).setScale(2, RoundingMode.HALF_UP);

        Map<String, BigDecimal> result = new LinkedHashMap<>();
        result.put("MACD_Line", macdLine);
        result.put("MACD_Signal", macdSignal);
        result.put("MACD_Histogram", macdHistogram);
        return result;
    }


    /**
     * Calculates Bollinger Bands (Middle, Upper, Lower).
     * @param prices List of prices (assumed to be ordered oldest to newest).
     * @param period The period for the SMA (middle band).
     * @param stdDev The number of standard deviations for upper/lower bands.
     * @return A map containing BB_Middle, BB_Upper, BB_Lower.
     */
    private Map<String, BigDecimal> calculateBollingerBands(List<BigDecimal> prices, int period, int stdDev) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        if (prices.size() < period) {
            result.put("BB_Middle", BigDecimal.ZERO);
            result.put("BB_Upper", BigDecimal.ZERO);
            result.put("BB_Lower", BigDecimal.ZERO);
            return result;
        }

        BigDecimal middleBand = calculateSma(prices, period);

        // Calculate standard deviation over the last 'period' prices
        List<BigDecimal> lastPeriodPrices = prices.subList(prices.size() - period, prices.size());
        BigDecimal sumOfSquares = BigDecimal.ZERO;
        for (BigDecimal price : lastPeriodPrices) {
            BigDecimal diff = price.subtract(middleBand);
            sumOfSquares = sumOfSquares.add(diff.multiply(diff));
        }
        BigDecimal variance = sumOfSquares.divide(new BigDecimal(period), 4, RoundingMode.HALF_UP);
        BigDecimal standardDeviation = BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).setScale(2, RoundingMode.HALF_UP);

        BigDecimal upperBand = middleBand.add(standardDeviation.multiply(new BigDecimal(stdDev))).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lowerBand = middleBand.subtract(standardDeviation.multiply(new BigDecimal(stdDev))).setScale(2, RoundingMode.HALF_UP);

        result.put("BB_Middle", middleBand);
        result.put("BB_Upper", upperBand);
        result.put("BB_Lower", lowerBand);
        return result;
    }

    // --- Signal Interpretation Helper Methods ---

    private String getRSISignal(Double rsi) {
        if (rsi == null) return "N/A";
        if (rsi > 70) return "Overbought";
        if (rsi < 30) return "Oversold";
        if (rsi >= 50 && rsi <= 70) return "Strong Momentum"; // Rising from neutral to strong
        if (rsi >= 30 && rsi < 50) return "Weak Momentum"; // Falling from neutral to weak
        return "Neutral";
    }

    private String getMACDSignal(BigDecimal macdLine, BigDecimal macdSignal, BigDecimal macdHistogram) {
        if (macdLine == null || macdSignal == null || macdHistogram == null) return "N/A";

        // Check for crossovers
        if (macdLine.compareTo(macdSignal) > 0 && macdLine.subtract(macdSignal).compareTo(BigDecimal.ZERO) > 0) {
            // MACD line is above signal line
            if (macdHistogram.compareTo(BigDecimal.ZERO) > 0) { // Histogram is positive and growing/stable
                return "Bullish Trend";
            } else { // Histogram is negative but MACD line is above signal line (recent crossover or weak trend)
                return "Bullish Crossover"; // Could be a recent crossover
            }
        } else if (macdLine.compareTo(macdSignal) < 0 && macdLine.subtract(macdSignal).compareTo(BigDecimal.ZERO) < 0) {
            // MACD line is below signal line
            if (macdHistogram.compareTo(BigDecimal.ZERO) < 0) { // Histogram is negative and growing/stable
                return "Bearish Trend";
            } else { // Histogram is positive but MACD line is below signal line (recent crossover or weak trend)
                return "Bearish Crossover"; // Could be a recent crossover
            }
        } else if (macdLine.compareTo(BigDecimal.ZERO) > 0 && macdSignal.compareTo(BigDecimal.ZERO) > 0) {
            return "Bullish Zone"; // Both positive, but no clear crossover
        } else if (macdLine.compareTo(BigDecimal.ZERO) < 0 && macdSignal.compareTo(BigDecimal.ZERO) < 0) {
            return "Bearish Zone"; // Both negative, but no clear crossover
        }
        return "Neutral";
    }


    private String getBollingerBandSignal(Double latestPrice, BigDecimal bbUpper, BigDecimal bbLower) {
        if (latestPrice == null || bbUpper == null || bbLower == null) return "N/A";
        
        BigDecimal currentPriceBd = BigDecimal.valueOf(latestPrice);

        if (currentPriceBd.compareTo(bbUpper) > 0) {
            return "Upper Band Breakout (Bullish)";
        }
        if (currentPriceBd.compareTo(bbLower) < 0) {
            return "Lower Band Bounce (Bearish)"; // Often a buy signal for mean reversion
        }
        // Could add "Squeeze" logic here if you calculate band width
        return "Within Bands (Neutral)";
    }

    // --- Signal Scoring Logic ---

    /**
     * Calculates a combined signal score based on various technical indicators.
     * @param response The StockAnalysisResponse containing indicator values and signals.
     * @return An integer score.
     */
    private int calculateSignalScore(
            BigDecimal latestPriceBd,
            TechnicalIndicators indicators
    ) {
        int totalScore = 0;
        // RSI Scoring (Max ~30 points)
        totalScore = applyRsi(indicators, totalScore);
        totalScore = applyMacd(indicators, totalScore);
        totalScore = applyBollingerBands(indicators, totalScore, latestPriceBd);
        totalScore = applySma(indicators, totalScore, latestPriceBd);
        totalScore = applyVolume(indicators, totalScore);
        totalScore = applyATR(indicators, totalScore);
        totalScore = applyVolatility(indicators, totalScore);
        totalScore = applySentiment(indicators, totalScore);

        return normalizeScore(totalScore);
    }

    private int normalizeScore(int totalScore) {
        // --- NORMALIZATION
        // Normalize the score from its original range of -35 to 95 to a clean 0-100 scale.
        // Formula: (score - min) / (max - min) * 100
        // (totalScore - (-35)) / (95 - (-35)) * 100 = (totalScore + 35) / 130 * 100
        double normalizedScore = ((double) totalScore + 35.0) / 145.0 * 100.0;

        // Return the rounded integer value
        return (int) Math.round(normalizedScore);
    }

    private int applyVolatility(TechnicalIndicators indicators, int totalScore) {
        // --- Volatility Scoring
        // Penalize for high volatility, as it indicates higher risk
        if (indicators.getVolatility() != null && indicators.getVolatility() > 0.1) {
            totalScore += 5;
        }
        return totalScore;
    }

    private int applyATR(TechnicalIndicators indicators, int totalScore) {
        // --- NEW! Volatility Scoring for Short-Term Trading (Max 10 points) ---
        // Short-term traders want volatility, so we add points for high ATR (Average True Range).
        // The ATR value depends on the stock, so these thresholds may need tuning.
        Double atr = indicators.getAtr();
        if (atr != null) {
            if (atr > 2.0) { // Example threshold for high volatility
                totalScore += 10;
            } else if (atr > 1.0) { // Example threshold for moderate volatility
                totalScore += 5;
            }
        }
        return totalScore;
    }

    private int applyVolume(TechnicalIndicators indicators, int totalScore) {
        // --- Volume Scoring
        // Add points for high trading volume (e.g., above 100 million)
        if (indicators.getVolume() != null && indicators.getVolume() > 100000000) {
            totalScore += 5;
        }
        return totalScore;
    }

    private int applySentiment(TechnicalIndicators indicators, int totalScore) {
        // --- NEW scoring logic for Volume, Volatility, and Sentiment using the new DTO ---
        Double sentiment = indicators.getSentiment();
        if (sentiment != null) {
            // Apply scoring based on the AlphaVantage 5-tier definition
            if (sentiment >= 0.35) {
                // Bullish
                totalScore += 10;
            } else if (sentiment >= 0.15) {
                // Somewhat-Bullish
                totalScore += 5;
            } else if (sentiment <= -0.35) {
                // Bearish
                totalScore -= 10;

            } else if (sentiment <= -0.15) {
                // Somewhat-Bearish
                totalScore -= 5;
            }
            // Neutral sentiment (-0.15 < x < 0.15) adds no points, which is the desired outcome.
        }
        return totalScore;
    }

    private int applySma(TechnicalIndicators indicators, int totalScore, BigDecimal latestPriceBd) {
        // Additional Scoring: Price vs. Moving Averages (Example)
        Double sma50 = indicators.getSma50();
        if (latestPriceBd != null) {
            if (sma50 != null && latestPriceBd.compareTo(BigDecimal.valueOf(sma50)) > 0) {
                totalScore += 5; // Price above 50-day SMA (bullish)
            }
        }
        return totalScore;
    }

    private int applyBollingerBands(TechnicalIndicators indicators, int totalScore, BigDecimal latestPriceBd) {
        // Bollinger Band Scoring (Max 20 points)
        Double bbUpper = indicators.getBbUpper();
        Double bbLower = indicators.getBbLower();
        if (latestPriceBd != null && bbUpper != null && bbLower != null) {
            if (latestPriceBd.compareTo(BigDecimal.valueOf(bbUpper)) > 0) {
                totalScore -= 20; // Breakout above upper band is a strong sell signal
            } else if (latestPriceBd.compareTo(BigDecimal.valueOf(bbLower)) < 0) {
                totalScore += 20; // Breakout below lower band is a strong buy signal
            }
        }
        return totalScore;
    }

    private int applyMacd(TechnicalIndicators indicators, int totalScore) {
        // MACD Scoring (Max 25 points)
        Double macdLine = indicators.getMacdLine();
        Double macdSignal = indicators.getMacdSignal();
        if (macdLine != null && macdSignal != null) {
            BigDecimal macdDelta = BigDecimal.valueOf(macdLine).subtract(BigDecimal.valueOf(macdSignal));
            if (macdDelta.compareTo(BigDecimal.ZERO) > 0) {
                totalScore += Math.min(25, macdDelta.multiply(new BigDecimal(100)).intValue()); // Cap at 25
            }
        }
        return totalScore;
    }

    private int applyRsi(TechnicalIndicators indicators, int totalScore) {
        Double rsi = indicators.getRsi();
        if (rsi != null) {
            if (rsi > 50 && rsi < 70) {
                totalScore += (int) ((rsi - 50) * 1.5); // Max 30
            } else if (rsi <= 30) {
                totalScore += 15; // Oversold bounce potential
            }
        }
        return totalScore;
    }

    /**
     * Interprets the numerical signal score into a human-readable string.
     * @param score The calculated signal score.
     * @return Interpretation string.
     */
    private String interpretSignalScore(int score) {
        if (score >= 80) {
            return "Strong Buy";
        }
        if (score >= 60) {
            return "Buy";
        }
        if (score >= 40) {
            return "Neutral";
        }
        if (score >= 20) {
            return "Sell";
        }
        // Any score below 20 is considered a strong sell signal
        return "Strong Sell";
    }
}

//TODO: clean
    //    private String interpretSignalScore(int score) {
//        if (score >= 40) return "Strong Buy";
//        if (score >= 20) return "Buy";
//        if (score >= 0) return "Neutral";
//        if (score >= -20) return "Sell"; // Assuming negative scores are possible if you add bearish points
//        return "Strong Sell";
//    }


// performStockAnalysis() {
// //         --- 3. Signal Scoring ---
////        int signalScore = calculateSignalScore(
////                latestPriceBd,
////                indicators
////        );
////      String scoreInterpretation = interpretSignalScore(signalScore);
////        response.setScoreInterpretation(scoreInterpretation);
//}