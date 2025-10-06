package com.tradrbackend.response;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tradrbackend.model.HistoricalPrice;

import com.tradrbackend.model.TechnicalIndicators;
import lombok.Data;

// @AllArgsConstructor // Lombok annotation for an all-args constructor
// @NoArgsConstructor // Lombok annotation for a no-args constructor
@Data // Lombok annotation for getters, setters, toString, equals, hashCode
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY, // Serialize any field (private, protected, public)
    getterVisibility = JsonAutoDetect.Visibility.NONE, // Do NOT auto-detect regular getters (e.g., getMessage())
    isGetterVisibility = JsonAutoDetect.Visibility.NONE // Do NOT auto-detect 'is' getters (e.g., isStatisticallySignificant())
)
public class StockAnalysisResponse {
    private String message;
    @JsonProperty("isStatisticallySignificant")
    private boolean isStatisticallySignificant;
    @JsonProperty("pValue")
    private Double pValue;
    
    // --- NEW FIELDS FOR TECHNICAL INDICATORS AND SCORING ---
    private String receivedTicker;
    private Integer receivedDurationValue;
    private String receivedDurationUnit;
    private String error;

    private Double latestPrice; // Current price
    private TechnicalIndicators indicators;
    private Integer signalScore; // e.g., +3, -2
    private double probability;
    private String scoreInterpretation; // e.g., "Buy", "Strong Sell"
    @JsonProperty("historicalPrices") // Matches the name used in SwiftUI
    private List<HistoricalPrice> historicalPrices;
}