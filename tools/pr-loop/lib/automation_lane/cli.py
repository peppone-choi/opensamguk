"""Explicit snapshot in, advisory JSON out. Never access default host registry."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from work_units.schema import read_record
from . import tool_version
from .contracts import timestamp
from .planner import report
from .resources import observe


def main(argv=None):
    parser = argparse.ArgumentParser(description="Read-only automation preparation and capacity report")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--now", help="Explicit fixture clock (ISO time with timezone)")
    parser.add_argument("--observe-resources", action="store_true")
    parser.add_argument("--disk-root", type=Path, default=Path.cwd())
    parser.add_argument("--artifact-root", type=Path)
    args = parser.parse_args(argv)
    try:
        snapshot = read_record(args.input)
        if snapshot.get("schema") != "automation-snapshot/1":
            raise ValueError("AUTOMATION_SNAPSHOT_SCHEMA")
        now = timestamp(args.now) if args.now else datetime.now(timezone.utc)
        if args.observe_resources:
            measured = observe(args.disk_root)
            measured["heavyTokens"] = snapshot.get("resources", {}).get("heavyTokens", {})
            snapshot["resources"] = measured
            if args.now is None:
                now = datetime.now(timezone.utc)
        result = report(snapshot, now, artifact_root=args.artifact_root)
        result["observerVersion"] = tool_version()
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print(json.dumps({"mode": "OBSERVE", "status": "UNKNOWN", "reason": str(exc),
                          "executionAllowed": False}, ensure_ascii=False))
        return 2
