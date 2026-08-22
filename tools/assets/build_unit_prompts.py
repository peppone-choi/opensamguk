#!/usr/bin/env python3
"""han 병종 스프라이트 프롬프트 빌더 (OPENSAM-209).

data/unitset/units.json 의 실제 crewType 데이터(armType/tier/composition/role)에서
병종별 생성 프롬프트를 결정론적으로 만든다. 지어낸 설명이 아니라 데이터가 SSoT다.

키 공간은 <병종>/<변형>이다. 오늘 등록된 변형은 base 하나뿐이지만, 진형(陣形)이
제품에 생기면 같은 병종 아래 형제 파일이 붙을 뿐 기존 키는 바뀌지 않는다.
진형 개념은 아직 코드·데이터 어디에도 없으므로 여기서 만들어내지 않는다 —
축만 열어두고 값은 비워둔다.

산출: <out-dir>/prompts/<id>_<slug>__<변형>.txt  +  <out-dir>/plan.json
"""
import argparse, json, pathlib, re, unicodedata

# 한국어 장비 어휘 -> 영어 시각 묘사. 값은 units.json 에 실재하는 것만 담는다.
WEAPON = {
    "환수도": "ring-pommel single-edged sabre",
    "모": "long spear",
    "장모": "extra-long pike",
    "부": "battle axe",
    "투부": "throwing axe",
    "궁": "wooden bow",
    "단궁": "short bow",
    "목궁": "plain wooden bow",
    "강궁": "heavy composite bow",
    "노": "handheld crossbow",
    "연노": "repeating crossbow",
    "독시": "poisoned arrows",
    "표창": "throwing javelins",
    "극": "ji halberd (spear with side blade)",
    "과": "ge dagger-axe",
    "검": "straight double-edged sword",
    "대도": "great two-handed blade",
    "참마검": "long anti-cavalry sword",
    "구겸도": "hooked polearm",
    "삭": "long cavalry lance",
    "치중": "supply carts and pack loads",
    "투석": "stone-throwing catapult",
    "충차": "covered battering ram",
    "정란": "tall siege observation tower",
    "운제": "wheeled scaling ladder",
}
ARMOR = {
    "의복": "a plain cloth tunic, no armour",
    "경피갑": "light leather armour",
    "평피갑": "standard leather armour",
    "중피갑": "heavy leather armour",
    "경철갑": "light iron lamellar",
    "평철갑": "standard iron lamellar",
    "중철갑": "heavy iron lamellar with shoulder guards",
    "경등갑": "light rattan armour",
    "중등갑": "heavy rattan armour",
}
SHIELD = {
    "소형방패": "a small round shield",
    "중형방패": "a medium rectangular shield",
    "대형방패": "a large tower shield",
}
# armType -> (부대 편성 묘사, 셀 폭). 폭은 병종군 크기 위계를 우리가 선언한다 —
# 프롬프트에 크기를 맡기면 AI 가 지키지 않는다(도시 아이콘에서 실측 확인).
# armType -> (부대 편성 묘사, 대조 문구, 셀 폭, 스타일 앵커 파일).
# 앵커가 병종군마다 다른 이유: c3(보병 방패벽)를 전 병종에 물리면 기병·차병까지
# 방패벽 블록으로 끌려간다(2026-08-21 사용자 지적). c3 는 팔레트/외곽선/카메라만
# 고정하는 스타일 앵커이고, 편성은 병종군 앵커가 소유한다.
# 폭은 병종군 크기 위계를 우리가 선언한다 — 프롬프트에 크기를 맡기면 AI 가
# 지키지 않는다(도시 아이콘에서 실측 확인).
ARM = {
    1: ("a SINGLE foot soldier standing at the ready", "", 96, "s1_foot.png"),
    2: ("a SINGLE archer standing", "", 96, "s2_archer.png"),
    3: ("a SINGLE rider MOUNTED on one Han-dynasty horse",
        "The man is on horseback -- not standing on foot.", 140, "s3_cavalry.png"),
    4: ("a SINGLE masked shaman in flowing dark robes", "", 96, "s4_occult.png"),
    5: ("ONE large wooden war machine with 3 small crewmen around it",
        "The machine, not the men, is the subject.", 256, "a5_siege.png"),
}

