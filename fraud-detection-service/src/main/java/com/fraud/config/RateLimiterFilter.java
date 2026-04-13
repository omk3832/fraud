package com.fraud.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p><b>Where:</b> Edge of the app — Spring WebFlux {@link WebFilter} with {@code @Order(0)} so it runs
 * before routing to controllers. Rejects excess traffic with HTTP 429 (see {@code shouldBypass} for
 * health/actuator paths that skip the limit).
 *
 * <p><b>Why here:</b> Protects the whole JVM (CPU, threads, downstream calls) from abusive or accidental
 * traffic spikes. This is <em>ingress</em> fairness/capacity; it is not a substitute for per-dependency
 * limits — those stay on outbound gateways (Resilience4j bulkhead + circuit breaker).
 *
 * <p><b>Implementation:</b> Bucket4j token bucket ({@code fraud.ratelimit.*}), separate from Resilience4j.
 */
@Component
@Order(0)
public class RateLimiterFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterFilter.class);

    private final boolean enabled;
    private final boolean perClientIp;
    private final long capacity;
    private final long refillTokens;
    private final Duration refillPeriod;
    private final Bucket globalBucket;
    private final ConcurrentHashMap<String, Bucket> perClientBuckets = new ConcurrentHashMap<>();

    public RateLimiterFilter(
            @Value("${fraud.ratelimit.enabled:true}") boolean enabled,
            @Value("${fraud.ratelimit.per-client-ip:false}") boolean perClientIp,
            @Value("${fraud.ratelimit.capacity:500}") long capacity,
            @Value("${fraud.ratelimit.refill-tokens:500}") long refillTokens,
            @Value("${fraud.ratelimit.refill-period:1s}") Duration refillPeriod) {
        this.enabled = enabled;
        this.perClientIp = perClientIp;
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriod = refillPeriod;
        this.globalBucket = newBucket();
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.intervally(refillTokens, refillPeriod));
        return Bucket.builder().addLimit(limit).build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        if (shouldBypass(path)) {
            return chain.filter(exchange);
        }
        Bucket bucket = resolveBucket(exchange);
        if (bucket.tryConsume(1)) {
            return chain.filter(exchange);
        }
        log.debug("Rate limit exceeded for path {} client {}", path, clientKey(exchange));
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"error\":\"rate_limited\",\"message\":\"Too many requests\"}".getBytes();
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private boolean shouldBypass(String path) {
        return path.startsWith("/actuator")
                || path.startsWith("/favicon.ico")
                || path.equals("/")
                || path.equals("/health");
    }

    private Bucket resolveBucket(ServerWebExchange exchange) {
        if (!perClientIp) {
            return globalBucket;
        }
        String key = clientKey(exchange);
        return perClientBuckets.computeIfAbsent(key, k -> newBucket());
    }

    private static String clientKey(ServerWebExchange exchange) {
        if (exchange.getRequest().getRemoteAddress() == null
                || exchange.getRequest().getRemoteAddress().getAddress() == null) {
            return "unknown";
        }
        return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
}
