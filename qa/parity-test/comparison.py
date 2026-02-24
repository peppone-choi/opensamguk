"""
Comparison utilities for parity testing.

Handles the known differences between legacy (PHP/MariaDB) and new (Kotlin/PostgreSQL):
  - Field name mapping (Korean → English)
  - Structural comparison (ignore exact numeric values from RNG)
  - Type coercion (PHP returns strings for numbers, Kotlin returns typed values)
"""
from __future__ import annotations

import re
from typing import Any
from deepdiff import DeepDiff


# ── Korean ↔ English field mapping ───────────────────────────────────────────
FIELD_MAP_KR_EN = {
    # General fields
    "장수번호": "generalId",
    "이름": "name",
    "소속국": "nationId",
    "소속도시": "cityId",
    "통솔": "leadership",
    "무력": "strength",
    "지력": "intelligence",
    "매력": "charm",
    "병사수": "crew",
    "훈련": "training",
    "사기": "morale",
    "금": "gold",
    "쌀": "rice",
    "경험": "experience",
    "공헌": "dedication",
    "명성": "reputation",
    "계급": "rank",
    "레벨": "level",
    "국가명": "nationName",
    "도시명": "cityName",
    # Nation fields
    "국번": "nationId",
    "수도": "capital",
    "국력": "power",
    "기술": "tech",
    # City fields
    "도시번호": "cityId",
    "인구": "population",
    "민심": "trust",
    "농업": "agriculture",
    "상업": "commerce",
    "치안": "security",
    "수비": "defence",
    "성벽": "wall",
    # Turn/time
    "턴": "turn",
    "년": "year",
    "월": "month",
    # Result
    "결과": "result",
    "이유": "reason",
}

FIELD_MAP_EN_KR = {v: k for k, v in FIELD_MAP_KR_EN.items()}

# Fields whose exact numeric values should NOT be compared (RNG-dependent)
RNG_DEPENDENT_FIELDS = {
    "crew", "병사수",
    "training", "훈련",
    "morale", "사기",
    "gold", "금",
    "rice", "쌀",
    "experience", "경험",
    "dedication", "공헌",
    "population", "인구",
    "trust", "민심",
}


def normalize_keys(obj: Any, mapping: dict[str, str] | None = None) -> Any:
    """Recursively rename keys using a mapping dict."""
    if mapping is None:
        mapping = FIELD_MAP_KR_EN
    if isinstance(obj, dict):
        return {mapping.get(k, k): normalize_keys(v, mapping) for k, v in obj.items()}
    if isinstance(obj, list):
        return [normalize_keys(item, mapping) for item in obj]
    return obj


def coerce_types(obj: Any) -> Any:
    """PHP returns many numbers as strings; coerce to match Kotlin types."""
    if isinstance(obj, str):
        if re.fullmatch(r"-?\d+", obj):
            return int(obj)
        if re.fullmatch(r"-?\d+\.\d+", obj):
            return float(obj)
        if obj.lower() in ("true", "false"):
            return obj.lower() == "true"
        return obj
    if isinstance(obj, dict):
        return {k: coerce_types(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [coerce_types(item) for item in obj]
    return obj


def structural_shape(obj: Any) -> Any:
    """Replace leaf values with their type name — for structural comparison."""
    if isinstance(obj, dict):
        return {k: structural_shape(v) for k, v in sorted(obj.items())}
    if isinstance(obj, list):
        if not obj:
            return ["<empty_list>"]
        return [structural_shape(obj[0]), f"...({len(obj)} items)"]
    return type(obj).__name__


def compare_responses(
    legacy_data: Any,
    new_data: Any,
    *,
    structural_only: bool = False,
    ignore_fields: set[str] | None = None,
    normalize: bool = True,
) -> dict:
    """
    Compare legacy and new API responses.

    Returns dict with:
      - equal: bool
      - diff: DeepDiff result (if not equal)
      - structural_match: bool  (shape comparison ignoring values)
    """
    if normalize:
        legacy_data = coerce_types(normalize_keys(legacy_data))
        new_data = coerce_types(new_data)

    exclude_paths = set()
    if ignore_fields:
        # Build regex patterns for fields to ignore at any depth
        exclude_regex = [re.compile(rf".*\['{f}'\]") for f in ignore_fields]
    else:
        exclude_regex = []

    # Structural comparison
    legacy_shape = structural_shape(legacy_data)
    new_shape = structural_shape(new_data)
    structural_match = legacy_shape == new_shape

    if structural_only:
        return {
            "equal": structural_match,
            "structural_match": structural_match,
            "legacy_shape": legacy_shape,
            "new_shape": new_shape,
            "diff": None,
        }

    # Value comparison
    diff = DeepDiff(
        legacy_data,
        new_data,
        ignore_order=True,
        exclude_regex_paths=exclude_regex or None,
        significant_digits=2,
    )

    return {
        "equal": not bool(diff),
        "structural_match": structural_match,
        "diff": diff if diff else None,
    }


def format_report(results: list[dict]) -> str:
    """Format a list of test results into a human-readable report."""
    lines = ["=" * 72, "PARITY TEST REPORT", "=" * 72, ""]
    passed = sum(1 for r in results if r["passed"])
    total = len(results)
    lines.append(f"Results: {passed}/{total} passed\n")

    for r in results:
        status = "✅ PASS" if r["passed"] else "❌ FAIL"
        lines.append(f"{status}  {r['test_name']}")
        if not r["passed"] and r.get("details"):
            for detail_line in str(r["details"]).split("\n")[:10]:
                lines.append(f"         {detail_line}")
        lines.append("")

    lines.append("=" * 72)
    return "\n".join(lines)
