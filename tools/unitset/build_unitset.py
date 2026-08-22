#!/usr/bin/env python3
"""병종표 빌더 — `data/unitset/units.json` 의 han 세트 수치를 다시 만든다.

**세 갈래를 엮은 것이지 한 쪽을 베낀 것이 아니다.**

  1. devsam/core `che` 병종표 — armType 5종(보/궁/기/귀/차)과 그 상성 골격, 4개 수치
     (attack/defence/speed/avoid), cost/rice, phaseSkillTrigger 어휘를 그대로 쓴다.
     여기서 벗어나면 전투 엔진과 골든이 깨진다. armType 을 늘리지 않는 이유가 그것이다.
  2. Total War: Three Kingdoms 의 **조성 방식** — 병종을 (무기 · 갑옷 · 방패) 조합으로 보고
     역할(전선유지/측후방/원거리)과 등급(민병→정예→최정예)을 매기는 축. 수치나 이름을
     가져오지 않는다(CC BY-NC-SA 자료다). 가져온 것은 "무엇으로 무엇을 입고 무엇을 드는가"
     라는 분해 축 하나뿐이다.
  3. `data/unitset/units.json` — 병종은 이 파일 하나다. che 세트(코틀린 사본)와 함께 있다. 이름·출전·게이팅·조성·등급은
     사람이 적고(authored), 수치는 이 스크립트가 조성에서 유도해 같은 파일에 다시
     써넣는다(generated). 명부를 다른 파일에 두지 않는다 — 둘이 되면 어느 쪽이
     맞는지 아무도 모른다. 예전 `data/v2/unit-types.json` 은 여기로 흡수됐다.
     (아래 옛 설명)  이 저장소가 이미 사료 실측으로 확정해 둔 병종 명부
     71종(PRIMARY_ATTESTED 62 · ROMANCE_ATTESTED 7 · GAME_REFERENCE 2). **이름·출전·
     게이팅·역할은 전부 거기서 읽는다.** 여기서 다시 짓지 않는다 — 로스터가 둘이 되면
     어느 쪽이 맞는지 아무도 모른다. 이 빌더가 더하는 것은 그 명부에 없는 것 하나,
     곧 **게임 수치**뿐이다.

**수치는 손으로 찍지 않는다.** 무기가 공격을, 갑옷이 방어와 속도를, 방패가 회피와 대궁병
계수를 정하고 cost/rice 는 거기서 유도된다. 손으로 찍은 표는 균형이 어디서 깨졌는지
아무도 못 찾는다 — 조합을 바꾸면 수치가 따라 움직여야 한다.

    python3 tools/unitset/build_unitset.py
    python3 tools/unitset/build_unitset.py --check
"""

from __future__ import annotations

import argparse
import json
import sys
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "data" / "unitset" / "units.json"

# ── 설계표는 파일 안에 있다 ─────────────────────────────────────────────────────
# 무기·갑옷·방패·등급·성격·계수·공식이 전부 data/unitset/units.json 의 `tables` 에 있다.
# 여기서 다시 적지 않는다 — 표가 두 곳에 있으면 어느 쪽이 맞는지 아무도 모른다.
UNITSET = ROOT / "data" / "unitset" / "units.json"
CASTLE, FOOT, ARCHER, CAV, WIZARD, SIEGE = 0, 1, 2, 3, 4, 5

DOC = json.loads(UNITSET.read_text())
T = DOC["tables"]
WEAPON, ARMOR, SHIELD = T["weapons"], T["armors"], T["shields"]
TIER, TRAIT, RENOWN = T["tiers"], T["traits"], T["renown"]["adjust"]
BASE_ATK = {int(k): {int(a): b for a, b in v.items()} for k, v in T["armTypeCoef"]["attack"].items()}
BASE_DEF = {int(k): {int(a): b for a, b in v.items()} for k, v in T["armTypeCoef"]["defence"].items()}
TRAIT_OF = {m: k for k, v in TRAIT.items() for m in v["members"]}
TECH_MAT = T["techByMaterial"]
CEILING = T["materialCeiling"]["rules"]
GATE_KEYS = tuple(T["gateKeys"])
WA = T["forbidRegionsForCavalry"]


