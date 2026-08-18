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
  3. `data/v2/unit-types.json` — 이 저장소가 이미 사료 실측으로 확정해 둔 병종 명부
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
BASE_ATK = {
    FOOT:   {ARCHER: 1.2, CAV: 0.8, SIEGE: 1.2},
    ARCHER: {FOOT: 0.8, CAV: 1.2, SIEGE: 1.2},
    CAV:    {ARCHER: 1.2, FOOT: 1.2, SIEGE: 0.8},
    SIEGE:  {FOOT: 1.2, ARCHER: 1.2, CAV: 1.2},
}
BASE_DEF = {
    FOOT:   {ARCHER: 0.8, CAV: 1.2, SIEGE: 0.8},
    ARCHER: {FOOT: 1.2, CAV: 1.2, SIEGE: 0.8},
    CAV:    {ARCHER: 0.8, FOOT: 0.8, SIEGE: 1.2},
    SIEGE:  {FOOT: 1.2, ARCHER: 1.2, CAV: 1.2},
}


def mul(base: dict, extra: dict) -> dict:
    out = dict(base)
    for k, v in extra.items():
        out[k] = round(out.get(k, 1.0) * v, 3)
    return out


# ── 명부는 읽어온다 ─────────────────────────────────────────────────────────────
ROSTER = ROOT / "data" / "v2" / "unit-types.json"

# 역할 → (armType, 무기, 갑옷, 방패, 기술). 이름이 무기를 말하면 아래 OVERRIDE 가 이긴다.
ROLE = {
    "INFANTRY_LEVY":     (FOOT, "검", "의복", None, 0),
    "INFANTRY_LINE":     (FOOT, "검", "평피갑", "중형방패", 0),
    "INFANTRY_SKIRMISH": (FOOT, "표창", "경피갑", None, 0),
    "GARRISON":          (FOOT, "모", "평피갑", "대형방패", 0),
    "INFANTRY_SHIELD":   (FOOT, "모", "경철갑", "대형방패", 1000),
    "INFANTRY_SHOCK":    (FOOT, "환수도", "경철갑", "중형방패", 1000),
    "INFANTRY_FANATIC":  (FOOT, "부", "의복", None, 1000),
    "INFANTRY_ELITE":    (FOOT, "환수도", "평철갑", "중형방패", 2000),
    "INFANTRY_GUARD":    (FOOT, "극", "평철갑", "중형방패", 3000),
    "RANGED_LIGHT":      (ARCHER, "표창", "경피갑", None, 1000),
    "RANGED_HEAVY":      (ARCHER, "노", "경피갑", None, 2000),
    "CAVALRY_LIGHT":     (CAV, "궁", "경피갑", None, 0),
    "CAVALRY_RAID":      (CAV, "모", "경피갑", None, 1000),
    "CAVALRY_RANGED":    (CAV, "강궁", "경피갑", None, 1000),
    "CAVALRY_SHOCK":     (CAV, "삭", "경철갑", None, 2000),
    "CAVALRY_GUARD":     (CAV, "극", "평철갑", "소형방패", 3000),
    "BEAST":             (CAV, "모", "중등갑", None, 3000),
    "CHARIOT":           (SIEGE, "과", "경철갑", None, 2000),
    "NAVY_LIGHT":        (FOOT, "모", "경등갑", None, 0),
    "NAVY_LINE":         (FOOT, "노", "중등갑", None, 2000),
    "NAVY_CAPITAL":      (FOOT, "모", "중등갑", "대형방패", 3000),   # 누선은 뱃전에 방패벽을 세운다
    "SIEGE":             (SIEGE, "충차", "의복", None, 1000),
    "LOGISTICS":         (SIEGE, "치중", "의복", None, 3000),
    # 지역 부대는 조성을 명부가 직접 들고 온다 — 아래 값은 쓰이지 않는다.
    "REGIONAL":          (FOOT, "검", "의복", None, 0),
}

