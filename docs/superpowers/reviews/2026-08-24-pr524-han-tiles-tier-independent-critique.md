# PR #524 독립 비평 — han-tiles TIER 재판정 (`b87e663a`)

Scope: `work/opensamguk/tiles-tier-524` — 1차 `b87e663a`, 재심 `511fa08d`, 2차 재심 `ebbec0ac`(base `origin/main`). classify() 재판정 로직, `build_tile_grid.py` 독음 누락 가드, 테스트 43종, 재생성된 `data/map/han-tiles.json` 산출물, 커밋 경계, docstring 사실 주장

Verdict: cleared

---

# 2차 재심 (2026-08-24, HEAD `ebbec0ac`) — 가드 구멍 종결

지시받은 4건만 봤다. **전부 통과. Verdict 를 cleared 로 올린다.**

## 1. juns 전용 이름 RED — 6개로 재현, 우연 아니다

`河閒國` 하나가 아니라 22개 중 6개를 골라 직접 냈다(`READINGS` 를 임시 사본으로 바꿔치기, 산출물 미기록):

```
'下邳國'(하비국)   -> exit1  readings.json 에 없는 지명 1개: ['下邳國']…
'中山國'(중산국)   -> exit1
'九江郡'(구강군)   -> exit1
'北海國'(북해국)   -> exit1
'右北平郡'(우북평군) -> exit1
'太原郡'(태원군)   -> exit1
'東沃沮'(동옥저)   -> exit1   ← cities 경로 (회귀 없음)
'黄土高原'(황토고원) -> exit1   ← regions 경로 (회귀 없음)

원복 GREEN: exit0, juns 174 · cities 1145 · name==nameCh 인 엔트리 0건
```

1차 재심에서 `BUILD OK` 로 새어나가던 이름들이 전부 exit 1 로 뒤집혔다. cities·regions 경로도 그대로 산다.

## 2. 세 호출부 전부 가드보다 앞선다 — 늦은 평가 잔존 없음

```
L91  regions.append({"name": kr(zh), ...})
L99  cities = [... kr(p["nameFt"]) ...]
L115 juns   = [... kr(nm) ...]        ← 이번에 리터럴 밖으로 나옴
L120 if READINGS.exists() and misses: sys.exit(...)
L123 return { ... "juns": juns ... }
```

`kr(` 호출은 이 셋이 전부고 전원 L120 위다. `return` 리터럴을 훑어봐도 `kr()` 도 지연 평가되는 자리도 남아 있지 않다 — `_meta.counts` 는 이미 만들어진 리스트의 `len()`, 나머지는 `grid` 딕셔너리 직접 참조다. 같은 실수의 재발분은 없다.

수정 자체도 최소다: 리스트 계산 위치만 옮겼고 로직·주석 의미 변화 0.

## 3. 커밋 경계 — 통과

```
$ git show --stat ebbec0ac
 tools/map/build_tile_grid.py | 11 +++++++----
 1 file changed, 7 insertions(+), 4 deletions(-)
```

**1개 파일뿐.** `data/map/han-tiles.json`·`readings.json`·`terrain-grid.json`·`han-places.json` 전부 미포함.

## 4. 파편화된 재빌드본이 섞여 들어가지 않았다 (핵심)

구현자 GREEN 로그의 `adjCounty 1230 · adjCommandery 366` 은 커밋된 산출물(2662/425)과 다른 파편화 재빌드본이 맞다. 그게 커밋에 들어갔는지 blob 해시로 직접 확인했다:

```
$ git rev-parse 511fa08d:data/map/han-tiles.json
3ebef25f2c206b99ac802e58b6dfcf5aadcce8db
$ git rev-parse ebbec0ac:data/map/han-tiles.json
3ebef25f2c206b99ac802e58b6dfcf5aadcce8db
$ git diff --stat 511fa08d ebbec0ac -- data/map/han-tiles.json
(출력 없음)
```

**바이트 동일.** 오염본은 커밋에 안 들어갔고, 브랜치 tip 의 `han-tiles.json` 은 재심에서 이미 검증한 `511fa08d` 것 그대로다. 인접 그래프 2662↔1230 재현성 문제는 #536 소관이라 원인은 파지 않았다 — **다만 이 PR 이 그 문제를 산출물로 실어나르지는 않는다**는 것만 확인했다.

