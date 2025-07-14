package com.tradrbackend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tradrbackend.response.StockAnalysisResponse;
import com.tradrbackend.service.AlphaVantageService;
import com.tradrbackend.service.StockAnalyzerService;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@RestController // Marks this class as a REST controller
@RequestMapping("/api/stock") // Base path for all endpoints in this controller
public class StockController {

    private final AlphaVantageService alphaVantageService;
    private final StockAnalyzerService stockAnalyzer;

    // Spring will automatically inject these services
    @Autowired
    public StockController(AlphaVantageService alphaVantageService, StockAnalyzerService stockAnalyzer) {
        this.alphaVantageService = alphaVantageService;
        this.stockAnalyzer = stockAnalyzer;
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
}
