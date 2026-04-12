package com.fraud.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.model.FraudResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@TestPropertySource(
        properties = {
                "fraud.ml.transport=local",
                "fraud.ml.local.model-resource=classpath:ml/toy_model.json",
                "fraud.ml.local.feature-list-resource=classpath:ml/toy_feature_list.json",
                "fraud.ml.local.template-resource=classpath:ml/toy_de_template_defaults.json",
        })
class LocalXgboostScorerTest {

    @Autowired
    LocalXgboostScorer localXgboostScorer;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void toyModel_matches_python_reference_score_and_risk_band() throws Exception {
        JsonNode ref = objectMapper.readTree(
                new ClassPathResource("ml/toy_reference.json").getInputStream());
        double exp = ref.get("expected_score").asDouble();
        JsonNode features = objectMapper.createObjectNode()
                .put("f0", ref.get("input_f0").asDouble())
                .put("f1", ref.get("input_f1").asDouble());

        FraudResponse res = localXgboostScorer.score(features);

        assertThat(res.getScore()).isCloseTo(exp, within(1e-4));
        assertThat(res.getRiskLevel()).isEqualTo(exp >= 0.3 ? "HIGH" : exp >= 0.1 ? "MEDIUM" : "LOW");
    }

    @Test
    void decileFromPandasCut_uniformBins_matchesLeftClosedFirstInterval() {
        double[] e = {0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
        assertThat(LocalXgboostScorer.decileFromPandasCut(0.0, e)).isEqualTo(10);
        assertThat(LocalXgboostScorer.decileFromPandasCut(0.1, e)).isEqualTo(10);
        assertThat(LocalXgboostScorer.decileFromPandasCut(0.1000001, e)).isEqualTo(9);
        assertThat(LocalXgboostScorer.decileFromPandasCut(1.0, e)).isEqualTo(1);
        assertThat(LocalXgboostScorer.decileFromPandasCut(-0.01, e)).isNull();
        assertThat(LocalXgboostScorer.decileFromPandasCut(1.01, e)).isNull();
    }
}
