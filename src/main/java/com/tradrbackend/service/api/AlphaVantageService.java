package com.tradrbackend.service.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.tradrbackend.config.AlphaVantageKeyConfig;
import com.tradrbackend.model.AlphaVantageData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service // Marks this as a Spring service component
public class AlphaVantageService {

    @Value("${alphavantage.api.key}") // Inject API key from application.properties
    private List<String> apiKey;

    @Value("${alphavantage.api.key2}") // Inject API key from application.properties
    private List<String> apiKey2;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

        // Static final variables for the JSON keys
    private static final String TIME_SERIES_DAILY_KEY = "Time Series (Daily)";
    private static final String TIME_SERIES_DAILY_FUNCTION = "TIME_SERIES_DAILY";
    private static final String PREMIUM_TIME_SERIES_DAILY_ADJUSTED_FUNCTION = "TIME_SERIES_DAILY_ADJUSTED"; // New: for adjusted daily data
    private static final String CLOSE_PRICE_KEY = "4. close"; // For TIME_SERIES_DAILY
    private static final String PREMIUM_ADJUSTED_CLOSE_PRICE_KEY = "5. adjusted close"; // For TIME_SERIES_DAILY_ADJUSTED
    private static final String LATEST_VOLUME_KEY = "5. volume";
    private static final String ERROR_MESSAGE_KEY = "Error Message";
    private static final String API_NOTE_KEY = "Note";
    private static final String API_INFORMATION_KEY = "Information";
    // --- Benchmark Constants ---
    // Alpha Vantage Symbol for S&P 500 Index (or a similar major index)
    private static final String SP500_TICKER = "SPY";
    private static final int ROLLING_WINDOW = 252; // Approximately one trading year
    private final AlphaVantageKeyConfig keyConfig; // Inject the key configuration manager



