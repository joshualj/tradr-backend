package com.tradrbackend.service.prediction;

import com.tradrbackend.response.StockAnalysisResponse;
import com.tradrbackend.service.prediction.common.PredictionHelperService;
import org.json.JSONObject;
import com.tradrbackend.model.TechnicalIndicators;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// this was an output from Gemini for integrating with the Python API
public class RegressionPredictionService {

    // The URL of your Python API's new parameters endpoint
    private static final String API_URL = "http://localhost:5000/get_model_params";

    private final PredictionHelperService predictionHelperService;

    public RegressionPredictionService(PredictionHelperService predictionHelperService) {
        this.predictionHelperService = predictionHelperService;
    }

    public void makePrediction(TechnicalIndicators technicalIndicators, StockAnalysisResponse response) {
        // ... (rest of the method logic is unchanged)
        try {
            System.out.println("Fetching model parameters from the Python API...");
            ModelParameters params = fetchModelParameters();
            System.out.println("Parameters fetched successfully.");

            // 2. Define your stock's current RAW factor values and calculate the NEW features
            Map<String, Double> stockFactors = predictionHelperService.getIndicatorMap(technicalIndicators);
            // The map now contains the features the model expects, which are derived in this method.

            // 3. Normalize the stock's factors using the fetched means and standard deviations
            Map<String, Double> normalizedFactors = normalizeFactors(stockFactors, params);
            System.out.println("\nNormalized Factors: " + normalizedFactors);

            // 4. Calculate the raw score (linear combination of normalized factors and coefficients)
            double rawScore = calculateRawScore(normalizedFactors, params.getCoefficients(), params.getFeatureNames());
            System.out.println("\nRaw Score (Log-odds): " + rawScore);

            // 5. Transform the raw score into a probability (0-1) using the sigmoid function
            double probability = 1.0 / (1.0 + Math.exp(-rawScore));
            System.out.println("Probability of Outperformance: " + probability);

            // 6. Scale the probability to a final score out of 100
            double finalScore = probability * 100;
            System.out.printf("Final Stock Score: %.2f / 100\n", finalScore);
            response.setSignalScore((int) finalScore);
            response.setProbability(probability);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

//TODO: clean
//    public void makePrediction(TechnicalIndicators technicalIndicators, StockAnalysisResponse response) {
//        try {
//            // 1. Fetch all model parameters from the Python API
//            System.out.println("Fetching model parameters from the Python API...");
//            ModelParameters params = fetchModelParameters();
//            System.out.println("Parameters fetched successfully.");
//
//            // 2. Define your stock's current raw factor values
//            Map<String, Double> stockFactors = getIndicatorMap(technicalIndicators);
//
//            // 3. Normalize the stock's factors using the fetched means and standard deviations
//            Map<String, Double> normalizedFactors = normalizeFactors(stockFactors, params);
//            System.out.println("\nNormalized Factors: " + normalizedFactors);
//
//            // 4. Calculate the raw score (linear combination of normalized factors and coefficients)
//            double rawScore = calculateRawScore(normalizedFactors, params.getCoefficients(), params.getFeatureNames());
//            System.out.println("\nRaw Score (Log-odds): " + rawScore);
//
//            // 5. Transform the raw score into a probability (0-1) using the sigmoid function
//            double probability = 1.0 / (1.0 + Math.exp(-rawScore));
//            System.out.println("Probability of Outperformance: " + probability);
//
//            // 6. Scale the probability to a final score out of 100
//            double finalScore = probability * 100;
//            System.out.printf("Final Stock Score: %.2f / 100\n", finalScore);
//            response.setSignalScore((int) finalScore);
//            technicalIndicators.setProbability(probability);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }

    /**
     * Fetches all model parameters from the Python API endpoint.
     * @return A ModelParameters object containing all data.
     * @throws Exception if the HTTP request fails or the JSON is invalid.
     */
    private static ModelParameters fetchModelParameters() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch model parameters. HTTP Status Code: " + response.statusCode());
        }

        JSONObject jsonResponse = new JSONObject(response.body());

        List<String> featureNames = jsonResponse.getJSONArray("feature_names").toList().stream()
                .map(Object::toString).collect(Collectors.toList());
        List<Double> coefficients = jsonResponse.getJSONArray("coefficients").toList().stream()
                .map(obj -> ((Number) obj).doubleValue()).collect(Collectors.toList());
        List<Double> means = jsonResponse.getJSONArray("means").toList().stream()
                .map(obj -> ((Number) obj).doubleValue()).collect(Collectors.toList());
        List<Double> stds = jsonResponse.getJSONArray("stds").toList().stream()
                .map(obj -> ((Number) obj).doubleValue()).collect(Collectors.toList());

        return new ModelParameters(featureNames, coefficients, means, stds);
    }

