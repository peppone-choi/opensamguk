# PR #528 독립 비평 — work/opensamguk/han-unitset-2000-crewtype

Verdict: fix-required
Scope: app/, common/, data/, docs/, logic/, tools/

리뷰 시각: 2026-08-24. 리뷰 대상 HEAD: `a4235574` (`origin/main` = `b9cec50c` 기준,
merge-base 확인 완료). 리뷰어는 이 작업의 저자가 아니며, PR 본문·커밋 메시지·브리핑을
증거로 채택하지 않고 전부 직접 재실행했다. 직접 확인하지 못한 것은 UNKNOWN 으로 적는다.

리뷰 중 같은 브랜치에 다른 리뷰어의 리뷰 커밋(`5e7e7f71`,
`...-pr528-independent-critique.md`)이 푸시됐다. 독립성 유지를 위해 그 파일 내용은 읽지
않았고, 아래 결론은 전부 독립 실측이다.

## 실제로 돌린 것과 실측 숫자

### 1. 디프 범위 (`git diff --stat origin/main...HEAD`)

```
app/game-engine/.../ScenarioBlankPlayerCommandIT.kt        |   11 +-
common/src/test/.../UnitCatalogTest.kt                     |   42 +
data/unitset/units.json                                    | 1307 +++++++++---
logic/src/test/.../HanGateRegionsTest.kt                   |    6 +
logic/src/test/.../UnitSetTableHanTest.kt                  |    7 +-
tools/assets/build_unit_prompts.py                         |  455 +++++  (신규)
tools/assets/check_sprite_chroma.py                        |   44 +++++  (신규)
tools/unitset/build_unitset.py                             |   65 +-
8 files changed, 1768 insertions(+), 169 deletions(-)
```

PR 본문이 설명하는 파일은 6개다. `tools/assets/build_unit_prompts.py` 와
`tools/assets/check_sprite_chroma.py` 는 본문 어디에도 없다 — 아래 B1 참조.

### 2. CI `agent-system` 잡 python 스텝 6개 (개별 실행, 전부 실측)

| 스텝 | 실측 |
| --- | --- |
| `unittest discover -s tools/map/tests` | Ran **28** tests, OK, **skipped=10**, failures=0, errors=0 |
| `unittest discover -s tools/scenario/tests` | Ran **252** tests (46.3s), OK, **skipped=1**, failures=0, errors=0 |
| `unittest discover -s tools/agent-system/tests` | Ran **9** tests, OK, skipped=0 |
| `han_route_node_candidates.py --check` | exit 0, 출력 없음 |
| `han_route_node_selection.py --check` | exit 0, 출력 없음 |
| `validate_han_route_node_selection.py` | exit 0 — `approved production manifest ... approved=780 scenarios=15 selectionSha256=a0171193... migrationSha256=d7ed5ae6...` |

즉 PR 본문이 주장한 "매니페스트 앵커가 깨지지 않는다"는 재현된다.