# 역할 → 등급. 民兵(1) 값싸고 약하다 · 部曲(2) 표준 전력 · 精銳(3) 최고.
# 사료가 이미 이 사다리를 갖고 있다 — 材官·郡兵 은 징집이고, 部曲 은 호족의 사병이고,
# 北軍五校·虎賁·羽林 은 중앙 상비군이다. 등급은 그 실상에 이름을 붙인 것뿐이다.
TIER = {
    1: ("民兵", "민병", ["INFANTRY_LEVY", "INFANTRY_SKIRMISH", "GARRISON",
                        "CAVALRY_LIGHT", "NAVY_LIGHT"]),
    2: ("部曲", "부곡", ["REGIONAL", "INFANTRY_LINE", "INFANTRY_SHOCK", "INFANTRY_SHIELD",
                        "INFANTRY_FANATIC", "RANGED_LIGHT", "RANGED_HEAVY",
                        "CAVALRY_RAID", "CAVALRY_RANGED", "CHARIOT", "SIEGE",
                        "NAVY_LINE", "LOGISTICS"]),
    3: ("精銳", "정예", ["INFANTRY_ELITE", "INFANTRY_GUARD", "CAVALRY_SHOCK",
                        "CAVALRY_GUARD", "BEAST", "NAVY_CAPITAL"]),
}
TIER_OF = {r: t for t, (_, _, rs) in TIER.items() for r in rs}
assert set(TIER_OF) == set(ROLE), set(TIER_OF) ^ set(ROLE)

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

# 이름이 장비를 말하는 병종. 사료가 적어둔 것을 역할 기본값이 덮지 않게 한다.
# (부분 문자열이 아니라 한자 표기 전체로 건다 — 우연히 겹치는 글자가 많다.)
OVERRIDE = {
    "彊弩":   {"weapon": "노"},
    "連弩士": {"weapon": "연노"},
    "大戟士": {"weapon": "극"},
    "幽州突騎": {"weapon": "삭"},
    "板楯蠻兵": {"weapon": "표창", "shield": "대형방패"},   # 板楯 = 널방패가 이름이다
    "藤甲兵": {"armor": "중등갑"},
    "鐵騎":   {"armor": "중철갑"},
    "鐵車兵": {"armor": "중철갑"},
    "象兵":   {"weapon": "모", "armor": "중등갑"},
    "飛刀":   {"weapon": "투부"},
    "挹婁舟師": {"weapon": "독시"},                        # 「矢施毒，人中皆死」
    "白馬義從": {"weapon": "강궁"},
    "霹靂車": {"weapon": "투석"},
    "井闌":   {"weapon": "충차"},
    "雲梯":   {"weapon": "충차"},
    "木牛流馬": {"weapon": "치중"},
    "赤甲軍": {"armor": "평철갑"},                        # 赤甲 = 붉은 갑옷이 이름이다
    "白毦兵": {"armor": "평철갑"},
    "虎豹騎": {"armor": "중철갑"},
}

# 게이팅으로 옮길 수 있는 requires 키. 나머지(general·ruler·event·faith·minGold …)는
# 대응하는 제약 타입이 없다 — 지어내지 않고 info 문구로만 남긴다.
GATE_KEYS = ("province", "commandery", "region", "city", "external", "tribe")

# 왜 땅에는 말이 없다(三國志 卷30 倭人 「其地無牛、馬」). 기병 전 병종에 주둔지 금제.
WA = ["邪馬壹國", "奴國", "投馬國"]


