package com.tradrbackend.service.prediction.common;

import com.tradrbackend.model.TechnicalIndicators;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PredictionHelperService {

    /**
     * Calculates the average of the last 20 daily (Volume / SharesOutstanding) ratios.
     * This ensures the feature matches the 20-day rolling mean used during model training.
     * * @param ratios A list of the daily (volume / shares outstanding) ratio for the last 20 days.
     * @return The 20-day average ratio, or 0.0 if the list is null or empty.
     */
//    private double calculate20DayRollingAverageRatio(List<Double> ratios) {
//        if (ratios == null || ratios.isEmpty()) {
//            System.err.println("WARNING: Cannot calculate 20-day average, ratios list is empty/null.");
//            return 0.0;
//        }
//
//        double sum = 0.0;
//        for (double ratio : ratios) {
//            sum += ratio;
//        }
//        // The Python model takes the mean (average) of the last 20 values.
//        return sum / ratios.size();
//    }

    private double calculateRelativeVolume(List<BigDecimal> volumes20Day, double sharesOutstanding) {
        if (volumes20Day == null || volumes20Day.isEmpty() || sharesOutstanding == 0) {
            System.err.println("WARNING: Cannot calculate relative volume. Volumes list is empty or shares outstanding is zero. Returning 0.0");
            return 0.0;
        }

        double sumOfRatios = 0.0;
        for (BigDecimal volume : volumes20Day) {
            sumOfRatios += volume.doubleValue() / sharesOutstanding;
        }

        // Calculate the average of the 20 daily ratios
        return sumOfRatios / volumes20Day.size();
    }

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
        Double marketCap = technicalIndicators.getMarketCap();
        BigDecimal latestNetIncomeCommon = technicalIndicators.getLatestNetIncome();
        // Inputs needed for Relative Volume
        double latestVolume = technicalIndicators.getLatestVolume();
        double sharesOutstanding = technicalIndicators.getSharesOutstanding(); // Must be fetched
        double ttmEps = technicalIndicators.getPeRatioTtm(); // Must be fetched

        // f. relative_volume: (volume / shares_outstanding) [Model used 20-day avg of this ratio]
        //double relativeVolume = latestVolume / sharesOutstanding;
        double relativeVolume = calculateRelativeVolume(
                technicalIndicators.getVolume20DayAvg(),
                sharesOutstanding
        );

        // Fundamental Features (2)
        // p_i_ratio = market_cap / latest_net_income_common
        //double pIRatio = marketCap / latestNetIncomeCommon.doubleValue();
        double pIRatio = close / ttmEps;

        double sp500PeProxy = technicalIndicators.getSp500PeProxy(); // Must be fetched

        // Missing Feature 2: Relative PE Ratio: pe_ratio_ttm / sp500_pe_proxy
        double relativePeRatio = pIRatio / sp500PeProxy;
        //double relativePeRatio = calculate20DayRollingAverageRatio(technicalIndicators.getVolume20DayAvg().stream().map(BigDecimal::doubleValue).toList());

        // 2. Calculate the NEW, relative features (matching Python logic)

        // a. Price/EMA Ratio: (close - ema_20) / ema_20
        double priceEmaRatio = (close - ema20) / ema20;

        // b. RSI Centered: rsi - 50
        double rsiCentered = rsi - 50.0;

        // c. %Bandwidth (BB): (bb_upper - bb_lower) / bb_middle
        double bbPercentWidth = (bbUpper - bbLower) / bbMiddle;

        // d. ATR/Price Ratio: atr / close
        double atrPriceRatio = atr / close;

        // log_market_cap = Math.log(market_cap)
        double logMarketCap = Math.log(marketCap);

        // Inputs needed for Relative PE Ratio
        //double peRatioTtm = technicalIndicators.getPeRatioTtm(); // Must be fetched

        Map<String, Double> indicatorMap = new HashMap<>();
        indicatorMap.put("price_ema_ratio", priceEmaRatio);
        indicatorMap.put("rsi_centered", rsiCentered);
        indicatorMap.put("bb_percent_width", bbPercentWidth);
        indicatorMap.put("atr_price_ratio", atrPriceRatio);
        indicatorMap.put("volatility", volatility);

        // New and previously forgotten mappings:
        indicatorMap.put("relative_volume", relativeVolume);
        indicatorMap.put("p_i_ratio", pIRatio);
        indicatorMap.put("log_market_cap", logMarketCap);
        indicatorMap.put("relative_pe_ratio", relativePeRatio);

        return indicatorMap;
    }
}