def php_round(x: float, ndigits: int = 0) -> float:
    """`Util::round`/`PhpRound` 와 같은 half-away-from-zero 반올림.
    파이썬 내장 round() 는 half-to-even 이라 .5 에서 결과가 갈릴 수 있다."""
    q = Decimal(1).scaleb(-ndigits)
    return float(Decimal(str(x)).quantize(q, rounding=ROUND_HALF_UP))


def mul(base: dict, extra: dict) -> dict:
    out = dict(base)
    for k, v in extra.items():
        out[int(k)] = php_round(out.get(int(k), 1.0) * v, 3)
    return out


def trait_of(req: dict):
    for k in ("tribe", "adjacentTribe", "external", "event"):
        v = req.get(k)
        for t in ([v] if isinstance(v, str) else (v if isinstance(v, list) else [])):
            if t in TRAIT_OF:
                return TRAIT_OF[t]
    if req.get("region") == "南中" or req.get("terrain") == "MOUNTAIN":
        return "산지"          # 종족 이름이 없어도 험지에서 싸우면 험지 사람이다
    if req.get("court") or req.get("ruler"):
        return "중앙"
    return None


def derive(u: dict) -> dict:
    """authored 필드만 읽어 generated 필드를 다시 만든다. 같은 입력이면 같은 출력."""
    if not u.get("derived", True):
        return u                       # 성벽 — 손으로 적은 자리, 유도하지 않는다

    arm, tier = u["armType"], u["tier"]
    c = u["composition"]
    weapon, armor, sh = c["weapon"], c["armor"], c["shield"]

    w, am, shd = WEAPON[weapon], ARMOR[armor], SHIELD[sh or "없음"]
    atk, a_extra, d_extra = w["attack"], w["attackCoef"], w["defenceCoef"]
    w_note, a_note, s_note = w["note"], am["note"], shd["note"]
    dfn, spd, weight = am["defence"], am["speed"] + w["speed"], am["weight"]
    if sh and not w["shieldable"]:
        sys.exit(f"{u['name']}: {weapon} 는 방패를 들 수 없다")
    avoid, vs_archer = shd["avoid"], shd["vsArcherDefence"]

    if arm == CAV:
        spd += 1                      # 말은 빠르다. 대신 갑옷 무게가 그대로 남는다.
        avoid += 5
        # 활을 든 기병은 붙지 않고 돈다. 방패 대신 거리로 산다 — 치고빠지기가 곧 방어다.
        if weapon in ("궁", "강궁", "단궁"):
            avoid += 10
    if arm == SIEGE:
        spd -= 1
        avoid = 0

    a = mul(BASE_ATK.get(arm, {}), a_extra)
    d = mul(BASE_DEF.get(arm, {}), d_extra)
    if vs_archer != 1.0:
        d = mul(d, {ARCHER: vs_archer})
    if am["rattan"]:
        d = mul(d, {SIEGE: 1.5})      # 등갑은 불에 두 배로 탄다(투석·화시가 차병·궁병 계열이다).

    # 집단 성격 → 명성 순서로 얹는다. 곱은 성격에만 쓰고 명성은 더하기다.
    req_raw = u.get("requires") or {}
    trait = trait_of(req_raw)
    renown = u["evidence"]["class"] != "GAME_REFERENCE"
    extra = []
    if trait:
        adj, why = TRAIT[trait]["adjust"], TRAIT[trait]["why"]
        atk = int(atk * adj["atk"]) if isinstance(adj.get("atk"), float) else atk + adj.get("atk", 0)
        dfn = int(dfn * adj["dfn"]) if isinstance(adj.get("dfn"), float) else dfn + adj.get("dfn", 0)
        spd += adj.get("spd", 0)
        avoid += adj.get("avoid", 0)
        extra.append(f"{trait}: {why}.")
    if renown:
        atk += RENOWN["atk"]; dfn += RENOWN["dfn"]; avoid += RENOWN["avoid"]
        extra.append("사료가 이름을 남긴 부대 — 같은 등급 기본 병종보다 낫다.")

    magic = {1: 0.5, 2: 0.55, 3: 0.6}[tier] if arm == WIZARD else 0.0  # tables.formulas.magicCoef
    cost = int(php_round((atk + dfn) / 30)) + weight + (2 if arm == CAV else 0)
    # 차병은 사람이 적게 먹는다. 다만 0 아래로는 내리지 않는다.
    rice = max(1, cost + (1 if arm == CAV else 0) - (5 if arm == SIEGE else 0))

    # ── 기술축 ①: reqTech 는 손으로 찍지 않고 재료가 정한다. 병종은 그것을 물려받는다.
    tech_mat = max(TECH_MAT["weapon"].get(weapon, 0),
                   TECH_MAT["armor"].get(armor, 0),
                   TECH_MAT["shield"].get(sh or "없음", 0))

    # ── 기술축 ②: 집단 재료 천장. 넘으면 요란하게 실패한다 — 조용히 깎으면 표와 실물이 갈린다.
    gate_vals = {v for k, v in req_raw.items() if isinstance(v, str)}
    gate_vals |= {x for v in req_raw.values() if isinstance(v, list) for x in v if isinstance(x, str)}
    for rule in CEILING:
        if rule["key"] not in gate_vals:
            continue
        if armor in rule.get("forbidArmor", []):
            sys.exit(f"{u['name']}: {rule['key']} 는 {armor} 를 쓸 수 없다 — {rule['cite']}")
        if weapon in rule.get("forbidWeapon", []):
            sys.exit(f"{u['name']}: {rule['key']} 는 {weapon} 를 쓸 수 없다 — {rule['cite']}")
        if arm in rule.get("forbidArmType", []):
            sys.exit(f"{u['name']}: {rule['key']} 는 armType {arm} 을 쓸 수 없다 — {rule['cite']}")

    gates, notes = [], []
    for k, v in req_raw.items():
        if k in GATE_KEYS and isinstance(v, str):
            gates.append(v)
        elif k in GATE_KEYS and isinstance(v, list):
            gates += [x for x in v if isinstance(x, str)]
        elif k == "adjacentTribe" and isinstance(v, list):
            gates += v
        elif k == "adjacentTribe" and isinstance(v, str):
            gates.append(v)
        else:
            notes.append(f"{k}={v}")

    req = []
    # reqTech 는 **순수 유도값**이다. 사료상 특수 부대의 하한은 authored `techFloor` 에만 적는다 —
    # 유도한 reqTech 를 다시 authored 로 읽으면 매 실행마다 값이 올라가는 피드백 루프가 된다
    # (2026-08-21 실측: 황건병이 0 → 1000 으로 굳었다).
    tech = max(u.get("techFloor") or 0, tech_mat)
    if tech:
        req.append({"type": "ReqTech", "reqTech": tech})
    if gates:
        req.append({"type": "ReqRegions", "reqRegions": sorted(set(gates))})
    if arm == CAV:
        req.append({"type": "ForbidRegions", "forbidRegions": WA})

    tier_han, tier_ko = TIER[str(tier)]["han"], TIER[str(tier)]["ko"]
    info = [f"{tier_ko}({tier_han}) 등급.",
            f"{weapon}·{armor}" + (f"·{sh}" if sh else "") + ".",
            w_note, a_note] + ([s_note] if s_note else []) + extra
    # 제약 타입이 없는 조건은 문구로만 남긴다 — 없는 메커니즘을 있는 척하지 않는다.
    info += [f"조건(미구현): {n}" for n in notes]
    if tech_mat:
        info.append(f"기술 {tech_mat} 요구 — 재료({weapon}·{armor}) 가 정한 하한이다.")
    if u.get("nameCoined"):
        info.append(f"이름 주의: {u['nameCoined']}")

    u.update(tierName=tier_ko, attack=atk, defence=dfn, speed=spd, avoid=avoid,
             magicCoef=magic, cost=cost, rice=rice, reqConstraints=req,
             attackCoef={str(k): v for k, v in sorted(a.items())},
             defenceCoef={str(k): v for k, v in sorted(d.items())},
             info=[i for i in info if i], reqTech=tech)
    return u


