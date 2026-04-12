package com.fraud.util;

/*
 * ============================================================================
 * [FEATURE-ALLOWLIST] — Daily file reload + filter for ML feature keys
 * ============================================================================
 * LOCATION: fraud-detection-service/src/main/java/com/fraud/util/FeatureFilter.java
 * CONFIG:   fraud-detection-service/src/main/resources/application.properties
 *            keys → fraud.features.file-path, fraud.features.reload.cron
 * SCHEDULE: @EnableScheduling must be on the Spring Boot app class (see below).
 * ============================================================================
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the set of DE feature names that may be forwarded to ML ({@code features.txt} / external file).
 * <p>
 * <b>Why {@link AtomicReference}:</b> the allowlist can be replaced at runtime (scheduled reload). Requests
 * must never see a half-built set; we swap the whole {@link Set} atomically after loading.
 * <p>
 * <b>Why optional external file:</b> ops can update keys on disk (e.g. your system cron publishes a new file)
 * without redeploying the JAR; classpath {@code features.txt} remains the fallback for local/dev.
 * <p>
 * <b>Why {@link #scheduledReloadExternalFeatureFile}:</b> Java does not auto-detect file edits; something must
 * re-read the file into memory. This schedule aligns with when your cron drops the new file (same wall-clock
 * or shortly after). If you prefer only your cron, call a reload endpoint instead and remove this task.
 */
@Component
public class FeatureFilter {

    // -------------------------------------------------------------------------
    // [FEATURE-ALLOWLIST] Fields (external path + in-memory allowlist snapshot)
    // -------------------------------------------------------------------------

    private static final Logger log = LoggerFactory.getLogger(FeatureFilter.class);
    /** Bundled default list when no external path is configured or file is missing. */
    private static final String CLASSPATH_FEATURES = "features.txt";

    private final ObjectMapper mapper = new ObjectMapper();
    /** Current allowlist; replaced atomically on reload so concurrent {@link #filter} calls stay safe. */
    private final AtomicReference<Set<String>> allowedFeaturesRef = new AtomicReference<>();
    /** OS path to allowlist file; empty means classpath-only and scheduled file reload is skipped. */
    private final String externalFilePath;

    public FeatureFilter(@Value("${fraud.features.file-path:}") String externalFilePath) {
        this.externalFilePath = externalFilePath == null ? "" : externalFilePath.trim();
    }

    // -------------------------------------------------------------------------
    // [FEATURE-ALLOWLIST] Startup: load file or classpath (first time into RAM)
    // -------------------------------------------------------------------------

    /**
     * Load once at startup so the service is usable before the first scheduled reload.
     * Prefer external file when configured and present so prod matches ops-managed list.
     */
    @PostConstruct
    void init() {
        if (!externalFilePath.isEmpty()) {
            Path p = Path.of(externalFilePath);
            if (Files.isRegularFile(p)) {
                loadFromPath(p).ifPresentOrElse(
                        allowedFeaturesRef::set,
                        () -> {
                            log.warn("Could not load {}; falling back to classpath {}", externalFilePath, CLASSPATH_FEATURES);
                            allowedFeaturesRef.set(loadFromClasspath());
                        });
                log.info("Feature allowlist loaded from file {} ({} names)", externalFilePath, allowedFeaturesRef().size());
                return;
            }
            log.warn("fraud.features.file-path set but file not found: {} — using classpath {}", externalFilePath, CLASSPATH_FEATURES);
        }
        allowedFeaturesRef.set(loadFromClasspath());
        log.info("Feature allowlist loaded from classpath {} ({} names)", CLASSPATH_FEATURES, allowedFeaturesRef().size());
    }

    // -------------------------------------------------------------------------
    // [FEATURE-ALLOWLIST] Daily (or custom cron) re-read disk → RAM — NOT your OS
    // cron; that job only writes the file. Tip: stagger write vs read to avoid races.
    // -------------------------------------------------------------------------

    /**
     * Re-reads the external file into RAM on a fixed schedule (default 07:00 daily).
     * Why: your system cron updates the file on disk; this task is the in-JVM “reader” so new keys apply
     * without app restart. No-op when {@code fraud.features.file-path} is empty. Time zone = JVM default
     * unless you set {@code TZ} / {@code user.timezone}.
     */
    @Scheduled(cron = "${fraud.features.reload.cron:0 0 7 * * *}")
    public void scheduledReloadExternalFeatureFile() {
        if (externalFilePath.isEmpty()) {
            return;
        }
        Path p = Path.of(externalFilePath);
        if (!Files.isRegularFile(p)) {
            log.warn("Scheduled feature reload skipped — not a regular file: {}", externalFilePath);
            return;
        }
        Optional<Set<String>> next = loadFromPath(p);
        if (next.isEmpty()) {
            // Why: avoid clearing production allowlist on a bad read or empty file.
            log.warn("Scheduled feature reload failed — keeping previous allowlist ({} names)", allowedFeaturesRef().size());
            return;
        }
        int n = next.get().size();
        allowedFeaturesRef.set(next.get());
        log.info("Feature allowlist reloaded from {} ({} names) — scheduled task", externalFilePath, n);
    }

    // -------------------------------------------------------------------------
    // [FEATURE-ALLOWLIST] Per-request: strip unknown DE keys before ML
    // -------------------------------------------------------------------------

    /**
     * Drops any JSON fields not in the allowlist so ML only receives known feature columns (matches {@code features.txt} contract).
     */
    public JsonNode filter(JsonNode inputFeatures) {
        ObjectNode filtered = mapper.createObjectNode();
        Set<String> allowed = allowedFeaturesRef();
        inputFeatures.fieldNames().forEachRemaining(field -> {
            if (allowed.contains(field)) {
                filtered.set(field, inputFeatures.get(field));
            }
        });
        return filtered;
    }

    private Set<String> allowedFeaturesRef() {
        Set<String> s = allowedFeaturesRef.get();
        return s != null ? s : Set.of();
    }

    // -------------------------------------------------------------------------
    // [FEATURE-ALLOWLIST] Load helpers (classpath jar vs disk file)
    // -------------------------------------------------------------------------

    /** Why {@link Set#copyOf}: publish an immutable snapshot after build so callers never mutate internal state. */
    private Set<String> loadFromClasspath() {
        var in = getClass().getClassLoader().getResourceAsStream(CLASSPATH_FEATURES);
        if (in == null) {
            throw new IllegalStateException("Classpath resource missing: " + CLASSPATH_FEATURES);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Set<String> set = new LinkedHashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    set.add(t);
                }
            }
            return Set.copyOf(set);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load feature list from classpath: " + CLASSPATH_FEATURES, e);
        }
    }

    /**
     * Why {@link Optional}: reload paths treat missing/empty/IO errors as failure without throwing into the scheduler.
     */
    private Optional<Set<String>> loadFromPath(Path path) {
        try {
            Set<String> set = new LinkedHashSet<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    set.add(t);
                }
            }
            if (set.isEmpty()) {
                log.warn("Feature file {} is empty; reload ignored", path);
                return Optional.empty();
            }
            return Optional.of(Set.copyOf(set));
        } catch (Exception e) {
            log.error("Failed to read feature file {}: {}", path, e.toString());
            return Optional.empty();
        }
    }
}
