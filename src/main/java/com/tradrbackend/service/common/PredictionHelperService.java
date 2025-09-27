package com.tradrbackend.service.common;

import com.tradrbackend.model.TechnicalIndicators;

import java.util.HashMap;
import java.util.Map;

public class PredictionHelperService {

    /**
     * REFURBISHED: Calculates the model's required input features
     * from the raw indicators in TechnicalIndicators.
     * @param technicalIndicators Raw indicator values.
     * @return Map containing the five relative features required by the Python model.
     */
    public Map<String, Double> getIndicatorMap(TechnicalIndicators technicalIndicators) {

        // 1. Get raw values (assuming these getters exist in TechnicalIndicators)
        double close = technicalIndicators.getLatestClosePrice();
        double ema20 = technicalIndicators.getEma20().doubleValue();
        double rsi = technicalIndicators.getRsi();
        double bbUpper = technicalIndicators.getBbUpper();
        double bbLower = technicalIndicators.getBbLower();
        double bbMiddle = technicalIndicators.getBbMiddle(); // You will need to calculate/store this one
        double atr = technicalIndicators.getAtr();
        double volatility = technicalIndicators.getVolatility();

        // 2. Calculate the NEW, relative features (matching Python logic)

        // a. Price/EMA Ratio: (close - ema_20) / ema_20
        double priceEmaRatio = (close - ema20) / ema20;

        // b. RSI Centered: rsi - 50
        double rsiCentered = rsi - 50.0;

        // c. %Bandwidth (BB): (bb_upper - bb_lower) / bb_middle
        double bbPercentWidth = (bbUpper - bbLower) / bbMiddle;

        // d. ATR/Price Ratio: atr / close
        double atrPriceRatio = atr / close;

        // 3. Populate the map with the new features
        Map<String, Double> indicatorMap = new HashMap<>();
        indicatorMap.put("price_ema_ratio", priceEmaRatio);
        indicatorMap.put("rsi_centered", rsiCentered);
        indicatorMap.put("bb_percent_width", bbPercentWidth);
        indicatorMap.put("atr_price_ratio", atrPriceRatio);
        indicatorMap.put("volatility", volatility); // volatility remains as is

        return indicatorMap;
    }
}