### 3. JVM 테스트

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test --rerun-tasks`
→ `BUILD SUCCESSFUL in 51s`. exit code 로 판정하지 않고
`**/build/test-results/test/*.xml` 원문을 파싱한 결과:

| 모듈 | XML 파일 | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- | --- |
| `common` | 45 | **255** | 0 | 0 | 0 |
| `logic` | 296 | **3320** | 0 | 0 | 0 |

지목된 클래스:

- `UnitCatalogTest` — tests=**8**, failures=0, errors=0, skipped=0
- `HanGateRegionsTest` — tests=**5**, failures=0, errors=0, skipped=0
- `UnitSetTableHanTest` — tests=5, failures=0, errors=0, skipped=0

PR 본문은 `common:test` 254 / `UnitCatalogTest` 7 로 적었다. 실측은 255 / 8 이다
(본문이 마지막 커밋 `a4235574` 이전 상태로 쓰인 뒤 갱신되지 않았다). N1 참조.

### 4. 생성물 무결성

`python3 tools/unitset/build_unitset.py --check` → exit 0,
`data/unitset/units.json — 최신`. **손으로 고친 흔적 없음** — 커밋된 파일은 생성기가
authored 필드에서 다시 유도한 바이트와 정확히 일치한다.

## 확인해서 좋다고 판정한 것

### G1. B1 회귀 테스트는 진짜로 빨개진다 (뮤테이션으로 증명)

가장 중요한 공격 지점이었다. 이 브랜치의 조상 PR 이 "절대 실패할 수 없는 가드"로
지적당한 전력이 있으므로 단언 문구만 보고 판정하지 않고 실제로 죽여 봤다.

**(a) 데이터 레이어 뮤테이션** — 저장소를 건드리지 않고 스크래치 복사본에서
`build_unitset.py` 의 `bucket = "tribe" if k == "tribe" else "_other"` 를
`bucket = "_other"`(옛 단일 리스트 동작)로 되돌린 뒤 재생성:

```
BASE   애뢰 노수 [{ReqRegions ['永昌']}, {ReqRegions ['夷']}]     ← 2개
MUTANT 애뢰 노수 [{ReqRegions ['夷', '永昌']}]                   ← 1개
base 와 mutant 사이에서 달라지는 유닛: 32종
```

**(b) 엔드투엔드 뮤테이션** — 그 mutant `units.json` 을 빌드 산출물
(`common/build/resources/main/unitset/units.json`, 소스·데이터 파일 아님)에 얹고
`./gradlew :common:test --tests '*UnitCatalogTest*' -x :common:processResources`:

```
UnitCatalogTest > 郡과 부족을 동시에 요구하는 병종은 ReqRegions 가 뭉치지 않는다(B1)() FAILED
    org.opentest4j.AssertionFailedError at UnitCatalogTest.kt:107
8 tests completed, 1 failed
```

검증 후 산출물은 원본으로 복원했고 `data/unitset/units.json` 과 바이트 일치 확인
(`RESTORED-OK`). **결론: 공허한 가드가 아니다. B1 회귀는 실재하며 32종에 걸쳐 있다.**

### G2. `永昌` 테스트 이관은 테스트 약화가 아니다

- 이 base 의 `common/src/main/kotlin/opensamguk/common/constants/HanGateIndex.kt` 에
  `永昌` 등장 횟수 **0**, `越巂` **0**. 즉 `cityWithKey("永昌")` 는 실제로 못 찾는다 —
  PR 본문의 `NoSuchElementException` 주장은 데이터로 뒷받침된다.
- 더 결정적으로, `git show origin/main:.../HanGateRegionsTest.kt` 에는 `永昌`/`越巂` 가
  **존재하지 않는다**. 그 assertion 은 `origin/main` 의 자산이 아니라 이 브랜치의
  `aaad9f16` 이 새로 더한 것이고, `f2d986d8` 이 다시 뺀 것이다. 기존 커버리지를 깎은 게
  아니라 **자기가 넣었다 뺀 것**이다. 약화 아님.

### G3. `UnitSetTableHanTest` 델타 완화(`assertEquals` → `1e-9`)는 정당하다

주장을 직접 재현했다. 유닛 2000 의 `rice=6`, tech 계수 1.15 에서:

```
6 * 1.15            = 6.8999999999999995
6 * 1.15 * 100/100.0 = 6.9                → 불일치
```

다른 tech 계수(1.0/1.3/1.45/1.6/1.75/1.9)에서는 정확히 일치한다.
`costWithTech(tech, crew=100) = cost * getTechCost(tech) * crew / 100.0` 의 곱셈 순서
차이에서 오는 1 ULP 오차가 맞고 프로덕션 결함이 아니다. 다만 `1e-9` 는 이 경우 필요한
것보다 넉넉하다(N4).

### G4. 본문의 "최종 상태 2000" 주장은 사실이다

조상 PR #508 은 본문이 2167 이라 적고 브랜치는 2000 이던 불일치로 막혔다. 이번엔
`data/unitset/units.json` 실측 `sets.han.defaultCrewTypeId = 2000` 이고 제목·본문 모두
2000 이다. `origin/main` 대비 `sets` 블록 디프는 `2006 → 2000` 한 줄뿐이다.
유닛 2000 = 민병대 도병, tier 1, `generic=true`, `reqConstraints=[]`, atk 110 / def 75 /
cost 6. 2167 유닛 데이터 자체는 변경되지 않았다. **본문과 실측이 일치한다.**

### G5. 제외됐어야 할 경로망 기계장치는 실제로 없다

디프 전체에서 route-node / route-corridor / route-network 를 참조하는 곳은 코드·데이터에
하나도 없다(유일한 히트는 다른 리뷰어가 방금 푸시한 리뷰 문서의 본문 텍스트다).
`infra/.../map/han.json`, `HanGateIndex.kt`, `HanCityConst.kt` 전부 디프에 없다.
매니페스트 앵커 3종이 exit 0 인 것과 정합한다.

## 블로킹 지적

### B1. 선언되지 않은 스코프 — 병종과 무관한 스프라이트 도구 499줄

이 PR 은 "unitset 전용"으로 선언됐고 본문은 6개 파일만 설명한다. 그런데 브랜치는 신규
파일 2개를 더 싣는다:

- `tools/assets/build_unit_prompts.py` (+455) — OPENSAM-209 "han 병종 스프라이트 프롬프트
  빌더". 이미지 생성 프롬프트 텍스트와 `plan.json` 을 뽑는다.
- `tools/assets/check_sprite_chroma.py` (+44) — OPENSAM-209 "생성 raw 의 크로마 배경 검사".

실측:

- 추가된 1768 줄 중 **499 줄(28%)** 이 이 두 파일이다.
- 저장소 어디에서도 참조되지 않는다 — `.github/`, `*.py`, `*.sh`, `*.kts`, `*.md`
  전수 grep 결과 **참조 0건**. CI 스텝도 없다.
- 테스트 **0건**. `tools/` 아래 다른 도구들과 달리 `tools/*/tests/` 에 대응 테스트가 없다.
- 병종 밸런스/게이트 로직과 인과관계가 전혀 없다 — `units.json` 을 읽기만 하는 소비자다.

이 PR 은 바로 "선언 범위를 지키기 위해 지도/게이트 절반을 덜어냈다"는 것이 존재
이유다. 그 PR 이 정작 본문에 없는 무관한 도구 499줄을 함께 싣는 것은 같은 규칙의 위반이다.
게이트는 저자의 선언을 믿는데, 실제보다 적게 선언하면 게이트를 통과한 게 아니라 게이트에
거짓을 말한 것이다. **별도 PR(OPENSAM-209)로 빼거나, 최소한 본문에 명시하고 왜 이
PR 에 있어야 하는지 밝혀야 한다.**

### B2. F4(2167 → 2000) 결정에는 회귀 테스트가 하나도 없다 — 뮤테이션으로 증명

이 브랜치가 2라운드를 돈 유일한 이유가 "2167 은 `generic:false` · def 130 인 특수
부대라 보편 기본값이 될 수 없다"는 독립 리뷰 지적이었다. 그런데 그 결정을 지키는
테스트가 없다.

`common/build/resources/main/unitset/units.json` 의 `sets.han.defaultCrewTypeId` 를
**2000 → 2167 로 되돌리고** `UnitCatalogTest` 를 돌린 실측:

```
> Task :common:test
BUILD SUCCESSFUL in 3s        ← 8 tests 전부 GREEN
```

이유는 명확하다:

- `UnitCatalogTest` 의 `기본 병종은 시작 시점부터 무제약으로 뽑을 수 있다` 는
  `default.reqConstraints.isEmpty()` 만 본다. 2167 도 `reqConstraints=[]` 이므로 통과한다.
  이 테스트가 잡는 것은 **2006(ReqTech 1000) 결함뿐**이다.
- `UnitSetTableHanTest` 의 `기본 병종은 세트마다 다르다` 는
  `assertTrue(hanDefault >= 2000)` — id 대역 확인일 뿐 값을 고정하지 않는다.
- 테스트 전체에서 `2000` 을 기본 병종으로 고정하는 단언은 **0건**.

즉 누군가 내일 `defaultCrewTypeId` 를 2167 로(혹은 다른 `generic:false` 유닛으로) 되돌려도
`common`·`logic` 3575 테스트가 전부 초록이다. **이 PR 이 고친 그 결함이 무방비로 재발
가능하다.** PR 본문이 명시적으로 선언한 설계 규칙("기본 병종은 어느 세력이나 뽑는
generic 유닛이고, 사료가 이름을 남긴 부대는 그 위에 얹힌다")을 한 줄로 고정할 수 있다:

```kotlin
assertTrue(default.generic, "$set 기본 병종 ${meta.defaultCrewTypeId} 이 generic 이 아니다")
```

수정 없이 머지하면 리뷰 사이클 한 바퀴가 통째로 휘발된다.

## 비블로킹 지적

- **N1. PR 본문의 검증 숫자가 브랜치 최신 상태와 다르다.** 본문은 `common:test` 254 /
  `UnitCatalogTest` tests=7 이라 적었으나 실측은 255 / 8 이다. `a4235574`(B1 테스트 추가)
  이후 본문이 갱신되지 않았다. 결론(전부 초록)은 바뀌지 않지만, 본문의 "실측 근거"
  블록이 최신 HEAD 를 재현하지 않는다.
- **N2. `HanGateRegionsTest` 에 남긴 이관 주석이 사실과 다르다.** 주석은 B1 이
  "그 아래 `UnitSetTableHanTest` 등 다른 회귀로 이미 커버된다"고 적었다.
  `UnitSetTableHanTest` 의 5개 테스트는 세트 지원·목록 분리·기본 병종 id 대역·세트 밖 id
  거절·비용 곡선뿐이고 게이트 AND 를 전혀 보지 않는다. B1 을 실제로 커버하는 것은
  다른 모듈의 `UnitCatalogTest`(G1 에서 증명) 하나다. 다음 사람이 이 주석을 믿고
  `UnitSetTableHanTest` 를 고치면 커버리지 판단을 틀린다.
- **N3. `build_unitset.py --check` 가 CI 에 없다.** `.github/` 전수 grep 결과
  `build_unitset` 참조 0건. 지금은 `units.json` 이 최신이지만(실측 확인), 이 PR 이
  생성기를 바꾸는 이상 손으로 고친 `units.json` 을 CI 가 잡지 못하는 구멍은 남는다.
  `agent-system` 잡에 한 줄이면 된다. 기존 결함이지 이 PR 이 만든 것은 아니다.
- **N4. `1e-9` 는 필요보다 넉넉하다.** 실측 오차는 6.9 근처에서 1 ULP(~8.9e-16)다.
  절대 델타 대신 같은 식(`cost * getTechCost(tech) * 100 / 100.0`)으로 비교하면 오차를
  허용하지 않고도 통과한다. 지금 값은 실제 밸런스 드리프트(예: rice 6 → 6.000000001)를
  삼킬 여지를 남긴다 — 현실적 위험은 낮다.
- **N5. `check_sprite_chroma.py` 의 `main()` 은 불량을 세고도 항상 0 을 돌려준다.**
  docstring 이 "불량 키를 stdout 에" 라고 밝히므로 설계상 의도로 읽히지만, 이름이
  `check_*` 라 게이트로 오인해 CI 에 붙이면 절대 빨개지지 않는다 — 이 브랜치가 고치는
  중인 "공허한 가드"와 같은 부류다. 또 `len(list(raw.glob('*.png')))//2` 는 모든 파일이
  `.raw.png` 와 짝을 이룬다고 가정한다. (B1 대로 이 파일이 이 PR 을 떠나면 함께 사라진다.)

