#!/usr/bin/env python3
"""
================================================================================
TESTING ONLY — NOT FOR PRODUCTION
================================================================================
Builds a synthetic fraud feature payload from the same feature list the Java
FeatureFilter uses (`features.txt`). Use this when you have no DE service or
DE response file, to exercise Java → ML end‑to‑end.

Outputs:
  - fraud-detection-service/src/test/resources/TEST_ONLY_fraud_request.json
  - python_ml/testing/TEST_ONLY_ml_feature_payload.json

Re-run this script after `features.txt` changes.
================================================================================
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
FEATURES_TXT = (
    REPO_ROOT
    / "fraud-detection-service"
    / "src"
    / "main"
    / "resources"
    / "features.txt"
)
OUT_JAVA_TEST = (
    REPO_ROOT
    / "fraud-detection-service"
    / "src"
    / "test"
    / "resources"
    / "TEST_ONLY_fraud_request.json"
)
OUT_ML_ONLY = REPO_ROOT / "python_ml" / "testing" / "TEST_ONLY_ml_feature_payload.json"

# Shown inside JSON so curl/IDE users see it immediately (safe: keyed string).
TESTING_MARKER = {
    "purpose": "TESTING_ONLY_SYNTHETIC_PAYLOAD",
    "source": "features.txt via TEST_ONLY_generate_payload_from_features_txt.py",
    "do_not_use_in_production": True,
}


def load_feature_names(path: Path) -> list[str]:
    raw = path.read_text(encoding="utf-8").splitlines()
    names = [line.strip() for line in raw if line.strip()]
    return names


def default_value(name: str):
    if name == "memberuid":
        return "TEST_ONLY_memberuid_001"
    return 0.0


def build_feature_map(names: list[str]) -> dict:
    out = {}
    for name in names:
        if name == "target":
            continue
        out[name] = default_value(name)
    return out


def main() -> int:
    if not FEATURES_TXT.is_file():
        print(f"Missing {FEATURES_TXT}", file=sys.stderr)
        return 1

    names = load_feature_names(FEATURES_TXT)
    features = build_feature_map(names)

    java_body = {
        "_TESTING_ONLY": TESTING_MARKER,
        "txn_id": "TEST_ONLY_TXN_001",
        **features,
    }
    OUT_JAVA_TEST.parent.mkdir(parents=True, exist_ok=True)
    OUT_JAVA_TEST.write_text(json.dumps(java_body, indent=2), encoding="utf-8")
    print(f"Wrote {OUT_JAVA_TEST} ({len(features)} feature keys + txn_id)")

    ml_body = {
        "_TESTING_ONLY": TESTING_MARKER,
        **features,
    }
    OUT_ML_ONLY.parent.mkdir(parents=True, exist_ok=True)
    OUT_ML_ONLY.write_text(json.dumps(ml_body, indent=2), encoding="utf-8")
    print(f"Wrote {OUT_ML_ONLY}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