TIER = {
    1: "ragged conscript militia, mismatched gear, worn colours",
    2: "retainer troops of a noble house, uniform but plain",
    3: "elite guard troops, crisp uniform, bright trim, disciplined stance",
}

# 변형 축(진형 등)은 아직 제품에 없다. 값이 생기면 여기에만 추가한다.
VARIANTS = ("byeongsa", "gisu", "bundaejang")   # 병사 / 기수 / 분대장
# 자세 축(2026-08-22 사용자 확정). 병사만 전투 자세를 진다 — 기수는 깃발을 들고 있고
# 분대장은 부대에 한 명뿐이라 공격·방어 자세의 소비처가 없다. 대신 셋 다 쓰러진다.
POSES = {
    "idle": "POSE: standing still at the ready, weight even on both feet, weapon held ready but not swinging.",
    "move": "POSE: walking forward at a steady march, one leg forward in mid-stride, weapon shouldered or carried low.",
    "run": "POSE: running hard in a charge, body leaning forward, both legs far apart in mid-stride.",
    "attack": "POSE: mid-attack, the weapon committed forward in a full thrust or swing, body twisted into the blow.",
    "defend": "POSE: braced in a defensive guard, crouched low behind his cover, weapon drawn back close to the body.",
    "hit": "POSE: recoiling from a blow, head snapped back, body twisted off balance, arms flung wide.",
    "death": "POSE: collapsing, knees buckled and body falling sideways toward the ground, weapon slipping from the hand.",
}
POSES_BY_VARIANT = {
    "byeongsa": ("idle", "move", "run", "attack", "defend", "hit", "death"),
    "gisu": ("idle", "move", "run", "death"),
    "bundaejang": ("idle", "move", "run", "death"),
    "unit": ("idle",),
}
# 말 위에서는 같은 이름의 자세가 다른 그림이다.
POSES_MOUNTED = {
    "move": "POSE: the horse walking forward at a steady pace, the rider upright in the saddle.",
    "run": "POSE: the horse at full gallop, all four legs stretched out, the rider leaning low over its neck.",
    "hit": "POSE: the rider recoiling from a blow, thrown back in the saddle, the horse rearing slightly.",
    "death": "POSE: the rider slumping and falling sideways out of the saddle, the horse stumbling under him.",
}
MACHINE_ROLES = {"NAVY_CAPITAL", "NAVY_LINE", "NAVY_LIGHT", "CHARIOT", "LOGISTICS", "BEAST"}

# role -> 편성 묘사 덮어쓰기. armType 만 보면 틀리는 병종이 있다 — 수군 7종은 armType 1(보병)
# 이라 "두 열로 선 보병"이 되고, 맹수부대는 armType 3(기병)이라 기마병이 되며, 전차는
# armType 5(차병)라 공성기계가 된다. role 은 사람이 적은 authored 필드이므로 이게 정본이다.
ROLE_FORM = {
    "NAVY_CAPITAL": ("ONE large multi-decked Han-dynasty river warship (樓船 tower ship) seen from the side-front, "
                     "with tiered wooden decks, a mast, and soldiers manning the rails", 256),
    "NAVY_LINE": ("ONE armoured Han-dynasty war-boat with a covered hull and oar banks, soldiers at the rails", 248),
    "NAVY_LIGHT": ("TWO small fast Han-dynasty river skiffs with rowers and a few soldiers aboard", 224),
    "BEAST": ("a pack of war beasts -- tigers and leopards straining forward -- with 2 handlers holding chains", 232),
    "CHARIOT": ("TWO horse-drawn Han-dynasty war chariots side by side, each with a driver and a fighter, "
                "spoked wheels and a low railed car", 256),
    "LOGISTICS": ("a baggage train of 3 ox-drawn supply carts with drovers walking alongside", 240),
}

