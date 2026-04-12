TESTING ONLY — synthetic DE-shaped data (not for production)

What was added
  - python_ml/testing/TEST_ONLY_generate_payload_from_features_txt.py
    Reads fraud-detection-service/src/main/resources/features.txt (197 rows).
    Skips the label column `target`. Writes 196 numeric/string feature fields + markers.

  - fraud-detection-service/src/test/resources/TEST_ONLY_fraud_request.json
    Body for POST /fraud/check (includes required txn_id).

  - python_ml/testing/TEST_ONLY_ml_feature_payload.json
    Body for POST http://localhost:8001/predict (ML only).

  - fraud-detection-service/src/test/java/.../TestOnlySyntheticFraudPayloadTest.java
    Verifies JSON maps into FraudRequest and FeatureFilter keeps 196 allowlisted keys.

Regenerate after features.txt changes
  python3 python_ml/testing/TEST_ONLY_generate_payload_from_features_txt.py

End-to-end without DE (local only, recommended)
  Run Java with: --spring.profiles.active=local
  Uses classpath local-stub/de_features_stub.json as if DE returned it; /fraud/check body stays payment-sized.
  Start ML (uvicorn on ml.api.url), then:

  curl -s -X POST http://localhost:8080/fraud/check \
    -H "Content-Type: application/json" \
    -d @fraud-detection-service/src/test/resources/TEST_ONLY_fraud_request.json
