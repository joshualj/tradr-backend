package com.tradrbackend.service;

import com.tradrbackend.model.AlphaVantageData;
import com.tradrbackend.model.TechnicalIndicators;
import com.tradrbackend.response.StockAnalysisResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class FinancialDataService {

    // Define a set of valid units to use for validation
    private static final Set<String> VALID_UNITS = Set.of("day", "week", "month", "year");

    private final AlphaVantageService alphaVantageService;
    private final FinnHubService finnHubService;
    private final StockAnalyzerService stockAnalyzer;

    public FinancialDataService(AlphaVantageService alphaVantageService, FinnHubService finnHubService, StockAnalyzerService stockAnalyzer) {
        this.alphaVantageService = alphaVantageService;
        this.finnHubService = finnHubService;
        this.stockAnalyzer = stockAnalyzer;
    }

    public Mono<StockAnalysisResponse> getStockAnalysisResponse(String ticker, int duration, String unit, boolean useRegressionCoefficientModel) {
        // Step 1: Perform input validation.
        try {
            validateInputs(duration, unit);
        } catch (IllegalArgumentException e) {
            // If validation fails, return an error Mono immediately.
            return Mono.just(createErrorResponse(e.getMessage()));
        }

        //Step 2: Create a single Mono for all Alpha Vantage data.
        Mono<AlphaVantageData> alphaVantageDataMono = alphaVantageService.getAlphaVantageData(ticker)
                .onErrorResume(RuntimeException.class, e -> {
                    System.err.println("Error fetching Alpha Vantage data: " + e.getMessage());
                    return Mono.error(new RuntimeException("Error fetching Alpha Vantage data.", e));
                });
//         --- END CONSOLIDATED API CALL ---

        // Create a Mono for fetching news sentiment from AlphaVantage.
        Mono<Double> newsSentimentMono = alphaVantageService.getNewsSentiment(ticker)
                .onErrorResume(RuntimeException.class, e -> {
                    System.err.println("Error fetching news sentiment: " + e.getMessage());
                    return Mono.error(new RuntimeException("Error fetching news sentiment.", e));
                });

        // Step 3: Concurrently fetch data from other services.
        // We assume FinnHubService returns a reactive Mono.
        Mono<BigDecimal> marketCapMono = finnHubService.getMarketCapitalization(ticker)
                .onErrorMap(IOException.class, e -> new RuntimeException("Error fetching market capitalization", e));

        // Step 4: Combine all Monos and perform the final analysis.
        // We use Mono.zip to wait for all the API calls to complete.
        return Mono.zip(alphaVantageDataMono, marketCapMono, newsSentimentMono)
                .flatMap(tuple -> {
                    Map<LocalDate, BigDecimal> historicalData = tuple.getT1().getHistoricalPrices();
                    BigDecimal latestVolume = tuple.getT1().getLatestVolume();
                    BigDecimal marketCap = tuple.getT2();
                    Double sentiment = tuple.getT3(); // Extract the sentiment value

                    // Chain the reactive volatility calculation here.
                    // flatMap is used to switch from Mono<Double> to the final Mono<StockAnalysisResponse>.
                    return calculateVolatility(historicalData)
                            .flatMap(volatility -> {
                                StockAnalysisResponse response = new StockAnalysisResponse();

                                // Explicitly set the request parameters on the response object.
                                setInputsToResponse(response, ticker, duration, unit);
                                TechnicalIndicators technicalIndicators = new TechnicalIndicators(
                                        volatility,
                                        marketCap.doubleValue(),
                                        latestVolume.doubleValue(),
                                        sentiment
                                );
                                //TODO: clean
                                // The original code had a getLatestVolume call here, but it wasn't
                                // included in the Mono.zip. We can add a placeholder value.
                                // If alphaVantageService.getLatestVolume also returns a Mono,
                                // we would zip it here as well.
//                                response.setVolatility(volatility);
//                                response.setVolume(latestVolume.doubleValue());
//                                response.setMarketCap(marketCap.doubleValue());
//                                response.setSentiment(sentiment); // Set the new sentiment value

                                // Call the StockAnalyzerService with the combined data.
                                try {
                                    return Mono.just(stockAnalyzer.performStockAnalysis(
                                            historicalData,
                                            response,
                                            technicalIndicators,
                                            duration,
                                            unit,
                                            useRegressionCoefficientModel
                                    ));
                                } catch (IOException | InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                })
                .onErrorResume(e -> Mono.just(createErrorResponse(e.getMessage())));
    }

    /**
     * Performs basic validation on the duration and unit parameters.
     *
     * @param duration The duration for the analysis.
     * @param unit The unit of duration.
     * @throws IllegalArgumentException if the inputs are invalid.
     */
    private void validateInputs(int duration, String unit) {
        if (duration <= 0) {
            throw new IllegalArgumentException("Duration must be a positive integer.");
        }
        if (!VALID_UNITS.contains(unit.toLowerCase())) {
            throw new IllegalArgumentException("Invalid unit. Supported units are: " + String.join(", ", VALID_UNITS));
        }
    }

    /**
     * Creates a standardized error response object.
     * @param message The error message.
     * @return A StockAnalysisResponse object with error details.
     */
    private StockAnalysisResponse createErrorResponse(String message) {
        StockAnalysisResponse errorResponse = new StockAnalysisResponse();
        errorResponse.setMessage("Error fetching or processing stock data: " + message);
        errorResponse.setStatisticallySignificant(false);
        errorResponse.setPValue(null);
        errorResponse.setError("Invalid Input"); // Custom error code for better client-side handling
        return errorResponse;
    }

    private void setInputsToResponse(StockAnalysisResponse stockAnalysisResponse, String ticker, int duration, String unit) {
        stockAnalysisResponse.setReceivedTicker(ticker); // Set received ticker
        stockAnalysisResponse.setReceivedDurationValue(duration); // Set received duration
        stockAnalysisResponse.setReceivedDurationUnit(unit); // Set received unit
    }


    private StockAnalysisResponse getHistoricalDataErrorResponse(StockAnalysisResponse stockAnalysisResponse) {
        stockAnalysisResponse.setMessage("No historical data available for analysis.");
        stockAnalysisResponse.setError("No data");
        stockAnalysisResponse.setStatisticallySignificant(false);
        return stockAnalysisResponse;
    }

    /**
     * Calculates historical volatility for a stock based on the standard deviation of daily returns.
     * This is now a reactive method that returns a Mono<Double>.
     *
     * @param historicalPrices A map of closing prices.
     * @return A Mono emitting the calculated volatility as a Double.
     */
    private Mono<Double> calculateVolatility(Map<LocalDate, BigDecimal> historicalPrices) {
        return Mono.fromCallable(() -> {
            // Sort prices by date to ensure correct order
            List<BigDecimal> sortedPrices = historicalPrices.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .toList();

            if (sortedPrices.size() < 2) {
                return 0.0;
            }

            // Calculate daily returns
            List<Double> returns = new ArrayList<>();
            for (int i = 1; i < sortedPrices.size(); i++) {
                BigDecimal previousPrice = sortedPrices.get(i - 1);
                BigDecimal currentPrice = sortedPrices.get(i);
                if (previousPrice.compareTo(BigDecimal.ZERO) > 0) {
                    Double dailyReturn = currentPrice.subtract(previousPrice)
                            .divide(previousPrice, 4, RoundingMode.HALF_UP)
                            .doubleValue();
                    returns.add(dailyReturn);
                }
            }

            if (returns.isEmpty()) {
                return 0.0;
            }

            // Calculate the mean of the returns
            Double meanReturn = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

            // Calculate the standard deviation of the returns (volatility)
            double variance = returns.stream()
                    .mapToDouble(r -> Math.pow(r - meanReturn, 2))
                    .average()
                    .orElse(0.0);

            return Math.sqrt(variance);
        }).subscribeOn(Schedulers.boundedElastic()); // Run this CPU-intensive task on a separate thread pool.
    }
}
//TODO: clean
// Step 2: Create a Mono for the blocking AlphaVantage call.
// We wrap the blocking call in a Mono and run it on a dedicated thread pool
// to prevent blocking the main event loop. This is the key to a reactive service.
// --- CONSOLIDATED API CALL ---

//        Mono<LinkedHashMap<LocalDate, BigDecimal>> historicalDataMono = alphaVantageService.getHistoricalAdjustedPrices(ticker)
//                .onErrorResume(RuntimeException.class, e -> {
//                    System.err.println("Error fetching historical data: " + e.getMessage());
//                    // This onErrorResume is now correctly handling errors from the upstream Mono.
//                    return Mono.error(new RuntimeException("Error fetching historical data.", e));
//                });
//
//        Mono<BigDecimal> latestVolumeMono = alphaVantageService.getLatestVolume(ticker)
//                .onErrorResume(RuntimeException.class, e -> {
//                    System.err.println("Error fetching latest volume: " + e.getMessage());
//                    return Mono.error(new RuntimeException("Error fetching latest volume.", e));
//                });