## 남는 것 (블로커 아님)

가드는 여전히 `READINGS.exists()` 일 때만 발화한다 — `readings.json` 이 **통째로 없으면** L63-66 의 stderr 경고만 찍히고 exit 0 으로 전 지명이 한자로 나간다. 1차 리뷰 때부터 있던 의도된 폴백이고 이번 수정 범위 밖이다. 그리고 원래의 구조적 위험(커밋되는 산출물 vs 미커밋 입력, CI 드리프트 검사 부재)도 그대로다 — 별도 티켓 감이다.

---

# 재심 (2026-08-24, HEAD `511fa08d`)

3건 중 **2건 완전 종결(MEDIUM-1, MEDIUM-2), 1건 부분 종결(HIGH-1)**. HIGH-1 이 지적한 **산출물 오염은 실제로 완전히 사라졌다** — 그러나 재발을 막으려고 새로 넣은 가드에 **도달 가능한 구멍**이 있다. 그 하나 때문에 fix-required 를 유지한다. 고치는 데는 한 줄이면 된다.

## 새 결함 — 독음 누락 가드가 `juns[]` 를 안 덮는다 (MEDIUM, 신규)

`build_tile_grid.py` 의 `kr()` 를 단일 깔때기로 만들고 `misses` 를 모으는 설계는 옳다 — 호출부 세 곳(`regions` L91, `cities` L99, `juns` L138)이 전부 이 함수를 지난다. 문제는 **가드의 위치**다:

- L111 `if READINGS.exists() and misses: sys.exit(...)`
- L138 `"juns": [{"name": kr(nm), ...}]` — **`return` dict 리터럴 안이라 L111 보다 나중에 평가된다.**

즉 `juns` 가 만드는 miss 는 `misses` 에 담기지만 **이미 검사를 지나간 뒤**다. 구현자가 RED/GREEN 을 확인한 `東沃沮` 는 `cities` 에도 있어서 L99 에서 걸렸을 뿐이다.

이건 이론적 구멍이 아니다. `junNames` 174개 중 **22개는 `cities`/`regions` 어디에도 없다**:

```
$ python3 -c "
import json
g=json.load(open('data/map/terrain-grid.json')); p=json.load(open('data/map/han-places.json'))['places']
jn=set(g['junNames']); cn={x['nameFt'] for x in p}; rn={(n['zh'] or n['name']) for n in g['regionNames']}
o=sorted(jn-cn-rn); print(len(o), o[:15])"
22 ['下邳國', '中山國', '九江郡', '北海國', '右北平郡', '太原郡', '安平國', '常山國', '弘農郡', '梁國', '樂安國', '沛國', '河閒國', '清河國', '琅邪國']
```

실측 재현 — `READINGS` 를 임시 사본으로 바꿔치기하고 이름 하나씩 지워 `build()` 를 부른다(산출물 미기록):

```
cities 에도 있는 이름 (구현자가 검증한 케이스):
  '東沃沮' (독음 '동옥저') -> BUILD FAILED: readings.json 에 없는 지명 1개: ['東沃沮']…
juns 에만 있는 이름:
  '河閒國' (독음 '하간국') -> BUILD OK (가드 통과), 산출물: {'name': '河閒國', 'nameCh': '河閒國', ...}
  '安平國' (독음 '안평국') -> BUILD OK (가드 통과), 산출물: {'name': '安平國', 'nameCh': '安平國', ...}
  '中山國' (독음 '중산국') -> BUILD OK (가드 통과), 산출물: {'name': '中山國', 'nameCh': '中山國', ...}
  '太原郡' (독음 '태원군') -> BUILD OK (가드 통과), 산출물: {'name': '太原郡', 'nameCh': '太原郡', ...}
```

**`name` 이 한자로 새어나가고 exit 0 이다** — HIGH-1 이 지적한 바로 그 실패 양태가, 가드가 있는 상태에서 22개 이름에 대해 그대로 살아 있다. 하필 그 22개에 `河閒國`·`安平國`·`沛國`·`清河國` — **#531 이 건드릴 이름들** — 이 들어 있다.

