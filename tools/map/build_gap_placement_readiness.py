#!/usr/bin/env python3
"""확정 실결손 縣이 **지금 배치될 수 있는가**를 좌표 확보 상태로 가른다.

여섯 심사 원장이 실결손 110 건을 확정했지만, 그중 어느 것이 지도에 놓일 수 있는지는 별개 질문이다
— 사료가 「220 년에 있었다」 를 말해도 좌표가 없으면 놓을 수 없다. 이 도구가 그 둘을 붙인다.

좌표 출처 둘
  - `namu-place-locations-v1.json` — 나무위키 수확분. (canonicalGroup, sourceName) 키에 lon/lat 과
    **현대 지명 서술**을 함께 싣는다.
  - `gap-snapshot-reconciliation-v1.json` 의 tgazCoordinates — TGAZ 이름 조인 결과.
    **이름만으로 붙은 것이라 그대로 믿으면 안 된다.**

그래서 두 검사를 같이 돌린다
  1. 두 출처가 다 있으면 상호 거리를 잰다. 다만 **독립 검증이 아니다** — 두 원장이 겹치는 縣
     21 건 중 16 건이 소수점 5 자리까지 완전 일치한다. 독립 출처에서 나올 수 없는 수치이고,
     둘 다 CHGIS 를 거슬러 올라간다는 뜻이다. 독립적인 것은 나무위키 쪽의 현대 지명 서술뿐이다.
     그러니 일치는 근거가 못 되고, **어긋나는 값이 오히려 봐야 할 것이다** — 中陽 86.9 km,
     圜陽 약 60 km, 武州 약 7 km.
  2. TGAZ 단독이면 지도의 郡 중심(속현 seat 평균)과의 거리로 同名異地를 걸러낸다. 200 km 초과는
     판정이 아니라 **심사 트리거**다 — 廣漢 白水는 204 km 인데 진짜다(郡이 북으로 길다).

    python3 tools/map/build_gap_placement_readiness.py [--check]
"""
import argparse
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit_county_coverage import make_normalizer  # noqa: E402

LEDGERS = (
    ("survived-county-adjudications-v1", "adjudications"),
    ("abolished-verdict-corrections-v1", "corrections"),
    ("jinshu-token-adjudications-v1", "adjudications"),
    ("dushi-disposition-adjudications-v1", "adjudications"),
    ("songshu-provenance-adjudications-v1", "adjudications"),
    ("weishu-affiliation-adjudications-v1", "adjudications"),
)
NAMU = ROOT / "data/curated/han/namu-place-locations-v1.json"
RECON = ROOT / "data/curated/han/gap-snapshot-reconciliation-v1.json"
TILES = ROOT / "data/map/han-tiles.json"
OUT = ROOT / "data/curated/han/gap-placement-readiness-v1.json"
SUSPECT_KM = 200
# 앞선 심사가 좌표를 기각한 건. 여기 있는 것은 거리와 무관하게 배치에 쓰지 않는다.
REJECTED = {
    ("隴西郡", "氐道"): "사천 蜀郡 湔氐道 계승 사슬이다 (survived-county-adjudications-v1)",
    ("漢陽郡", "阿阳"): "산동 좌표의 同名異地다 (dushi-disposition-adjudications-v1)",
    ("遼西郡", "陽樂"): "산동 좌표의 同名異地다. 진짜는 hvd_87636 (119.038, 39.775) 이므로 재핀하면 쓸 수 있다",
    ("北海國", "平昌"): "TGAZ 에 이 시대 北海 平昌 레코드가 없다 (abolished-verdict-corrections-v1)",
}