## UNKNOWN — 직접 확인하지 못한 것

- **Docker 기반 IT 2종**(`ScenarioBlankPlayerCommandIT`,
  `ScenarioBlankUnificationIT`)을 이 리뷰에서 실행하지 않았다. 이 PR 의 **선언된 동기
  전체**(두 IT 가 RED → GREEN)는 따라서 리뷰어 실측으로 재현되지 않았다. 본문은
  "이전 검증에서 각각 tests=1 failures=0 확인" 이라 적지만, 이 리뷰는 그 주장을 증거로
  채택하지 않는다. `ScenarioBlankPlayerCommandIT.kt` 의 하드코딩 1100 → 동적
  `UnitSetTable.defaultCrewTypeId` 변경도 그래서 실행 검증되지 않았다 — 코드 읽기로는
  타당하다(`world.getState().meta["unitSet"]` 폴백 + `error(...)` 로 null 은 시끄럽게 죽는다).
- `#524`, `#519`, `#501`, PR B(`34177c3f`) 의 내용은 이 리뷰 범위 밖이며 검증하지 않았다.
- 다른 리뷰어가 같은 브랜치에 푸시한 `5e7e7f71` 리뷰 문서는 독립성 유지를 위해 읽지 않았다.

## 판정

**fix-required.**

