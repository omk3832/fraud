package com.fraud.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fraud.model.FraudRequest;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * <p><b>Where:</b> Thin wrapper around {@link DEClient} — every DE fetch goes through this bean so
 * Resilience4j applies at the <em>outbound HTTP</em> boundary (not inside {@code FraudService}).
 *
 * <p><b>Why bulkhead:</b> Limits how many requests can hit DE at once so a slow or saturated DE does not
 * pin unbounded concurrent calls and starve the rest of the service.
 *
 * <p><b>Why circuit breaker:</b> DE is a remote dependency; if it fails or times out too often, open the
 * breaker to fail fast and recover instead of hammering a bad dependency (config: {@code deService} in
 * {@code application.properties}).
 */
@Component
public class DeFetchGateway {

    private final DEClient deClient;

    public DeFetchGateway(DEClient deClient) {
        this.deClient = deClient;
    }

    @CircuitBreaker(name = "deService")
    @Bulkhead(name = "deBulkhead", type = Bulkhead.Type.SEMAPHORE)
    public Mono<JsonNode> fetch(FraudRequest req) {
        return deClient.fetch(req);
    }
}