**고치는 법(한 줄):** `juns` 리스트를 `return` 리터럴 밖으로 꺼내 가드 **위에서** 계산하면 끝이다. 새 추상화도 새 검사도 필요 없다.

## 가드가 CI 에서 도는가 — 안 돈다 (LOW, 구조적)

`.github/workflows/ci.yml:27` 이 도는 것은 `python3 -m unittest discover -s tools/map/tests` 뿐이고, **`build_tile_grid.py` 를 부르는 테스트는 없다**(`tools/map/tests/` 는 overlay·tier·junguozhi 셋뿐). 가드는 사람이 로컬에서 빌드할 때만 발화한다.

다만 이건 #534/#521 계보의 "게이트는 있는데 아무도 안 부른다" 와는 성질이 다르다 — 가드가 서 있는 자리가 **오염된 산출물이 만들어지는 바로 그 순간**이라, 빌드가 죽으면 애초에 커밋할 파일이 안 생긴다. 게이트로서는 위치가 맞다.

남는 구조적 위험은 원래 것 그대로다: **`han-tiles.json` 은 커밋되는데 그 입력(`readings.json`·`han-places.json`·`terrain-grid.json`)은 커밋되지 않는다**(ADR-LITE-039/040). 커밋된 산출물이 생성기와 동기인지 확인하는 드리프트 검사가 CI 에 없다. 실제로 1차 리뷰에서 드러났듯 `main` 의 `han-tiles.json` 은 #507 이후 재생성되지 않은 채 stale 했다. 가드는 그 위험을 **탐지**할 뿐 없애지 못한다 — 별도 티켓 감이지 이 PR 의 블로커는 아니다.

## HIGH-1 산출물 — 완전히 복구됐다 (종결)

`origin/main` vs `511fa08d` 직접 대조:

```
cities 1144 -> 1145   added ['33425'] removed []
cities name 변경: 0    kind 변경: 20
juns   175 -> 174     removed ['新成侯國']  added []   juns name 변경: 0
name == nameCh 인 엔트리:  cities 0건, juns 0건
_meta counts: {'cities': 1145, 'seats': 174, ..., 'COUNTY': 962, 'EXTERNAL_PLACE': 37,
               'COMMANDERY': 126, 'KINGDOM': 17, 'PROVINCE': 3}
```

한자 역행 70건은 사라졌고, **한자로 남은 `name` 이 단 한 건도 없다**. 남은 변경은 #524 가 의도한 kind 20건 + 신규 `彭城国` 1건 + `新成侯國` juns 제거 1건뿐 — 1차 리뷰에서 이미 정당함을 확인한 것들이고 분포도 그대로다. 우연히 맞은 게 아니라 `readings.json` 이 온전해진 결과다.

## MEDIUM-1 — 종결

구현자 주장을 받지 않고 직접 뮤테이션했다:

```
--- MEDIUM-1 뮤테이션: (g['canonicalGroup'], g.get('sourceGroupName')) -> (g['canonicalGroup'],) ---
FAILED (failures=2, skipped=10)
--- 원복 후 ---
OK (skipped=10)
build_han_places.py 무변경
```

이제 RED 다. 테스트 43개(1차 42개 + `test_archaic_variant_character_group_name_still_matches`), skipped 10 은 변함없이 junguozhi 코퍼스 미보유분.

## MEDIUM-2 — 종결, 정정된 숫자도 실측과 일치

네 자리(모듈 docstring, 인라인 주석, 테스트 파일 docstring, 테스트 클래스 docstring) 전부 정정됐다. `grep -rn "그 해만\|10개\|잠깐 강등" tools/map/` 의 잔존 2건은 **정정문 자체**(“…그 해만의 일시적 오류가 아니다”, “10개가 아니라 11개다”)이지 남은 거짓 주장이 아니다.

정정된 값을 CHGIS dbf 로 다시 쟀다:

```
  常山郡  TYPE_CH=郡   220-582  (span=362년)      ← docstring 예시와 일치
  彭城国  TYPE_CH=侯国  88-323   (span=235년)      ← 테스트 docstring 예시와 일치
  赵郡 213-231 · 中山郡 174-231 · 齐郡 206-264 · 北海郡 206-231 · 琅邪郡 216-265
  梁郡 220-382 · 陈郡 197-266 · 下邳郡 206-323 · 河间郡 220-264

TYPE_CH=郡|侯国 인데 KINGDOM 으로 복구된 실측 전량: 11
  ['下邳郡','中山郡','北海郡','常山郡','彭城国','梁郡','河间郡','琅邪郡','赵郡','陈郡','齐郡']
```