# 이름이 지고 있는 정체성. armType/조성만으로는 지워진다 — 象兵은 말이 아니라 코끼리를 타고,
# 白馬義從은 이름 그대로 흰 말이다(2026-08-21 사용자 지적). 이름/한자가 근거이므로 지어낸 게 아니다.
IDENTITY = {
    2116: ("a troop of 4 WAR ELEPHANTS in a loose staggered line facing lower-right, each with a mahout on "
           "the neck and two spearmen in a howdah platform on its back",
           "Large tusked elephants -- absolutely no horses."),
    2117: "Every horse is PURE WHITE -- this unit is named for its white horses (白馬義從).",
    2115: "Their armour is woven rattan (藤甲), pale straw-coloured and bulky, not metal.",
    2195: "The bows are ASYMMETRIC -- short below the grip and long above it (木弓短下長上), "
          "plain wood, with bamboo arrows.",
    2154: "The transport is a wooden ox-shaped hand-cart (木牛流馬), not a live animal.",
    2106: "They are shamanic warrior-priests (巫), in feathered headdress and painted markings, not regular soldiers.",
}

# 이름의 한자가 색·표식을 직접 말하는 병종. 黃巾은 이름 자체가 "누런 두건"이라
# 노란 두건이 없으면 그 병종이 아니다(2026-08-21 사용자 지적). 근거는 units.json 의 han 한자.
COLOR = {
    2161: "EVERY man wears a bright YELLOW head-cloth tied around his brow -- 黃巾 means 'yellow turban'. "
          "Ragged peasant rebels, farm tools and looted weapons, no uniform armour.",
    2162: "Ragged Yellow-Turban remnant rebels from the Baibo valley -- pale off-white head-cloths, "
          "mismatched looted gear, no uniform armour.",
    2163: "Ragged mountain-bandit rebels of the Black Mountain -- dark grey and black head-cloths and "
          "hide wraps, mismatched looted gear, no uniform armour.",
    2120: "Their lamellar is lacquered BRIGHT RED overall -- 赤甲 means 'red armour'.",
    2131: "WHITE feather-and-yak-tail tassels crown every helmet -- 白毦 means 'white plume'.",
    2202: "Refugee troops from Nanyang and the Three Adjuncts resettled in Shu -- northern-style gear "
          "that does not match the local Yi province troops, worn and scavenged, a hard predatory bearing.",
    2127: "Former Yellow-Turban rebels reformed into line troops: plain issued armour, but a few still "
          "keep a faded yellow cloth knotted at the waist.",
    1106: "WHITE feather-and-yak-tail tassels crown every helmet (白毦).",
    1301: "Every horse is PURE WHITE (白馬).",
    1402: "Their ritual robes are WHITE.",
    1403: "Their ritual robes are BLACK.",
    1405: "Their ritual robes are deep INDIGO BLUE.",
    1406: "Their ritual robes are YELLOW.",
}

# 복식 전체를 규정하는 COLOR 항목. 일반 한군 복장 문장을 덧붙이면 "no uniform armour" 와 모순난다.
COLOR_IS_FULL_DRESS = {2161, 2162, 2163}

