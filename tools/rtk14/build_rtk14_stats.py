#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RTK14(삼국지14) 무장정보 xlsx → 오픈삼국 local scenario JSON 빌더.

**DIVERGENCE 도구** — devsam/core 패러티 대상 아님. 산출 데이터(정치/매력)는 코에이 IP이므로
출력 JSON은 git-ignored 배포 디렉터리에 원본 파일명 그대로 쓴다. 이 스크립트(알고리즘)만 커밋한다.

동명이인 처리: devsam 시나리오는 동명을 접미숫자로 구분(마충1/마충2)하고 RTK14도 동명을 다중행으로
보유한다. 단순 이름매칭은 동명이인을 붕괴시킨다. 그래서 devsam이 유지하는 통/무/지(leadership/strength/
intel) 지문으로 RTK14 다중행에 **1:1 greedy-최적배정**한 뒤 **exact devsam 이름**을 키로 굽는다.
런타임은 별도 룩업 없이 local scenario tuple의 politics/charm 필드를 직접 소비한다.

사용:
  python3 tools/rtk14/build_rtk14_stats.py \
      --xlsx "/path/to/삼국지14 무장정보.xlsx" \
      --scenario-dir infra/src/main/resources/scenario \
      --out-dir infra/src/main/resources/rtk14-scenarios.local

배포 secret 준비:
  python3 tools/rtk14/build_rtk14_stats.py \
      --xlsx "/path/to/삼국지14 무장정보.xlsx" \
      --dump-rtk-source-json /tmp/rtk14-source.json
  gzip -c /tmp/rtk14-source.json | base64

배포 workflow:
  RTK14_STATS_JSON_B64에는 위 source JSON의 gzip+base64만 넣고, checkout된 scenario_*.json과
  이 스크립트를 조합해 git-ignored scenario_*.json 완성본을 생성한다.

