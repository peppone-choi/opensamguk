"""Bounded resource observations and advisory admission; no process control."""
import math
import shutil
import time
from datetime import datetime, timezone
from pathlib import Path
from .contracts import timestamp


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0


def admission(snapshot, demand, now, reserved=None):
    reserved = reserved or {"cpuPercent": 0, "memoryBytes": 0, "diskBytes": 0, "heavy": 0}
    try:
        age = (now - timestamp(snapshot["observedAt"])).total_seconds()
        if age < 0 or age > 300:
            return "RESOURCE_OBSERVATION_STALE"
        required = ("cpuPercent", "memoryUsed", "memoryTotal", "diskUsed", "diskTotal")
        if any(not number(snapshot.get(k)) for k in required) or not snapshot["memoryTotal"] or not snapshot["diskTotal"]:
            return "RESOURCE_OBSERVATION_UNKNOWN"
        if (any(not number(demand.get(k)) for k in ("cpuPercent", "memoryBytes", "diskBytes")) or
                not isinstance(demand.get("heavy"), bool)):
            return "RESOURCE_ESTIMATE_UNKNOWN"
        estimates = {
            "CPU": snapshot["cpuPercent"] + reserved["cpuPercent"] + demand["cpuPercent"],
            "MEMORY": 100 * (snapshot["memoryUsed"] + reserved["memoryBytes"] + demand["memoryBytes"]) / snapshot["memoryTotal"],
            "DISK": 100 * (snapshot["diskUsed"] + reserved["diskBytes"] + demand["diskBytes"]) / snapshot["diskTotal"]}
        for name, percent in estimates.items():
            if percent >= 90:
                return name + "_90_PERCENT_LIMIT"
        if demand["heavy"]:
            tokens = snapshot.get("heavyTokens", {})
            if not isinstance(tokens, dict):
                return "HEAVY_TOKEN_OBSERVATION_UNKNOWN"
            source = tokens.get("source")
            if (not isinstance(source, dict) or source.get("kind") != "external-token-manager" or
                    not isinstance(source.get("id"), str) or not source["id"].strip()):
                return "HEAVY_TOKEN_OBSERVATION_UNKNOWN"
            try:
                token_age = (now - timestamp(tokens.get("observedAt"))).total_seconds()
            except (ValueError, TypeError):
                return "HEAVY_TOKEN_OBSERVATION_UNKNOWN"
            if token_age < 0 or token_age > 300:
                return "HEAVY_TOKEN_OBSERVATION_STALE"
            if (not isinstance(tokens.get("capacity"), int) or isinstance(tokens["capacity"], bool) or
                    not isinstance(tokens.get("inUse"), int) or isinstance(tokens["inUse"], bool) or
                    tokens["capacity"] < 0 or tokens["inUse"] < 0 or
                    tokens["inUse"] + reserved["heavy"] >= tokens["capacity"]):
                return "HEAVY_TOKEN_UNAVAILABLE"
        return None
    except (KeyError, ValueError, TypeError, AttributeError):
        return "RESOURCE_OBSERVATION_UNKNOWN"


def observe(disk_root, *, proc=Path("/proc"), cgroup=Path("/sys/fs/cgroup")):
    """Linux host/cgroup readings are optional; unavailable signals stay UNKNOWN."""
    result = {"observedAt": datetime.now(timezone.utc).isoformat(), "cpuPercent": None,
              "memoryUsed": None, "memoryTotal": None, "diskUsed": None, "diskTotal": None,
              "source": {}, "warnings": []}
    try:
        def cpu():
            row = [int(v) for v in (proc / "stat").read_text().splitlines()[0].split()[1:]]
            return sum(row[:8]), row[3] + row[4]
        before = cpu()
        # When available, cgroup usage/quota describes this environment's budget.
        quota, period = (cgroup / "cpu.max").read_text().split()
        use_before = int(dict(line.split() for line in (cgroup / "cpu.stat").read_text().splitlines())["usage_usec"])
        start = time.monotonic()
        time.sleep(0.1)
        after = cpu()
        use_after = int(dict(line.split() for line in (cgroup / "cpu.stat").read_text().splitlines())["usage_usec"])
        elapsed = time.monotonic() - start
        if quota != "max":
            result["cpuPercent"] = 100 * (use_after - use_before) / (elapsed * 1e6 * int(quota) / int(period))
            result["source"]["cpu"] = "cgroup-v2"
        elif after[0] > before[0]:
            result["cpuPercent"] = 100 * (1 - (after[1] - before[1]) / (after[0] - before[0]))
            result["source"]["cpu"] = "procfs-host"
    except (OSError, ValueError, KeyError, IndexError, ZeroDivisionError):
        result["warnings"].append("CPU_UNKNOWN")
    try:
        mem = dict(line.split(":", 1) for line in (proc / "meminfo").read_text().splitlines())
        total, available = (int(mem[k].split()[0]) * 1024 for k in ("MemTotal", "MemAvailable"))
        result.update(memoryTotal=total, memoryUsed=total - available)
        result["source"]["memory"] = "procfs-host"
        maximum = (cgroup / "memory.max").read_text().strip()
        if maximum != "max" and int(maximum) < total:
            result.update(memoryTotal=int(maximum), memoryUsed=int((cgroup / "memory.current").read_text()))
            result["source"]["memory"] = "cgroup-v2"
    except (OSError, ValueError, KeyError):
        result.update(memoryTotal=None, memoryUsed=None)
        result["warnings"].append("MEMORY_OR_CGROUP_UNKNOWN")
    try:
        disk = shutil.disk_usage(disk_root)
        result.update(diskTotal=disk.total, diskUsed=disk.used)
        result["source"]["disk"] = "statvfs"
    except OSError:
        result["warnings"].append("DISK_UNKNOWN")
    return result
