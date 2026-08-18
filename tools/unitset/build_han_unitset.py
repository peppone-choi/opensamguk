#!/usr/bin/env python3
"""후한 군현 맵용 병종표 빌더 — `data/unitset/han.json` 을 낸다.

**세 갈래를 엮은 것이지 한 쪽을 베낀 것이 아니다.**

  1. devsam/core `che` 병종표 — armType 5종(보/궁/기/귀/차)과 그 상성 골격, 4개 수치
     (attack/defence/speed/avoid), cost/rice, phaseSkillTrigger 어휘를 그대로 쓴다.
     여기서 벗어나면 전투 엔진과 골든이 깨진다. armType 을 늘리지 않는 이유가 그것이다.
  2. Total War: Three Kingdoms 의 **조성 방식** — 병종을 (무기 · 갑옷 · 방패) 조합으로 보고
     역할(전선유지/측후방/원거리)과 등급(민병→정예→최정예)을 매기는 축. 수치나 이름을
     가져오지 않는다(CC BY-NC-SA 자료다). 가져온 것은 "무엇으로 무엇을 입고 무엇을 드는가"
     라는 분해 축 하나뿐이다.
  3. `data/unitset/han.json` — 병종은 이 파일 하나다. 이름·출전·게이팅·조성·등급은
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

    python3 tools/unitset/build_han_unitset.py
    python3 tools/unitset/build_han_unitset.py --check
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "data" / "unitset" / "han.json"

FOOT, ARCHER, CAV, WIZ, SIEGE = 1, 2, 3, 4, 5

# ── 무기 ────────────────────────────────────────────────────────────────────────
# (공격, 공격계수 덧, 방어계수 덧, 방패 가능, 비고)
# 관통 무기(부·노)는 방어 높은 상대에 계수를 얹고, 장병기(모·삭·극)는 기병 돌격을 되받는다.
# 되받음은 새 메커니즘이 아니라 defenceCoef[기병] < 1 한 줄이다 — 엔진을 건드리지 않는다.
WEAPON = {
    "환수도": (110, {ARCHER: 1.15}, {}, True, "베기 위주. 빠르지만 두꺼운 갑옷에 약하다."),
    "검":     (100, {ARCHER: 1.2}, {}, True, "가볍고 빠르다. 전선 유지용."),
    "부":     (150, {FOOT: 1.3}, {}, True, "관통. 느리지만 중장 보병을 쪼갠다."),
    "모":     (100, {}, {CAV: 0.6}, True, "장병기. 정면 돌격을 되받는다."),
    "삭":     (100, {FOOT: 0.9}, {CAV: 0.5}, False, "긴 장창. 기병에 가장 강하고 보병전엔 둔하다."),
    "극":     (125, {FOOT: 1.1}, {CAV: 0.8}, True, "창과 도끼를 겸한다. 되받음은 얕다."),
    "구겸도": (175, {}, {CAV: 0.7}, False, "양손 대도. 방패를 들 수 없어 무르다."),
    "궁":     (100, {}, {}, False, "표준 활."),
    "강궁":   (150, {}, {}, False, "강한 활. 사거리와 관통이 붙는다."),
    "단궁":   (140, {}, {}, False, "짧고 억센 활."),
    "노":     (150, {FOOT: 1.3}, {}, False, "관통. 갑옷을 뚫지만 재장전이 느리다."),
    "연노":   (175, {CAV: 1.3}, {}, False, "연사. 탄약이 적어 오래 못 쏜다."),
    "화시":   (125, {SIEGE: 1.6}, {}, False, "불화살. 공성병기와 등갑에 치명적이다."),
    "과":     (110, {CAV: 1.2}, {CAV: 0.7}, True, "갈고리 달린 창. 말과 수레를 걸어 끌어내린다."),
    "대도":   (140, {FOOT: 1.15}, {}, False, "양손 큰 칼. 방패를 못 든다."),
    "참마검": (160, {CAV: 1.4}, {CAV: 0.6}, False, "말을 베는 긴 칼. 느리지만 기병을 끊는다."),
    "철추":   (145, {FOOT: 1.35}, {}, True, "쇠몽둥이. 갑옷을 부수지만 굼뜨다."),
    "표창":   (120, {CAV: 1.25}, {}, True, "던지고 붙는다. 첫 합에서 기병을 흔든다."),
    "투부":   (130, {FOOT: 1.2}, {}, True, "던지는 도끼. 방패째 부순다."),
    "취전":   (110, {FOOT: 1.3}, {}, False, "독 불대. 갑옷을 가리지 않는다."),
    "독시":   (130, {FOOT: 1.25}, {}, False, "독 바른 화살. 스치면 죽는다."),
    "치중":   (50, {}, {}, False, "싣고 나른다. 싸우라고 만든 것이 아니다."),
    "투석":   (150, {}, {}, False, "돌을 날린다."),
    "충차":   (150, {}, {}, False, "성문을 깬다."),
}

# ── 갑옷 ── (방어, 속도, 무게등급 0경장/1일반/2중장, 비고)
ARMOR = {
    "의복":   (75, 8, 0, "갑옷이랄 것이 없다."),
    "경피갑": (100, 8, 0, "가죽. 가볍다."),
    "평피갑": (115, 7, 1, "가죽. 무난하다."),
    "중피갑": (120, 7, 1, "두꺼운 가죽."),
    "경철갑": (140, 7, 1, "쇠미늘. 무겁지 않다."),
    "평철갑": (175, 7, 2, "쇠미늘. 튼튼하다."),
    "중철갑": (200, 6, 2, "두꺼운 쇠미늘. 느리다."),
    "강철갑": (230, 6, 2, "최상급 갑주. 매우 느리다."),
    "경등갑": (105, 8, 0, "등나무. 가볍고 물에 뜬다."),
    "중등갑": (160, 7, 1, "등나무를 겹쳤다. 불에 약하다."),
}
# 무기 무게가 속도를 먹는다. 갑옷과 따로 센다 — 가벼운 갑옷에 큰 칼도, 그 반대도 있다.
WEAPON_SPD = {"부": -1, "참마검": -1, "구겸도": -1, "삭": -1, "철추": -1, "대도": -1, "연노": 1}

# 등갑은 불에 두 배로 탄다 — 화시(1.6)에 더해 계수를 한 번 더 얹는다.
RATTAN = {"경등갑", "중등갑"}

# ── 방패 ── (회피, 대궁병 방어계수, 비고)
SHIELD = {
    None:     (0, 1.0, None),
    "소형방패": (15, 0.85, "작고 가볍다. 피하는 데 쓴다."),
    "중형방패": (10, 0.8, "가리며 싸운다."),
    "대형방패": (5, 0.65, "화살을 막는 벽이 된다."),
}

# ── armType 골격 상성 (che 그대로) ──────────────────────────────────────────────
CASTLE, WIZARD = 0, 4      # v1 GameUnitConst 와 같은 번호를 쓴다

BASE_ATK = {
    FOOT:   {ARCHER: 1.2, CAV: 0.8, SIEGE: 1.2},
    ARCHER: {FOOT: 0.8, CAV: 1.2, SIEGE: 1.2},
    CAV:    {ARCHER: 1.2, FOOT: 1.2, SIEGE: 0.8},
    SIEGE:  {FOOT: 1.2, ARCHER: 1.2, CAV: 1.2},
    WIZARD: {SIEGE: 1.2},
}
BASE_DEF = {
    FOOT:   {ARCHER: 0.8, CAV: 1.2, SIEGE: 0.8},
    ARCHER: {FOOT: 1.2, CAV: 1.2, SIEGE: 0.8},
    CAV:    {ARCHER: 0.8, FOOT: 0.8, SIEGE: 1.2},
    SIEGE:  {FOOT: 1.2, ARCHER: 1.2, CAV: 1.2},
    WIZARD: {SIEGE: 0.8},
}


def mul(base: dict, extra: dict) -> dict:
    out = dict(base)
    for k, v in extra.items():
        out[k] = round(out.get(k, 1.0) * v, 3)
    return out


# 등급. 民兵(1) 값싸고 약하다 · 部曲(2) 표준 전력 · 精銳(3) 최고.
# 사료가 이미 이 사다리를 갖고 있다 — 材官·郡兵 은 징집이고, 部曲 은 호족의 사병이고,
# 北軍五校·虎賁·羽林 은 중앙 상비군이다. 등급은 그 실상에 이름을 붙인 것뿐이다.
TIER = {
    0: ("—", "—", []),
    1: ("民兵", "민병", []),
    2: ("部曲", "부곡", []),
    3: ("精銳", "정예", []),
}

# 집단 성격. 같은 무기를 들어도 누가 드느냐에 따라 다르게 싸운다 — TROM 이 "서량 부곡
# 창병"과 "북방 부곡 창병"을 따로 두는 이유가 이것이다. 사료가 그들을 어떻게 적었는지가
# 방향을 정하고(유목은 말, 산지는 험지, 중앙은 갖춘 장비), 크기는 게임이 정한다.
TRAIT = {
    "유목": (["羌", "鮮卑", "烏桓", "烏丸", "匈奴", "屠各", "胡", "高句麗"],
             dict(spd=1, avoid=5, dfn=0.90), "말 위에서 산다 — 빠르고 가볍다"),
    "산지": (["山越", "叟", "賨", "蠻", "夷", "青羌", "挹婁"],
             dict(avoid=10, atk=10, dfn=0.95), "험한 곳을 탄다 — 붙잡히지 않는다"),
    "중앙": ([], dict(dfn=1.10, avoid=5), "조정이 입히고 먹인다 — 장비가 갖춰져 있다"),
}
TRAIT_OF = {t: k for k, (ts, _, _) in TRAIT.items() for t in ts}

# 사료가 이름을 남긴 부대는 같은 등급 기본 병종보다 낫다. 그래야 게이팅을 뚫고
# 뽑을 이유가 있다. 이름값이지 사료 수치가 아니다 — 사료에 수치는 없다.
RENOWN = dict(atk=10, dfn=15, avoid=5)


# ── 명부는 이 파일 하나다 ───────────────────────────────────────────────────────
UNITSET = ROOT / "data" / "unitset" / "han.json"


def trait_of(req: dict):
    for k in ("tribe", "adjacentTribe"):
        v = req.get(k)
        for t in ([v] if isinstance(v, str) else (v if isinstance(v, list) else [])):
            if t in TRAIT_OF:
                return TRAIT_OF[t]
    if req.get("region") == "南中" or req.get("terrain") == "MOUNTAIN":
        return "산지"          # 종족 이름이 없어도 험지에서 싸우면 험지 사람이다
    if req.get("court") or req.get("ruler"):
        return "중앙"
    return None


# 게이팅으로 옮길 수 있는 requires 키. 나머지(general·ruler·event·faith·minGold …)는
# 대응하는 제약 타입이 없다 — 지어내지 않고 info 문구로만 남긴다.
GATE_KEYS = ("province", "commandery", "region", "city", "external", "tribe")

# 왜 땅에는 말이 없다(三國志 卷30 倭人 「其地無牛、馬」). 기병 전 병종에 주둔지 금제.
WA = ["邪馬壹國", "奴國", "投馬國"]


def derive(u: dict) -> dict:
    """authored 필드만 읽어 generated 필드를 다시 만든다. 같은 입력이면 같은 출력."""
    if not u.get("derived", True):
        return u                       # 성벽 — 손으로 적은 자리, 유도하지 않는다

    arm, tier = u["armType"], u["tier"]
    c = u["composition"]
    weapon, armor, sh = c["weapon"], c["armor"], c["shield"]

    atk, a_extra, d_extra, shieldable, w_note = WEAPON[weapon]
    dfn, spd, weight, a_note = ARMOR[armor]
    spd += WEAPON_SPD.get(weapon, 0)
    if sh and not shieldable:
        sys.exit(f"{u['name']}: {weapon} 는 방패를 들 수 없다")
    avoid, vs_archer, s_note = SHIELD[sh]

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
    if armor in RATTAN:
        d = mul(d, {SIEGE: 1.5})      # 등갑은 불에 두 배로 탄다(투석·화시가 차병·궁병 계열이다).

    # 집단 성격 → 명성 순서로 얹는다. 곱은 성격에만 쓰고 명성은 더하기다.
    req_raw = u.get("requires") or {}
    trait = trait_of(req_raw)
    renown = u["evidence"]["class"] != "GAME_REFERENCE"
    extra = []
    if trait:
        adj, why = TRAIT[trait][1], TRAIT[trait][2]
        atk = int(atk * adj["atk"]) if isinstance(adj.get("atk"), float) else atk + adj.get("atk", 0)
        dfn = int(dfn * adj["dfn"]) if isinstance(adj.get("dfn"), float) else dfn + adj.get("dfn", 0)
        spd += adj.get("spd", 0)
        avoid += adj.get("avoid", 0)
        extra.append(f"{trait}: {why}.")
    if renown:
        atk += RENOWN["atk"]; dfn += RENOWN["dfn"]; avoid += RENOWN["avoid"]
        extra.append("사료가 이름을 남긴 부대 — 같은 등급 기본 병종보다 낫다.")

    magic = {1: 0.5, 2: 0.55, 3: 0.6}[tier] if arm == WIZARD else 0.0
    cost = round((atk + dfn) / 30) + weight + (2 if arm == CAV else 0)
    # 차병은 사람이 적게 먹는다. 다만 0 아래로는 내리지 않는다.
    rice = max(1, cost + (1 if arm == CAV else 0) - (5 if arm == SIEGE else 0))

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
    tech = u.get("reqTech", 0) or next((r["reqTech"] for r in u.get("reqConstraints", [])
                                        if r.get("type") == "ReqTech"), 0)
    if tech:
        req.append({"type": "ReqTech", "reqTech": tech})
    if gates:
        req.append({"type": "ReqRegions", "reqRegions": sorted(set(gates))})
    if arm == CAV:
        req.append({"type": "ForbidRegions", "forbidRegions": WA})

    tier_han, tier_ko, _ = TIER[tier]
    info = [f"{tier_ko}({tier_han}) 등급.",
            f"{weapon}·{armor}" + (f"·{sh}" if sh else "") + ".",
            w_note, a_note] + ([s_note] if s_note else []) + extra
    # 제약 타입이 없는 조건은 문구로만 남긴다 — 없는 메커니즘을 있는 척하지 않는다.
    info += [f"조건(미구현): {n}" for n in notes]

    u.update(tierName=tier_ko, attack=atk, defence=dfn, speed=spd, avoid=avoid,
             magicCoef=magic, cost=cost, rice=rice, reqConstraints=req,
             attackCoef={str(k): v for k, v in sorted(a.items())},
             defenceCoef={str(k): v for k, v in sorted(d.items())},
             info=[i for i in info if i], reqTech=tech)
    return u


def build() -> dict:
    doc = json.loads(UNITSET.read_text())
    ids = [u["id"] for u in doc["crewTypes"]]
    assert len(ids) == len(set(ids)), "id 중복"
    doc["crewTypes"] = [derive(u) for u in doc["crewTypes"]]
    doc["_meta"]["counts"] = {
        "units": len(ids),
        "byCategory": {c: sum(1 for u in doc["crewTypes"] if u["category"] == c)
                       for c in ("COMMON", "FACTION", "REGIONAL", "CHARACTER", "OTHER")},
    }
    return doc


if __name__ == "__main__":
    doc = build()
    UNITSET.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n")
    print(f"{UNITSET.relative_to(ROOT)} — 병종 {len(doc['crewTypes'])}")
