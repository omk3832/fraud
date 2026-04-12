"""Regenerate placeholder 2-feature XGBoost JSON for Java dev.

Writes:
- fraud-detection-service/src/test/resources/ml/toy_*.json (+ toy_reference.json for tests)
- fraud-detection-service/src/main/resources/ml/model.json (+ feature_list.json, de_template_defaults.json)

Training is intentionally imbalanced so predict([0,0]) is LOW (stub has no f0/f1; model input is zeros).
For your real fraud model, use python_ml/export_for_java.py instead.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

_ROOT = Path(__file__).resolve().parents[2]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

import xgboost as xgb  # noqa: E402

_TEST_OUT = _ROOT / "fraud-detection-service" / "src" / "test" / "resources" / "ml"
_MAIN_OUT = _ROOT / "fraud-detection-service" / "src" / "main" / "resources" / "ml"


def _train():
    rng = np.random.default_rng(42)
    n0, n1 = 500, 30
    x0 = np.zeros((n0, 2), dtype=np.float32) + rng.normal(0, 0.02, (n0, 2)).astype(np.float32)
    x1 = np.ones((n1, 2), dtype=np.float32) * 0.9 + rng.normal(0, 0.05, (n1, 2)).astype(np.float32)
    x = np.vstack([x0, x1])
    y = np.array([0] * n0 + [1] * n1)
    dtrain = xgb.DMatrix(x, label=y, feature_names=["f0", "f1"])
    params = {
        "objective": "binary:logistic",
        "max_depth": 3,
        "eta": 0.2,
        "min_child_weight": 5,
        "verbosity": 0,
    }
    return xgb.train(params, dtrain, num_boost_round=25)


def main() -> None:
    bst = _train()
    z = float(bst.predict(xgb.DMatrix(np.array([[0.0, 0.0]], dtype=np.float32), feature_names=["f0", "f1"]))[0])
    ref = float(bst.predict(xgb.DMatrix(np.array([[0.5, 0.5]], dtype=np.float32), feature_names=["f0", "f1"]))[0])
    print(f"Placeholder model: pred[0,0]={z:.4f} (expect LOW for DE stub), pred[0.5,0.5]={ref:.4f}")

    _TEST_OUT.mkdir(parents=True, exist_ok=True)
    _MAIN_OUT.mkdir(parents=True, exist_ok=True)
    bst.save_model(str(_TEST_OUT / "toy_model.json"))
    bst.save_model(str(_MAIN_OUT / "model.json"))
    for path in (_TEST_OUT / "toy_feature_list.json", _MAIN_OUT / "feature_list.json"):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(["f0", "f1"], f)
    with open(_TEST_OUT / "toy_de_template_defaults.json", "w", encoding="utf-8") as f:
        json.dump({}, f)
    with open(_MAIN_OUT / "de_template_defaults.json", "w", encoding="utf-8") as f:
        json.dump({}, f)
    with open(_TEST_OUT / "toy_reference.json", "w", encoding="utf-8") as f:
        json.dump({"input_f0": 0.5, "input_f1": 0.5, "expected_score": ref}, f)
    print("Updated", _TEST_OUT, "and", _MAIN_OUT)


if __name__ == "__main__":
    main()
