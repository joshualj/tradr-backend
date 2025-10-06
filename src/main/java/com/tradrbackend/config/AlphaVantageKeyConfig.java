package com.tradrbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AlphaVantageKeyConfig {

    @Value("${alphavantage.api.key}")
    private List<String> keys1;

    @Value("${alphavantage.api.key2}")
    private List<String> keys2;
    private int currentKeys1Index = 0;
    private int currentKeys2Index = 0;

    public List<String> getKeys(boolean isKeys1) {
        return isKeys1 ? keys1 : keys2;
    }

    public void setKeys(List<String> keys) {
        this.keys1 = keys;
    }

    // Thread-safe method to get the next key in a round-robin fashion
    public synchronized String getNextKey(boolean isKeys1) {
        String key;
        if (isKeys1) {
            if (keys1 == null || keys1.isEmpty()) {
                throw new IllegalStateException("Alpha Vantage API keys1 not configured.");
            }
            key = keys1.get(currentKeys1Index);
            currentKeys1Index = (currentKeys1Index + 1) % keys1.size();
        } else {
            if (keys2 == null || keys2.isEmpty()) {
                throw new IllegalStateException("Alpha Vantage API keys2 not configured.");
            }
            key = keys2.get(currentKeys2Index);
            currentKeys2Index = (currentKeys2Index + 1) % keys2.size();
        }
        return key;
    }

    // Method to get the current key without rotating (useful for retries on a new key)
    public synchronized String getCurrentKey(boolean isKeys1) {
        List<String> keysCurr = isKeys1 ? keys1 : keys2;
        if (keysCurr == null || keysCurr.isEmpty()) {
            throw new IllegalStateException("Alpha Vantage API keys not configured.");
        }
        return keysCurr.get(isKeys1 ? currentKeys1Index : currentKeys2Index);
    }

    // Method to explicitly rotate to the next key (used on error)
    public synchronized String rotateKey(boolean isKeys1) {
        if (isKeys1) {
            currentKeys1Index = (currentKeys1Index + 1) % keys1.size();
            return keys1.get(currentKeys1Index);
        }
        currentKeys2Index = (currentKeys2Index + 1) % keys2.size();
        return keys2.get(currentKeys2Index);
    }
}
