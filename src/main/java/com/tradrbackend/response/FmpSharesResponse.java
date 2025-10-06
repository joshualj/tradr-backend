package com.tradrbackend.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FmpSharesResponse {
    // Note: Outstanding shares can be very large, use Long or BigDecimal for safety
    @JsonProperty("outstandingShares")
    private Long outstandingShares;

    public FmpSharesResponse() {
    }

    public Long getOutstandingShares() {
        return outstandingShares;
    }

    public void setOutstandingShares(Long outstandingShares) {
        this.outstandingShares = outstandingShares;
    }
}