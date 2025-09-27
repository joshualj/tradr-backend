package com.tradrbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.math.BigDecimal;

@Service
public class FinnHubService {
    // Inject the Finnhub API key from application.properties
    @Value("${finnhub.api.key}")
    private String apiKey;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // Static final variables for the JSON keys
    private static final String COMPANY_PROFILE_FUNCTION = "/stock/profile2";
    private static final String MARKET_CAP_KEY = "marketCapitalization";
    private static final String CURRENCY_KEY = "currency";

    // Constructor for dependency injection
    public FinnHubService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        System.out.println("DEBUG: FinnHubService constructor is being called.");

        // Finnhub API base URL
        this.restClient = restClientBuilder.baseUrl("https://finnhub.io/api/v1").build();
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches the market capitalization for a given stock ticker from Finnhub.
     * <p>
     * NOTE: This method is designed to be non-blocking and returns a Mono.
     * </p>
     * @param ticker The stock ticker symbol (e.g., "AAPL").
     * @return A Mono of BigDecimal containing the market capitalization, or null if an error occurs.
     */
    public Mono<BigDecimal> getMarketCapitalization(String ticker) {
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("FinnhubService: API key is not configured.");
            return Mono.empty();
        }

//        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromPath(COMPANY_PROFILE_FUNCTION)
//                .queryParam("symbol", ticker)
//                .queryParam("token", apiKey);

        String url = UriComponentsBuilder.fromPath(COMPANY_PROFILE_FUNCTION)
                .queryParam("symbol", ticker)
                .queryParam("token", apiKey)
                .toUriString();
        System.out.println("DEBUG: Finnhub URL: " + url);

        return Mono.fromCallable(() -> {
            try {
                String jsonResponse = restClient.get()
                        .uri(url)
                        .retrieve()
                        .body(String.class);

                // Check for empty or invalid responses from the API
                if (jsonResponse == null || jsonResponse.trim().isEmpty() || "{}".equals(jsonResponse.trim())) {
                    System.err.println("FinnhubService: Empty or invalid response for ticker: " + ticker);
                    Mono.error(new IllegalStateException("Finnhub API returned an empty or invalid response."));
                }

                JsonNode rootNode = objectMapper.readTree(jsonResponse);

                // Check for a generic 'error' key, which Finnhub uses for API key or request issues
                if (rootNode.has("error")) {
                    String errorMessage = rootNode.get("error").asText();
                    System.err.println("FinnhubService: API Error for " + ticker + ": " + rootNode.get("error").asText());
                    Mono.error(new RuntimeException("Finnhub API Error: " + errorMessage));
                }

                // Finnhub returns an empty object {} on an invalid ticker or error
                if (rootNode.isEmpty() || !rootNode.has(MARKET_CAP_KEY)) {
                    System.err.println("FinnhubService: Market cap data not found for " + ticker);
                    Mono.error(new RuntimeException("Market cap data not found for " + ticker));
                }

                // The market cap is a double, so we parse it and convert to BigDecimal
                double marketCapInMillions = rootNode.get(MARKET_CAP_KEY).asDouble();
                // Finnhub returns market cap in millions, so we multiply by 1,000,000
                BigDecimal marketCap = BigDecimal.valueOf(marketCapInMillions).multiply(BigDecimal.valueOf(1_000_000));

                System.out.println("FinnhubService: Fetched market cap for " + ticker + " is $" + marketCap);
                return marketCap;
            } catch (IOException e) {
                System.err.println("FinnhubService: Error processing API response for " + ticker + ": " + e.getMessage());
                throw e;
            } catch (Exception e) {
                System.err.println("FinnhubService: Unexpected error fetching market cap for " + ticker + ": " + e.getMessage());
                throw e;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}