package com.fraud.benchmark;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dev helper: {@code POST /fraud/check} load test with throughput + latency percentiles (2xx only).
 * <p>
 * Run (app must be listening):
 * <pre>
 * mvn -q test-compile exec:java \\
 *   -Dexec.mainClass=com.fraud.benchmark.LoadBenchmark \\
 *   -Dexec.classpathScope=test \\
 *   -Dexec.args="http://127.0.0.1:8080/fraud/check 1000 25"
 * </pre>
 * Args: {@code [url] [totalRequests] [threadPoolSize]}
 */
public final class LoadBenchmark {

    private static final String DEFAULT_BODY =
            "{\"txn_id\":\"bench-001\",\"memberuid\":\"bench-member\"}";

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "http://127.0.0.1:8080/fraud/check";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int poolSize = args.length > 2 ? Integer.parseInt(args[2]) : 30;

        if (n < 1 || poolSize < 1) {
            throw new IllegalArgumentException("count and concurrency must be >= 1");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(DEFAULT_BODY))
                .build();

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger err = new AtomicInteger();
        List<Long> successNanos = Collections.synchronizedList(new ArrayList<>(Math.min(n, 1024)));

        long t0 = System.nanoTime();

        try (ExecutorService pool = Executors.newFixedThreadPool(poolSize)) {
            List<Future<?>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    long reqStart = System.nanoTime();
                    try {
                        HttpResponse<Void> r = client.send(request, HttpResponse.BodyHandlers.discarding());
                        long dt = System.nanoTime() - reqStart;
                        if (r.statusCode() == 200) {
                            ok.incrementAndGet();
                            successNanos.add(dt);
                        } else {
                            err.incrementAndGet();
                        }
                    } catch (Exception e) {
                        err.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        double seconds = (System.nanoTime() - t0) / 1_000_000_000.0;
        double rps = seconds > 0 ? n / seconds : 0.0;

        LatencyStats lat = LatencyStats.fromNanos(successNanos);

        String latencyJson;
        if (lat.samples == 0) {
            latencyJson =
                    "\"latency_ms\":{\"samples\":0,"
                            + "\"min\":null,\"max\":null,\"mean\":null,"
                            + "\"p50\":null,\"p95\":null,\"p99\":null,"
                            + "\"note\":\"only HTTP 2xx counted\""
                            + "}";
        } else {
            latencyJson = String.format(
                    "\"latency_ms\":{"
                            + "\"samples\":%d,"
                            + "\"min\":%.3f,"
                            + "\"max\":%.3f,"
                            + "\"mean\":%.3f,"
                            + "\"p50\":%.3f,"
                            + "\"p95\":%.3f,"
                            + "\"p99\":%.3f,"
                            + "\"note\":\"per-request wall time until response headers (2xx only)\""
                            + "}",
                    lat.samples,
                    lat.minMs,
                    lat.maxMs,
                    lat.meanMs,
                    lat.p50Ms,
                    lat.p95Ms,
                    lat.p99Ms);
        }

        System.out.printf(
                "{"
                        + "\"url\":\"%s\","
                        + "\"count\":%d,"
                        + "\"concurrency\":%d,"
                        + "\"seconds\":%.3f,"
                        + "\"rps\":%.2f,"
                        + "\"ok\":%d,"
                        + "\"errors\":%d,"
                        + "%s"
                        + "}%n",
                url, n, poolSize, seconds, rps, ok.get(), err.get(), latencyJson);
    }

    private static final class LatencyStats {
        final int samples;
        final double minMs;
        final double maxMs;
        final double meanMs;
        final double p50Ms;
        final double p95Ms;
        final double p99Ms;

        private LatencyStats(
                int samples,
                double minMs,
                double maxMs,
                double meanMs,
                double p50Ms,
                double p95Ms,
                double p99Ms) {
            this.samples = samples;
            this.minMs = minMs;
            this.maxMs = maxMs;
            this.meanMs = meanMs;
            this.p50Ms = p50Ms;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
        }

        static LatencyStats fromNanos(List<Long> nanosList) {
            if (nanosList.isEmpty()) {
                return new LatencyStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            long[] a = new long[nanosList.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = nanosList.get(i);
            }
            java.util.Arrays.sort(a);
            long sum = 0L;
            for (long v : a) {
                sum += v;
            }
            double meanMs = (sum / (double) a.length) / 1_000_000.0;
            return new LatencyStats(
                    a.length,
                    a[0] / 1_000_000.0,
                    a[a.length - 1] / 1_000_000.0,
                    meanMs,
                    percentileNanosToMs(a, 50),
                    percentileNanosToMs(a, 95),
                    percentileNanosToMs(a, 99));
        }

        /** Nearest-rank percentile on sorted nanoseconds → ms. */
        private static double percentileNanosToMs(long[] sortedAsc, int p) {
            if (sortedAsc.length == 0) {
                return Double.NaN;
            }
            double rank = Math.ceil(p / 100.0 * sortedAsc.length) - 1;
            int idx = (int) Math.max(0, Math.min(sortedAsc.length - 1, rank));
            return sortedAsc[idx] / 1_000_000.0;
        }
    }
}
