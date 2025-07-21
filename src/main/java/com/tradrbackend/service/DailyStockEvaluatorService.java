package com.tradrbackend.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

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

    private static final String APP_ID = "tradrfirebaseservice"; // Your actual Firebase Project ID

    @Autowired
    public DailyStockEvaluatorService(Firestore firestore, AlphaVantageService alphaVantageService, StockAnalyzerService stockAnalyzerService) {
        this.firestore = firestore;
        this.alphaVantageService = alphaVantageService;
        this.stockAnalyzerService = stockAnalyzerService;
    }

    @Scheduled(cron = "0 34 21 * * ?") // Runs every day at 1 AM
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

                if (userData != null && userData.containsKey("stocks")) {
                    List<String> trackedStocks = (List<String>) userData.get("stocks");
                    System.out.println("Processing stocks for user: " + userId + " - " + trackedStocks);

                    for (String ticker : trackedStocks) {
                        String msg = "";
                        try {
                            Map<LocalDate, BigDecimal> historicalData = alphaVantageService.getHistoricalAdjustedPrices(ticker, 5, "day");
                            StockAnalysisResponse analysisResult = stockAnalyzerService.performStockAnalysis(historicalData);
                            msg = analysisResult.getMessage();

                            // --- START: NEW DEBUG LOGS FOR ANALYSIS RESULT ---
                            System.out.println("DEBUG: Analysis Result for " + ticker + ": " + msg);
                            System.out.println("DEBUG: Is statistically significant for " + ticker + ": " + analysisResult.isStatisticallySignificant());
                            // --- END: NEW DEBUG LOGS ---

                            if (analysisResult.isStatisticallySignificant()) {
                                Map<String, Object> alert = new HashMap<>();
                                alert.put("stockTicker", ticker);
                                alert.put("alertType", msg.contains("significantly higher") ? "significant_increase" : "significant_drop");
                                
                                double percentageChange = 0.0;
                                try {
                                    int startIndex = msg.indexOf("Difference: ") + "Difference: ".length();
                                    int endIndex = msg.indexOf("%", startIndex);
                                    if (startIndex != -1 && endIndex != -1) {
                                        percentageChange = Double.parseDouble(msg.substring(startIndex, endIndex).trim());
                                    }
                                } catch (Exception e) {
                                    System.err.println("Could not parse percentage change from message: " + msg + ". Error: " + e.getMessage());
                                }
                                alert.put("percentageChange", percentageChange);
                                alert.put("periodDays", 5);
                                BigDecimal latestPrice = historicalData.entrySet().stream()
                                    .max(Map.Entry.comparingByKey())
                                    .map(Map.Entry::getValue)
                                    .orElse(null);
                                alert.put("currentPrice", latestPrice != null ? latestPrice.doubleValue() : null);

                                alert.put("alertTimestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                                alert.put("message", msg);
                                alert.put("isRead", false);

                                CollectionReference userAlertsCollection = firestore.collection(String.format("artifacts/%s/users/%s/userAlerts", APP_ID, userId)); // Corrected path for alerts

                                userAlertsCollection.add(alert).get();
                                System.out.println("Alert generated for " + userId + ": " + ticker);
                            }

                            Thread.sleep(15000);

                        } catch (IOException e) {
                            System.err.println("Error fetching or analyzing stock " + ticker + " for user " + userId + ": " + e.getMessage());
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
        }
    }
}
