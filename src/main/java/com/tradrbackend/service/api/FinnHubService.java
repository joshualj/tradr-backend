package com.tradrbackend.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradrbackend.service.CurrencyConverterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;

@Service
public class FinnHubService {
    // Inject the Finnhub API key from application.properties
    @Value("${finnhub.api.key}")
    private String apiKey;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CurrencyConverterService currencyConverterService;

    // Static final variables for the JSON keys
    private static final String COMPANY_PROFILE_FUNCTION = "/stock/profile2";
    private static final String MARKET_CAP_KEY = "marketCapitalization";
    private static final String METRIC_FUNCTION = "/stock/metric";
    private static final String TTM_EPS_KEY = "epsTTM"; // Key often found in /stock/metric response

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Constructor for dependency injection
    public FinnHubService(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            CurrencyConverterService currencyConverterService) {
        System.out.println("DEBUG: FinnHubService constructor is being called.");

        // Finnhub API base URL
        this.restClient = restClientBuilder.baseUrl("https://finnhub.io/api/v1").build();
        this.objectMapper = objectMapper;
        this.currencyConverterService = currencyConverterService;
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
            return Mono.error(new IllegalStateException("API key missing"));
        }

        String url = UriComponentsBuilder.fromPath(COMPANY_PROFILE_FUNCTION)
                .queryParam("symbol", ticker)
                .queryParam("token", apiKey)
                .toUriString();
        System.out.println("DEBUG: Finnhub URL: " + url);

        return Mono.fromCallable(() -> {
                    // blocking call on boundedElastic
                    String jsonResponse = restClient.get()
                            .uri(url)
                            .retrieve()
                            .body(String.class);

                    if (jsonResponse == null || jsonResponse.trim().isEmpty() || "{}".equals(jsonResponse.trim())) {
                        throw new IllegalStateException("Finnhub API returned an empty or invalid response.");
                    }

                    JsonNode rootNode = objectMapper.readTree(jsonResponse);

                    if (rootNode.has("error")) {
                        String errorMessage = rootNode.get("error").asText();
                        throw new RuntimeException("Finnhub API Error: " + errorMessage);
                    }

                    if (rootNode.isEmpty() || !rootNode.has(MARKET_CAP_KEY)) {
                        throw new RuntimeException("Market cap data not found for " + ticker);
                    }

                    double marketCapInMillions = rootNode.get(MARKET_CAP_KEY).asDouble();
                    String currency = rootNode.get("currency").asText();

                    BigDecimal marketCap = BigDecimal.valueOf(marketCapInMillions)
                            .multiply(BigDecimal.valueOf(1_000_000));

                    return new AbstractMap.SimpleEntry<>(marketCap, currency);
                })
                .subscribeOn(Schedulers.boundedElastic()) // do blocking JSON + HTTP here
                .flatMap(entry -> {
                    BigDecimal marketCap = entry.getKey();
                    String currency = entry.getValue();

                    if ("USD".equalsIgnoreCase(currency)) {
                        // already USD
                        return Mono.just(marketCap);
                    } else {
                        // convert asynchronously
                        return currencyConverterService.getRate(currency, "USD")
                                .map(marketCap::multiply);
                    }
                })
                .doOnNext(cap -> System.out.println("FinnhubService: Fetched market cap for " + ticker + " is $" + cap));
    }

    // --- NEW HELPER METHOD: USES RELIABLE /stock/metric ENDPOINT ---

    /**
     * Fetches the stock metrics object for a given ticker using the /stock/metric endpoint.
     * @param ticker The stock symbol (e.g., "AAPL").
     * @return A Mono emitting the 'metric' JsonNode containing TTM EPS and other metrics.
     */
    private Mono<JsonNode> fetchStockMetrics(String ticker) {
        if (apiKey == null || apiKey.isEmpty()) {
            return Mono.error(new IllegalStateException("Finnhub API key is not loaded."));
        }

        // Endpoint usage: /stock/metric?symbol={symbol}&metric=all&token={key}
        String uri = UriComponentsBuilder.fromPath(METRIC_FUNCTION)
                .queryParam("symbol", ticker)
                .queryParam("metric", "all")
                .queryParam("token", apiKey)
                .toUriString();

        return Mono.fromCallable(() -> {
            String jsonBody;
            try {
                jsonBody = restClient.get()
                        .uri(uri)
                        .retrieve()
                        .body(String.class);
            } catch (HttpStatusCodeException e) {
                throw new RuntimeException(
                        String.format("FinnHub API Status Error (%s). Body: %s",
                                e.getStatusCode().value(),
                                e.getResponseBodyAsString())
                );
            }

            if (jsonBody == null || jsonBody.trim().isEmpty() || jsonBody.trim().startsWith("<!DOCTYPE html>")) {
                throw new RuntimeException("API key or subscription issue: FinnHub returned an empty or HTML page. Check API key validity.");
            }

            try {
                JsonNode rootNode = objectMapper.readTree(jsonBody);
                // The TTM EPS data is typically nested under the 'metric' field
                JsonNode metricNode = rootNode.path("metric");

                if (metricNode.isMissingNode() || !metricNode.isObject()) {
                    throw new RuntimeException("Metric data block not found in Finnhub response for " + ticker);
                }

                return metricNode;
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse FinnHub Stock Metric JSON response structure.", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * **CORRECTED TTM CALCULATION:** Calculates the Trailing Twelve Months (TTM) EPS
     * by fetching the pre-calculated value from the /stock/metric endpoint.
     * @param ticker The stock symbol (e.g., "AAPL").
     * @return A Mono emitting the calculated TTM EPS as a Double.
     */
    public Mono<Double> getTtmEps(String ticker) {

        return fetchStockMetrics(ticker)
                .map(metricNode -> {
                    // Look for the pre-calculated TTM EPS value
                    JsonNode epsNode = metricNode.path(TTM_EPS_KEY);

                    if (epsNode.isMissingNode() || epsNode.isNull()) {
                        throw new RuntimeException("TTM EPS data (key: " + TTM_EPS_KEY + ") not found in metrics for " + ticker);
                    }

                    if (!epsNode.isDouble() && !epsNode.isFloatingPointNumber() && !epsNode.isInt()) {
                        throw new RuntimeException("TTM EPS value is not a valid number for " + ticker + ". Value found: " + epsNode.asText());
                    }

                    return epsNode.asDouble();
                })
                // Handle errors from the fetch or calculation
                .onErrorResume(e -> {
                    System.err.println("FinnHub Service Error during TTM EPS calculation for " + ticker + ": " + e.getMessage());
                    // Propagate a meaningful error
                    return Mono.error(new RuntimeException("Error fetching TTM EPS data from Finnhub via /stock/metric: " + e.getMessage(), e));
                });
    }
}