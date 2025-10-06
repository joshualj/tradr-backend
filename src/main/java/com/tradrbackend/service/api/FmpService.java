package com.tradrbackend.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradrbackend.response.FmpRatioResponse;
import com.tradrbackend.response.FmpSharesResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Mono;

import java.io.IOException;

@Service
public class FmpService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper; // Used to manually parse the array response

    // Use Spring's @Value to inject the API key and base URL from configuration
    @Value("${financial.modeling.prep.api.key}") // Add a default placeholder for clarity
    private String apiKey;

    private static final String FMP_BASE_URL = "https://financialmodelingprep.com/stable/";

    // Constructor for dependency injection of RestClient and ObjectMapper
    public FmpService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.baseUrl(FMP_BASE_URL).build();
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches the Trailing Twelve Months (TTM) Price-to-Earnings Ratio for a given ticker.
     * The synchronous RestClient call is wrapped in a Mono.fromCallable() to integrate
     * into the reactive pipeline.
     * @param ticker The stock symbol (e.g., "AAPL").
     * @return A Mono emitting the P/E Ratio as a Double.
     */
    public Mono<Double> getPriceToEarningsRatioTTM(String ticker) {
        // Construct the full path and query parameters
        // Note: The API path uses 'ratios-ttm' with 'symbol' as a query parameter
        String uri = String.format("ratios-ttm?symbol=%s&apikey=%s", ticker, apiKey);

        return Mono.fromCallable(() -> {
                    // 1. Perform the blocking GET request and retrieve the raw JSON body (String)
                    String jsonBody = restClient.get()
                            .uri(uri)
                            .retrieve()
                            .body(String.class);

                    if (jsonBody == null || jsonBody.trim().isEmpty() || jsonBody.equals("[]")) {
                        throw new RuntimeException("FMP returned empty or null data for " + ticker);
                    }

                    // 2. Use ObjectMapper to parse the JSON string into an array of objects
                    FmpRatioResponse[] responseArray;
                    try {
                        responseArray = objectMapper.readValue(jsonBody, FmpRatioResponse[].class);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to parse FMP JSON response.", e);
                    }

                    // 3. Extract the required value from the first element
                    if (responseArray.length > 0 && responseArray[0].getPriceToEarningsRatioTTM() != null) {
                        return responseArray[0].getPriceToEarningsRatioTTM();
                    } else {
                        throw new RuntimeException("FMP data missing priceToEarningsRatioTTM for " + ticker);
                    }
                })
                .onErrorResume(e -> {
                    System.err.println("FMP Service Error during API call for " + ticker + ": " + e.getMessage());
                    // Return a Reactor error to propagate the failure up the pipeline
                    return Mono.error(new RuntimeException("Error fetching FMP data.", e));
                });
    }

    /**
     * Fetches the outstanding shares for a given ticker using the V4 /shares_float endpoint.
     * @param ticker The stock symbol (e.g., "AAPL").
     * @return A Mono emitting the outstanding shares count as a Long.
     */
    public Mono<Long> getOutstandingShares(String ticker) {
        // Construct the full path, overriding the base URL to use V4
        String uri = String.format("%sshares-float?symbol=%s&apikey=%s", FMP_BASE_URL, ticker, apiKey);

        return Mono.fromCallable(() -> {
                    // 1. Perform the blocking GET request using the manually constructed V4 URI
                    String jsonBody = restClient.get()
                            .uri(uri)
                            .retrieve()
                            .body(String.class);

                    if (jsonBody == null || jsonBody.trim().isEmpty() || jsonBody.equals("[]")) {
                        throw new RuntimeException("FMP returned empty or null outstanding shares data for " + ticker);
                    }

                    // 2. Use ObjectMapper to parse the JSON string into an array of FmpSharesResponse
                    FmpSharesResponse[] responseArray;
                    try {
                        responseArray = objectMapper.readValue(jsonBody, FmpSharesResponse[].class);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to parse FMP shares JSON response.", e);
                    }

                    // 3. Extract the required value from the first element
                    if (responseArray.length > 0 && responseArray[0].getOutstandingShares() != null) {
                        return responseArray[0].getOutstandingShares();
                    } else {
                        throw new RuntimeException("FMP data missing outstandingShares for " + ticker);
                    }
                })
                .onErrorResume(e -> {
                    System.err.println("FMP Service Error during API call for outstanding shares (" + ticker + "): " + e.getMessage());
                    return Mono.error(new RuntimeException("Error fetching FMP shares data.", e));
                });
    }
}