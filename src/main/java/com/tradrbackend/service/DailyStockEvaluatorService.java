package com.tradrbackend.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.tradrbackend.service.api.AlphaVantageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.tradrbackend.response.StockAnalysisResponse;

@Service
public class DailyStockEvaluatorService {

    private final Firestore firestore;
    private final AlphaVantageService alphaVantageService;
    private final StockAnalyzerService stockAnalyzerService;
    private final FinancialDataService financialDataService;

    private static final String APP_ID = "tradrfirebaseservice"; // Your actual Firebase Project ID

    @Autowired
    public DailyStockEvaluatorService(Firestore firestore, AlphaVantageService alphaVantageService,
                                      StockAnalyzerService stockAnalyzerService, FinancialDataService financialDataService) {
        this.firestore = firestore;
        this.alphaVantageService = alphaVantageService;
        this.stockAnalyzerService = stockAnalyzerService;
        this.financialDataService = financialDataService;
    }

    @Scheduled(cron = "0 30 06 * * ?") // Runs every day at 6 30 AM (local time is PST, so 9 30 EST market open)
    public void runDailyStockEvaluation() {
        System.out.println("Starting daily stock evaluation job...");
        try {
            // Log the exact collection path being queried using explicit String.format
            String usersCollectionPath = String.format("artifacts/%s/users", APP_ID);
            System.out.println("DEBUG: Attempting to query users collection.");
            System.out.println("DEBUG: Users collection path: " + usersCollectionPath);

            CollectionReference usersCollection = firestore.collection(usersCollectionPath);
            QuerySnapshot userDocs = usersCollection.get().get(); // Blocking call for simplicity

            if (userDocs.isEmpty()) {
                System.out.println("No user documents found in collection: " + usersCollectionPath + ". Please ensure frontend creates user documents explicitly.");
            }

            for (QueryDocumentSnapshot userDoc : userDocs.getDocuments()) {
                String userId = userDoc.getId(); // This is the actual userId (document ID in 'users' collection)
                System.out.println("Found user document with ID: " + userId); // Log found user

                // Now, fetch the specific 'trackedStocksDoc' for this user
                DocumentReference trackedStocksDocRef = firestore.collection(String.format("artifacts/%s/users/%s/userStocks", APP_ID, userId)).document("trackedStocksDoc");
                
                // --- START: Detailed Logging for trackedStocksDoc and userData ---
                System.out.println("DEBUG: Attempting to fetch trackedStocksDoc for user: " + userId + " at path: " + trackedStocksDocRef.getPath());
                
                com.google.cloud.firestore.DocumentSnapshot trackedStocksSnap = trackedStocksDocRef.get().get();

                if (!trackedStocksSnap.exists()) {
                    System.out.println("DEBUG: trackedStocksDoc does NOT exist for user: " + userId);
                    continue; // Skip to next user if document doesn't exist
                }

                Map<String, Object> userData = trackedStocksSnap.getData();
                
                if (userData == null) {
                    System.out.println("DEBUG: userData is null for trackedStocksDoc of user: " + userId);
                    continue; // Skip to next user if data is null
                }
                System.out.println("DEBUG: Fetched userData for user " + userId + ": " + userData);
                // --- END: Detailed Logging ---

                String stocks = "stocks";

                if (userData.containsKey(stocks)) {
                    List<String> trackedStocks = (List<String>) userData.get(stocks);
                    System.out.println("Processing stocks for user: " + userId + " - " + trackedStocks);

                    int defaultDuration = 3;
                    String defaultUnit = "month";
                    for (String ticker : trackedStocks) {
                        String msg = "";
                        try {
                            // NEW: Use the FinancialDataService to get a complete analysis response.
                            // The .block() call makes the reactive stream synchronous, which is
                            // suitable for this scheduled job's sequential processing.
                            StockAnalysisResponse analysisResult = financialDataService.getStockAnalysisResponse(
                                    ticker,
                                    defaultDuration,
                                    defaultUnit,
                                    false
                            ).block();

                            if (analysisResult == null || analysisResult.getError() != null) {
                                System.err.println("Analysis for " + ticker + " failed with error: " + (analysisResult != null ? analysisResult.getError() : "Unknown error"));
                                continue;
                            }

                                                        // --- START: NEW DEBUG LOGS FOR ANALYSIS RESULT ---
                            System.out.println("DEBUG: Analysis Result for " + ticker + ": " + analysisResult.getMessage());
                            System.out.println("DEBUG: Is statistically significant for " + ticker + ": " + analysisResult.isStatisticallySignificant());
                            System.out.println("DEBUG: RSI Signal for " + ticker + ": " + analysisResult.getIndicators().getRsiSignal());
                            System.out.println("DEBUG: MACD Signal for " + ticker + ": " + analysisResult.getIndicators().getMacdSignal());
                            System.out.println("DEBUG: BB Signal for " + ticker + ": " + analysisResult.getIndicators().getBollingerBandSignal());
                            System.out.println("DEBUG: Signal Score for " + ticker + ": " + analysisResult.getSignalScore());
                            System.out.println("DEBUG: Score Interpretation for " + ticker + ": " + analysisResult.getScoreInterpretation());
                            // --- END: NEW DEBUG LOGS ---
                            boolean shouldAlert = analysisResult.isStatisticallySignificant() ||
                                                  "Buy".equals(analysisResult.getScoreInterpretation()) ||
                                                  "Strong Buy".equals(analysisResult.getScoreInterpretation());

                            if (shouldAlert) {
                                Map<String, Object> alert = new HashMap<>();
                                alert.put("stockTicker", ticker);
                                // Determine alert type based on statistical significance or score
                                if (analysisResult.isStatisticallySignificant()) {
                                    alert.put("alertType", analysisResult.getMessage().contains("change") ? "statistical_change" : "statistical_alert");
                                } else if ("Strong Buy".equals(analysisResult.getScoreInterpretation())) {
                                    alert.put("alertType", "strong_buy_signal");
                                } else if ("Buy".equals(analysisResult.getScoreInterpretation())) {
                                    alert.put("alertType", "buy_signal");
                                } else {
                                    alert.put("alertType", "general_signal"); // Fallback
                                }
                                
                                alert.put("percentageChange", analysisResult.getIndicators().getPercentageChangeFromMean()); // You might add this to indicatorValues
                                alert.put("periodDays", analysisResult.getReceivedDurationValue()); // Use the duration from analysis
                                alert.put("periodUnit", analysisResult.getReceivedDurationUnit()); // Use the unit from analysis
                                alert.put("currentPrice", analysisResult.getLatestPrice());
                                alert.put("alertTimestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                                alert.put("message", analysisResult.getMessage()); // Use the statistical message
                                alert.put("isStatisticallySignificant", analysisResult.isStatisticallySignificant());
                                alert.put("pValue", analysisResult.getPValue()); // Will be null for now
                                alert.put("indicatorValues", analysisResult.getIndicators()); // Store all raw indicator values
                                alert.put("rsiSignal", analysisResult.getIndicators().getRsiSignal());
                                alert.put("macdSignal", analysisResult.getIndicators().getMacdSignal());
                                alert.put("bollingerBandSignal", analysisResult.getIndicators().getBollingerBandSignal());
                                alert.put("signalScore", analysisResult.getSignalScore());
                                alert.put("scoreInterpretation", analysisResult.getScoreInterpretation());
                                alert.put("isRead", false);

                                CollectionReference userAlertsCollection = firestore.collection(String.format("artifacts/%s/users/%s/userAlerts", APP_ID, userId));
                                userAlertsCollection.add(alert).get();
                                System.out.println("Alert generated for " + userId + " - " + ticker + ": " + analysisResult.getScoreInterpretation());
                            } else {
                                System.out.println("No significant alert generated for " + userId + " - " + ticker + ". Score: " + analysisResult.getSignalScore() + ", Is Significant: " + analysisResult.isStatisticallySignificant());
                            }

                            Thread.sleep(15000);

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            System.err.println("Daily stock evaluation interrupted.");
                            return;
                        } catch (Exception e) {
                            System.err.println("An unexpected error occurred during analysis for stock " + ticker + " for user " + userId + ": " + e.getMessage());
                        }
                    }
                } else {
                    System.out.println("No 'stocks' data found for user: " + userId + " in trackedStocksDoc.");
                }
            }
            System.out.println("Daily stock evaluation job finished.");
        } catch (InterruptedException | ExecutionException e) {
            System.err.println("Error fetching user data for daily evaluation: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
