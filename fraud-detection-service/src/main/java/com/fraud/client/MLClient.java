package com.fraud.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class MLClient {

    private final WebClient webClient;

    public MLClient(@Qualifier("mlWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<JsonNode> predict(JsonNode features) {
        return webClient.post()
                .uri("/predict")
                .bodyValue(features)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }
}
