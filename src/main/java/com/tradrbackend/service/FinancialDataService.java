package com.tradrbackend.service;

import com.tradrbackend.model.AlphaVantageData;
import com.tradrbackend.model.TechnicalIndicators;
import com.tradrbackend.response.StockAnalysisResponse;
import com.tradrbackend.service.api.AlphaVantageService;
import com.tradrbackend.service.api.FinnHubService;
import com.tradrbackend.service.api.FmpService;
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
    private final SimFinDataService simFinDataService;
    private final FmpService fmpService;
    private final StockAnalyzerService stockAnalyzer;

    public FinancialDataService(AlphaVantageService alphaVantageService, FinnHubService finnHubService,
                                StockAnalyzerService stockAnalyzer, SimFinDataService simFinDataService,
                                FmpService fmpService) {
        this.simFinDataService = simFinDataService;
        this.alphaVantageService = alphaVantageService;
        this.finnHubService = finnHubService;
        this.fmpService = fmpService;
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

        Mono<Double> sp500PeProxyMono = alphaVantageService.getSp500PeProxy();

        Mono<Double> newsSentimentMono = Mono.just(0.0);

        // Commenting out for now because News Sentiment is not currently used by models
//        Mono<Double> newsSentimentMono = alphaVantageService.getNewsSentiment(ticker)
//                .onErrorResume(RuntimeException.class, e -> {
//                    System.err.println("Error fetching news sentiment: " + e.getMessage());
//                    return Mono.error(new RuntimeException("Error fetching news sentiment.", e));
//                });

        // Step 3: Concurrently fetch data from other services.
        // We assume FinnHubService returns a reactive Mono.
        Mono<BigDecimal> marketCapMono = finnHubService.getMarketCapitalization(ticker)
                .onErrorMap(IOException.class, e -> new RuntimeException("Error fetching market capitalization", e));

        Mono<Double> peRatioMono = finnHubService.getTtmEps(ticker)
                .onErrorMap(IOException.class, e -> new RuntimeException("Error fetching TTM EPS", e));

//        Mono<Double> peRatioMono = fmpService.getPriceToEarningsRatioTTM(ticker)
//                .onErrorMap(IOException.class, e -> new RuntimeException("Error fetching TTM PE", e));

        Mono<Long> sharesOutstandingMono = fmpService.getOutstandingShares(ticker)
                .onErrorMap(IOException.class, e -> new RuntimeException("Error fetching shares outstanding", e));

        //TODO: for up-to-date data, convert to API call rather than fetching from DB call - simFinData service is not yet auto-updated
        Mono<BigDecimal> latestNetIncomeMono = simFinDataService.getLatestNetIncomeCommon(ticker)
                .onErrorMap(IOException.class, e -> new RuntimeException("Error fetching latest volume", e));

        // Step 4: Combine all Monos and perform the final analysis.
        // We use Mono.zip to wait for all the API calls to complete.
        return Mono.zip(alphaVantageDataMono,
                        marketCapMono,
                        newsSentimentMono,
                        latestNetIncomeMono,
                        peRatioMono,
                        sharesOutstandingMono,
                        sp500PeProxyMono)
                .flatMap(tuple -> {
                    Map<LocalDate, BigDecimal> historicalData = tuple.getT1().getHistoricalPrices();
                    BigDecimal latestVolume = tuple.getT1().getLatestVolume();
                    List<BigDecimal> volumes20Day = tuple.getT1().getVolumes20Day();
                    BigDecimal marketCap = tuple.getT2();
                    Double sentiment = tuple.getT3(); // Extract the sentiment value
                    BigDecimal latestNetIncome = tuple.getT4();
                    double peRatioTtm = tuple.getT5();
                    double sharesOutstanding = tuple.getT6();
                    double sp500PeProxy = tuple.getT7();

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
                                        volumes20Day,
                                        sentiment,
                                        latestNetIncome,
                                        peRatioTtm,
                                        sharesOutstanding,
                                        sp500PeProxy
                                );

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