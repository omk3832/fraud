package com.fraud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.client.DEClient;
import com.fraud.client.MLClient;
import com.fraud.ml.LocalXgboostScorer;
import com.fraud.model.FraudRequest;
import com.fraud.model.FraudResponse;
import com.fraud.util.FeatureFilter;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Service
public class FraudService {
    private static final Logger log = LoggerFactory.getLogger(FraudService.class);

    private final FeatureFilter featureFilter;
    private final DEClient deClient;
    private final MLClient mlClient;
    private final ObjectProvider<LocalXgboostScorer> localXgboostScorer;
    private final String mlTransport;
    private final boolean mlEnabled;
    private final Duration deTimeout;
    private final Duration mlTimeout;
    private final boolean deEnabled;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final boolean deStubEnabled;
    private final String deStubResource;

    private JsonNode stubDeFeatures;

    public FraudService(FeatureFilter featureFilter, DEClient deClient,
                        MLClient mlClient,
                        ObjectProvider<LocalXgboostScorer> localXgboostScorer,
                        ObjectMapper objectMapper,
                        ResourceLoader resourceLoader,
                        @Value("${fraud.ml.enabled:false}") boolean mlEnabled,
                        @Value("${fraud.ml.transport:http}") String mlTransport,
                        @Value("${fraud.de.enabled:true}") boolean deEnabled,
                        @Value("${fraud.de.stub.enabled:false}") boolean deStubEnabled,
                        @Value("${fraud.de.stub.resource}") String deStubResource,
                        @Value("${de.timeout}") Duration deTimeout,
                        @Value("${ml.timeout}") Duration mlTimeout) {
        this.featureFilter = featureFilter;
        this.deClient = deClient;
        this.mlClient = mlClient;
        this.localXgboostScorer = localXgboostScorer;
        this.mlTransport = mlTransport;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.mlEnabled = mlEnabled;
        this.deEnabled = deEnabled;
        this.deStubEnabled = deStubEnabled;
        this.deStubResource = deStubResource;
        this.deTimeout = deTimeout;
        this.mlTimeout = mlTimeout;
    }

    @PostConstruct
    void loadLocalDeStub() throws IOException {
        if (!deStubEnabled) {
            return;
        }
        Resource res = resourceLoader.getResource(deStubResource);
        if (!res.exists() || !res.isReadable()) {
            throw new IllegalStateException(
                    "fraud.de.stub.enabled=true but resource not readable: " + deStubResource);
        }
        stubDeFeatures = objectMapper.readTree(res.getInputStream());
        log.warn("LOCAL TESTING: using fixed DE stub from {} ({} top-level keys). Do not use in production.",
                deStubResource, stubDeFeatures.size());
    }

    /**
     * Production: payment {@code req} → DE → wide feature JSON → ML. With {@code fraud.de.stub.enabled=true}
     * (local profile), the DE response is read from classpath instead of HTTP — same ML path as prod.
     * With {@code fraud.de.enabled=false}, the check body is sent as features (narrow tests only).
     */
    @CircuitBreaker(name = "deService", fallbackMethod = "fallback")
    @Bulkhead(name = "deService", type = Bulkhead.Type.SEMAPHORE)
    public Mono<FraudResponse> process(FraudRequest req) {
        if (deStubEnabled) {
            if (stubDeFeatures == null) {
                return Mono.error(new IllegalStateException("DE stub JSON not loaded"));
            }
            return scoreFromFeatures(stubDeFeatures);
        }
        if (!deEnabled) {
            return scoreFromFeatures(objectMapper.valueToTree(req));
        }
        return deClient.fetch(req)
                .timeout(deTimeout) // 20ms timeout
                .doOnError(ex -> log.warn("DE call failed or timed out: {}", ex.toString()))
                .flatMap(this::scoreFromFeatures);
    }

    /**
     * Orchestration step after DE returns features: either call ML predict or stub until the model exists.
     */
    private Mono<FraudResponse> scoreFromFeatures(JsonNode deFeatures) {
        if (!mlEnabled) {
            FraudResponse res = new FraudResponse();
            res.setError("ML_DISABLED");
            res.setMessage("ML service is disabled");
            return Mono.just(res);
        }
        JsonNode forMl = featureFilter.filter(deFeatures);
        if ("local".equalsIgnoreCase(mlTransport)) {
            LocalXgboostScorer scorer = localXgboostScorer.getIfAvailable();
            if (scorer == null) {
                return Mono.error(new IllegalStateException(
                        "fraud.ml.transport=local but LocalXgboostScorer is not available (check fraud.ml.transport)"));
            }
            return Mono.fromCallable(() -> scorer.score(forMl))
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(mlTimeout)
                    .doOnError(ex -> log.warn("Local ML predict failed: {}", ex.toString()))
                    .onErrorResume(ex -> Mono.just(mlFailureResponse(ex)));
        }
        return mlClient.predict(forMl)
                .timeout(mlTimeout)
                .map(this::mapMlResponse)
                .doOnError(ex -> log.warn("ML predict failed: {}", ex.toString()))
                .onErrorResume(ex -> Mono.just(mlFailureResponse(ex)));
    }

    private FraudResponse mlFailureResponse(Throwable ex) {
        Throwable c = reactor.core.Exceptions.unwrap(ex);
        FraudResponse res = new FraudResponse();
        String msg = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
        res.setError("ML_UNAVAILABLE");
        res.setMessage(msg);
        return res;
    }

    public Mono<FraudResponse> fallback(FraudRequest req, Throwable ex) {

        Throwable cause = reactor.core.Exceptions.unwrap(ex);

        FraudResponse res = new FraudResponse();

        //  TIMEOUT
        if (cause instanceof TimeoutException) {
            log.warn("DE TIMEOUT after {} ms", deTimeout.toMillis());

            res.setError("DE_TIMEOUT");
            res.setMessage("DE service did not respond within " + deTimeout.toMillis() + " ms");
            return Mono.just(res);
        }

        // OTHER FAILURE DE service is DOWN,API error,Any unexpected exception
        log.error("DE FAILURE: {}", cause.toString());

        res.setError("DE_FAILURE");
        res.setMessage("DE service is unavailable");

        return Mono.just(res);
    }

    private FraudResponse mapMlResponse(JsonNode ml) {
        FraudResponse res = new FraudResponse();
        if (ml.hasNonNull("score")) {
            res.setScore(ml.get("score").asDouble());
        } else if (ml.hasNonNull("probability")) {
            res.setScore(ml.get("probability").asDouble());
        } else {
            res.setScore(0.5);
        }
        if (ml.hasNonNull("riskLevel")) {
            res.setRiskLevel(ml.get("riskLevel").asText());
        } else if (ml.hasNonNull("risk")) {
            res.setRiskLevel(ml.get("risk").asText());
        } else {
            res.setRiskLevel("MEDIUM");
        }
        if (ml.has("decile") && !ml.get("decile").isNull()) {
            res.setDecile(ml.get("decile").asInt());
        }
        return res;
    }
}
