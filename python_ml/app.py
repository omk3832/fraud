"""
Fraud scoring API: accepts a flat JSON body shaped like the DE (data-engineering) feature response,
optionally grounded on one row from the training CSV as defaults for missing keys.
"""
from __future__ import annotations

import os
import pickle
from pathlib import Path
from typing import Any, Dict, Optional

import pandas as pd
import xgboost as xgb
from fastapi import FastAPI, Body, HTTPException

# -----------------------------
# Paths (override with env vars)
# -----------------------------
BASE_DIR = Path(__file__).resolve().parent
PKL_DIR = Path(os.environ.get("FRAUD_PKL_DIR", BASE_DIR / "pkl"))
# Training CSV is large (~GB); only the first row is read for a DE-like template.
_DEFAULT_DE_CSV = Path.home() / "Downloads" / "fraud_doc" / "V2_finaldata_training.csv"
DE_REFERENCE_CSV = Path(os.environ.get("FRAUD_DE_REFERENCE_CSV", _DEFAULT_DE_CSV))
FEATURE_IMPORTANCE_CSV = os.environ.get(
    "FRAUD_FEATURE_IMPORTANCE_CSV",
    str(Path.home() / "Downloads" / "fraud_doc" / "3xgb_feature_importance_20260326.csv"),
)

MODEL_FILE = PKL_DIR / "xgb_model_20260206.pkl"
FEATURE_LIST_FILE = PKL_DIR / "xgb_feature_list_20260206.pkl"
DECILE_BINS_FILE = PKL_DIR / "xgb_decile_bins_20260206.pkl"

app = FastAPI(title="Fraud Prediction API")

# -----------------------------
# Load model artifacts
# -----------------------------
with open(MODEL_FILE, "rb") as f:
    model = pickle.load(f)

with open(FEATURE_LIST_FILE, "rb") as f:
    feature_list: list = pickle.load(f)

with open(DECILE_BINS_FILE, "rb") as f:
    decile_bins = pickle.load(f)

feature_importance: Optional[pd.DataFrame] = None
if Path(FEATURE_IMPORTANCE_CSV).is_file():
    feature_importance = pd.read_csv(FEATURE_IMPORTANCE_CSV)

# One training row → baseline DE feature dict (missing API fields fall back to these, then to 0 / "0").
de_template_row: Optional[Dict[str, Any]] = None
if DE_REFERENCE_CSV.is_file():
    _de_df = pd.read_csv(DE_REFERENCE_CSV, nrows=1)
    _drop = [c for c in _de_df.columns if c == "target" or str(c).startswith("Unnamed")]
    _de_df = _de_df.drop(columns=_drop, errors="ignore")
    _raw = _de_df.iloc[0].to_dict()
    de_template_row = {k: _raw[k] for k in feature_list if k in _raw}
    print(
        f"DE reference CSV: {DE_REFERENCE_CSV} "
        f"({len(de_template_row)} of {len(feature_list)} model features matched)."
    )
else:
    print(f"DE reference CSV not found at {DE_REFERENCE_CSV}; using numeric defaults only.")

print(f"Model loaded from {PKL_DIR}; {len(feature_list)} features.")


def _features_from_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    inner = payload.get("features")
    if isinstance(inner, dict):
        return dict(inner)
    return dict(payload)


def _build_feature_row(payload: Dict[str, Any]) -> Dict[str, Any]:
    """Merge DE JSON over training template, then defaults, keeping only model features."""
    incoming = _features_from_payload(payload)
    row: Dict[str, Any] = {}
    if de_template_row:
        for name in feature_list:
            if name in de_template_row:
                row[name] = de_template_row[name]
    for name in feature_list:
        if name not in row:
            row[name] = "0" if name in ("ipaddress", "deviceid") else 0.0
    for k, v in incoming.items():
        if k in feature_list and k != "target":
            row[k] = v
    return row


def _fraud_probability(df: pd.DataFrame) -> float:
    booster = model.get_booster()
    dmatrix = xgb.DMatrix(df, feature_names=list(df.columns))
    return float(booster.predict(dmatrix)[0])


def _risk_level(score: float) -> str:
    """Band predicted fraud probability (absolute score, not OOT decile bucket labels)."""
    if score >= 0.3:
        return "HIGH"
    if score >= 0.1:
        return "MEDIUM"
    return "LOW"


def _score_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    row_full = _build_feature_row(payload)
    df = pd.DataFrame([row_full])[feature_list]
    df = df.fillna(0)
    for col in ("ipaddress", "deviceid"):
        if col in df.columns:
            df[col] = df[col].astype(str)

    score = _fraud_probability(df)
    labels = list(range(10, 0, -1))
    decile = pd.cut(
        [score],
        bins=decile_bins,
        labels=labels,
        include_lowest=True,
    )[0]
    decile_int = int(decile) if decile is not None and pd.notna(decile) else None

    out: Dict[str, Any] = {
        "score": score,
        "decile": decile_int,
        "riskLevel": _risk_level(score),
    }
    if feature_importance is not None:
        out["top_features"] = feature_importance.head(10).to_dict(orient="records")
    return out


@app.post("/predict")
def predict(payload: Dict[str, Any] = Body(...)):
    """Primary endpoint used by the Java ML client; body = DE JSON feature map."""
    try:
        return _score_payload(payload)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e)) from e


@app.post("/score")
def score(payload: Dict[str, Any] = Body(...)):
    """Alias for clients expecting /score."""
    try:
        return _score_payload(payload)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