# 이름 앞머리가 가리키는 비한족 복식. 한군 복장을 그대로 입히면 46종 REGIONAL 이 전부
# 같은 모습이 된다. 출전은 units.json _meta.sources 의 三國志·後漢書 東夷/西羌/烏桓鮮卑傳.
ETHNIC = (
    ("강족", "Qiang highland tribesmen -- felt and hide clothing, braided hair, no Han uniform"),
    ("선비", "Xianbei steppe riders -- fur-trimmed coats, leather boots, topknots, no Han uniform"),
    ("오환", "Wuhuan steppe riders -- shaved-side hair, fur and hide dress, no Han uniform"),
    ("흉노", "Xiongnu steppe warriors -- heavy fur coats, pointed caps, no Han uniform"),
    ("휴도", "Xiutu steppe riders -- fur and hide dress, no Han uniform"),
    ("남흉노", "Southern Xiongnu riders -- fur coats and pointed caps, no Han uniform"),
    ("산월", "Shanyue southern hill people -- bare-armed, short tunics, straw and bamboo gear"),
    ("판순만", "Banshun Man southern tribesmen -- wooden shields, bare arms, feathered ornaments"),
    ("상림만", "Xianglin southern tribesmen -- tropical dress, bare arms, cane shields"),
    ("무릉만", "Wuling Man of the Five Streams -- bare-armed southern hill tribesmen, hemp wraps, "
              "feathered and beaded ornaments, faces and arms tattooed"),
    ("오계만", "Wuxi Man of the Five Streams -- bare-armed southern hill tribesmen, hemp wraps, "
              "feathered ornaments, tattooed arms"),
    ("장가이", "Zangke Yi of the far south -- bare-armed, patterned woven cloth, bone and shell ornaments"),
    ("월수", "Yuexi Sou highlanders of Nanzhong -- rough hide and hemp, braided hair, bare feet"),
    ("애뢰", "Ailao of Yongchang -- tropical dress, pierced noses and stretched earlobes, "
            "patterned dyed cloth, bare arms"),
    ("오호만", "Wuhu Man of Yulin in the far south -- bare-chested, plain loincloths and hemp wraps, "
              "cane ornaments"),
    ("남중", "Nanzhong southern highlanders -- hide and hemp dress, feathered ornaments, bare arms"),
    # 倭 — 三國志 卷30 魏書 東夷傳 倭人條가 복식을 직접 적었다. 그대로 옮긴다:
    #   「男子皆露紒，以木緜招頭」 관을 쓰지 않고 상투를 드러내며 나무껍질 천으로 머리를 동인다
    #   「男子無大小皆黥面文身」 남자는 어른아이 없이 얼굴과 몸에 문신한다
    #   「其衣橫幅，但結束相連，略無縫」 옷은 가로폭 천을 묶어 이었을 뿐 바느질이 거의 없다
    #   「皆徒跣」 모두 맨발이다
    # 주의: 흔히 떠올리는 미즈라(角髪, 양쪽으로 묶어 늘어뜨린 머리)는 5~6세기 고분시대다.
    # 3세기 왜인에게 씌우면 시대 착오다 — 사료가 말하는 것은 露紒(드러낸 상투) 하나뿐이다.
    ("왜인", "Wa islanders of the 3rd century -- NO headgear at all; the hair is gathered into a bare "
            "exposed topknot bound with a band of pale bark-fibre cloth across the brow. Faces and "
            "bodies are tattooed with dark curved lines. Clothing is a single width of undyed cloth "
            "wrapped and knotted, essentially unsewn, no Chinese robes and no armour. All barefoot. "
            "Do NOT use the later Kofun-era side-looped mizura hairstyle"),
    ("마한", "Mahan peninsular tribesmen -- white hemp robes, feathered headgear"),
    ("변진", "Byeonjin peninsular warriors -- iron-plate armour, plumed helmets"),
    ("진한", "Jinhan peninsular foot troops -- hemp tunics, plain gear"),
    ("낙랑", "Lelang commandery troops -- mixed Han and peninsular dress"),
    ("옥저", "Okjeo peninsular troops -- hide and hemp dress"),
    ("부여", "Buyeo northern riders -- fur robes and leather caps"),
    ("고구려", "Goguryeo warriors -- plumed hoods, patterned tunics, lamellar"),
    ("읍루", "Yilou northern forest people -- fur pelts, bone ornaments"),
    ("맥궁", "northern forest archers -- fur dress, short horn bows"),
    ("수족", "southwestern tribal archers -- woven cloth and cane gear"),
    ("고구려 산기병", "Goguryeo mounted skirmishers -- plumed hoods and lamellar"),
)


