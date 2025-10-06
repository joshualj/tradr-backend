package com.tradrbackend.service.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradrbackend.model.TechnicalIndicators;
import com.tradrbackend.service.prediction.common.PredictionHelperService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

// this was an output from Gemini for integrating with the Python API
public class RandomForestPredictionService {
    private static final Map<Integer, String> MODEL_ENDPOINTS = Map.of(
            7, "/predict/7day",
            30, "/predict/30day",
            60, "/predict/60day",
            180, "/predict/180day",
            365, "/predict/365day",
            730, "/predict/730day", // 2 years
            1460, "/predict/1460day" // 4 years
    );
    private static final String API_URL = "http://localhost:5000";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PredictionHelperService predictionHelperService;

    public RandomForestPredictionService(PredictionHelperService predictionHelperService) {
        this.predictionHelperService = predictionHelperService;
    }

    /**
     * Data Transfer Object for the JSON response from the Python API.
     */
    public static class RandomForestPredictionResponse {
        private int prediction;
        private double probability;

        // Default constructor is required for ObjectMapper
        public RandomForestPredictionResponse() {}

        public int getPrediction() {
            return prediction;
        }

        public void setPrediction(int prediction) {
            this.prediction = prediction;
        }

        public double getProbability() {
            return probability;
        }

        public void setProbability(double probability) {
            this.probability = probability;
        }

        @Override
        public String toString() {
            return "PredictionResponse{" +
                    "prediction=" + prediction +
                    ", probability=" + probability +
                    '}';
        }
    }

    /**
     * Takes a map of technical indicator values, sends it to the Python API,
     * and returns the model's prediction result.
     *
     * @param indicatorValues A map of indicator names to their values.
     * @return The response from the prediction API.
     * @throws IOException if an I/O error occurs.
     * @throws InterruptedException if the operation is interrupted.
     */
    public RandomForestPredictionResponse makePrediction(TechnicalIndicators technicalIndicators, int timeframeDays) throws IOException, InterruptedException {

        Map<String, Double> indicatorValues = predictionHelperService.getIndicatorMap(technicalIndicators);
        // Convert the indicator map to a JSON string
        String requestBody = objectMapper.writeValueAsString(indicatorValues);
        String apiPath = MODEL_ENDPOINTS.getOrDefault(timeframeDays, "/predict/30");
        String apiUrl = API_URL + apiPath;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            // Parse the JSON response into our PredictionResponse object
            return objectMapper.readValue(response.body(), RandomForestPredictionResponse.class);
        } else {
            System.err.println("API request failed with status code: " + response.statusCode());
            System.err.println("Response body: " + response.body());
            throw new IOException("Failed to get a successful response from the prediction API.");
        }
    }
//TODO: clean
//    private Map<String, Object> getIndicatorMap(TechnicalIndicators technicalIndicators) {
//        Map<String, Object> indicatorMap = new HashMap<>();
//        indicatorMap.put("SMA_50", technicalIndicators.getSma50());
//        indicatorMap.put("RSI", technicalIndicators.getRsi());
//        indicatorMap.put("MACD", technicalIndicators.getMacdLine());
//        indicatorMap.put("EMA_20", technicalIndicators.getEma20());
//        indicatorMap.put("BB_UPPER", technicalIndicators.getBbUpper());
//        indicatorMap.put("BB_LOWER", technicalIndicators.getBbLower());
//        indicatorMap.put("ATR", technicalIndicators.getAtr());
//        indicatorMap.put("VOLATILITY", technicalIndicators.getVolatility());
//        return indicatorMap;
//    }
}
