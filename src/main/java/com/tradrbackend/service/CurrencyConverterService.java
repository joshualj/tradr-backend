package com.tradrbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;

@Service
public class CurrencyConverterService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public CurrencyConverterService(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    public Mono<BigDecimal> getRate(String sourceCurrency, String targetCurrency) {
        if (sourceCurrency.equalsIgnoreCase(targetCurrency)) {
            return Mono.just(BigDecimal.ONE);
        }

        // Use exchangerate.host API (no key required)
        String url = String.format(
                "https://api.exchangerate.host/convert?from=%s&to=%s",
                sourceCurrency.toUpperCase(),
                targetCurrency.toUpperCase()
        );

        return Mono.fromCallable(() -> {
            String jsonResponse = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(); // safe inside fromCallable (runs on boundedElastic)

            JsonNode rootNode = objectMapper.readTree(jsonResponse);

            if (rootNode == null || !rootNode.has("info") || rootNode.get("info").get("rate").isNull()) {
                throw new RuntimeException("CurrencyConverterService: Invalid response or missing rate for " +
                        sourceCurrency + " to " + targetCurrency);
            }

            double rate = rootNode.get("info").get("rate").asDouble();
            return BigDecimal.valueOf(rate);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
