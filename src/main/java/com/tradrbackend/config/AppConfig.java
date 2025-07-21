package com.tradrbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;


@Configuration // Marks this class as a source of bean definitions
public class AppConfig {

    /**
     * Defines a RestTemplate bean that can be injected into other components.
     * This makes RestTemplate available throughout your Spring application context.
     *
     * @return A new instance of RestTemplate.
     */
    @Bean // Marks the method to produce a bean managed by the Spring container
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}