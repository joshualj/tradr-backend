package com.tradrbackend.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap; // Modern Spring Boot HTTP client
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service // Marks this as a Spring service component
public class AlphaVantageService {

    @Value("${alphavantage.api.key}") // Inject API key from application.properties
    private String apiKey;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

        // Static final variables for the JSON keys
    private static final String TIME_SERIES_DAILY_KEY = "Time Series (Daily)";
    private static final String TIME_SERIES_DAILY_FUNCTION = "TIME_SERIES_DAILY";
    private static final String PREMIUM_TIME_SERIES_DAILY_ADJUSTED_FUNCTION = "TIME_SERIES_DAILY_ADJUSTED"; // New: for adjusted daily data
    private static final String CLOSE_PRICE_KEY = "4. close"; // For TIME_SERIES_DAILY
    private static final String PREMIUM_ADJUSTED_CLOSE_PRICE_KEY = "5. adjusted close"; // For TIME_SERIES_DAILY_ADJUSTED

    // Constructor for dependency injection of RestClient
    public AlphaVantageService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.baseUrl("https://www.alphavantage.co").build();
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches historical adjusted closing prices for a given ticker.
     * Fetches a "full" daily series to support various technical indicator calculations.
     *
     * @param ticker The stock ticker symbol (e.g., "IBM").
     * @return A map of LocalDate to BigDecimal (adjusted close price). Sorted by date (oldest to newest).
     * @throws IOException If there's an issue with API communication or JSON parsing.
     */
    public Map<LocalDate, BigDecimal> getHistoricalAdjustedPrices(String ticker) throws IOException {
        // Using "TIME_SERIES_DAILY_ADJUSTED" with outputsize=full for comprehensive historical data
        // String url = String.format("https://www.alphavantage.co/query?function=TIME_SERIES_DAILY_ADJUSTED&symbol=%s&outputsize=full&apikey=%s", ticker, apiKey);
        
        String url = UriComponentsBuilder.fromPath("/query")
        .queryParam("function", TIME_SERIES_DAILY_FUNCTION) // <--- Changed from PREMIUM_TIME_SERIES_DAILY_ADJUSTED_FUNCTION
        .queryParam("symbol", ticker)
        .queryParam("outputsize", "compact") // <--- Changed from "full"
        .queryParam("apikey", apiKey)
        .toUriString();

        String jsonResponse = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);

        JsonNode rootNode = objectMapper.readTree(jsonResponse);

        if (rootNode.has("Error Message")) {
            throw new IOException("Alpha Vantage API Error: " + rootNode.get("Error Message").asText());
        }
        if (rootNode.has("Note")) {
            // This is often the API limit message
            throw new IOException("Alpha Vantage API Limit Reached: " + rootNode.get("Note").asText());
        }

        JsonNode timeSeriesNode = rootNode.get(TIME_SERIES_DAILY_KEY);
        if (timeSeriesNode == null) {
            throw new IOException("Could not find " + TIME_SERIES_DAILY_KEY + " in Alpha Vantage response for " + ticker);
        }

        // Use TreeMap to ensure dates are sorted, then convert to LinkedHashMap to maintain insertion order
        // which is important for indicator calculations (oldest to newest)
        Map<LocalDate, BigDecimal> historicalData = new TreeMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        timeSeriesNode.fields().forEachRemaining(entry -> {
            LocalDate date = LocalDate.parse(entry.getKey(), formatter);
            // BigDecimal adjustedClose = new BigDecimal(entry.getValue().get(PREMIUM_ADJUSTED_CLOSE_PRICE_KEY).asText()); <- in premium, adjust to adjusted close
            BigDecimal adjustedClose = new BigDecimal(entry.getValue().get(CLOSE_PRICE_KEY).asText());
            historicalData.put(date, adjustedClose);
        });

