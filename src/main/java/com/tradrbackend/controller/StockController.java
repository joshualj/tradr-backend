package com.tradrbackend.controller;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tradrbackend.response.StockAnalysisResponse;
import com.tradrbackend.service.AlphaVantageService;
import com.tradrbackend.service.DailyStockEvaluatorService;
import com.tradrbackend.service.StockAnalyzerService;

@RestController // Marks this class as a REST controller
@RequestMapping("/api/stock") // Base path for all endpoints in this controller
public class StockController {

    private final AlphaVantageService alphaVantageService;
    private final StockAnalyzerService stockAnalyzer;
    private final DailyStockEvaluatorService dailyStockEvaluatorService; // Inject the DailyStockEvaluatorService


    // Spring will automatically inject these services
    @Autowired
    public StockController(AlphaVantageService alphaVantageService, StockAnalyzerService stockAnalyzer, DailyStockEvaluatorService dailyStockEvaluatorService) {
        this.alphaVantageService = alphaVantageService;
        this.stockAnalyzer = stockAnalyzer;
        this.dailyStockEvaluatorService = dailyStockEvaluatorService;

    }

    @GetMapping("/analyze")
    public ResponseEntity<StockAnalysisResponse> analyzeStock(
            @RequestParam String ticker,
            @RequestParam int duration,
            @RequestParam String unit) {
        try {
            // 1. Get historical data from Alpha Vantage

            Map<LocalDate, BigDecimal> historicalData = alphaVantageService.getHistoricalAdjustedPrices(ticker, duration, unit);

            // 2. Perform statistical analysis
            StockAnalysisResponse analysisResult = stockAnalyzer.performStockAnalysis(historicalData);

            return ResponseEntity.ok(analysisResult); // Return the analysis result with 200 OK
        } catch (IOException e) {
            // Handle API specific errors or general IO errors
            // --- IMPORTANT CHANGE: Return a StockAnalysisResponse for errors too ---
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                 .body(new StockAnalysisResponse("Error fetching or processing stock data: " + e.getMessage(), false, null));
        } catch (IllegalArgumentException e) {
            // Handle invalid input errors (e.g., invalid unit)
            // --- IMPORTANT CHANGE: Return a StockAnalysisResponse for errors too ---
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                 .body(new StockAnalysisResponse("Invalid input: " + e.getMessage(), false, null));
        } catch (Exception e) {
            // Catch any other unexpected errors
            // --- IMPORTANT CHANGE: Return a StockAnalysisResponse for errors too ---
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body(new StockAnalysisResponse("An unexpected error occurred: " + e.getMessage(), false, null));
        }
    }

    /**
     * REST endpoint to manually trigger the daily stock evaluation job.
     * This is useful for testing and debugging the scheduled task without waiting for the cron.
     * Access via GET request to /api/stock/trigger-daily-evaluation
     */
    @GetMapping("/trigger-daily-evaluation")
    public ResponseEntity<String> triggerDailyEvaluation() {
        try {
            System.out.println("Manual trigger for daily stock evaluation received.");
            dailyStockEvaluatorService.runDailyStockEvaluation();
            return ResponseEntity.ok("Daily stock evaluation job triggered successfully.");
        } catch (Exception e) { // Catch broader exception for controller
            System.err.println("Error triggering daily stock evaluation: " + e.getMessage());
            e.printStackTrace(); // <--- ADDED THIS LINE TO PRINT FULL STACK TRACE
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("Failed to trigger daily stock evaluation: " + e.getMessage());
        }
    }
}
