package com.fraud.service;

import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Duration;

@Service
public class MetricsService {

    @Autowired
    private MeterRegistry registry;
    private final Counter request;
    private final Counter failure;
    private final Timer latency;

    public MetricsService(MeterRegistry registry) {
        this.request = registry.counter("fraud.request");
        this.failure = registry.counter("fraud.failure");
        this.latency = registry.timer("fraud.latency");
    }

    public void incRequest() { request.increment(); }
    public void incFailure() { failure.increment(); }
    public void record(long time) {
        latency.record(Duration.ofMillis(time));
    }
}
