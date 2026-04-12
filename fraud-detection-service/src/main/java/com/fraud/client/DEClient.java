package com.fraud.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fraud.model.FraudRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class DEClient {

    private final WebClient webClient;

    public DEClient(@Qualifier("deWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<JsonNode> fetch(FraudRequest req) {
        return webClient.post()
                .uri("/v1/features/fetch")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }
}