def load_roster():
    doc = json.loads(ROSTER.read_text())
    out = []
    named = 0
    for u in doc["units"]:
        # 기본 사다리는 역할표를 거치지 않는다 — 조성·등급·기술을 명부가 직접 들고 있다.
        if u.get("generic"):
            c = u["composition"]
            out.append(dict(id=2000 + len(out), name=u["ko"], han=u["han"],
                            arm=u["armType"], weapon=c["weapon"], armor=c["armor"],
                            shield=c["shield"], tech=u["reqTech"], role="GENERIC",
                            tier=u["tier"], req=[], notes=[],
                            forbid=WA if u["armType"] == CAV else [],
                            cat=u["category"], trait=None, renown=False,
                            src=u["source"]["cite"], cls=u["source"]["class"]))
            continue
        n, named = named, named + 1
        role = u["role"]
        if role not in ROLE:
            sys.exit(f"{u['han']}: 모르는 역할 {role} — ROLE 표에 넣어라")
        arm, weapon, armor, shield, tech = ROLE[role]
        tier = TIER_OF[role]
        if "composition" in u:
            # 명부가 조성을 직접 들고 있으면 역할 기본값을 쓰지 않는다. 같은 역할이라도
            # 무기가 다르면 다른 병종이다 — 지역별 무기 배분이 여기서 갈린다.
            c = u["composition"]
            weapon, armor, shield = c["weapon"], c["armor"], c["shield"]
            arm, tier, tech = u["armType"], u["tier"], u["reqTech"]
        ov = OVERRIDE.get(u["han"], {})
        weapon, armor = ov.get("weapon", weapon), ov.get("armor", armor)
        shield = ov["shield"] if "shield" in ov else shield

        req, notes = [], []
        for k, v in (u.get("requires") or {}).items():
            if k in GATE_KEYS and isinstance(v, str):
                req.append(v)
            elif k in GATE_KEYS and isinstance(v, list):
                req += [x for x in v if isinstance(x, str)]
            elif k == "adjacentTribe" and isinstance(v, list):
                req += v
            else:
                notes.append(f"{k}={v}")
        out.append(dict(id=2100 + n, name=u["ko"], han=u["han"], arm=arm, weapon=weapon,
                        armor=armor, shield=shield, tech=tech, role=role,
                        tier=tier, req=sorted(set(req)), notes=notes,
                        cat=u["category"], trait=trait_of(u),
                        renown=u["source"]["class"] != "GAME_REFERENCE",
                        forbid=WA if arm == CAV else [],
                        src=f"{u['source']['cite']} 「{u['source']['quote'][:40]}」",
                        cls=u["source"]["class"]))
    return out


def trait_of(u: dict):
    r = u.get("requires") or {}
    for k in ("tribe", "adjacentTribe"):
        v = r.get(k)
        for t in ([v] if isinstance(v, str) else (v if isinstance(v, list) else [])):
            if t in TRAIT_OF:
                return TRAIT_OF[t]
    if r.get("region") == "南中" or r.get("terrain") == "MOUNTAIN":
        return "산지"          # 종족 이름이 없어도 험지에서 싸우면 험지 사람이다
    if r.get("court") or r.get("ruler"):
        return "중앙"
    return None