    // Constructor for dependency injection of RestClient
    public AlphaVantageService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                               AlphaVantageKeyConfig keyConfig) {
        this.restClient = restClientBuilder.baseUrl("https://www.alphavantage.co").build();
        this.objectMapper = objectMapper;
        this.keyConfig = keyConfig;
    }

    /**
     * Helper method to check if the response indicates a rate limit error (using "Note" or "Information").
     * @param rootNode The root JSON node of the API response.
     * @return true if a rate limit or usage warning is present.
     */
    private boolean isRateLimitError(JsonNode rootNode) {
        return rootNode.has(API_NOTE_KEY) || rootNode.has(API_INFORMATION_KEY);
    }

    /**
     * Helper method to extract the warning message from the "Note" or "Information" field.
     * @param rootNode The root JSON node of the API response.
     * @return The error message text.
     */
    private String getRateLimitMessage(JsonNode rootNode) {
        if (rootNode.has(API_NOTE_KEY)) {
            return rootNode.get(API_NOTE_KEY).asText();
        }
        if (rootNode.has(API_INFORMATION_KEY)) {
            return rootNode.get(API_INFORMATION_KEY).asText();
        }
        return "Unknown rate limit error message.";
    }

    /**
     * Private helper method to fetch the daily time series data from Alpha Vantage.
     * This method makes the actual API call and handles basic error checking.
     *
     * @param ticker The stock ticker symbol.
     * @return A Mono emitting a JsonNode containing the full API response.
     */
    private Mono<JsonNode> fetchDailyTimeSeriesData(String ticker) {
        // Use Mono.fromCallable to wrap the blocking code.
        return Mono.fromCallable(() -> {

                    boolean usesKeyOne = true;
                    int maxRetries = keyConfig.getKeys(usesKeyOne).size();

                    // Loop through all available keys (up to maxRetries)
                    for (int attempt = 0; attempt < maxRetries; attempt++) {

                        // 1. Get the key for the current attempt. The KeyConfig manages the rotation index.
                        String currentKey = keyConfig.getCurrentKey(usesKeyOne);

                        try {
                            String uri = UriComponentsBuilder.fromPath("/query")
                                    .queryParam("function", TIME_SERIES_DAILY_FUNCTION)
                                    .queryParam("symbol", ticker)
                                    .queryParam("outputsize", "compact")
                                    .queryParam("apikey", currentKey) // Use the rotated key
                                    .toUriString();

                            // The network call is a blocking operation.
                            String jsonResponse = restClient.get()
                                    .uri(uri)
                                    .retrieve()
                                    .body(String.class);

                            JsonNode rootNode = objectMapper.readTree(jsonResponse);

                            // --- API KEY ROTATION/RETRY LOGIC (Handles "Note" and "Information") ---
                            if (isRateLimitError(rootNode)) {
                                String apiNote = getRateLimitMessage(rootNode);
                                System.err.println("Alpha Vantage API Limit Reached with key " + currentKey + " (Daily): " + apiNote);

                                if (attempt < maxRetries - 1) {
                                    keyConfig.rotateKey(usesKeyOne); // Rotate key for the next iteration
                                    System.out.println("DEBUG: Rotating key and retrying for " + ticker + " (Daily)...");
                                    continue; // Retry with the next key
                                } else {
                                    // Last key failed, throw a final exception
                                    throw new RuntimeException("All " + maxRetries + " API keys exhausted for " + ticker + " (Daily). Limit reached.");
                                }
                            }

                            // Handle generic error message (not limit-related, e.g., bad symbol)
                            if (rootNode.has(ERROR_MESSAGE_KEY)) {
                                String errorMessage = rootNode.get(ERROR_MESSAGE_KEY).asText();
                                System.err.println("Alpha Vantage API Error: " + errorMessage);
                                throw new RuntimeException("Alpha Vantage API Error: " + errorMessage);
                            }

                            // Success checks
                            JsonNode timeSeriesNode = rootNode.get(TIME_SERIES_DAILY_KEY);
                            if (timeSeriesNode == null) {
                                throw new RuntimeException("Could not find '" + TIME_SERIES_DAILY_KEY + "' in Alpha Vantage response.");
                            }

                            System.out.println("Successfully fetched daily time series data for " + ticker + " using key: " + currentKey);
                            return rootNode; // Success! Exit the loop and return the result.

                        } catch (Exception e) {
                            // This handles non-API-specific errors (network, parsing, etc.)
                            System.err.println("Non-API Error fetching daily data for " + ticker + " with key " + currentKey + ": " + e.getMessage());
                            // Since it's not a known API limit, we fail immediately to prevent infinite rotation
                            throw new RuntimeException("Failed to fetch daily time series data.", e);
                        }
                    }

                    // Fallback: If the loop somehow finishes without success or final exception
                    throw new RuntimeException("Exhausted all API key retries after loop finished.");
                })
                // Offload the blocking work to a dedicated thread pool.
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * New public method to get all necessary Alpha Vantage data from a single API call.
     * @param ticker The stock ticker symbol.
     * @return A Mono emitting an AlphaVantageData record.
     */
    public Mono<AlphaVantageData> getAlphaVantageData(String ticker) {
        // Call the private helper method and process the full response.
        return fetchDailyTimeSeriesData(ticker)
                .map(rootNode -> {
                    JsonNode timeSeriesNode = rootNode.get(TIME_SERIES_DAILY_KEY);

                    // 1. Extract historical prices
                    LinkedHashMap<LocalDate, Map<String, BigDecimal>> historicalDataMap = StreamSupport.stream(
                                    Spliterators.spliteratorUnknownSize(timeSeriesNode.fields(), Spliterator.ORDERED), false)
                            .collect(Collectors.toMap(
                                    entry -> LocalDate.parse(entry.getKey()),
                                    entry -> {
                                        JsonNode dataNode = entry.getValue();
                                        Map<String, BigDecimal> dailyMetrics = new HashMap<>();
                                        dailyMetrics.put("close", new BigDecimal(dataNode.get(CLOSE_PRICE_KEY).asText()));
                                        dailyMetrics.put("volume", new BigDecimal(dataNode.get(LATEST_VOLUME_KEY).asText()));
                                        return dailyMetrics;
                                    }

                            ))
                            .entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue,
                                    (oldValue, newValue) -> oldValue,
                                    LinkedHashMap::new
                            ));

                    // a. Historical Prices (used for EMA, RSI, BB, Volatility)
                    LinkedHashMap<LocalDate, BigDecimal> historicalPrices = historicalDataMap.entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    entry -> entry.getValue().get("close"),
                                    (oldValue, newValue) -> oldValue,
                                    LinkedHashMap::new
                            ));

                    // b. List of last 20 trading volumes (used for Relative Volume)
                    // Get the volumes, reverse the list (so the latest is last), and take the last 20.
                    // Since historicalDataMap is sorted ASCENDING by date, we just take the last 20 elements.
                    List<BigDecimal> volumes20Day = historicalDataMap.values().stream()
                            .skip(Math.max(0, historicalDataMap.size() - 20)) // Skip all but the last 20
                            .map(map -> map.get("volume"))
                            .collect(Collectors.toList());

                    // --- 3. Extract latest volume and price ---
                    // The latest date is the last key in the sorted map.
                    LocalDate latestDate = historicalPrices.keySet().stream().reduce((a, b) -> b).orElse(null);
                    BigDecimal latestVolume = (latestDate != null) ? historicalDataMap.get(latestDate).get("volume") : BigDecimal.ZERO;

                    // Return the new consolidated data record.
                    return new AlphaVantageData(historicalPrices, latestVolume, volumes20Day);
                });
    }

    /**
     * Private helper method to fetch the daily time series data from Alpha Vantage
     * using the COMPACT output size (but would like a 252-day benchmark lookback).
     *
     * @param ticker The stock ticker symbol.
     * @return A Mono emitting a JsonNode containing the full API response.
     */
    public Mono<JsonNode> fetchBenchmarkData(String ticker) {
        return Mono.fromCallable(() -> {

                    boolean usesKeyOne = false;
                    int maxRetries = keyConfig.getKeys(usesKeyOne).size();

                    for (int attempt = 0; attempt < maxRetries; attempt++) {

                        String currentKey = keyConfig.getCurrentKey(usesKeyOne);

                        try {
                            String uri = UriComponentsBuilder.fromPath("/query")
                                    .queryParam("function", TIME_SERIES_DAILY_FUNCTION)
                                    .queryParam("symbol", ticker)
                                    .queryParam("outputsize", "compact")
                                    .queryParam("apikey", currentKey) // Use the rotated key
                                    .toUriString();

                            // Blocking network call
                            String jsonResponse = restClient.get()
                                    .uri(uri)
                                    .retrieve()
                                    .body(String.class);

                            JsonNode rootNode = objectMapper.readTree(jsonResponse);

                            // --- API KEY ROTATION/RETRY LOGIC (Handles "Note" and "Information") ---
                            if (isRateLimitError(rootNode)) {
                                String apiNote = getRateLimitMessage(rootNode);
                                System.err.println("Alpha Vantage API Limit Reached (Benchmark) with key " + currentKey + ": " + apiNote);

                                if (attempt < maxRetries - 1) {
                                    keyConfig.rotateKey(usesKeyOne);
                                    System.out.println("DEBUG: Rotating key and retrying benchmark data for " + ticker + "...");
                                    continue; // Retry with the next key
                                } else {
                                    throw new RuntimeException("All " + maxRetries + " API keys exhausted for benchmark " + ticker + ". Limit reached.");
                                }
                            }

                            // Centralized error handling for non-limit errors
                            if (rootNode.has(ERROR_MESSAGE_KEY)) {
                                String errorMessage = rootNode.get(ERROR_MESSAGE_KEY).asText();
                                System.err.println("Alpha Vantage API Error (Benchmark): " + errorMessage);
                                throw new RuntimeException("Alpha Vantage API Error (Benchmark): " + errorMessage);
                            }

                            JsonNode timeSeriesNode = rootNode.get(TIME_SERIES_DAILY_KEY);
                            if (timeSeriesNode == null) {
                                throw new RuntimeException("Could not find '" + TIME_SERIES_DAILY_KEY + "' in Alpha Vantage benchmark response.");
                            }

                            System.out.println("Successfully fetched time series data for benchmark " + ticker + " using key: " + currentKey);
                            return rootNode; // Success! Return the result.

                        } catch (Exception e) {
                            System.err.println("Error fetching time series data for benchmark " + ticker + " with key " + currentKey + ": " + e.getMessage());
                            // Throw immediately for non-rate-limit errors
                            throw new RuntimeException("Failed to fetch benchmark time series data.", e);
                        }
                    }

                    // Fallback for unexpected loop exit
                    throw new RuntimeException("Exhausted all API key retries after loop finished for benchmark data.");
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Fetches the S&P 500 historical data and calculates the Price Proxy:
     * (Latest Close / X-Day Simple Moving Average), where X is the number of available data points (max 100 on free tier).
     *
     * @return A Mono emitting the calculated S&P 500 Price Proxy (a Double).
     */
    public Mono<Double> getSp500PeProxy() {
        // Call the new helper method for the benchmark ticker
        return fetchBenchmarkData(SP500_TICKER)
                .map(rootNode -> {
                    JsonNode timeSeriesNode = rootNode.get(TIME_SERIES_DAILY_KEY);

                    // Determine the actual number of data points available (max 100 for 'compact')
                    int availableDays = timeSeriesNode.size();

                    if (availableDays == 0) {
                        throw new RuntimeException("No time series data available for S&P 500 benchmark.");
                    }

                    // 1. Iterate over all available days to sum up the closes
                    Iterator<String> dateFields = timeSeriesNode.fieldNames();
                    int count = 0;
                    BigDecimal sumOfCloses = BigDecimal.ZERO;
                    AtomicReference<BigDecimal> latestClose = new AtomicReference<>();

                    while (dateFields.hasNext() && count < availableDays) {
                        String dateKey = dateFields.next();
                        JsonNode dayData = timeSeriesNode.path(dateKey);
                        String closePriceString = dayData.get(CLOSE_PRICE_KEY).asText();
                        BigDecimal closePrice = new BigDecimal(closePriceString);

                        if (count == 0) {
                            // Capture the most recent (first) close price
                            latestClose.set(closePrice);
                        }

                        // Sum up all available prices for the average
                        sumOfCloses = sumOfCloses.add(closePrice);
                        count++;
                    }

                    // 2. Calculate the Simple Moving Average (SMA) over the available window
                    BigDecimal windowSize = new BigDecimal(availableDays);
                    BigDecimal sma = sumOfCloses.divide(windowSize, 4, BigDecimal.ROUND_HALF_UP);

                    // 3. Calculate the final proxy ratio: Latest Close / SMA
                    BigDecimal proxyRatio = latestClose.get().divide(sma, 6, BigDecimal.ROUND_HALF_UP);

                    return proxyRatio.doubleValue();
                })
                .onErrorResume(e -> {
                    System.err.println("AlphaVantageService: Error calculating S&P 500 proxy: " + e.getMessage());
                    return Mono.error(new RuntimeException("Error calculating S&P 500 benchmark proxy.", e));
                });
    }

    /**
     * Fetches the latest trading volume for a given stock ticker.
     * This method is non-blocking and returns a Mono.
     * @param ticker The stock ticker symbol.
     * @return A Mono emitting a BigDecimal containing the latest volume.
     */
    public Mono<BigDecimal> getLatestVolume(String ticker) {
        // Wrap the blocking logic in Mono.fromCallable()
        return Mono.fromCallable(() -> {
                    try {
                        String jsonResponse = restClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/query")
                                        .queryParam("function", TIME_SERIES_DAILY_FUNCTION)
                                        .queryParam("symbol", ticker)
                                        .queryParam("apikey", apiKey)
                                        .build())
                                .retrieve()
                                .body(String.class);

                        JsonNode rootNode = objectMapper.readTree(jsonResponse);

                        if (rootNode.has(ERROR_MESSAGE_KEY)) {
                            String errorMessage = rootNode.get(ERROR_MESSAGE_KEY).asText();
                            System.err.println("AlphaVantageService: API returned an error for " + ticker + ": " + errorMessage);
                            throw new IOException("Alpha Vantage API Error: " + errorMessage);
                        }
                        if (rootNode.has(API_NOTE_KEY)) {
                            throw new IOException("Alpha Vantage API Limit Reached: " + rootNode.get(API_NOTE_KEY).asText());
                        }

                        JsonNode timeSeriesNode = rootNode.path(TIME_SERIES_DAILY_KEY);

                        if (timeSeriesNode.isMissingNode() || !timeSeriesNode.isObject() || timeSeriesNode.isEmpty()) {
                            System.err.println("AlphaVantageService: Time series data not found for " + ticker);
                            throw new IOException("Time series data not found for " + ticker);
                        }

                        // Get the first entry (which is the latest date)
                        String latestDateKey = timeSeriesNode.fieldNames().next();
                        JsonNode latestData = timeSeriesNode.path(latestDateKey);

                        if (latestData.has(LATEST_VOLUME_KEY)) {
                            return new BigDecimal(latestData.get(LATEST_VOLUME_KEY).asText());
                        }

                        throw new IOException("Volume data not found for latest date in response.");
                    } catch (Exception e) {
                        System.err.println("AlphaVantageService: Error fetching latest volume for " + ticker + ": " + e.getMessage());
                        // Propagate the exception to the Mono
                        throw new RuntimeException("Failed to fetch latest volume.", e);
                    }
                })
                // Offload the blocking work to a dedicated thread pool
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Fetches the latest sentiment score for a given ticker from AlphaVantage.
     * This method is non-blocking and returns a Mono.
     * @param ticker The stock ticker symbol.
     * @return A Mono emitting the sentiment score as a Double.
     */
    public Mono<Double> getNewsSentiment(String ticker) {
        // Wrap the blocking logic in Mono.fromCallable()
        String url = UriComponentsBuilder.fromPath("/query")
                .queryParam("function", "NEWS_SENTIMENT")
                .queryParam("tickers", ticker)
                .queryParam("apikey", apiKey2)
                .toUriString();

        // Wrap the blocking logic in Mono.fromCallable()
        return Mono.fromCallable(() -> {
                    try {
                        // The actual network call is a blocking operation.
                        String response = restClient.get()
                                .uri(url) // Use the cleanly built URL
                                .retrieve()
                                .body(String.class);
                        return parseSentimentScore(response);
                    } catch (Exception e) {
                        // Propagate the error in a reactive way
                        throw new RuntimeException("Error fetching or parsing news sentiment for " + ticker, e);
                    }
                })
                // Offload the blocking work to a dedicated thread pool
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Parses the JSON response from Alpha Vantage's news sentiment endpoint.
     * @param json The JSON string to parse.
     * @return The sentiment score as a Double, or 0.0 if not found.
     */
    private Double parseSentimentScore(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode feedNode = root.path("feed");

            if (feedNode.isArray() && !feedNode.isEmpty()) {
                JsonNode firstArticle = feedNode.get(0);
                return firstArticle.path("overall_sentiment_score").asDouble(0.0);
            }
        } catch (IOException e) {
            System.err.println("Error parsing sentiment JSON: " + e.getMessage());
        }
        return 0.0;
    }
}

//TODO: clean
//    /**
//     * Fetches historical adjusted prices for a given ticker from Alpha Vantage.
//     * This method is non-blocking and returns a Mono, suitable for reactive pipelines.
//     * The blocking I/O is offloaded to a separate thread using Schedulers.boundedElastic().
//     * @param ticker The stock ticker symbol.
//     * @return A Mono emitting a LinkedHashMap of historical prices, or an error if the fetch fails.
//     */
//    public Mono<LinkedHashMap<LocalDate, BigDecimal>> getHistoricalAdjustedPrices(String ticker) {
//        // Use Mono.fromCallable to wrap the blocking code. The code inside this block
//        // won't be executed until something subscribes to the Mono.
//        return Mono.fromCallable(() -> {
//                    try {
//                        String url = UriComponentsBuilder.fromPath("/query")
//                                .queryParam("function", TIME_SERIES_DAILY_FUNCTION)
//                                .queryParam("symbol", ticker)
//                                .queryParam("outputsize", "compact")
//                                .queryParam("apikey", apiKey)
//                                .toUriString();
//
//                        // The network call is a blocking operation
//                        String jsonResponse = restClient.get()
//                                .uri(url)
//                                .retrieve()
//                                .body(String.class);
//
//                        // Parse the JSON response
//                        JsonNode rootNode = objectMapper.readTree(jsonResponse);
//
//                        // Instead of Mono.error(), we now throw exceptions to correctly
//                        // signal the failure to the Mono.fromCallable operator.
//                        if (rootNode.has(ERROR_MESSAGE_KEY)) {
//                            System.err.println("Alpha Vantage API Error: " + rootNode.get(ERROR_MESSAGE_KEY).asText());
//                            throw new IOException("Alpha Vantage API Error: " + rootNode.get(ERROR_MESSAGE_KEY).asText());
//                        }
//                        if (rootNode.has(API_NOTE_KEY)) {
//                            System.err.println("Alpha Vantage API Limit Reached: " + rootNode.get(API_NOTE_KEY).asText());
//                            throw new IOException("Alpha Vantage API Limit Reached: " + rootNode.get(API_NOTE_KEY).asText());
//                        }
//
//                        JsonNode timeSeriesNode = rootNode.get(TIME_SERIES_DAILY_KEY);
//                        if (timeSeriesNode == null) {
//                            System.err.println("Could not find " + TIME_SERIES_DAILY_KEY + " in Alpha Vantage response for " + ticker);
//                            throw new IOException("Could not find " + TIME_SERIES_DAILY_KEY + " in Alpha Vantage response for " + ticker);
//                        }
//
//                        // The data processing logic remains the same
//                        LinkedHashMap<LocalDate, BigDecimal> historicalPrices = StreamSupport.stream(
//                                        Spliterators.spliteratorUnknownSize(timeSeriesNode.fields(), Spliterator.ORDERED), false)
//                                .collect(Collectors.toMap(
//                                        entry -> LocalDate.parse(entry.getKey()),
//                                        entry -> new BigDecimal(entry.getValue().get(CLOSE_PRICE_KEY).asText())
//                                ))
//                                .entrySet().stream()
//                                .sorted(Map.Entry.comparingByKey())
//                                .collect(Collectors.toMap(
//                                        Map.Entry::getKey,
//                                        Map.Entry::getValue,
//                                        (oldValue, newValue) -> oldValue,
//                                        LinkedHashMap::new
//                                ));
//
//                        System.out.println("Successfully fetched historical prices for " + ticker);
//                        return historicalPrices;
//                    } catch (Exception e) {
//                        System.err.println("Error fetching historical prices for " + ticker + ": " + e.getMessage());
//                        // Throwing the exception here ensures it's correctly propagated as a Mono.error()
//                        throw new RuntimeException("Failed to fetch historical prices.", e);
//                    }
//                })
//                // Use subscribeOn to offload the blocking work to a dedicated thread pool
//                .subscribeOn(Schedulers.boundedElastic());
//    }