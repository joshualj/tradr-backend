package com.tradrbackend.controller;

import com.tradrbackend.service.FinancialDataService;
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
import reactor.core.publisher.Mono;

@RestController // Marks this class as a REST controller
@RequestMapping("/api/stock") // Base path for all endpoints in this controller
public class StockController {

    private final AlphaVantageService alphaVantageService;
    private final StockAnalyzerService stockAnalyzer;
    private final DailyStockEvaluatorService dailyStockEvaluatorService; // Inject the DailyStockEvaluatorService
    private final FinancialDataService financialDataService;


    // Spring will automatically inject these services
    @Autowired
    public StockController(AlphaVantageService alphaVantageService, StockAnalyzerService stockAnalyzer,
                           DailyStockEvaluatorService dailyStockEvaluatorService, FinancialDataService financialDataService) {
        this.alphaVantageService = alphaVantageService;
        this.stockAnalyzer = stockAnalyzer;
        this.dailyStockEvaluatorService = dailyStockEvaluatorService;
        this.financialDataService = financialDataService;
    }

    @GetMapping("/analyze")
    public Mono<ResponseEntity<StockAnalysisResponse>> analyzeStock(
            @RequestParam String ticker,
            @RequestParam int duration,
            @RequestParam String unit) {

        // Return the Mono directly. The framework handles the subscription.
        return financialDataService.getStockAnalysisResponse(ticker, duration, unit, false)
                // When the Mono completes successfully, map the result to a 200 OK response.
                .map(ResponseEntity::ok)
                // Handle potential errors in the reactive chain.
                // This is a more elegant way to handle errors than a try/catch block.
                .onErrorResume(IllegalArgumentException.class, e -> {
                    System.err.println("IllegalArgumentException in analyzeStock for " + ticker + ": " + e.getMessage());
                    StockAnalysisResponse errorResponse = new StockAnalysisResponse();
                    errorResponse.setMessage("Invalid input: " + e.getMessage());
                    errorResponse.setStatisticallySignificant(false);
                    errorResponse.setPValue(null);
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse));
                })
                .onErrorResume(Exception.class, e -> {
                    System.err.println("An unexpected error occurred in analyzeStock for " + ticker + ": " + e.getMessage());
                    StockAnalysisResponse errorResponse = new StockAnalysisResponse();
                    errorResponse.setMessage("An unexpected error occurred: " + e.getMessage());
                    errorResponse.setStatisticallySignificant(false);
                    errorResponse.setPValue(null);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
                });
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
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("Failed to trigger daily stock evaluation: " + e.getMessage());
        }
    }
}