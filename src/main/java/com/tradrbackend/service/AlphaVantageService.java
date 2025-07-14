package com.tradrbackend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient; // Modern Spring Boot HTTP client
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

@Service // Marks this as a Spring service component
public class AlphaVantageService {

    @Value("${alphavantage.api.key}") // Inject API key from application.properties
    private String apiKey;

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Constructor for dependency injection of RestClient
    public AlphaVantageService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl("https://www.alphavantage.co").build();
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
    public Map<LocalDate, BigDecimal> getHistoricalAdjustedPrices(String ticker, int duration, String unit) throws IOException {
        // Alpha Vantage's TIME_SERIES_DAILY_ADJUSTED is generally the best for historical analysis
        // because it handles splits and dividends.
        String function = "TIME_SERIES_DAILY";
        String outputSize = "compact"; // Get up to 20 years of historical data

        String url = UriComponentsBuilder.fromPath("/query")
                .queryParam("function", function)
                .queryParam("symbol", ticker)
                .queryParam("outputsize", outputSize)
                .queryParam("apikey", apiKey)
                .toUriString();

        System.out.println("--- AlphaVantageService DEBUG URL ---: " + url);


        String jsonResponse = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);

        // Basic error checking for API response
        if (jsonResponse.contains("Error Message") || jsonResponse.contains("Invalid API key") || jsonResponse.contains("Thank you for using Alpha Vantage! Our standard API call frequency is 5 calls per minute and 500 calls per day.")) {
            throw new IOException("Alpha Vantage API Error: " + jsonResponse);
        }
        if (jsonResponse.contains("\"Information\": \"The comodity you are looking for does not exist.")) {
            throw new IOException("Alpha Vantage API: Stock symbol '" + ticker + "' not found.");
        }


        // Parse the JSON response
        JsonNode rootNode;
        // Alpha Vantage sends errors in these fields
        try {
            rootNode = objectMapper.readTree(jsonResponse);
        } catch (JsonProcessingException e) {
            throw new IOException("Alpha Vantage returned non-JSON or malformed data:\n" + jsonResponse, e);
        }

        if (rootNode.has("Information") || rootNode.has("Note") || rootNode.has("Error Message")) {
            throw new IOException("Alpha Vantage error response:\n" + rootNode.toPrettyString());
        }

        JsonNode timeSeriesNode = rootNode.get("Time Series (Daily)");
        System.out.println("Dates returned from Alpha Vantage:");
        timeSeriesNode.fieldNames().forEachRemaining(System.out::println);

        if (timeSeriesNode == null || !timeSeriesNode.isObject()) {
            throw new IOException("Could not parse time series data from Alpha Vantage response: " + jsonResponse);
        }

        Map<LocalDate, BigDecimal> historicalData = new TreeMap<>(); // TreeMap to keep dates sorted
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // Calculate the target end date for filtering
        LocalDate endDate = LocalDate.now();
        LocalDate startDate;

        switch (unit.toLowerCase()) {
            case "day":
                startDate = endDate.minusDays(Math.abs(duration));
                break;
            case "week":
                startDate = endDate.minusWeeks(Math.abs(duration));
                break;
            case "month":
                startDate = endDate.minusMonths(Math.abs(duration));
                break;
            case "year":
                startDate = endDate.minusYears(Math.abs(duration));
                break;
            default:
                throw new IllegalArgumentException("Invalid unit: " + unit);
        }
        System.out.println("Filtering data from " + startDate + " to " + endDate);


        for (java.util.Iterator<Map.Entry<String, JsonNode>> it = timeSeriesNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            LocalDate date = LocalDate.parse(entry.getKey(), formatter);

            // Filter data to only include the requested timeframe
            if (date.isAfter(startDate.minusDays(1)) && date.isBefore(endDate.plusDays(1))) { // Adjust for inclusive range
                JsonNode dayData = entry.getValue();
                // Alpha Vantage's adjusted close is usually "4. close"
                JsonNode closeNode = dayData.get("4. close");
                if (closeNode != null) {
                    historicalData.put(date, new BigDecimal(closeNode.asText()));
                }
            }
        }

        if (historicalData.isEmpty()) {
            throw new IOException("No historical data found for " + ticker + " in the specified timeframe.");
        }

        return historicalData;
    }
}
