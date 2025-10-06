package com.tradrbackend.service;

import com.tradrbackend.repository.SimFinRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;

@Service
public class SimFinDataService {

    private final SimFinRepository simFinRepository;

    public SimFinDataService(SimFinRepository simFinRepository) {
        this.simFinRepository = simFinRepository;
    }

    public Mono<BigDecimal> getLatestNetIncomeCommon(String ticker) {
        return Mono.fromCallable(() -> simFinRepository.findTop1LatestNetIncomeCommonByCompositeStockId_TickerOrderByCompositeStockId_DateDesc(ticker)
            .orElse(BigDecimal.ZERO)
        ).subscribeOn(Schedulers.boundedElastic());
    }
}