def km(lon1: float, lat1: float, lon2: float, lat2: float) -> float:
    return math.hypot((lon1 - lon2) * 111.0 * math.cos(math.radians((lat1 + lat2) / 2)),
                      (lat1 - lat2) * 111.0)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    normalize = make_normalizer()
    fold = make_normalizer(group=True)
    key = lambda jun, name: (fold(jun), normalize(name))  # noqa: E731

    namu = {key(r["canonicalGroup"], r["sourceName"]): r
            for r in json.loads(NAMU.read_text(encoding="utf-8"))["rows"]
            if isinstance(r.get("lon"), (int, float))}
    tgaz = {}
    for row in json.loads(RECON.read_text(encoding="utf-8"))["counties"]:
        coordinates = row.get("tgazCoordinates")
        if coordinates:
            tgaz[key(row["commandery"], row["name"])] = (float(coordinates["longitude"]),
                                                         float(coordinates["latitude"]))

    tiles = json.loads(TILES.read_text(encoding="utf-8"))
    jurisdictions = {j["id"]: j for j in tiles["jurisdictionRecords"]}
    cities = {c["id"]: c for c in tiles["cities"]}
    centroid = {}
    for commandery in tiles["commanderyRecords"]:
        points = []
        for jid in commandery["jurisdictionIds"]:
            jurisdiction = jurisdictions.get(jid)
            city = cities.get(jurisdiction.get("seatPlaceId") or "") if jurisdiction else None
            if city and city.get("lon") is not None:
                points.append((city["lon"], city["lat"]))
        if points:
            centroid[fold(commandery["nameCh"])] = (sum(p[0] for p in points) / len(points),
                                                    sum(p[1] for p in points) / len(points),
                                                    len(points))

    rows = []
    for ledger, field in LEDGERS:
        document = json.loads((ROOT / f"data/curated/han/{ledger}.json").read_text(encoding="utf-8"))
        for entry in document[field]:
            if not entry["verdict"].startswith("REAL_GAP"):
                continue
            jun, name = entry["commandery"], entry.get("canonName") or entry["name"]
            k = key(jun, name)
            row = {"commandery": jun, "name": name, "verdict": entry["verdict"], "ledger": ledger}
            if (jun, name) in REJECTED:
                row["readiness"] = "COORDINATE_REJECTED"
                row["note"] = REJECTED[(jun, name)]
                rows.append(row)
                continue
            n, t = namu.get(k), tgaz.get(k)
            if n and t:
                row["readiness"] = "READY_BOTH_SOURCES"
                row["lon"], row["lat"] = n["lon"], n["lat"]
                row["crossSourceKm"] = round(km(n["lon"], n["lat"], t[0], t[1]), 1)
                row["modernLocation"] = n.get("modernLocation")
            elif n:
                row["readiness"] = "READY_NAMU"
                row["lon"], row["lat"] = n["lon"], n["lat"]
                row["modernLocation"] = n.get("modernLocation")
            elif t:
                base = centroid.get(k[0]) or next(
                    (v for kk, v in centroid.items()
                     if kk.rstrip("郡国國尹") == k[0].rstrip("郡国國尹")), None)
                row["lon"], row["lat"] = t
                if base:
                    distance = km(t[0], t[1], base[0], base[1])
                    row["commanderyCentroidKm"] = round(distance)
                    row["commanderyCentroidSample"] = base[2]
                    row["readiness"] = "REVIEW_TGAZ_FAR" if distance > SUSPECT_KM else "READY_TGAZ"
                else:
                    row["readiness"] = "REVIEW_TGAZ_NO_CENTROID"
            else:
                row["readiness"] = "NO_COORDINATE"
            rows.append(row)

    counts: dict[str, int] = {}
    for row in rows:
        counts[row["readiness"]] = counts.get(row["readiness"], 0) + 1
    document = {
        "schemaVersion": 1,
        "ledgerId": "gap-placement-readiness-v1",
        "note": ("여섯 심사 원장이 확정한 실결손이 **지금 지도에 놓일 수 있는가** 를 좌표 확보 상태로 가른 것이다. "
                 "사료 축이 「220 년에 있었다」 를 말해도 좌표가 없으면 놓을 수 없다 — 두 질문은 따로다."),
        "method": {
            "coordinateSources": "namu-place-locations-v1 (현대 지명 서술 포함) 과 TGAZ 이름 조인 결과",
            "crossSourceCaveat": ("두 출처가 다 있는 건의 상호 거리는 **독립 검증이 아니다** — 실측 8 건 중 6 건이 0.0 km 로 "
                                  "완전 일치하는 것은 둘이 결국 CHGIS 를 공유한다는 뜻이다. 독립적인 부분은 나무위키 쪽의 현대 지명 서술뿐이고, "
                                  "다른 값을 내는 건(西河 中陽 86.9 km)이 오히려 봐야 할 것이다."),
            "homonymScreen": (f"TGAZ 단독은 지도 郡 중심과의 거리로 걸렀다. {SUSPECT_KM} km 초과는 판정이 아니라 심사 트리거다 — "
                              "廣漢 白水는 204 km 인데 진짜다(郡이 북으로 길고 같은 지점에 白水關·후대 白水郡이 겹친다). "
                              "郡 중심 표본 수(commanderyCentroidSample)가 작으면 거리 자체가 약하다."),
            "rejected": "앞선 심사가 좌표를 기각한 건은 거리와 무관하게 COORDINATE_REJECTED 로 뺀다.",
        },
        "totals": counts,
        "counties": rows,
    }
    payload = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != payload:
            print(f"STALE {OUT}")
            return 1
        print(f"OK {OUT}")
        return 0
    OUT.write_text(payload, encoding="utf-8")
    for name in sorted(counts):
        print(f"{counts[name]:4d}  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
