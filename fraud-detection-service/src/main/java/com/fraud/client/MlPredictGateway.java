package com.fraud.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * <p><b>Where:</b> Thin wrapper around {@link MLClient} — remote ML predicts pass through here when
 * {@code fraud.ml.transport=http}. With {@code fraud.ml.transport=local} this gateway is not on the hot path.
 *
 * <p><b>Why bulkhead:</b> Caps concurrent outbound ML HTTP calls so prediction latency or ML service
 * overload cannot grow unbounded concurrency in this service.
 *
 * <p><b>Why circuit breaker:</b> ML is remote; repeated failures should trip open and back off (config:
 * {@code mlService} in {@code application.properties}).
 */
@Component
public class MlPredictGateway {

    private final MLClient mlClient;

    public MlPredictGateway(MLClient mlClient) {
        this.mlClient = mlClient;
    }

    @CircuitBreaker(name = "mlService")
    @Bulkhead(name = "mlBulkhead", type = Bulkhead.Type.SEMAPHORE)
    public Mono<JsonNode> predict(JsonNode features) {
        return mlClient.predict(features);
    }
}
