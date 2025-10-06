package com.tradrbackend.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Data class for mapping the required fields from the Financial Modeling Prep Ratios TTM endpoint.
 */
@Setter
@Getter
public class FmpRatioResponse {

    // Matches "priceToEarningsRatioTTM" from the FMP JSON.
    @JsonProperty("priceToEarningsRatioTTM")
    private Double priceToEarningsRatioTTM;

    // Default constructor is necessary for Jackson deserialization
    public FmpRatioResponse() {
    }
}