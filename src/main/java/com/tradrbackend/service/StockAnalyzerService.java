package com.tradrbackend.service;

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

        /**
     * Performs comprehensive stock analysis including statistical significance,
     * technical indicator calculations, and signal scoring.
     *
     * @param historicalData A map of LocalDate to BigDecimal (adjusted close price), sorted oldest to newest.
     * @param duration The user-specified duration value for statistical analysis.
     * @param unit The user-specified duration unit for statistical analysis (e.g., "day", "month").
     * @return A StockAnalysisResponse object containing all analysis results.
     */
    public StockAnalysisResponse performStockAnalysis(Map<LocalDate, BigDecimal> historicalData, String ticker, int duration, String unit) {
        StockAnalysisResponse response = new StockAnalysisResponse();
        response.setIndicatorValues(new LinkedHashMap<String, Double>()); // Initialize map
        response.setReceivedDurationValue(duration); // Set received duration
        response.setReceivedDurationUnit(unit); // Set received unit

        if (historicalData == null || historicalData.isEmpty()) {
            response.setMessage("No historical data available for analysis.");
            response.setError("No data");
            response.setStatisticallySignificant(false);
            return response;
        }

                // Convert the full map of historical data into a List of HistoricalPrice DTOs for the response
        List<HistoricalPrice> historicalPriceList = historicalData.entrySet().stream()
                .map(entry -> new HistoricalPrice(entry.getKey(), entry.getValue().doubleValue()))
                // --- FIX IS HERE ---
                // Sort by the 'date' field of the HistoricalPrice object
                .sorted(Comparator.comparing(HistoricalPrice::getDate)) // Corrected Comparator
                .collect(Collectors.toList());

        response.setHistoricalPrices(historicalPriceList); // Set the historical prices in the response

        // Use a list of only the *prices* (BigDecimals) for calculations, maintaining original order
        List<BigDecimal> prices = historicalPriceList.stream()
                                    .map(hp -> BigDecimal.valueOf(hp.getClose()))
                                    .collect(Collectors.toList());
        List<LocalDate> dates = historicalPriceList.stream()
                                   .map(HistoricalPrice::getDate)
                                   .collect(Collectors.toList());

        // Ensure enough data for analysis
        if (prices.size() < 2) {
            response.setMessage("Not enough data for meaningful analysis (less than 2 data points).");
            response.setError("Insufficient data");
            response.setStatisticallySignificant(false);
            return response;
        }

        // Set latest price and received ticker early
        BigDecimal latestPriceBd = prices.get(prices.size() - 1);
        response.setLatestPrice(latestPriceBd.doubleValue());
        response.setReceivedTicker(ticker); // Placeholder for ticker, ideally passed in


        // --- 1. Enhanced Statistical Significance ---
        // Filter historical data to the user-specified period for statistical analysis
        LocalDate endDate = dates.get(dates.size() - 1);
        LocalDate startDateForStats;
        switch (unit.toLowerCase()) {
            case "day":
                startDateForStats = endDate.minusDays(duration - 1);
                break;
            case "week":
                startDateForStats = endDate.minusWeeks(duration - 1);
                break;
            case "month":
                startDateForStats = endDate.minusMonths(duration - 1);
                break;
            case "year":
                startDateForStats = endDate.minusYears(duration - 1);
                break;
            default:
                response.setMessage("Invalid duration unit for statistical analysis: " + unit);
                response.setError("Invalid Unit");
                response.setStatisticallySignificant(false);
                return response;
        }

        List<BigDecimal> pricesForStatisticalAnalysis = historicalData.entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(startDateForStats) && !entry.getKey().isAfter(endDate))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());

        if (pricesForStatisticalAnalysis.size() < 2) {
            response.setMessage("Not enough data for statistical analysis over the specified period (" + duration + " " + unit + ").");
            response.setStatisticallySignificant(false);
            response.setPValue(null);
        } else {
            BigDecimal meanPrice = calculateMean(pricesForStatisticalAnalysis);
            BigDecimal stdDev = calculateStandardDeviation(pricesForStatisticalAnalysis, meanPrice);

            BigDecimal percentageChangeFromMean = BigDecimal.ZERO;
            if (meanPrice.compareTo(BigDecimal.ZERO) != 0) {
                percentageChangeFromMean = latestPriceBd.subtract(meanPrice)
                                                        .divide(meanPrice, 4, RoundingMode.HALF_UP)
                                                        .multiply(new BigDecimal(100));
            }

            // Define "statistical significance" based on a combination of factors:
            // 1. A significant percentage change from the mean over the period (e.g., > 5%)
            // 2. The latest price being a certain number of standard deviations away from the mean (e.g., > 1.5 std dev)
            boolean isSignificantByPercent = Math.abs(percentageChangeFromMean.doubleValue()) > SIGNIFICANCE_THRESHOLD_PERCENT; // Example: 5% change
            
            boolean isSignificantByStdDev = false;
            if (stdDev.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal zScore = latestPriceBd.subtract(meanPrice).divide(stdDev, 2, RoundingMode.HALF_UP);
                isSignificantByStdDev = Math.abs(zScore.doubleValue()) > 1.5; // Example: 1.5 standard deviations away
            }

            boolean finalIsSignificant = isSignificantByPercent || isSignificantByStdDev;

            String statisticalMessage = String.format("Latest price ($%.2f) vs. mean ($%.2f) over %d %s(s): %.2f%% change. Std Dev: %.2f.",
                    latestPriceBd.doubleValue(), meanPrice.doubleValue(), duration, unit, percentageChangeFromMean.doubleValue(), stdDev.doubleValue());

            // Add a note if the actual data used is less than the requested period
            long requestedDays = convertDurationToDays(duration, unit);
            if (pricesForStatisticalAnalysis.size() < requestedDays) {
                statisticalMessage += " (Analysis based on " + pricesForStatisticalAnalysis.size() + " available data points, less than requested period due to API limits).";
            }

            response.setMessage(statisticalMessage);
            response.setStatisticallySignificant(finalIsSignificant);
            response.setPValue(null); // Still no p-value calculated in this simplified example
        }


        // --- 2. Technical Indicator Calculations ---
        // Ensure latestPrice is set, as it might be used by indicator signal methods
        response.setLatestPrice(latestPriceBd.doubleValue());

        Map<String, Double> indicatorValues = new LinkedHashMap<>();

        // SMA 50
        if (prices.size() >= 50) {
            BigDecimal sma50 = calculateSMA(prices, 50);
            indicatorValues.put("SMA50", sma50.doubleValue());
        }
        // SMA 200 - Removed as 100 data points are insufficient
        // if (prices.size() >= 200) {
        //     BigDecimal sma200 = calculateSMA(prices, 200);
        //     indicatorValues.put("SMA200", sma200.doubleValue());
        // }

        // RSI
        if (prices.size() >= RSI_PERIOD) {
            Double rsi = calculateRSI(prices, RSI_PERIOD);
            indicatorValues.put("RSI", rsi);
            response.setRsiSignal(getRSISignal(rsi));
        }

        // MACD
        if (prices.size() >= MACD_SLOW_PERIOD + MACD_SIGNAL_PERIOD) { // Need enough data for longest EMA + signal
            Map<String, BigDecimal> macdResult = calculateMACD(prices, MACD_FAST_PERIOD, MACD_SLOW_PERIOD, MACD_SIGNAL_PERIOD);
            indicatorValues.put("MACD_Line", macdResult.get("MACD_Line").doubleValue());
            indicatorValues.put("MACD_Signal", macdResult.get("MACD_Signal").doubleValue());
            indicatorValues.put("MACD_Histogram", macdResult.get("MACD_Histogram").doubleValue());
            response.setMacdSignal(getMACDSignal(macdResult.get("MACD_Line"), macdResult.get("MACD_Signal"), macdResult.get("MACD_Histogram")));
        }

        // Bollinger Bands
        if (prices.size() >= BOLLINGER_BAND_PERIOD) {
            Map<String, BigDecimal> bbResult = calculateBollingerBands(prices, BOLLINGER_BAND_PERIOD, BOLLINGER_BAND_STD_DEV);
            indicatorValues.put("BB_Middle", bbResult.get("BB_Middle").doubleValue());
            indicatorValues.put("BB_Upper", bbResult.get("BB_Upper").doubleValue());
            indicatorValues.put("BB_Lower", bbResult.get("BB_Lower").doubleValue());
            response.setBollingerBandSignal(getBollingerBandSignal(latestPriceBd.doubleValue(), bbResult.get("BB_Upper"), bbResult.get("BB_Lower")));
        }
        
        response.setIndicatorValues(indicatorValues);

        // --- 3. Signal Scoring ---
        int signalScore = calculateSignalScore(response);
        String scoreInterpretation = interpretSignalScore(signalScore);

        response.setSignalScore(signalScore);
        response.setScoreInterpretation(scoreInterpretation);

        return response;
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
    private BigDecimal calculateSMA(List<BigDecimal> prices, int period) {
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
        BigDecimal ema = calculateSMA(prices.subList(0, period), period); // Initial SMA for first EMA

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

        BigDecimal middleBand = calculateSMA(prices, period);

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
    private int calculateSignalScore(StockAnalysisResponse response) {
        int totalScore = 0;

        Double rsi = response.getIndicatorValues().get("RSI");
        BigDecimal macdLine = response.getIndicatorValues().containsKey("MACD_Line") ? BigDecimal.valueOf(response.getIndicatorValues().get("MACD_Line")) : null;
        BigDecimal macdSignal = response.getIndicatorValues().containsKey("MACD_Signal") ? BigDecimal.valueOf(response.getIndicatorValues().get("MACD_Signal")) : null;
        BigDecimal macdHistogram = response.getIndicatorValues().containsKey("MACD_Histogram") ? BigDecimal.valueOf(response.getIndicatorValues().get("MACD_Histogram")) : null;
        Double latestPrice = response.getLatestPrice();
        BigDecimal bbUpper = response.getIndicatorValues().containsKey("BB_Upper") ? BigDecimal.valueOf(response.getIndicatorValues().get("BB_Upper")) : null;
        BigDecimal bbLower = response.getIndicatorValues().containsKey("BB_Lower") ? BigDecimal.valueOf(response.getIndicatorValues().get("BB_Lower")) : null;
        BigDecimal sma50 = response.getIndicatorValues().containsKey("SMA50") ? BigDecimal.valueOf(response.getIndicatorValues().get("SMA50")) : null;
        //BigDecimal sma200 = response.getIndicatorValues().containsKey("SMA200") ? BigDecimal.valueOf(response.getIndicatorValues().get("SMA200")) : null; // Removed SMA200


        // RSI Scoring (Max ~30 points)
        if (rsi != null) {
            if (rsi > 50 && rsi < 70) {
                totalScore += (int) ((rsi - 50) * 1.5); // Max 30
            } else if (rsi <= 30) {
                totalScore += 15; // Oversold bounce potential
            }
        }

        // MACD Scoring (Max 25 points)
        if (macdLine != null && macdSignal != null) {
            BigDecimal macdDelta = macdLine.subtract(macdSignal);
            if (macdDelta.compareTo(BigDecimal.ZERO) > 0) {
                totalScore += Math.min(25, macdDelta.multiply(new BigDecimal(100)).intValue()); // Cap at 25
            }
        }

        // Bollinger Band Scoring (Max 20 points)
        if (latestPrice != null && bbUpper != null && bbLower != null) {
            BigDecimal currentPriceBd = BigDecimal.valueOf(latestPrice);
            if (currentPriceBd.compareTo(bbUpper) > 0) {
                totalScore += 20; // Breakout
            } else if (currentPriceBd.compareTo(bbLower) < 0) {
                totalScore += 10; // Possible bounce
            }
        }

        // Additional Scoring: Price vs. Moving Averages (Example)
        if (latestPrice != null) {
            BigDecimal currentPriceBd = BigDecimal.valueOf(latestPrice);
            if (sma50 != null && currentPriceBd.compareTo(sma50) > 0) {
                totalScore += 5; // Price above 50-day SMA (bullish)
            }
            // Removed SMA200 scoring
            // if (sma200 != null && currentPriceBd.compareTo(sma200) > 0) {
            //     totalScore += 10; // Price above 200-day SMA (stronger bullish sign)
            // }
        }

        return totalScore;
    }

    /**
     * Interprets the numerical signal score into a human-readable string.
     * @param score The calculated signal score.
     * @return Interpretation string.
     */
    private String interpretSignalScore(int score) {
        if (score >= 40) return "Strong Buy";
        if (score >= 20) return "Buy";
        if (score >= 0) return "Neutral";
        if (score >= -20) return "Sell"; // Assuming negative scores are possible if you add bearish points
        return "Strong Sell";
    }
}

    /**
     * Calculates if the latest price is significantly different from the mean
     * over a given historical period.
     *
     * @param historicalPrices A map of date to adjusted close price for the period.
     * @return A descriptive string indicating significance.
     */
    // public StockAnalysisResponse performStockAnalysis(Map<LocalDate, BigDecimal> historicalPrices) {
    //     if (historicalPrices == null || historicalPrices.isEmpty()) {
    //         return new StockAnalysisResponse("Cannot perform analysis: No historical data available.", false, null);
    //     }

    //     List<BigDecimal> prices = historicalPrices.values().stream()
    //             .collect(Collectors.toList());

    //     // Get the latest price (assuming TreeMap gives sorted dates, last entry is latest)
    //     LocalDate latestDate = historicalPrices.keySet().stream()
    //             .max(Comparator.naturalOrder())
    //             .orElseThrow(() -> new NoSuchElementException("No latest date found."));
    //     BigDecimal latestPrice = historicalPrices.get(latestDate);


    //     // Calculate the sum and mean
    //     BigDecimal sum = BigDecimal.ZERO;
    //     for (BigDecimal price : prices) {
    //         sum = sum.add(price);
    //     }

    //     BigDecimal mean = sum.divide(BigDecimal.valueOf(prices.size()), 4, RoundingMode.HALF_UP);

    //     // Calculate percentage difference
    //     BigDecimal difference = latestPrice.subtract(mean);
    //     BigDecimal percentageDifference = difference
    //             .divide(mean, 4, RoundingMode.HALF_UP)
    //             .multiply(BigDecimal.valueOf(100));
    //     boolean isStatisticallySignificant;
    //     String message;
    //     if (percentageDifference.abs().doubleValue() > SIGNIFICANCE_THRESHOLD_PERCENT) {
    //         message = String.format("The latest price (%.2f) is significantly %s than the mean (%.2f) over the period. Difference: %.2f%%",
    //                 latestPrice,
    //                 percentageDifference.doubleValue() > 0 ? "higher" : "lower",
    //                 mean,
    //                 percentageDifference.abs());
    //             isStatisticallySignificant = true;
    //     } else {
    //         message = String.format("The latest price (%.2f) is not significantly different from the mean (%.2f) over the period. Difference: %.2f%%",
    //                 latestPrice, mean, percentageDifference.abs());
    //             isStatisticallySignificant = false;
    //     }

    //     Random rand = new Random();
    //     Double pValue = rand.nextDouble(); // Generates a random double between 0.0 and 1.0
    //     // Optional: Make pValue correlate roughly with significance for demo purposes
    //     if (isStatisticallySignificant && pValue >= 0.05) {
    //         pValue = rand.nextDouble() * 0.04; // Ensure pValue is low if significant
    //     } else if (!isStatisticallySignificant && pValue < 0.05) {
    //         pValue = 0.05 + rand.nextDouble() * 0.94; // Ensure pValue is high if not significant
    //     }

    //     return new StockAnalysisResponse(message, isStatisticallySignificant, pValue);
    // }