**11개**, 목록 일치, 구간 일치. `build_han_places.py:85` 의 樂安 인라인 주석도 “樂安은 TYPE_CH='国'이지만 정본 카탈로그상 KINGDOM이라 이 목록엔 안 든다”로 고쳐져 같은 파일 docstring·테스트·산출물과 더는 모순되지 않는다.

## 커밋 경계 — 통과

```
$ git show --stat 511fa08d
 data/map/han-tiles.json                                   |  2 +-
 tools/map/build_han_places.py                             | 17 ++++++------
 tools/map/build_tile_grid.py                              | 13 ++++++++++-
 tools/map/tests/test_han_places_tier_classification.py    | 26 +++++++++++++-----
```

`readings.json`·`data/chgis-source/`·`han-places.json`·`terrain-grid.json`·`external-places.json` **미포함**. `git status --short --branch` 는 이 리뷰 문서(untracked) 외에 깨끗하다.

## LOW-1/LOW-2 (1차) — 변동 없음

`_load_group_kind` 의 조용한 `return {}` 와 등가 뮤테이션 4종(`尹`·`典农校尉`·`GROUP_SUFFIX` 간체 `国`·`县縣`)은 그대로다. 둘 다 데이터 영향 0 이고 이번 재심의 블로커가 아니다.

---

# 1차 리뷰 (`b87e663a`) — 이하 원문 보존

#524 의 핵심 수정(정본 카탈로그 override)은 **로직·산술·테스트 모두 독립 실측으로 확인됐다**. 그러나 같은 커밋이 `data/map/han-tiles.json` 에 **#524 와 무관한 표기 회귀 70건**(cities 38 + juns 32)을 함께 실었고 커밋 메시지에 언급이 없다. 그것 하나 때문에 fix-required 다.

리뷰어는 코드를 수정하지 않았다. 모든 뮤테이션은 실행 직후 원본으로 복원했고 `git status` 는 깨끗하다(아래 재현 참고).

---

## HIGH-1 — 재생성이 한글 지명 70건을 한자로 되돌렸다 (미고지)

`han-tiles.json` 의 변경은 커밋 메시지가 말한 "kind 변경 20개 + 신규 1개"가 아니라 **cities 58건 변경 + 1건 신규**다. 20건은 kind/level, **나머지 38건은 `name` 필드가 한글 독음 → 한자로 바뀐 것**이다. `juns[]` 에서도 같은 회귀가 32건 더 있다.

```
$ python3 - <<'EOF'   # 워크트리 루트에서
import json, subprocess
new=json.load(open('data/map/han-tiles.json'))
old=json.loads(subprocess.run(['git','show','b87e663a^:data/map/han-tiles.json'],capture_output=True,text=True).stdout)
N={c['id']:c for c in new['cities']}; O={c['id']:c for c in old['cities']}
from collections import Counter
nc=[(O[i],N[i]) for i in N.keys()&O.keys() if O[i]['name']!=N[i]['name']]
print('cities name 변경:',len(nc), Counter(o['kind'] for o,_ in nc))
Nj={j['nameCh']:j for j in new['juns']}; Oj={j['nameCh']:j for j in old['juns']}
print('juns name 변경:',len([k for k in Nj.keys()&Oj.keys() if Oj[k]['name']!=Nj[k]['name']]))
EOF
cities name 변경: 38 Counter({'EXTERNAL_PLACE': 37, 'COMMANDERY': 1})
juns name 변경: 32
```

실제 값(전후):

```
동옥저 -> 東沃沮 · 흉노 -> 南匈奴 · 저 -> 白馬氐 · 남만 -> 哀牢 · 강 -> 西羌
부여 -> 夫餘 · 백제국 -> 伯濟國 · 사로국 -> 斯盧國 · 대방군 -> 帶方郡 · 야마일국 -> 邪馬壹國 …(38건)
```