def derive(u: dict) -> dict:
    atk, a_extra, d_extra, shieldable, w_note = WEAPON[u["weapon"]]
    dfn, spd, weight, a_note = ARMOR[u["armor"]]
    spd += WEAPON_SPD.get(u["weapon"], 0)
    sh = u["shield"]
    if sh and not shieldable:
        sys.exit(f"{u['name']}: {u['weapon']} 는 방패를 들 수 없다")
    avoid, vs_archer, s_note = SHIELD[sh]

    if u["arm"] == CAV:
        spd += 1                      # 말은 빠르다. 대신 갑옷 무게가 그대로 남는다.
        avoid += 5
        # 활을 든 기병은 붙지 않고 돈다. 방패 대신 거리로 산다 — 치고빠지기가 곧 방어다.
        if u["weapon"] in ("궁", "강궁", "단궁"):
            avoid += 10
    if u["arm"] == SIEGE:
        spd -= 1
        avoid = 0

    a = mul(BASE_ATK.get(u["arm"], {}), a_extra)
    d = mul(BASE_DEF.get(u["arm"], {}), d_extra)
    if vs_archer != 1.0:
        d = mul(d, {ARCHER: vs_archer})
    if u["armor"] in RATTAN:
        d = mul(d, {SIEGE: 1.5})      # 등갑은 불에 두 배로 탄다(투석·화시가 차병·궁병 계열이다).

    # 집단 성격 → 명성 순서로 얹는다. 곱은 성격에만 쓰고 명성은 더하기다.
    notes_extra = []
    if u["trait"]:
        adj, why = TRAIT[u["trait"]][1], TRAIT[u["trait"]][2]
        atk = int(atk * adj["atk"]) if isinstance(adj.get("atk"), float) else atk + adj.get("atk", 0)
        dfn = int(dfn * adj["dfn"]) if isinstance(adj.get("dfn"), float) else dfn + adj.get("dfn", 0)
        spd += adj.get("spd", 0)
        avoid += adj.get("avoid", 0)
        notes_extra.append(f"{u['trait']}: {why}.")
    if u["renown"]:
        atk += RENOWN["atk"]; dfn += RENOWN["dfn"]; avoid += RENOWN["avoid"]
        notes_extra.append("사료가 이름을 남긴 부대 — 같은 등급 기본 병종보다 낫다.")

    cost = round((atk + dfn) / 30) + weight + (2 if u["arm"] == CAV else 0)
    rice = cost + (1 if u["arm"] == CAV else 0) - (5 if u["arm"] == SIEGE else 0)

    req = []
    if u["tech"]:
        req.append({"type": "ReqTech", "reqTech": u["tech"]})
    if u["req"]:
        req.append({"type": "ReqRegions", "reqRegions": u["req"]})
    if u["forbid"]:
        req.append({"type": "ForbidRegions", "forbidRegions": u["forbid"]})

    tier_han, tier_ko, _ = TIER[u["tier"]]
    info = [f"{tier_ko}({tier_han}) 등급.",
            f"{u['weapon']}·{u['armor']}" + (f"·{sh}" if sh else "") + ".",
            w_note, a_note] + ([s_note] if s_note else [])
    # 제약 타입이 없는 조건은 문구로만 남긴다 — 없는 메커니즘을 있는 척하지 않는다.
    info += notes_extra + [f"조건(미구현): {n}" for n in u["notes"]]
    return {
        "id": u["id"], "armType": u["arm"], "name": u["name"], "han": u["han"],
        "role": u["role"], "category": u["cat"], "tier": u["tier"], "tierName": tier_ko,
        "evidence": u["cls"],
        "attack": atk, "defence": dfn, "speed": spd, "avoid": avoid,
        "magicCoef": 0, "cost": cost, "rice": rice,
        "requirements": req,
        "attackCoef": {str(k): v for k, v in sorted(a.items())},
        "defenceCoef": {str(k): v for k, v in sorted(d.items())},
        "composition": {"weapon": u["weapon"], "armor": u["armor"], "shield": sh},
        "info": info,
        "source": u["src"],
    }


def build() -> dict:
    R = load_roster()
    assert len([u for u in R if u["role"] == "GENERIC"]) < 100, "기본 사다리가 100종을 넘었다 — id 대역이 겹친다"
    ids = [u["id"] for u in R]
    assert len(ids) == len(set(ids)), "id 중복"
    units = [derive(u) for u in R]
    return {
        "_meta": {
            "id": "han",
            "name": "후한 군현 병종표",
            "generator": "tools/unitset/build_han_unitset.py",
            "note": "수치는 무기·갑옷·방패 조합에서 유도된다. 손으로 찍지 않는다.",
            "armTypes": {"1": "보병", "2": "궁병", "3": "기병", "4": "귀병", "5": "차병"},
            "tiers": {str(t): {"han": h, "ko": k} for t, (h, k, _) in TIER.items()},
            "sources": ["devsam/core che 병종표 (armType·상성 골격)",
                        "data/v2/unit-types.json (이름·출전·게이팅·역할 — 사료 실측 71종)",
                        "Total War: Three Kingdoms (조성 축만 참고, 수치·이름 미차용)"],
            "counts": {"units": len(units)},
        },
        "id": "han",
        "defaultCrewTypeId": 2100,
        "crewTypes": units,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    blob = json.dumps(build(), ensure_ascii=False, indent=1) + "\n"
    if args.check:
        if not OUT.exists() or OUT.read_text() != blob:
            print(f"드리프트: {OUT.relative_to(ROOT)}")
            return 1
        print("일치.")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(blob)
    print(f"{OUT.relative_to(ROOT)} — 병종 {len(json.loads(blob)['crewTypes'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