기술적 핵심 — B1 게이트 AND 복원, 생성물 무결성, 매니페스트 앵커 보존, `永昌` 이관의
정당성 — 은 전부 재현되고 견고하다. G1 의 뮤테이션 결과는 이 브랜치의 조상이 지적당한
"공허한 가드" 문제가 여기서는 실제로 해결됐음을 보인다.

막는 것은 두 가지다:

1. **B1** — "unitset 전용"이라 선언한 PR 이 본문에 없는 무관한 스프라이트 도구 499줄
   (전체 추가의 28%)을 함께 싣는다. 이 PR 의 존재 이유 자체가 스코프 정직성이다.
2. **B2** — 리뷰 사이클 한 바퀴를 돌게 만든 F4(2167→2000) 결정이 테스트로 고정되지
   않았다. 뮤테이션 실측으로 2167 회귀 시 3575개 테스트가 전부 초록임을 확인했다.

둘 다 작은 수정이다: 파일 2개를 별도 PR 로 옮기고, `UnitCatalogTest` 에 단언 한 줄을
더하면 된다.

## 측정 기준 시점 명시

위 숫자는 전부 `a4235574` 의 **깨끗한 워킹트리**에서 측정했다(측정 시점
`git status --short` 무출력으로 확인). 리뷰 문서를 쓰는 동안 같은 워크트리에
다른 에이전트가 커밋되지 않은 편집 4건(`UnitCatalogTest.kt`, `HanGateRegionsTest.kt`,
`build_unitset.py`, `check_sprite_chroma.py`)을 만들어 놓은 것을 확인했다. 그것들은
PR #528 의 내용이 아니므로 이 리뷰의 대상이 아니며, 위 실측에도 영향을 주지 않았다.
이 리뷰가 판정하는 것은 원격에 푸시된 브랜치 상태다.