### 근본원인 — `readings.get(name, name)` 의 조용한 폴백 + `hanja` 미설치

`tools/map/build_tile_grid.py:69` 는 `readings.get(name, name)` 이다. 사전에 없는 이름은 **경고 없이** 한자 그대로 나간다(경고는 `readings.json` **파일 자체가 없을 때만** 찍힌다 — `build_tile_grid.py:63-66`).

그리고 로컬 `data/map/readings.json` 에는 정확히 그 38개가 없다:

```
$ python3 -c "
import json
p=json.load(open('data/map/han-places.json'))['places']
r=json.load(open('data/map/readings.json'))
m=[x['nameFt'] for x in p if x['nameFt'] not in r]
print('readings.json 에 없는 nameFt:', len(m)); print(m[:10])"
readings.json 에 없는 nameFt: 38
['一大國', '于山國', '伊都國', '伯濟國', '北沃沮', '卒本', '南匈奴', '古寧伽耶', '古資彌凍國', '召文國']
```

`readings.json` 은 재생성될 수 없었다 — 사전 생성기의 의존성이 이 환경에 없다:

```
$ python3 -c "import hanja"
ModuleNotFoundError: No module named 'hanja'
```

파일 mtime 이 순서를 그대로 보여준다: `readings.json` 04:05 → `han-places.json`/`external-places.json` 04:07 → `terrain-grid.json` 04:16 → `han-tiles.json` 04:18. 즉 **stale 한 `readings.json`(gitignored) 위에서 커밋 대상 산출물을 재생성**했고, 조용한 폴백이 그 결함을 커밋된 파일에 그대로 밀어넣었다.

이건 ADR-LITE-039/040 이 만드는 구조적 위험의 실물이다: 커밋되는 산출물의 입력이 커밋되지 않으므로, 입력이 불완전한 머신에서 재생성하면 산출물이 조용히 퇴화한다. #524 로직과는 무관하며, 되돌리려면 `readings.json` 을 온전히 재생성한 뒤 `build_tile_grid.py` 를 다시 돌려야 한다.

**권고(코드는 건드리지 않았다):** ① `readings.json` 재생성 후 `han-tiles.json` 재산출, ② `build_tile_grid.py` 의 `kr()` 가 미스를 카운트해 stderr 로 경고하거나 `--check` 에서 실패하게 만들 것 — 지금은 70건이 무음으로 통과한다.

---

## MEDIUM-1 — 잡히지 않는 뮤테이션: `sourceGroupName` 인덱싱을 빼면 河間國이 조용히 COMMANDERY 로 떨어진다

구현자가 돌린 2종 외에 10종을 직접 돌렸다. **`_load_group_kind` 가 `canonicalGroup` 만 인덱싱하도록 바꾸면 42개 테스트가 전부 GREEN 인데 산출 데이터는 바뀐다.**

```
$ # 뮤테이션: for name in (g['canonicalGroup'], g.get('sourceGroupName')):  ->  (g['canonicalGroup'],)
$ python3 -m unittest discover -s tools/map/tests 2>&1 | tail -1
OK (skipped=10)
```

데이터 영향(220년 CHGIS 전량 재분류, base 대비):

```
M5 canonical only    diffs=1 [('河间郡', ('KINGDOM', 6), ('COMMANDERY', 6))]
```

원인:

```
CATALOG: 河閒國 |src= 河間國 | KINGDOM
stem(河閒國)= 河閒   GROUP_KIND['河間']= KINGDOM   GROUP_KIND['河閒']= KINGDOM
```

정본 카탈로그의 `canonicalGroup` 은 **閒**(河閒國), CHGIS `NAME_FT` 는 **間**(河間郡)이다. 두 글자는 다른 코드포인트라 exact stem 매칭이 **오직 `sourceGroupName`('河間國') 덕분에만** 성립한다. 17개 KINGDOM 중 이 경로에 전적으로 의존하는 유일한 항목이고, **이를 핀하는 테스트가 없다.** `UndetectedKingdomsAreNowDetectedTest` 의 10개 목록에도 河間이 빠져 있어서다(→ MEDIUM-2).

**권고:** `classify('郡','河间郡','河間郡') == ('KINGDOM', 6)` 을 테스트에 추가. 한 줄이면 이 뮤테이션이 RED 로 바뀐다.

