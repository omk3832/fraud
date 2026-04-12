package com.fraud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient deWebClient(@Value("${de.api.url}") String baseUrl,
                                 @Value("${de.timeout}") Duration responseTimeout) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(responseTimeout)))
                .build();
    }

    @Bean
    public WebClient mlWebClient(@Value("${ml.api.url}") String baseUrl,
                                 @Value("${ml.timeout}") Duration responseTimeout) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(responseTimeout)))
                .build();
    }
}
