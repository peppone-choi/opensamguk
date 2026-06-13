#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RTK14(삼국지14) 무장정보 xlsx → 오픈삼국 divergence 스탯(정치/매력) 룩업 JSON 빌더.

**DIVERGENCE 도구** — devsam/core 패러티 대상 아님. 산출 데이터(정치/매력)는 코에이 IP이므로
출력 JSON은 git-ignored(`**/rtk14_stats.local.json`). 이 스크립트(알고리즘)만 커밋한다.

동명이인 처리: devsam 시나리오는 동명을 접미숫자로 구분(마충1/마충2)하고 RTK14도 동명을 다중행으로
보유한다. 단순 이름매칭은 동명이인을 붕괴시킨다. 그래서 devsam이 유지하는 통/무/지(leadership/strength/
intel) 지문으로 RTK14 다중행에 **1:1 greedy-최적배정**한 뒤 **exact devsam 이름**을 키로 굽는다.
런타임(Kotlin Rtk14Stats.lookup)은 정규화 없이 exact 조회만 한다.

사용:
  python3 tools/rtk14/build_rtk14_stats.py \
      --xlsx "/path/to/삼국지14 무장정보.xlsx" \
      --scenario infra/src/main/resources/scenario/scenario_1010.json \
      --out infra/src/main/resources/scenario/rtk14_stats.local.json

미매칭(RTK14에 base-name 없음)은 기본 (50,50)으로 출력한다.
"""
import argparse, json, re, zipfile
import xml.etree.ElementTree as ET
from collections import defaultdict

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
DEFAULT = 50
base_name = lambda n: re.sub(r"\d+$", "", str(n))


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
            rtk[c[1]].append({"L": _int(c[8]), "S": _int(c[9]), "I": _int(c[10]),
                              "pol": _int(c[11]), "cha": _int(c[12])})
    return rtk


def read_devsam(scenario_path):
    """scenario_*.json general[]+general_ex[] → [ {name,L,S,I} ] (positional idx 1/5/6/7)."""
    d = json.load(open(scenario_path, encoding="utf-8"))
    out = []
    for arr in (d.get("general", []) + d.get("general_ex", [])):
        if isinstance(arr, list) and len(arr) >= 8:
            out.append({"name": str(arr[1]), "L": arr[5], "S": arr[6], "I": arr[7]})
    return out


def assign(devsam, rtk):
    dist = lambda c, g: abs(c["L"] - g["L"]) + abs(c["S"] - g["S"]) + abs(c["I"] - g["I"])
    grp = defaultdict(list)
    for g in devsam:
        grp[base_name(g["name"])].append(g)

    out = {}
    stats = {"distinct": 0, "collapsed": 0, "fallback": 0}
    for bn, members in grp.items():
        cands = rtk.get(bn, [])
        if not cands:
            for g in members:
                out[g["name"]] = {"politics": DEFAULT, "charm": DEFAULT}
                stats["fallback"] += 1
            continue
        # greedy 1:1: 모든 (member,cand) 쌍을 거리순 → 양쪽 미사용일 때만 배정
        pairs = sorted((dist(c, g), gi, ci)
                       for gi, g in enumerate(members) for ci, c in enumerate(cands))
        asg, used = {}, set()
        for _, gi, ci in pairs:
            if gi in asg or ci in used:
                continue
            asg[gi] = ci; used.add(ci)
        for gi, g in enumerate(members):
            if gi not in asg:  # k>m: 남은 member는 nearest 재사용(불가피 붕괴)
                asg[gi] = min(range(len(cands)), key=lambda ci: dist(cands[ci], g))
                stats["collapsed"] += 1
            else:
                stats["distinct"] += 1
            c = cands[asg[gi]]
            out[g["name"]] = {"politics": c["pol"], "charm": c["cha"]}
    return out, stats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--xlsx", required=True)
    ap.add_argument("--scenario", default="infra/src/main/resources/scenario/scenario_1010.json")
    ap.add_argument("--out", default="infra/src/main/resources/scenario/rtk14_stats.local.json")
    a = ap.parse_args()

    rtk = read_rtk14(a.xlsx)
    devsam = read_devsam(a.scenario)
    out, stats = assign(devsam, rtk)
    json.dump(out, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print(f"devsam={len(devsam)} -> entries={len(out)} | "
          f"distinct={stats['distinct']} collapsed={stats['collapsed']} fallback={stats['fallback']}")
    print(f"wrote {a.out} (git-ignored — 코에이 IP)")


if __name__ == "__main__":
    main()