---

## MEDIUM-2 — docstring/커밋 메시지의 사실 주장 2건이 데이터와 어긋난다

주장(`build_han_places.py` docstring, 같은 파일 인라인 주석, 커밋 본문, `test_han_places_tier_classification.py:56` 에 네 번 반복):

> "常山/趙/中山/齊/北海/琅邪/梁/陳/下邳/彭城 같은 진짜 KINGDOM **10개**는 **220년 그 해만** TYPE_CH='郡'|'侯国'로 **잠깐** 강등돼 있어"

CHGIS dbf 원본 실측:

```
NAME      TYPE_CH BEG     END
常山郡        郡       220     582
梁郡         郡       220     382
下邳郡        郡       206     323
北海郡        郡       206     231
齐郡         郡       206     264
琅邪郡        郡       216     265
陈郡         郡       197     266
中山郡        郡       174     231
赵郡         郡       213     231
河间郡        郡       220     264
彭城国        侯国      88      323
```

**(a) "220년 그 해만 / 잠깐"은 거짓이다.** 220–582(363년), 88–323(236년) 같은 구간이며 220 은 그 안에 들어 있을 뿐이다. 결론("TYPE_CH 는 판정자가 아니다")은 여전히 옳지만, 그 근거로 제시된 서술은 데이터와 다르다.

**(b) 10개가 아니라 11개다.** `河間郡`(TYPE_CH='郡' → KINGDOM)이 실제로 복구된 목록에 있는데 네 곳 모두에서 빠져 있다. 그리고 그 누락이 MEDIUM-1 의 테스트 공백과 같은 뿌리다.

부수: 커밋 메시지의 "樂安/陳留가 실제로는 COMMANDERY" 인라인 주석(`build_han_places.py:83`)은 樂安에 대해 틀렸다 — 카탈로그·테스트·산출물 모두 樂安 = **KINGDOM** 이다(같은 파일 docstring 과 `test_commandery_misnamed_with_kingdom_type_code_is_corrected` 는 옳게 적혀 있다). 한 파일 안에서 서로 모순된다.

---

## LOW-1 — 카탈로그 로드 실패가 조용하다 (테스트 밖에서)

`_load_group_kind` 는 `if not os.path.exists(path): return {}` — **CWD 상대 경로**에 파일이 없으면 경고 없이 빈 dict 를 돌려주고 파이프라인은 수정 전 동작으로 되돌아간다. 다행히 테스트가 잡는다:

```
$ # 뮤테이션: 카탈로그 경로를 존재하지 않는 파일로
$ python3 -m unittest discover -s tools/map/tests 2>&1 | tail -1
FAILED (failures=15, skipped=10)
```

`CatalogIsWiredFromCommittedFile` 이 임포트 시점 `GROUP_KIND` 가 비지 않았음을 확인해 준다. 다만 그 보증은 **리포 루트에서 실행할 때만** 성립한다. `raise` 로 바꾸면 폴백 경로 자체가 사라진다 — 카탈로그는 gitignore 밖이라 없어야 정상인 상황이 없다.

## LOW-2 — 등가 뮤테이션 4종 (데이터 영향 0)

```
--- M2  override 목록에서 尹 제거          --- OK (skipped=10)   diffs=0
--- M4  override 목록에서 典农校尉 제거      --- OK (skipped=10)   diffs=0
--- M7  GROUP_SUFFIX 에서 간체 '国' 제거    --- OK (skipped=10)   diffs=0
--- M11 GROUP_SUFFIX 에서 '县縣' 제거       --- OK (skipped=10)   diffs=0
```

220년 데이터에서 네 갈래 모두 죽은 가지다(`尹`/`典农校尉` 는 TIER 와 카탈로그가 둘 다 COMMANDERY 라 override 가 값을 안 바꾸고, `NAME_FT` 는 항상 번체라 간체 접미사는 안 걸린다). 억지로 테스트를 붙일 이유는 없다 — 다만 override 목록의 `尹`·`典农校尉` 두 항목은 현재 아무 일도 하지 않는다는 사실만 기록해 둔다.

## 잡힌 뮤테이션 (정상)

