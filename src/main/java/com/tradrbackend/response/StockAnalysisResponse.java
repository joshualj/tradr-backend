package com.tradrbackend.response;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data // Lombok annotation for getters, setters, toString, equals, hashCode
@AllArgsConstructor // Lombok annotation for an all-args constructor
@NoArgsConstructor // Lombok annotation for a no-args constructor
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
}