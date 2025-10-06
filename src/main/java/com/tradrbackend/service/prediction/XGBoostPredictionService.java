package com.tradrbackend.service.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradrbackend.model.TechnicalIndicators;
import com.tradrbackend.response.StockAnalysisResponse;
import com.tradrbackend.service.prediction.common.PredictionHelperService;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.Map;

// this was an output from Gemini for integrating with the Python API
@Service
public class XGBoostPredictionService {

    private static final Map<Integer, String> MODEL_ENDPOINTS = Map.of(
            30, "/predict/30day",
            60, "/predict/60day",
            90, "/predict/90day",
            180, "/predict/180day",
            365, "/predict/365day",
            730, "/predict/730day", // 2 years
            1460, "/predict/1460day" // 4 years
    );
    private static final String API_BASE_URL = "http://localhost:5000"; // Assuming the Python API base URL

    private final RestClient restClient; // Use RestClient for consistency
    private final ObjectMapper objectMapper;
    private final PredictionHelperService predictionHelperService;

    // Use a record/class that mirrors the structure of ModelResponse.java
    public record XGBoostPredictionResponse(int prediction, double probability) {
        // Renamed from original ModelResponse fields for clarity of the model output
    }

    // Dependency injection updated to use RestClient
    public XGBoostPredictionService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                                    PredictionHelperService predictionHelperService) {
        this.restClient = restClientBuilder.baseUrl(API_BASE_URL).build();
        this.objectMapper = objectMapper;
        this.predictionHelperService = predictionHelperService;
    }

    /**
     * Takes a map of technical indicator values, sends it to the Python API,
     * and returns the model's prediction result wrapped in a Mono.
     *
     * @param technicalIndicators The indicators and metrics required for the model.
     * @param timeframeDays       The prediction horizon (e.g., 30, 60, 365).
     * @return A Mono emitting the prediction response.
     */
    public Mono<Void> makePrediction(TechnicalIndicators technicalIndicators, int timeframeDays, StockAnalysisResponse sarResponse) {


        // 1. Prepare the feature map (this is the input to the model)
        Map<String, Double> indicatorValues = predictionHelperService.getIndicatorMap(technicalIndicators);

        // 2. Determine the API path (e.g., "/predict/30day")
        String apiPath = MODEL_ENDPOINTS.getOrDefault(timeframeDays, "/predict/30day");

        // 3. Wrap the blocking REST call in a Mono
        return Mono.fromCallable(() -> {
                    try {
                        System.out.println("XGBoost: Sending " + timeframeDays + "-day prediction request to " + apiPath);

                        // Perform the blocking POST request using RestClient
                        XGBoostPredictionResponse response = restClient.post()
                                .uri(apiPath)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .body(indicatorValues)
                                .retrieve()
                                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, res) -> {
                                    // Custom error handling for non-200 status codes
                                    String responseBody = res.getBody() != null ? new String(res.getBody().readAllBytes()) : "No response body.";
                                    // CRITICAL: Throw an IOException with the status and body for the catch block to log.
                                    throw new IOException("XGBoost API request failed with status code: " + res.getStatusCode() +
                                            ". Response body: " + responseBody);
                                })
                                .body(XGBoostPredictionResponse.class); // Returns T or null

                        // --- START: Update logic (only executes on 2xx status) ---
                        if (response != null) {
                            sarResponse.setProbability(response.probability());
                            // NOTE: XGBoost prediction is 1 (outperform) or 0 (underperform).
                            // If you want a score from -10 to 10, you must transform this binary result.
                            sarResponse.setSignalScore(response.prediction());
                        } else {
                            System.err.println("XGBoostPredictionService: Received null response body for a successful status code.");
                        }
                        // --- END: Update logic ---

                        return null;

                    } catch (Exception e) {
                        // Catches other errors (e.g., deserialization, network issues)
                        System.err.println("XGBoostPredictionService: Failed to get prediction due to network or internal error: " + e.getMessage());
                        throw new RuntimeException("Failed to call XGBoost model API.", e);
                    }
                })
                // Offload the blocking work to the elastic thread pool
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    } // Convert Mono<Object/null> to Mono<Void> for consistency
}