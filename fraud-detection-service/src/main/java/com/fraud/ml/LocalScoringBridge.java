package com.fraud.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fraud.model.FraudResponse;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * <p><b>Where:</b> Between orchestration and {@link LocalXgboostScorer} when {@code fraud.ml.transport=local}.
 * Bulkhead sits on this small bridge so AOP sees a Spring bean method (same pattern as HTTP gateways).
 *
 * <p><b>Why bulkhead only:</b> Local scoring is CPU/native-bound; a semaphore caps parallel predicts so
 * many concurrent requests do not overload the host. There is no remote socket to isolate, so no circuit
 * breaker — combine with ingress rate limiting ({@code RateLimiterFilter}) for overload protection.
 */
@Component
@ConditionalOnProperty(name = "fraud.ml.transport", havingValue = "local")
public class LocalScoringBridge {

    private final LocalXgboostScorer scorer;

    public LocalScoringBridge(LocalXgboostScorer scorer) {
        this.scorer = scorer;
    }

    @Bulkhead(name = "localMl", type = Bulkhead.Type.SEMAPHORE)
    public FraudResponse score(JsonNode filteredFeatures) {
        return scorer.score(filteredFeatures);
    }
}