        // Convert TreeMap to LinkedHashMap to preserve order for iteration
        return historicalData.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()) // Ensure oldest to newest
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue, // Merge function in case of duplicate keys (shouldn't happen with TreeMap)
                        LinkedHashMap::new // Preserve order
                ));
    }

    /**
     * Overloaded method to get a specific duration of historical data.
     * This will now call the full data method and then filter.
     * @param ticker The stock ticker symbol.
     * @param duration The number of units.
     * @param unit The unit of duration (e.g., "day", "week", "month", "year").
     * @return A map of LocalDate to BigDecimal (adjusted close price) for the specified duration.
     * @throws IOException If there's an issue with API communication or JSON parsing.
     * @throws IllegalArgumentException If the unit is invalid.
     */
    public Map<LocalDate, BigDecimal> getHistoricalAdjustedPrices(String ticker, int duration, String unit) throws IOException, IllegalArgumentException {
        // Get full historical data first
        Map<LocalDate, BigDecimal> fullHistoricalData = getHistoricalAdjustedPrices(ticker);

        if (fullHistoricalData.isEmpty()) {
            return fullHistoricalData; // Return empty if no data was fetched
        }

        LocalDate endDate = fullHistoricalData.keySet().stream()
                                            .max(Comparator.naturalOrder())
                                            .orElseThrow(() -> new IllegalStateException("No dates found in historical data"));

        LocalDate startDate;
        switch (unit.toLowerCase()) {
            case "day":
                startDate = endDate.minusDays(duration - 1); // -1 because duration includes the end date
                break;
            case "week":
                startDate = endDate.minusWeeks(duration - 1);
                break;
            case "month":
                startDate = endDate.minusMonths(duration - 1);
                break;
            case "year":
                startDate = endDate.minusYears(duration - 1);
                break;
            default:
                throw new IllegalArgumentException("Invalid duration unit: " + unit);
        }

        // Filter the full historical data to the requested duration
        return fullHistoricalData.entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(startDate) && !entry.getKey().isAfter(endDate))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new // Preserve order
                ));
    }
}


    /**
     * Fetches historical daily adjusted stock data for a given ticker and timeframe.
     * Alpha Vantage provides daily, weekly, and monthly data.
     * We'll fetch daily and filter/adjust as needed.
     *
     * @param ticker The stock symbol (e.g., "IBM")
     * @param duration The number of units (e.g., 3 for 3 months)
     * @param unit The unit of time ("day", "week", "month", "year")
     * @return A map of date (LocalDate) to adjusted close price (BigDecimal)
     * @throws IOException If there's an issue with the API call or JSON parsing.
     */
    // public Map<LocalDate, BigDecimal> getHistoricalAdjustedPrices(String ticker, int duration, String unit) throws IOException {
    //     // Alpha Vantage's TIME_SERIES_DAILY_ADJUSTED is generally the best for historical analysis
    //     // because it handles splits and dividends.
    //     String function = "TIME_SERIES_DAILY";
    //     String outputSize = "compact"; // Get up to 20 years of historical data

    //     String url = UriComponentsBuilder.fromPath("/query")
    //             .queryParam("function", function)
    //             .queryParam("symbol", ticker)
    //             .queryParam("outputsize", outputSize)
    //             .queryParam("apikey", apiKey)
    //             .toUriString();

    //     System.out.println("--- AlphaVantageService DEBUG URL ---: " + url);


    //     String jsonResponse = restClient.get()
    //             .uri(url)
    //             .retrieve()
    //             .body(String.class);

    //     // Basic error checking for API response
    //     if (jsonResponse.contains("Error Message") || jsonResponse.contains("Invalid API key") || jsonResponse.contains("Thank you for using Alpha Vantage! Our standard API call frequency is 5 calls per minute and 500 calls per day.")) {
    //         throw new IOException("Alpha Vantage API Error: " + jsonResponse);
    //     }
    //     if (jsonResponse.contains("\"Information\": \"The comodity you are looking for does not exist.")) {
    //         throw new IOException("Alpha Vantage API: Stock symbol '" + ticker + "' not found.");
    //     }


    //     // Parse the JSON response
    //     JsonNode rootNode;
    //     // Alpha Vantage sends errors in these fields
    //     try {
    //         rootNode = objectMapper.readTree(jsonResponse);
    //     } catch (JsonProcessingException e) {
    //         throw new IOException("Alpha Vantage returned non-JSON or malformed data:\n" + jsonResponse, e);
    //     }

    //     if (rootNode.has("Information") || rootNode.has("Note") || rootNode.has("Error Message")) {
    //         throw new IOException("Alpha Vantage error response:\n" + rootNode.toPrettyString());
    //     }

    //     JsonNode timeSeriesNode = rootNode.get("Time Series (Daily)");
    //     System.out.println("Dates returned from Alpha Vantage:");
    //     timeSeriesNode.fieldNames().forEachRemaining(System.out::println);

    //     if (timeSeriesNode == null || !timeSeriesNode.isObject()) {
    //         throw new IOException("Could not parse time series data from Alpha Vantage response: " + jsonResponse);
    //     }

    //     Map<LocalDate, BigDecimal> historicalData = new TreeMap<>(); // TreeMap to keep dates sorted
    //     DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    //     // Calculate the target end date for filtering
    //     LocalDate endDate = LocalDate.now();
    //     LocalDate startDate;

    //     switch (unit.toLowerCase()) {
    //         case "day":
    //             startDate = endDate.minusDays(Math.abs(duration));
    //             break;
    //         case "week":
    //             startDate = endDate.minusWeeks(Math.abs(duration));
    //             break;
    //         case "month":
    //             startDate = endDate.minusMonths(Math.abs(duration));
    //             break;
    //         case "year":
    //             startDate = endDate.minusYears(Math.abs(duration));
    //             break;
    //         default:
    //             throw new IllegalArgumentException("Invalid unit: " + unit);
    //     }
    //     System.out.println("Filtering data from " + startDate + " to " + endDate);


    //     for (java.util.Iterator<Map.Entry<String, JsonNode>> it = timeSeriesNode.fields(); it.hasNext(); ) {
    //         Map.Entry<String, JsonNode> entry = it.next();
    //         LocalDate date = LocalDate.parse(entry.getKey(), formatter);

    //         // Filter data to only include the requested timeframe
    //         if (date.isAfter(startDate.minusDays(1)) && date.isBefore(endDate.plusDays(1))) { // Adjust for inclusive range
    //             JsonNode dayData = entry.getValue();
    //             // Alpha Vantage's adjusted close is usually "4. close"
    //             JsonNode closeNode = dayData.get("4. close");
    //             if (closeNode != null) {
    //                 historicalData.put(date, new BigDecimal(closeNode.asText()));
    //             }
    //         }
    //     }

    //     if (historicalData.isEmpty()) {
    //         throw new IOException("No historical data found for " + ticker + " in the specified timeframe.");
    //     }

    //     return historicalData;
    // }