**후기(리뷰 작성 중 브랜치가 움직였다).** 이 문서를 쓰는 도중 저자 쪽이 `22b55497`
(`fix(review): ... MEDIUM/LOW`)를 커밋했고, 그 커밋이 **아직 작성 중이던 이 리뷰
파일까지 함께 쓸어담았다**(`git add` 범위가 넓었다는 뜻이다 — 리뷰 산출물이 저자
커밋에 저자 이름으로 섞여 들어가는 것 자체가 작은 위생 문제다). `22b55497` 이 실제로
고친 것은 위 **N2**(이관 주석의 credit 정정)와 **N5**(`check_sprite_chroma.py` 를
non-zero 종료로)이고, `UnitCatalogTest` 의 중복 assertion 하나를 제거했다.

**그 커밋 이후에도 이 리뷰의 블로킹 2건은 그대로 남는다:**

- **B1** — `tools/assets/build_unit_prompts.py` / `check_sprite_chroma.py` 는 여전히
  브랜치에 있고 여전히 PR 본문에 없다. `22b55497` 은 후자를 **고쳐서 남겼다**.
- **B2** — `defaultCrewTypeId = 2000` 을 고정하는 단언은 여전히 0건이다.
  `22b55497` 은 오히려 그 테스트에서 assertion 을 **하나 뺐다**.

따라서 판정은 유지된다: **fix-required.**
