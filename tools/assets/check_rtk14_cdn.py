#!/usr/bin/env python3
"""RTK14 초상 3종 CDN 검증 — 1,000 안정 장수 ID × original/portrait/icon 을 HEAD 로 확인한다.

OPENSAM-100 완료 조건(「1,000 ID CDN HEAD/GET 200」)의 실측 도구. 결과는 숨기지 않는다:
실패 ID 는 전부 보고서에 적고, 종료 코드는 실패가 하나라도 있으면 1 이다.

사용:
  python3 tools/assets/check_rtk14_cdn.py --out reports/rtk14-cdn-YYYY-MM-DD.md
  python3 tools/assets/check_rtk14_cdn.py --base https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images@main
옵션 --ids 10001-10050 으로 범위를 좁힐 수 있다(스모크).
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import datetime as dt
import sys
import urllib.error
import urllib.request

DEFAULT_BASE = "https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images"
VARIANTS = {
    "original": "portraits/rtk14/serving/original/{id}.jpg",
    "portrait": "portraits/rtk14/serving/portrait/{id}.png",
    "icon": "portraits/rtk14/serving/icon/{id}.png",
}


def parse_ids(spec: str) -> list[int]:
    lo, _, hi = spec.partition("-")
    lo_i = int(lo)
    hi_i = int(hi) if hi else lo_i
    if not (10001 <= lo_i <= hi_i <= 11000):
        raise SystemExit("ids must lie in 10001-11000")
    return list(range(lo_i, hi_i + 1))


def head(url: str, timeout: float) -> tuple[int, int | None]:
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "opensamguk-cdn-check/1"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310 (fixed CDN host)
            length = resp.headers.get("Content-Length")
            return resp.status, int(length) if length and length.isdigit() else None
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception:  # network / timeout → 0
        return 0, None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--ids", default="10001-11000")
    ap.add_argument("--out", default=None, help="markdown report path")
    ap.add_argument("--workers", type=int, default=16)
    ap.add_argument("--timeout", type=float, default=15.0)
    args = ap.parse_args()

    ids = parse_ids(args.ids)
    base = args.base.rstrip("/")
    jobs = [(i, v, f"{base}/{VARIANTS[v].format(id=i)}") for i in ids for v in VARIANTS]
    results: dict[tuple[int, str], tuple[int, int | None]] = {}
    with cf.ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = {ex.submit(head, url, args.timeout): (i, v) for i, v, url in jobs}
        for fut in cf.as_completed(futs):
            results[futs[fut]] = fut.result()

    failures = sorted((k, r) for k, r in results.items() if r[0] != 200)
    lines = [
        f"# RTK14 초상 CDN 검증 — {dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds')}",
        "",
        f"- base: `{base}`",
        f"- ids: {ids[0]}..{ids[-1]} ({len(ids)}) × variants {', '.join(VARIANTS)} = {len(jobs)} 요청",
        f"- 200: {len(jobs) - len(failures)} · 실패: {len(failures)}",
        "",
    ]
    for v in VARIANTS:
        ok = sum(1 for (i, vv), r in results.items() if vv == v and r[0] == 200)
        lines.append(f"- {v}: {ok}/{len(ids)}")
    if failures:
        lines += ["", "## 실패 (숨기지 않는다)", "", "| id | variant | status |", "|---|---|---|"]
        lines += [f"| {i} | {v} | {r[0] or 'network'} |" for (i, v), r in failures]
    report = "\n".join(lines) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(report)
    sys.stdout.write(report)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