# role 덮어쓰기가 걸린 병종은 편성 자체가 달라서 c3 보병 앵커를 붙이면 스타일이 아니라
# 배치를 베낀다. role 전용 앵커를 따로 둔다.
ANCHOR_FOR_ROLE = {
    "NAVY_CAPITAL": "a_navy.png",
    "NAVY_LINE": "a_navy.png",
    "NAVY_LIGHT": "a_navy.png",
    "BEAST": "a_beast.png",
    "CHARIOT": "a_chariot.png",
    "LOGISTICS": "a_chariot.png",
}


def ethnic_note(name: str) -> str | None:
    """이름 앞머리로 비한족 복식을 고른다. 가장 긴 접두어가 이긴다."""
    hit = [(k, v) for k, v in ETHNIC if name.startswith(k) or k in name]
    if not hit:
        return None
    k, v = max(hit, key=lambda kv: len(kv[0]))
    return f"These are NOT Han regulars: {v}."


CORE = (
    "Pixel art isometric game unit sprite for a turn-based strategy map. "
    "Camera: 2:1 isometric, looking down-forward, exactly matching the camera of the attached reference sprite. "
    "This image is a HORIZONTAL ANIMATION STRIP of ONE single character, drawn once per pose, evenly "
    "spaced left to right on one row with clear empty background between frames and no separator lines, "
    "no frame borders, no grid. It is the SAME man in every frame: identical face, identical helmet, "
    "identical armour, identical colours, identical body proportions, identical pixel scale. "
    "Every frame faces the SAME way -- toward the LOWER-RIGHT, in three-quarter view. Never mirror a "
    "frame, never turn one frame to face left, never change the camera between frames. "
    "Rendering: crisp limited-palette pixel art, clean 1px dark outline, flat cel shading lit from the upper-left, "
    "no anti-aliased blur, no gradients, no text, no logos, no cast shadow beyond small contact shading. "
    "ANATOMY, strictly enforced: every human figure has exactly ONE head, TWO arms and TWO hands, and "
    "holds no more objects than two hands can hold. Every weapon, bow, quiver, shield and banner in the "
    "image belongs to a FULLY DRAWN, fully visible man -- no floating gear, no equipment without a body, "
    "no extra arms, no half-drawn or invisible figures. "
    "Background: FLAT SOLID MAGENTA #FF00FF, absolutely uniform, no glow, no vignette, no gradient."
)


def slug(name: str) -> str:
    s = unicodedata.normalize("NFC", name)
    s = re.sub(r"\s+", "-", s.strip())
    return re.sub(r"[^0-9A-Za-z가-힣_-]", "", s)


# 손 배정. "무기 오른손 · 방패 왼손"은 사용자 확정 규칙이지만 그대로 쓰면 궁병과 기병이
# 틀린다 — 활은 왼손으로 들고 오른손으로 시위를 당기고, 기병은 왼손이 고삐라 양손 쥐기가
# 아예 불가능하다(2026-08-21 사용자 지적). 병종이 규칙을 좁힌다.
BOW = {"궁", "단궁", "목궁", "강궁"}
CROSSBOW = {"노", "연노"}
THROWN = {"투부", "표창", "독시"}


def hand_rule(c: dict, comp: dict) -> str:
    w = comp.get("weapon") or ""
    mounted = c["armType"] == 3
    shield = bool(comp.get("shield"))
    if w in CROSSBOW:
        return ("The crossbow is shouldered with BOTH hands -- left hand under the stock, right hand at "
                "the trigger. No third hand appears.")
    if w in BOW:
        if mounted:
            return ("Horse archery: the bow is held out in the LEFT hand and the string is drawn with the "
                    "RIGHT hand, while the reins lie loose on the horse's neck. Exactly two hands.")
        return ("The bow is held out at arm's length in the LEFT hand and the string is drawn back with "
                "the RIGHT hand. Exactly two hands -- no spare hand.")
    if mounted:
        base = ("Each rider keeps the REINS in his LEFT hand and his weapon in his RIGHT hand. Nobody "
                "grips a weapon with both hands on horseback.")
        return (base + " His shield is strapped to that same left forearm, so the rein hand still shows."
                if shield else base)
    if shield:
        return ("Each fighter holds the shield on his LEFT arm and the weapon in his RIGHT hand, in a "
                "ONE-HANDED grip with the gripping hand clearly visible. No two-handed grip.")
    if w in THROWN:
        return ("Each man throws with the RIGHT hand and keeps spare shafts in the LEFT hand. "
                "Exactly two hands.")
    return ("Each man holds his weapon in the RIGHT hand; having no shield, the left hand is free and "
            "may also grip the weapon in a two-handed stance. Exactly two hands.")


