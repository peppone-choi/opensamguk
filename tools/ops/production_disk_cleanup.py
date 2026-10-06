#!/usr/bin/env python3
"""오래된 dangling image·build cache만 정리한다. 기본은 읽기 전용 plan이다."""

import argparse
import fcntl
import subprocess
from pathlib import Path

CONFIRM = "CLEAN BUILD CACHE AND DANGLING IMAGES"


def run(command: list[str]) -> None:
    result = subprocess.run(command, text=True, capture_output=True, timeout=180)
    if result.returncode != 0:
        raise RuntimeError("disk cleanup command failed")
    print(result.stdout, end="")


def cleanup(minimum_age_hours: int, apply: bool, lock_path: Path = Path("/tmp/opensamguk-production.lock")) -> int:
    if minimum_age_hours < 1:
        raise ValueError("positive minimum age required")
    with lock_path.open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("disk cleanup deferred: production operation owns the lock")
            return 0
        run(["df", "-Pk", "/"])
        run(["docker", "system", "df"])
        if not apply:
            print(f"plan only: dangling images and unused build cache older than {minimum_age_hours}h")
            return 0
        age = f"until={minimum_age_hours}h"
        run(["docker", "image", "prune", "--force", "--filter", "dangling=true", "--filter", age])
        run(["docker", "builder", "prune", "--force", "--filter", age])
        run(["df", "-Pk", "/"])
        run(["docker", "system", "df"])
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--minimum-age-hours", type=int, required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm", default="")
    args = parser.parse_args()
    if args.apply and args.confirm != CONFIRM:
        parser.error("apply requires the exact cleanup confirmation")
    try:
        return cleanup(args.minimum_age_hours, args.apply)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        print("::error::disk cleanup failed; inspect disk and Docker state")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