KEYS = ["set", "id", "name", "han", "armType", "tier", "tierName", "category", "role",
        "generic", "derived", "composition", "requires", "evidence", "nameCoined", "techFloor", "attack", "defence",
        "speed", "avoid", "magicCoef", "cost", "rice", "reqTech", "reqConstraints", "attackCoef",
        "defenceCoef", "initSkillTrigger", "phaseSkillTrigger", "iActionList", "info"]


def build() -> dict:
    doc = DOC
    ids = [u["id"] for u in doc["crewTypes"]]
    assert len(ids) == len(set(ids)), "id 중복"
    assert all(isinstance(i, int) for i in ids), "id 누락"
    for s, r in doc["sets"].items():          # id 대역을 벗어난 행은 세트가 섞였다는 뜻이다
        lo, hi = r["idRange"]
        bad = [u["id"] for u in doc["crewTypes"] if u["set"] == s and not lo <= u["id"] <= hi]
        assert not bad, f"{s} 세트 id 대역 이탈: {bad}"
    # che 행은 코틀린 사본이다 — 여기서 수치를 만들지 않는다. han 행만 다시 만든다.
    derived = [derive(u) if u["set"] == "han" else u for u in doc["crewTypes"]]
    unknown = sorted({k for u in derived for k in u} - set(KEYS))
    if unknown:                               # 알 수 없는 필드를 조용히 버리지 않는다
        sys.exit(f"KEYS 에 없는 필드: {unknown} — 오타이거나 KEYS 에 추가해야 한다")
    doc["crewTypes"] = [{k: u.get(k) for k in KEYS} for u in derived]
    doc["_meta"]["counts"] = {
        "units": len(ids),
        "bySet": {s: sum(1 for u in doc["crewTypes"] if u["set"] == s) for s in doc["sets"]},
        "byCategory": {c: sum(1 for u in doc["crewTypes"] if u["category"] == c)
                       for c in ("CHE", "COMMON", "FACTION", "REGIONAL", "CHARACTER", "OTHER")},
    }
    return doc


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--che", type=Path,
                    help="CheUnitSetExportTest -Dunitset.write=true 가 낸 common/build/che-export.json. "
                         "코틀린 GameUnitConst 를 고쳤을 때 che 세트 행을 갈아끼운다.")
    ap.add_argument("--check", action="store_true",
                    help="파일을 고치지 않고, units.json 이 최신인지만 확인한다. "
                         "authored 필드에서 다시 유도한 값이 파일과 다르면 비정상 종료한다(CI 게이트).")
    a = ap.parse_args()
    if a.che:
        fresh = json.loads(a.che.read_text())
        DOC["crewTypes"] = [u for u in DOC["crewTypes"] if u["set"] != "che"]
        base = {k: None for k in ("han", "tier", "tierName", "role", "generic", "composition", "requires")}
        base |= {"category": "CHE", "derived": False, "generic": False,
                 "evidence": {"class": "GAME_REFERENCE", "proximity": "NONE",
                              "cite": "legacy/devsam-core hwe/sammo/GameUnitConst.php", "quote": None}}
        DOC["crewTypes"] = [dict(base, **u) for u in fresh] + DOC["crewTypes"]
    doc = build()
    fresh_text = json.dumps(doc, ensure_ascii=False, indent=1) + "\n"
    if a.check:
        if fresh_text != UNITSET.read_text():
            sys.exit(f"{UNITSET.relative_to(ROOT)} 이 최신이 아니다 — "
                      f"python3 tools/unitset/build_unitset.py 로 다시 만들어라")
        print(f"{UNITSET.relative_to(ROOT)} — 최신")
    else:
        UNITSET.write_text(fresh_text)
        print(f"{UNITSET.relative_to(ROOT)} — 병종 {len(doc['crewTypes'])}")