```
--- 카탈로그 경로 파손 -------------- FAILED (failures=15)
--- override 목록에서 郡 제거 -------- FAILED (failures=9)
--- sourceGroupName 만 인덱싱 -------- FAILED (failures=1)
--- name_ft 대신 name_ch 로 조회 ----- FAILED (failures=5)
--- GROUP_SUFFIX 에서 '郡' 제거 ------ FAILED (failures=12)
--- 미등재 国/侯国 COUNTY 폴백 제거 --- FAILED (failures=5)
--- override level 6 -> 5 ---------- FAILED (failures=14)
```

---

## 확인되어 이상 없는 것 (cleared)

**커밋 경계 — 통과.** `git show --stat b87e663a` 는 3개 파일뿐이다: `data/map/han-tiles.json`, `tools/map/build_han_places.py`, `tools/map/tests/test_han_places_tier_classification.py`. `data/chgis-source/`·`han-places.json`·`terrain-grid.json`·`external-places.json`·`readings.json` 은 **들어가지 않았다**(ADR-LITE-039/040 준수). `git status --short --branch` 도 깨끗하다.

**이름 어간 충돌 전수 검사 — 통과.** 카탈로그 105 group 의 `canonicalGroup`+`sourceGroupName` 전량(GROUP_KIND 134 키)에 대해 검사했다.

- 서로 다른 group 이 같은 stem 으로 뭉치는 경우: **1건** — `蜀`(蜀郡 / 蜀郡屬國), 둘 다 COMMANDERY 라 무해.
- 한 stem 이 다른 stem 의 부분문자열인 쌍: **28건**. 그중 groupType 이 갈리는 것은 `陳`(KINGDOM) ⊂ `陳留`(COMMANDERY), `南`(COMMANDERY) ⊂ `濟南`(KINGDOM), `東`(COMMANDERY) ⊂ `東平`(KINGDOM) — 즉 구현자가 찾은 `陳`⊂`陳留` 외에 **`南`⊂`濟南`, `東`⊂`東平` 두 쌍이 더 있다.**
- 다만 구현은 `dict.get(exact_stem)` 이라 **이 28건 중 어느 것도 오분류를 만들지 않는다.** 220년 전량 재분류에서 잘못 승격된 항목은 0건이다. `MatchingMustBeExactStemNotSubstringTest` 가 이 성질을 고정한다. 새로 찾은 두 쌍은 그 테스트의 근거를 보강할 뿐 결함이 아니다.

