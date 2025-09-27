package com.tradrbackend.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

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
    private String apiKey;

    @Value("${alphavantage.api.key2}") // Inject API key from application.properties
    private String apiKey2;

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


    // Constructor for dependency injection of RestClient
    public AlphaVantageService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.baseUrl("https://www.alphavantage.co").build();
        this.objectMapper = objectMapper;
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
                    try {
                        String uri = UriComponentsBuilder.fromPath("/query")
                                .queryParam("function", TIME_SERIES_DAILY_FUNCTION)
                                .queryParam("symbol", ticker)
                                .queryParam("outputsize", "compact")
                                .queryParam("apikey", apiKey)
                                .toUriString();

                        // The network call is a blocking operation.
                        String jsonResponse = restClient.get()
                                .uri(uri)
                                .retrieve()
                                .body(String.class);

                        JsonNode rootNode = objectMapper.readTree(jsonResponse);

                        // Centralized error handling
                        if (rootNode.has(ERROR_MESSAGE_KEY)) {
                            String errorMessage = rootNode.get(ERROR_MESSAGE_KEY).asText();
                            System.err.println("Alpha Vantage API Error: " + errorMessage);
                            throw new RuntimeException("Alpha Vantage API Error: " + errorMessage);
                        }
                        if (rootNode.has(API_NOTE_KEY)) {
                            String apiNote = rootNode.get(API_NOTE_KEY).asText();
                            System.err.println("Alpha Vantage API Limit Reached: " + apiNote);
                            throw new RuntimeException("Alpha Vantage API Limit Reached: " + apiNote);
                        }

                        JsonNode timeSeriesNode = rootNode.get(TIME_SERIES_DAILY_KEY);
                        if (timeSeriesNode == null) {
                            throw new RuntimeException("Could not find '" + TIME_SERIES_DAILY_KEY + "' in Alpha Vantage response.");
                        }

                        System.out.println("Successfully fetched daily time series data for " + ticker);
                        return rootNode;
                    } catch (Exception e) {
                        System.err.println("Error fetching daily time series data for " + ticker + ": " + e.getMessage());
                        throw new RuntimeException("Failed to fetch daily time series data.", e);
                    }
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
                    LinkedHashMap<LocalDate, BigDecimal> historicalPrices = StreamSupport.stream(
                                    Spliterators.spliteratorUnknownSize(timeSeriesNode.fields(), Spliterator.ORDERED), false)
                            .collect(Collectors.toMap(
                                    entry -> LocalDate.parse(entry.getKey()),
                                    entry -> new BigDecimal(entry.getValue().get(CLOSE_PRICE_KEY).asText())
                            ))
                            .entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue,
                                    (oldValue, newValue) -> oldValue,
                                    LinkedHashMap::new
                            ));

                    // 2. Extract latest volume
                    String latestDateKey = timeSeriesNode.fieldNames().next();
                    JsonNode latestData = timeSeriesNode.path(latestDateKey);
                    BigDecimal latestVolume = new BigDecimal(latestData.get(LATEST_VOLUME_KEY).asText());

                    // Return the new consolidated data record.
                    return new AlphaVantageData(historicalPrices, latestVolume);
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