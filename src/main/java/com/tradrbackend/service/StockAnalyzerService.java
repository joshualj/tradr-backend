package com.tradrbackend.service;

import org.springframework.stereotype.Service;

import com.tradrbackend.response.StockAnalysisResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.stream.Collectors;

@Service // Marks this as a Spring service component
public class StockAnalyzerService {

    // You might want to make this configurable or a constant
    private static final double SIGNIFICANCE_THRESHOLD_PERCENT = 5.0; // 5% difference

    /**
     * Calculates if the latest price is significantly different from the mean
     * over a given historical period.
     *
     * @param historicalPrices A map of date to adjusted close price for the period.
     * @return A descriptive string indicating significance.
     */
    public StockAnalysisResponse performStockAnalysis(Map<LocalDate, BigDecimal> historicalPrices) {
        if (historicalPrices == null || historicalPrices.isEmpty()) {
            return new StockAnalysisResponse("Cannot perform analysis: No historical data available.", false, null);
        }

        List<BigDecimal> prices = historicalPrices.values().stream()
                .collect(Collectors.toList());

        // Get the latest price (assuming TreeMap gives sorted dates, last entry is latest)
        LocalDate latestDate = historicalPrices.keySet().stream()
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new NoSuchElementException("No latest date found."));
        BigDecimal latestPrice = historicalPrices.get(latestDate);


        // Calculate the sum and mean
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal price : prices) {
            sum = sum.add(price);
        }

        BigDecimal mean = sum.divide(BigDecimal.valueOf(prices.size()), 4, RoundingMode.HALF_UP);

        // Calculate percentage difference
        BigDecimal difference = latestPrice.subtract(mean);
        BigDecimal percentageDifference = difference
                .divide(mean, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        boolean isStatisticallySignificant;
        String message;
        if (percentageDifference.abs().doubleValue() > SIGNIFICANCE_THRESHOLD_PERCENT) {
            message = String.format("The latest price (%.2f) is significantly %s than the mean (%.2f) over the period. Difference: %.2f%%",
                    latestPrice,
                    percentageDifference.doubleValue() > 0 ? "higher" : "lower",
                    mean,
                    percentageDifference.abs());
                isStatisticallySignificant = true;
        } else {
            message = String.format("The latest price (%.2f) is not significantly different from the mean (%.2f) over the period. Difference: %.2f%%",
                    latestPrice, mean, percentageDifference.abs());
                isStatisticallySignificant = false;
        }

        Random rand = new Random();
        Double pValue = rand.nextDouble(); // Generates a random double between 0.0 and 1.0
        // Optional: Make pValue correlate roughly with significance for demo purposes
        if (isStatisticallySignificant && pValue >= 0.05) {
            pValue = rand.nextDouble() * 0.04; // Ensure pValue is low if significant
        } else if (!isStatisticallySignificant && pValue < 0.05) {
            pValue = 0.05 + rand.nextDouble() * 0.94; // Ensure pValue is high if not significant
        }

        return new StockAnalysisResponse(message, isStatisticallySignificant, pValue);
    }
}
