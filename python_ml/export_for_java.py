#!/usr/bin/env python3
"""
One-time export for Java XGBoost4J (fraud.ml.transport=local).

Reads the same pickle artifacts as app.py, writes:
  - model.json          — booster.save_model (UBJ/JSON)
  - feature_list.json   — ordered feature names
  - de_template_defaults.json — optional baseline row (same as app de_template_row)

Usage:
  export FRAUD_JAVA_EXPORT_DIR=/path/to/fraud-detection-service/src/main/resources/ml
  python export_for_java.py

Or pass one argument: output directory.
"""
from __future__ import annotations

import json
import math
import os
import pickle
import sys
from pathlib import Path


def _json_safe(obj):  # NaN/Inf are invalid in strict JSON; Jackson rejects them by default
    if isinstance(obj, dict):
        return {k: _json_safe(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_json_safe(v) for v in obj]
    if isinstance(obj, float) and (math.isnan(obj) or math.isinf(obj)):
        return None
    return obj

BASE_DIR = Path(__file__).resolve().parent
PKL_DIR = Path(os.environ.get("FRAUD_PKL_DIR", BASE_DIR / "pkl"))

MODEL_FILE = PKL_DIR / "xgb_model_20260206.pkl"
FEATURE_LIST_FILE = PKL_DIR / "xgb_feature_list_20260206.pkl"


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
        os.environ.get(
            "FRAUD_JAVA_EXPORT_DIR",
            BASE_DIR.parent / "fraud-detection-service" / "src" / "main" / "resources" / "ml",
        )
    )
    out.mkdir(parents=True, exist_ok=True)

    with open(MODEL_FILE, "rb") as f:
        model = pickle.load(f)
    with open(FEATURE_LIST_FILE, "rb") as f:
        feature_list: list = pickle.load(f)

    booster = model.get_booster()
    model_path = out / "model.json"
    booster.save_model(str(model_path))

    with open(out / "feature_list.json", "w", encoding="utf-8") as f:
        json.dump(feature_list, f, indent=2)

    # Optional: same CSV-driven template as FastAPI (only if app would have loaded it)
    template: dict = {}
    try:
        import pandas as pd  # noqa: WPS433

        _DEFAULT_DE_CSV = Path.home() / "Downloads" / "fraud_doc" / "V2_finaldata_training.csv"
        de_csv = Path(os.environ.get("FRAUD_DE_REFERENCE_CSV", _DEFAULT_DE_CSV))
        if de_csv.is_file():
            _de_df = pd.read_csv(de_csv, nrows=1)
            _drop = [c for c in _de_df.columns if c == "target" or str(c).startswith("Unnamed")]
            _de_df = _de_df.drop(columns=_drop, errors="ignore")
            _raw = _de_df.iloc[0].to_dict()
            template = {k: _raw[k] for k in feature_list if k in _raw}
    except Exception as e:  # pragma: no cover
        print(f"Template export skipped: {e}")

    with open(out / "de_template_defaults.json", "w", encoding="utf-8") as f:
        json.dump(_json_safe(template), f, indent=2, default=str)

    print(f"Exported Java ML bundle to {out} ({len(feature_list)} features).")


if __name__ == "__main__":
    main()
