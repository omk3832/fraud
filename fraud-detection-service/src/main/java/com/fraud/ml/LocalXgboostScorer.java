package com.fraud.ml;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.model.FraudResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads an exported XGBoost JSON model once and scores flat feature JSON in-process
 * (same contract as {@code python_ml/app.py}: merge template → defaults → incoming).
 */
@Component
@ConditionalOnProperty(name = "fraud.ml.transport", havingValue = "local")
public class LocalXgboostScorer {

    private static final Logger log = LoggerFactory.getLogger(LocalXgboostScorer.class);
    private static final Set<String> STRING_DEFAULT_FEATURES = Set.of("ipaddress", "deviceid");

    private final ResourceLoader resourceLoader;
    private final String modelResource;
    private final String featureListResource;
    private final String templateResource;
    private final String decileBinsResource;
    private final ObjectMapper objectMapper;

    private Booster booster;
    private List<String> featureList = List.of();
    private Map<String, JsonNode> templateDefaults = Map.of();
    /** Monotonic bin edges (length = bins + 1), same as Python {@code pd.cut(..., include_lowest=True)}; optional. */
    private double[] decileEdges;

    public LocalXgboostScorer(
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            @Value("${fraud.ml.local.model-resource}") String modelResource,
            @Value("${fraud.ml.local.feature-list-resource}") String featureListResource,
            @Value("${fraud.ml.local.template-resource:classpath:ml/de_template_defaults.json}") String templateResource,
            @Value("${fraud.ml.local.decile-bins-resource:}") String decileBinsResource) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.modelResource = modelResource;
        this.featureListResource = featureListResource;
        this.templateResource = templateResource;
        this.decileBinsResource = decileBinsResource == null ? "" : decileBinsResource.trim();
    }

    @PostConstruct
    void start() throws IOException {
        Path modelPath = materializeResource(resourceLoader.getResource(modelResource), ".json");
        try {
            booster = XGBoost.loadModel(modelPath.toString());
        } catch (XGBoostError e) {
            throw new IOException("XGBoost.loadModel failed for " + modelResource, e);
        }
        featureList = readFeatureList();
        templateDefaults = readTemplateDefaults();
        decileEdges = readDecileBinEdges();
        log.info("Local XGBoost loaded ({} features) from {}", featureList.size(), modelResource);
    }

    @PreDestroy
    void stop() {
        if (booster != null) {
            booster.dispose();
            booster = null;
        }
    }

    /**
     * @param incoming flat feature map (after {@link com.fraud.util.FeatureFilter})
     */
    public FraudResponse score(JsonNode incoming) {
        float[] row = buildFeatureRow(incoming);
        final DMatrix dmat;
        try {
            dmat = new DMatrix(row, 1, row.length);
        } catch (XGBoostError e) {
            throw new IllegalStateException("DMatrix allocation failed", e);
        }
        try {
            float[][] preds = booster.predict(dmat);
            double score = preds[0][0];
            FraudResponse res = new FraudResponse();
            res.setScore(score);
            res.setDecile(decileFromPandasCut(score, decileEdges));
            res.setRiskLevel(riskLevel(score));
            res.setScoringFeatures(rowToNamedMap(row));
            return res;
        } catch (XGBoostError e) {
            throw new IllegalStateException("XGBoost predict failed", e);
        } finally {
            dmat.dispose();
        }
    }

    private double[] readDecileBinEdges() {
        if (decileBinsResource.isEmpty()) {
            return null;
        }
        Resource res = resourceLoader.getResource(decileBinsResource);
        if (!res.exists()) {
            log.warn("fraud.ml.local.decile-bins-resource not found (decile omitted): {}", decileBinsResource);
            return null;
        }
        try (InputStream in = res.getInputStream()) {
            double[] edges = objectMapper.readValue(in, double[].class);
            if (edges == null || edges.length < 2) {
                log.warn("Decile bins JSON must be a non-empty array of edges; got {}", edges == null ? null : edges.length);
                return null;
            }
            return edges;
        } catch (Exception e) {
            log.warn("Could not load decile bins from {}: {}", decileBinsResource, e.toString());
            return null;
        }
    }

    /**
     * Match {@code python_ml/app.py}: {@code pd.cut([score], bins=edges, labels=range(n,0,-1), include_lowest=True)}.
     * Returns label {@code n - binIndex} with {@code n = len(edges)-1}.
     */
    static Integer decileFromPandasCut(double score, double[] e) {
        if (e == null || e.length < 2 || Double.isNaN(score)) {
            return null;
        }
        int nBins = e.length - 1;
        if (score < e[0] || score > e[nBins]) {
            return null;
        }
        int bin;
        if (score <= e[1]) {
            bin = 0;
        } else {
            bin = -1;
            for (int i = 1; i < nBins; i++) {
                if (score > e[i] && score <= e[i + 1]) {
                    bin = i;
                    break;
                }
            }
            if (bin < 0) {
                return null;
            }
        }
        return nBins - bin;
    }

    private List<String> readFeatureList() throws IOException {
        Resource res = resourceLoader.getResource(featureListResource);
        try (InputStream in = res.getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<>() {});
        }
    }

    private Map<String, JsonNode> readTemplateDefaults() {
        Resource res = resourceLoader.getResource(templateResource);
        if (!res.exists()) {
            return Collections.emptyMap();
        }
        try (InputStream in = res.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            if (!root.isObject()) {
                return Collections.emptyMap();
            }
            Map<String, JsonNode> m = new HashMap<>();
            ObjectNode obj = (ObjectNode) root;
            obj.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue()));
            return Map.copyOf(m);
        } catch (Exception e) {
            log.warn("Could not load template defaults from {}: {}", templateResource, e.toString());
            return Collections.emptyMap();
        }
    }

    private Map<String, Double> rowToNamedMap(float[] row) {
        Map<String, Double> m = new LinkedHashMap<>(row.length);
        for (int i = 0; i < row.length; i++) {
            m.put(featureList.get(i), (double) row[i]);
        }
        return m;
    }

    /** Match {@code python_ml/app.py} {@code _build_feature_row} merged row, then float features for DMatrix. */
    float[] buildFeatureRow(JsonNode incoming) {
        int n = featureList.size();
        float[] row = new float[n];
        for (int i = 0; i < n; i++) {
            String name = featureList.get(i);
            if ("target".equals(name)) {
                row[i] = 0f;
                continue;
            }
            JsonNode v = resolveValue(name, incoming);
            row[i] = (float) jsonToFeatureDouble(v, name);
        }
        return row;
    }

    private JsonNode resolveValue(String name, JsonNode incoming) {
        if (incoming != null && incoming.has(name) && !incoming.get(name).isNull()) {
            return incoming.get(name);
        }
        JsonNode t = templateDefaults.get(name);
        if (t != null && !t.isNull()) {
            return t;
        }
        return null;
    }

    /**
     * Convert JSON / defaults to a single float (XGBoost dense row).
     * String columns {@code ipaddress} / {@code deviceid} follow Python defaults of {@code "0"} then
     * parse-or-hash so non-numeric tokens still produce a stable float.
     */
    double jsonToFeatureDouble(JsonNode v, String featureName) {
        if (v == null || v.isNull()) {
            return defaultNumeric(featureName);
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isTextual()) {
            return stringToFeatureDouble(v.asText());
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? 1.0 : 0.0;
        }
        return defaultNumeric(featureName);
    }

    private double defaultNumeric(String featureName) {
        if (STRING_DEFAULT_FEATURES.contains(featureName)) {
            return stringToFeatureDouble("0");
        }
        return 0.0;
    }

    static double stringToFeatureDouble(String s) {
        if (s == null || s.isEmpty() || "0".equals(s)) {
            return 0.0;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {
            return fnv1a64ToSignedUnit(s);
        }
    }

    /** Stable [-1, 1] — not identical to every pandas/XGBoost edge case; validate with goldens for prod models. */
    private static double fnv1a64ToSignedUnit(String s) {
        long h = 0xcbf29ce484222325L; // FNV-1a 64-bit offset
        for (int i = 0; i < s.length(); i++) {
            h ^= (byte) s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h / (double) (1L << 62);
    }

    private static String riskLevel(double score) {
        if (score >= 0.3) {
            return "HIGH";
        }
        if (score >= 0.1) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static Path materializeResource(Resource resource, String suffix) throws IOException {
        if (!resource.exists()) {
            throw new IllegalStateException("ML resource missing: " + resource);
        }
        Path tmp = Files.createTempFile("fraud-xgb-model-", suffix);
        tmp.toFile().deleteOnExit();
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }
}