미매칭(RTK14에 base-name 없음)은 기본 (50,50)으로 주입한다.
"""
import argparse, json, re, zipfile
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
DEFAULT = 50
base_name = lambda n: re.sub(r"\d+$", "", str(n))

ALIASES = {
    "김배삼결": "금환삼결",
    "구역거": "구력거",
    "금선": "김선",
    "답둔": "답돈",
    "동다나": "동도나",
    "번주": "번조",
    "보질": "보즐",
    "비위": "비의",
    "향랑": "상랑",
    "향총": "상총",
    "교수": "교유",
    "금의": "김의",
    "양혼": "양흔",
    "휴원진": "수원진",
    "주태유평": "주태",
    "정봉승연": "정봉",
    "하후령녀": "하후영녀",
    "보련사": "보연사",
    "포삼낭": "포삼랑",
    "마운": "마운록",
    "고담륭": "고당륭",
    "서진순욱": "순욱",
    "유대연주": "유대",
    "여영기": "여령기",
    "이이자": "이이",
    "유위대": "유대",
}


def _int(x):
    try:
        return int(x)
    except (TypeError, ValueError):
        return 0


def read_rtk14(xlsx_path):
    """RTK14 xlsx → {무장명: [ {L,S,I,pol,cha}, ... ]} (동명이인 다중행 보존)."""
    z = zipfile.ZipFile(xlsx_path)
    ss = []
    r = ET.fromstring(z.read("xl/sharedStrings.xml"))
    for si in r.findall(NS + "si"):
        ss.append("".join(t.text or "" for t in si.iter(NS + "t")))
    root = ET.fromstring(z.read("xl/worksheets/sheet1.xml"))

    def cell(c):
        v = c.find(NS + "v")
        if v is None:
            return None
        return ss[int(v.text)] if c.get("t") == "s" else v.text

    rtk = defaultdict(list)
    for row in root.findall(".//" + NS + "row")[1:]:  # skip header
        c = [cell(x) for x in row.findall(NS + "c")]
        # 열: 0무장번호 1무장 ... 8통솔 9무력 10지력 11정치 12매력
        if len(c) >= 13 and c[1]:
            rtk[c[1]].append({"born": _int(c[3]), "dead": _int(c[5]),
                              "L": _int(c[8]), "S": _int(c[9]), "I": _int(c[10]),
                              "pol": _int(c[11]), "cha": _int(c[12])})
    return rtk


def _source_rows_to_rtk(rows):
    rtk = defaultdict(list)
    for row in rows:
        name = row.get("name")
        if not name:
            continue
        rtk[str(name)].append({
            "born": _int(row.get("born")),
            "dead": _int(row.get("dead")),
            "L": _int(row.get("L")),
            "S": _int(row.get("S")),
            "I": _int(row.get("I")),
            "pol": _int(row.get("pol")),
            "cha": _int(row.get("cha")),
        })
    return rtk


def rtk_to_source_rows(rtk):
    rows = []
    for name in sorted(rtk):
        for row in rtk[name]:
            rows.append({
                "name": name,
                "born": row["born"],
                "dead": row["dead"],
                "L": row["L"],
                "S": row["S"],
                "I": row["I"],
                "pol": row["pol"],
                "cha": row["cha"],
            })
    return rows


def read_rtk14_source_json(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    rows = data.get("rows", data) if isinstance(data, dict) else data
    if not isinstance(rows, list):
        raise ValueError("RTK source JSON must be a list or an object with rows[]")
    return _source_rows_to_rtk(rows)


def read_scenario(scenario_path):
    with open(scenario_path, encoding="utf-8") as f:
        return json.load(f)


def read_devsam(scenario):
    """scenario_*.json general[]+general_ex[] → [ {name,L,S,I} ] (positional idx 1/5/6/7)."""
    out = []
    for arr in (scenario.get("general", []) + scenario.get("general_ex", [])):
        if isinstance(arr, list) and len(arr) >= 8:
            out.append({"name": str(arr[1]), "L": arr[5], "S": arr[6], "I": arr[7],
                        "born": arr[9] if len(arr) > 9 else 0,
                        "dead": arr[10] if len(arr) > 10 else 0})
    return out


def _alias_candidates(base, members, rtk):
    exact = [(base, c) for c in rtk.get(base, [])]
    alias = ALIASES.get(base)
    if alias is None and " " in base:
        primary_name = base.split(" ", 1)[0]
        if primary_name in rtk:
            alias = primary_name
    if alias is None and "(" in base:
        primary_name = base.split("(", 1)[0]
        if primary_name in rtk:
            alias = primary_name
    if not alias:
        return exact, []

    accepted = [(alias, c) for c in rtk.get(alias, [])]
    return exact + accepted, [alias] if accepted else []


def assign(devsam, rtk):
    dist = lambda c, g: abs(c["L"] - g["L"]) + abs(c["S"] - g["S"]) + abs(c["I"] - g["I"])
    grp = defaultdict(list)
    for g in devsam:
        grp[base_name(g["name"])].append(g)

    out = {}
    stats = {"distinct": 0, "alias": 0, "collapsed": 0, "fallback": 0}
    report = {"aliases": [], "collapsed": [], "fallback": []}
    for bn, members in grp.items():
        cands, aliases = _alias_candidates(bn, members, rtk)
        if not cands:
            for g in members:
                out[g["name"]] = {"politics": DEFAULT, "charm": DEFAULT}
                stats["fallback"] += 1
                report["fallback"].append(g["name"])
            continue
        # greedy 1:1: 모든 (member,cand) 쌍을 거리순 → 양쪽 미사용일 때만 배정
        pairs = sorted((dist(c, g), abs((c.get("born") or 0) - (g.get("born") or 0)),
                        abs((c.get("dead") or 0) - (g.get("dead") or 0)), g["name"], gi, ci)
                       for gi, g in enumerate(members) for ci, (_, c) in enumerate(cands))
        asg, used = {}, set()
        for _, _, _, _, gi, ci in pairs:
            if gi in asg or ci in used:
                continue
            asg[gi] = ci; used.add(ci)
        for gi, g in enumerate(members):
            if gi not in asg:  # k>m: 남은 member는 nearest 재사용(불가피 붕괴)
                asg[gi] = min(range(len(cands)), key=lambda ci: dist(cands[ci][1], g))
                stats["collapsed"] += 1
                report["collapsed"].append(g["name"])
            else:
                stats["distinct"] += 1
            source, c = cands[asg[gi]]
            if source != bn:
                stats["alias"] += 1
                report["aliases"].append(f"{g['name']}->{source}")
            out[g["name"]] = {"politics": c["pol"], "charm": c["cha"]}
    return out, stats, report


def inject_scenario(scenario, assigned):
    for section in ("general", "general_ex"):
        for arr in scenario.get(section, []):
            if not isinstance(arr, list) or len(arr) < 2:
                continue
            stats = assigned.get(str(arr[1]), {"politics": DEFAULT, "charm": DEFAULT})
            while len(arr) < 16:
                arr.append(None)
            arr[14] = stats["politics"]
            arr[15] = stats["charm"]
    return scenario


def build_one(path, out_dir, rtk):
    scenario = read_scenario(path)
    devsam = read_devsam(scenario)
    out, stats, report = assign(devsam, rtk)
    injected = inject_scenario(scenario, out)
    out_path = Path(out_dir) / Path(path).name
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(injected, f, ensure_ascii=False, separators=(",", ":"))
    return str(out_path), len(devsam), len(out), stats, report


def main():
    ap = argparse.ArgumentParser()
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--xlsx")
    src.add_argument("--rtk-source-json")
    ap.add_argument("--dump-rtk-source-json")
    ap.add_argument("--scenario-dir", default="infra/src/main/resources/scenario")
    ap.add_argument("--out-dir", default="infra/src/main/resources/rtk14-scenarios.local")
    a = ap.parse_args()

    rtk = read_rtk14(a.xlsx) if a.xlsx else read_rtk14_source_json(a.rtk_source_json)
    if a.dump_rtk_source_json:
        out = Path(a.dump_rtk_source_json)
        out.parent.mkdir(parents=True, exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump({"rows": rtk_to_source_rows(rtk)}, f, ensure_ascii=False, separators=(",", ":"))
        print(f"wrote RTK source JSON rows={sum(len(v) for v in rtk.values())} -> {out}")
        return

    totals = {"files": 0, "devsam": 0, "entries": 0, "distinct": 0, "alias": 0, "collapsed": 0, "fallback": 0}
    for path in sorted(Path(a.scenario_dir).glob("scenario_*.json")):
        out_path, devsam_count, entry_count, stats, report = build_one(path, a.out_dir, rtk)
        totals["files"] += 1
        totals["devsam"] += devsam_count
        totals["entries"] += entry_count
        for k in ("distinct", "alias", "collapsed", "fallback"):
            totals[k] += stats[k]
        print(f"{path.name}: devsam={devsam_count} -> entries={entry_count} | "
              f"distinct={stats['distinct']} alias={stats['alias']} "
              f"collapsed={stats['collapsed']} fallback={stats['fallback']} -> {out_path}")
        if report["aliases"]:
            print("aliases=" + ",".join(report["aliases"]))
        if report["collapsed"]:
            print("collapsed=" + ",".join(report["collapsed"]))
        if report["fallback"]:
            print("fallback=" + ",".join(report["fallback"]))
    print(f"total files={totals['files']} devsam={totals['devsam']} entries={totals['entries']} | "
          f"distinct={totals['distinct']} alias={totals['alias']} "
          f"collapsed={totals['collapsed']} fallback={totals['fallback']}")
    print(f"wrote {a.out_dir} (git-ignored — 코에이 IP)")


if __name__ == "__main__":
    main()