    /**
     * Normalizes the raw factor values using the fetched means and standard deviations.
     * @param rawFactors The map of raw factor names to their values.
     * @param params The ModelParameters object with normalization data.
     * @return A new map with the normalized factor values.
     */
    private Map<String, Double> normalizeFactors(Map<String, Double> rawFactors, ModelParameters params) {
        Map<String, Double> normalizedFactors = new HashMap<>();
        List<String> featureNames = params.getFeatureNames();
        List<Double> means = params.getMeans();
        List<Double> stds = params.getStds();

        for (String factorName : rawFactors.keySet()) {
            int index = featureNames.indexOf(factorName);
            if (index != -1 && index < means.size() && index < stds.size()) {
                double rawValue = rawFactors.get(factorName);
                double mean = means.get(index);
                double std = stds.get(index);

                // Avoid division by zero
                if (std != 0) {
                    double normalizedValue = (rawValue - mean) / std;
                    normalizedFactors.put(factorName, normalizedValue);
                } else {
                    System.err.println("Standard deviation for '" + factorName + "' is zero. Skipping normalization.");
                    normalizedFactors.put(factorName, rawValue);
                }
            } else {
                System.err.println("Warning: Normalization parameters for factor '" + factorName + "' not found.");
                normalizedFactors.put(factorName, rawFactors.get(factorName));
            }
        }
        return normalizedFactors;
    }

    /**
     * Calculates the raw score using the normalized factor values and coefficients.
     * @param normalizedFactors The map of normalized factors.
     * @param coefficients The list of coefficients.
     * @param featureNames The list of feature names in the correct order.
     * @return The raw score.
     */
    private double calculateRawScore(Map<String, Double> normalizedFactors, List<Double> coefficients, List<String> featureNames) {
        double rawScore = 0.0;
        for (int i = 0; i < featureNames.size(); i++) {
            String featureName = featureNames.get(i);
            if (normalizedFactors.containsKey(featureName)) {
                rawScore += normalizedFactors.get(featureName) * coefficients.get(i);
            }
        }
        return rawScore;
    }

    /**
     * A helper class to store all model parameters.
     */
    private static class ModelParameters {
        private final List<String> featureNames;
        private final List<Double> coefficients;
        private final List<Double> means;
        private final List<Double> stds;

        public ModelParameters(List<String> featureNames, List<Double> coefficients, List<Double> means, List<Double> stds) {
            this.featureNames = featureNames;
            this.coefficients = coefficients;
            this.means = means;
            this.stds = stds;
        }

        public List<String> getFeatureNames() { return featureNames; }
        public List<Double> getCoefficients() { return coefficients; }
        public List<Double> getMeans() { return means; }
        public List<Double> getStds() { return stds; }
    }



//    private Map<String, Double> getIndicatorMap(TechnicalIndicators technicalIndicators) {
//        Map<String, Double> indicatorMap = new HashMap<>();
//        indicatorMap.put("sma_50", technicalIndicators.getSma50());
//        indicatorMap.put("ema_20", technicalIndicators.getEma20().doubleValue());
//        indicatorMap.put("rsi", technicalIndicators.getRsi());
//        indicatorMap.put("macd_line", technicalIndicators.getMacdLine());
//        indicatorMap.put("macd_signal", technicalIndicators.getMacdSignal());
//        indicatorMap.put("macd_histogram", technicalIndicators.getMacdHistogram());
//        indicatorMap.put("bb_upper", technicalIndicators.getBbUpper());
//        indicatorMap.put("bb_lower", technicalIndicators.getBbLower());
//        indicatorMap.put("atr", technicalIndicators.getAtr());
//        indicatorMap.put("volatility", technicalIndicators.getVolatility());
//        return indicatorMap;
//    }
}