def tier_of(c: dict) -> int:
    """che 세트는 frozen 사본이라 tier 가 없다. 있으면 그걸 쓰고, 없으면 징병비로 가른다
    (che 보병 8 ~ 호표기병 14). 스프라이트 화려함 축일 뿐 게임 수치가 아니다."""
    if c.get("tier"):
        return c["tier"]
    cost = c.get("cost") or 0
    return 1 if cost <= 8 else (2 if cost <= 12 else 3)


def is_machine(c: dict) -> bool:
    """배·전차·치중·맹수·공성기계는 사람 역할(병사/기수/분대장)이 없다 — 한 장이 곧 그 유닛이다."""
    return c["armType"] == 5 or (c.get("role") or "") in MACHINE_ROLES


def variants_for(c: dict) -> tuple[str, ...]:
    return ("unit",) if is_machine(c) else VARIANTS


def figure_height(c: dict) -> int:
    return ROLE_FORM[c["role"]][1] if c.get("role") in ROLE_FORM else ARM[c["armType"]][2]


# 역할별 덧문장. 같은 병종의 세 장은 장비·복식이 같아야 한 부대로 읽힌다 —
# 다른 것은 계급 표식과 손에 든 것뿐이다.
ROLE_NOTE = {
    "byeongsa": "He is a rank-and-file soldier of this unit -- plain issued gear, no rank marks.",
    "gisu": ("He is the STANDARD BEARER of this unit. He carries NO weapon and NO shield -- only a tall "
             "banner pole with a narrow pennant, and the pole is BOLT UPRIGHT and perfectly VERTICAL. "
             "His clothing and armour are exactly the same as the unit's soldiers."),
    "bundaejang": ("He is the SQUAD LEADER of this unit. Same troop type and same clothing as his "
                   "soldiers, but marked by rank: a plume or crest on the helmet, a coloured sash at the "
                   "waist, and better-finished fittings. He stands in a commanding stance with his weapon "
                   "raised."),
}


