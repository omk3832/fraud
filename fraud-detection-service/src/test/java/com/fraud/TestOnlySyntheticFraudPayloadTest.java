package com.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.model.FraudRequest;
import com.fraud.util.FeatureFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TESTING ONLY: validates synthetic {@code TEST_ONLY_fraud_request.json} built from {@code features.txt}.
 * This is the shape to use for end‑to‑end checks when DE is unavailable ({@code fraud.de.enabled=false}).
 */
@SpringBootTest
class TestOnlySyntheticFraudPayloadTest {

    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    FeatureFilter featureFilter;

    @Test
    void synthetic_json_deserializes_and_filters_to_ml_feature_allowlist() throws Exception {
        JsonNode root = objectMapper.readTree(
                new ClassPathResource("TEST_ONLY_fraud_request.json").getInputStream());

        assertThat(root.path("_TESTING_ONLY").path("purpose").asText())
                .isEqualTo("TESTING_ONLY_SYNTHETIC_PAYLOAD");

        FraudRequest req = objectMapper.treeToValue(root, FraudRequest.class);
        assertThat(req.getTxn_id()).isEqualTo("TEST_ONLY_TXN_001");
        assertThat(req.getMemberuid()).isEqualTo("TEST_ONLY_memberuid_001");
        assertThat(req.getExtraFields().size()).isGreaterThanOrEqualTo(180);

        JsonNode asTree = objectMapper.valueToTree(req);
        JsonNode forMl = featureFilter.filter(asTree);

        assertThat(forMl.size())
                .as("one column per features.txt row except label `target`, which is omitted from the JSON")
                .isEqualTo(196);
        assertThat(forMl.has("target")).isFalse();
    }
}
