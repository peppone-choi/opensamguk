"""Recompute missing work without promoting the product delivery catalog."""
from .schema import STAGES


def recompute(tree):
    catalog = tree.json("data/commands/input-catalog.json")
    if not catalog or catalog.get("schemaVersion") != 5:
        raise ValueError("CATALOG_ADAPTER_SCHEMA")
    baseline = tree.json("data/commands/input-delivery-baseline-v3.json")
    rows = []
    def add(identity, kind, detail):
        rows.append({"gapId": identity + ":" + kind, "inputId": identity, "kind": kind,
                     "detail": detail, "trackedBy": [], "status": "PENDING_ISSUE_CREATION"})
    for row in catalog["inputs"]:
        if row["deliveryState"] == "PLANNED":
            add(row["inputId"], "PLANNED", "No delivered handler")
        elif STAGES.index(row["deliveryState"]) < STAGES.index("UI_READY"):
            add(row["inputId"], "HANDLER_NO_UI", row["deliveryState"])
    by_id = {r["inputId"]: r for r in catalog["inputs"]}
    for row in baseline["entries"]:
        stage = row["deliveryState"]
        current = by_id.get(row["inputId"], {})
        if STAGES.index(stage) > 0 and any(not current.get("evidence", {}).get(s)
                                         for s in STAGES[1:STAGES.index(stage) + 1]):
            add(row["inputId"], "DEBT_MISSING_EVIDENCE", stage)
    return {"schemaVersion": 1, "entries": sorted(rows, key=lambda row: row["gapId"])}


def compare(base_tree, head_tree):
    expected = recompute(head_tree)
    current = head_tree.json("data/commands/command-work-gaps-v1.json", {"entries": []})
    old = base_tree.json("data/commands/command-work-gaps-v1.json", {"entries": []})
    now = {r["gapId"]: r for r in current["entries"]}
    before = {r["gapId"]: r for r in old["entries"]}
    reasons = []
    for gap in expected["entries"]:
        entry = now.get(gap["gapId"])
        if entry is None:
            reasons.append("GAP_VANISHED:" + gap["gapId"])
        elif entry.get("inputId") != gap["inputId"] or entry.get("kind") != gap["kind"]:
            reasons.append("GAP_IDENTITY:" + gap["gapId"])
        elif not set(before.get(gap["gapId"], {}).get("trackedBy", [])) <= set(entry.get("trackedBy", [])):
            reasons.append("GAP_UNTRACKED:" + gap["gapId"])
        elif not entry.get("trackedBy") and entry.get("status") != "PENDING_ISSUE_CREATION":
            reasons.append("GAP_PENDING_ISSUE:" + gap["gapId"])
    prior_catalog = {r["inputId"]: r for r in base_tree.json("data/commands/input-catalog.json")["inputs"]}
    current_catalog = {r["inputId"]: r for r in head_tree.json("data/commands/input-catalog.json")["inputs"]}
    for identity, row in prior_catalog.items():
        new = current_catalog.get(identity)
        if new is None:
            reasons.append("CATALOG_INPUT_DELETED:" + identity)
        elif STAGES.index(new["deliveryState"]) < STAGES.index(row["deliveryState"]):
            reasons.append("REGRESSION:" + identity)
    for entry in current["entries"]:
        for ref in entry.get("trackedBy", []):
            if not isinstance(ref, str) or "#" not in ref:
                reasons.append("GAP_TRACKER_REFERENCE:" + entry["gapId"])
    return expected, reasons
