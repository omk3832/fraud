package com.fraud.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FraudResponse {
    /**
     * Echo of {@link com.fraud.model.FraudRequest#getMemberuid()} (not a model feature).
     * Always serialized so clients see the correlation id even when null.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String memberuid;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String txn_id;
    private double score;
    private String message;
    /** OOT score decile (10 = lowest risk bin, 1 = highest), when ML returns it or local bins are configured. */
    private Integer decile;
    private String riskLevel;
    /** Set when scoring could not reach ML or the model returned an error (for debugging; omit in prod clients if you prefer). */
    private String error;
    /**
     * Feature name → numeric value fed to XGBoost (local ML only), in {@code feature_list.json} order.
     * Omit in production if the payload should stay small.
     */
    private Map<String, Double> scoringFeatures;
}