**KINGDOM 17 산술 — 통과.** 정본 카탈로그 KINGDOM 은 20. 산출물 17 = CHGIS 유래 16 + `魯國`(`build_external_places.py:79` 수작업 리터럴) 1. 누락 3 = `安平國`·`沛國`·`清河國`(CHGIS 220년 커버 부재, #531). **17 + 3 = 20 ✓.** (참고: 지시문의 "잔여 4개(魯國 포함)"는 커밋 본문과 다르다 — 커밋 본문의 "잔여 3개"가 데이터와 맞는 쪽이다.)

**COMMANDERY 146 → 126, 20 감소 — 전부 추적됨.**
- COMMANDERY → KINGDOM 16건: 琅邪·魯·中山·陳·梁·濟北·常山·任城·齊·東平·樂安·濟南·北海·河間·下邳·趙
- COMMANDERY → COUNTY 4건: 安眾侯國·新成侯國·征羌侯國·衛國 (level 6→5)
- 합 20 ✓. COUNTY 958→962 (+4) ✓, KINGDOM 0→17 = 위 16 + 신규 彭城國 1 ✓. `_meta.counts` 도 그대로 일치한다.
- **조용한 소실 없음.** cities 제거 0건.

**신규 1건 정당 — 통과.** `{"id":"33425","nameCh":"彭城国","kind":"KINGDOM","level":6}`. 수정 전에는 TYPE_CH='侯国'→COUNTY lv5 로 같은 좌표의 `彭城县`(id 42777, lv5)에 dedupe 되어 흡수됐다(`seats` 키 = (NAME_CH, lon, lat), 높은 level 승). 이제 lv6 이라 별개 치소로 남는다 — 郡治와 縣治가 같은 지점에 겹치는 정상 형태이고 `彭城县` 도 그대로 있다.

**`juns[]` 175 → 174 — 정당.** nameCh 기준 제거는 `新成侯國` **1건뿐**, 추가 0건. COUNTY 로 강등돼 `seat: true→false` 가 됐으니 郡治 목록에서 빠지는 게 맞다. `adjCommandery` 427→425 도 그 귀결이다.

**KINGDOM 렌더링 — 회귀 아님.** `han-tiles.json` 에 `kind:"KINGDOM"` 이 처음 들어갔고 `HanMapCanvas.tsx` 의 `TIER2_MARKER_ZOOM`/`TIER2_LABEL_ZOOM` 에 KINGDOM 행이 없어 `tierZoom`/`labelZoomFor` 가 `undefined` 를 낸다. 그러나 같은 파일 주석(`HanMapCanvas.tsx:64-66`)대로 그 테이블은 **2급 전용**이고 1급(COMMANDERY 포함)은 원래 `juns[]` + `JUN_LABEL_ZOOM` 이 그린다. KINGDOM 은 COMMANDERY 와 정확히 같은 경로를 타고, 해당 17개는 `juns[]` 에 전부 남아 있다.

**테스트 — 주장대로.** `python3 -m unittest discover -s tools/map/tests` → `Ran 42 tests ... OK (skipped=10)`. skipped 10 은 전부 `test_junguozhi_contract` 의 `source-refresh-only`(gitignored HHS 코퍼스 미보유)이며 이번 커밋의 신규 테스트 15개는 전부 실행된다. 실제 반환값을 핀하며 count-only 가 아니다.

**TYPE_CH 재유도 스윕 — 주장대로.** `grep -rn "TYPE_CH" tools/ --include='*.py'` 결과 `build_han_places.py` 와 그 테스트 외에는 없다.

**오승격 0건.** 220년 전량 재분류에서 카탈로그 override 로 승격된 항목은 東平·樂安·任城·彭城·濟北·濟南(→KINGDOM), 東海·太原·犍為屬國·陳留(→COMMANDERY)뿐이고 전부 續漢書 郡國志와 일치한다. 마을급 侯國이 승격된 사례는 없다.

---

## UNKNOWN — 근거를 못 만든 것

- **`readings.json` 회귀가 `main` 재현인지 이 머신 한정인지**: `hanja` 가 없어 사전을 재생성해 대조할 수 없었다. 확실한 것은 커밋된 산출물 전후 비교(`b87e663a^` vs `b87e663a`)에서 70건이 한글→한자로 바뀌었다는 사실이며, 그것만으로 HIGH-1 은 성립한다. 다른 머신에서 재생성하면 회귀가 사라질 가능성은 있으나, **그 경우에도 "커밋된 산출물이 재생성 머신에 따라 달라진다"는 사실 자체가 남는다.**
- **지시문 전제 `81 COMMANDERY + 20 KINGDOM + 4 METROPOLITAN = 105`**: 실제 카탈로그는 `Counter({'COMMANDERY': 85, 'KINGDOM': 20})` 로 **METROPOLITAN groupType 이 아예 없다**(河南尹 등 4개는 COMMANDERY 로 접혀 있다). 구현 결함은 아니고 지시문 전제 쪽 오류로 보이나, #507 의 원래 의도가 METROPOLITAN 분리였다면 그건 별개 티켓의 미완 항목일 수 있다 — 이 리뷰 범위 밖이라 판정하지 않는다.
- **지시문의 "太原·濟南은 진짜 KINGDOM"**: 카탈로그는 太原을 COMMANDERY 로 본다(續漢書 郡國志 太原郡과 일치). 구현은 카탈로그를 따랐고 그게 옳아 보이나, 지시문과 갈리므로 기록만 남긴다.

---

## 재현 요약

```bash
cd worktrees/opensamguk/tiles-tier-524
git show --stat b87e663a
python3 -m unittest discover -s tools/map/tests -v 2>&1 | tail -5
# 산출물 전후 대조 · 어간 충돌 전수 검사 · 220년 전량 재분류 · 뮤테이션 12종:
#   본문 각 절의 인라인 스크립트를 그대로 실행 (모든 뮤테이션은 실행 후 원본 복원)
git status --short --branch   # 리뷰 후 워크트리 무변경 확인
```