def prompt_for(c: dict, variant: str) -> str:
    comp = c.get("composition") or {}
    formation, contrast, _, _ = ARM[c["armType"]]
    override = ROLE_FORM.get(c.get("role"))
    if override:
        formation, contrast = override[0], ""
    ident = IDENTITY.get(c["id"])
    if isinstance(ident, tuple):
        formation, contrast = ident
        ident = None

    if variant == "unit":
        bits = [f"Subject: {formation}."]
        if contrast:
            bits.append(contrast)
        bits.append(f"Troop quality: {TIER[tier_of(c)]}.")
        if comp.get("weapon") and c["armType"] == 5:
            bits.append(f"The machine is a {WEAPON[comp['weapon']]}.")
        role = c.get("role") or ""
        if role in ("NAVY_CAPITAL", "NAVY_LINE", "NAVY_LIGHT"):
            bits.append("A small banner flies from the vessel's mast.")
        elif role == "LOGISTICS":
            bits.append("A small banner is lashed upright to one of the carts.")
        elif role == "CHARIOT":
            bits.append("A banner pole is SOCKETED UPRIGHT in the car -- no man holds it.")
        elif c["armType"] == 5:
            bits.append("A small banner flies on the machine itself.")
    else:
        bits = [f"Subject: {formation}, facing lower-right, about {figure_height(c)} pixels tall "
                f"in every frame."]
        if contrast:
            bits.append(contrast)
        bits.append(f"Troop quality: {TIER[tier_of(c)]}.")
        bits.append(ROLE_NOTE[variant])
        if variant == "gisu":
            # 기수는 무기·방패를 들지 않는다. 손 규칙은 탈것만 남는다.
            if c["armType"] == 3:
                bits.append("He keeps the reins in his LEFT hand and the banner pole in his RIGHT hand.")
            else:
                bits.append("He grips the banner pole with BOTH hands. Exactly two hands.")
        elif comp.get("weapon"):
            bits.append(f"He is armed with a {WEAPON[comp['weapon']]}. "
                        f"{hand_rule(c, comp)}".strip())
        if comp.get("armor"):
            bits.append(f"He wears {ARMOR[comp['armor']]}.")
        if comp.get("shield") and variant != "gisu":
            bits.append(
                f"He carries {SHIELD[comp['shield']]}, strapped over the LEFT forearm on the LEFT side "
                "of the body, with the left arm bent across the chest. The weapon is on the OPPOSITE "
                "side of the body, held clearly away from the torso. Shield and weapon are NEVER held "
                "by the same hand, and the blade never touches, overlaps, or emerges from behind the "
                "shield. The shield has a visible arm behind it."
            )

    if variant != "unit":
        poses = POSES_BY_VARIANT[variant]
        frames = [(POSES_MOUNTED[q] if c["armType"] == 3 and q in POSES_MOUNTED else POSES[q])
                  for q in poses]
        bits.append(f"The strip has EXACTLY {len(frames)} frames, in this order left to right: "
                    + " ".join(f"Frame {i}: {t.removeprefix('POSE: ')}"
                               for i, t in enumerate(frames, 1)))
    if ident:
        bits.append(ident)
    color = COLOR.get(c["id"])
    if color:
        bits.append(color)
    ethnic = ethnic_note(c["name"])
    if c["id"] in COLOR_IS_FULL_DRESS:
        pass
    elif ethnic:
        bits.append(ethnic)
    else:
        bits.append(
            "Han-dynasty ancient Chinese military dress: dark red and ochre tunics, bronze fittings, "
            "black lacquered helmets or cloth headwraps."
        )
    return CORE + " " + " ".join(bits)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--units", default="data/unitset/units.json")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--set", default="han")
    ap.add_argument("--include-unmapped-che", action="store_true",
                    help="han 대응이 없는 che 병종까지 포함해 통일 세트를 만든다")
    args = ap.parse_args()

    data = json.loads(pathlib.Path(args.units).read_text(encoding="utf-8"))
    # armType 0 = 성벽. 도시 아이콘이 그 역할을 하므로 유닛 스프라이트를 만들지 않는다.
    crew = [c for c in data["crewTypes"] if c["set"] == args.set and c["armType"] != 0]
    if args.set == "han" and args.include_unmapped_che:
        # 통일 유닛 체계: che 34종 중 27종은 equivalents 로 han 스프라이트를 그대로 쓴다.
        # han 대응이 null 인 귀병 7종만 자기 스프라이트가 필요하다.
        mapped = {e["che"] for e in data["equivalents"] if e.get("han") is not None}
        crew += [c for c in data["crewTypes"]
                 if c["set"] == "che" and c["armType"] != 0 and c["id"] not in mapped]

    out = pathlib.Path(args.out_dir)
    (out / "prompts").mkdir(parents=True, exist_ok=True)
    plan = []
    for c in crew:
        # 사람 병종은 병사/기수/분대장 3장, 기계·배는 unit 1장.
        for variant in variants_for(c):
            key = f"{c['id']}_{slug(c['name'])}__{variant}"
            (out / "prompts" / f"{key}.txt").write_text(prompt_for(c, variant), encoding="utf-8")
            plan.append({
                "key": key,
                "variant": variant,
                "poses": list(POSES_BY_VARIANT[variant]),
                "id": c["id"],
                "name": c["name"],
                "armType": c["armType"],
                "tier": tier_of(c),
                "cell": figure_height(c),
                "anchor": ANCHOR_FOR_ROLE.get(c.get("role"), ARM[c["armType"]][3]),
            })
    (out / "plan.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{len(plan)} prompts -> {out/'prompts'}")


if __name__ == "__main__":
    main()
