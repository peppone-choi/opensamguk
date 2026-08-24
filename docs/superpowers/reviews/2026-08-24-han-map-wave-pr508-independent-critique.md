# PR #508 `work/opensamguk/han-map-wave` 독립 적대적 비평

Date: 2026-08-24

Scope: PR #508 브랜치 `work/opensamguk/han-map-wave` 전체 (base `origin/main`, 110 files / +73,358 / -513) — data/unitset/units.json · tools/unitset/build_unitset.py · tools/map/tests · common/src/main/kotlin/opensamguk/common/constants · logic/src/test · app/game-engine/src/test · data/curated/han · data/map · .gitignore · .ai · docs/superpowers.

Reviewer: 코드 작성자가 아닌 독립 비평 에이전트. PR body·커밋 메시지·기존 리뷰 문서의 테스트 결과 주장은 근거로 채택하지 않고 전부 직접 재실행해 판정했다. 2026-08-23 자 두 비평 문서(`*-han-map-wave-pr508-independent-critique.md`, `*-han-city-const-gate-index-independent-critique.md`)는 이전(더 큰) 브랜치 상태를 본 stale 아티팩트라 읽지 않았다.

## 내가 직접 돌린 것 (재현 명령과 실측값)

| # | 명령 | 실측 결과 |
|---|------|-----------|
| 1 | `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test --no-daemon --rerun-tasks` | `BUILD SUCCESSFUL`. XML 집계 failures=0 errors=0. 개별: `HanGateRegionsTest` tests=6 f=0 e=0 s=0 · `UnitCatalogTest` 7/0/0/0 · `CityConstRegistryTest` 12/0/0/0 · `UnitSetTableHanTest` 5/0/0/0 |
| 2 | `python3 -m unittest discover -s tools/map/tests -p 'test_*.py'` | `Ran 29 tests ... OK`, 내 환경은 skipped=0 |
| 3 | `python3 tools/unitset/build_unitset.py --check` | `data/unitset/units.json — 최신` (커밋본 == 생성기 산출물, exit 0) |
| 4 | `git log --oneline origin/main..HEAD \| grep 753b8d8d` | 존재 |
| 5 | `git ls-files \| grep HanGateIndex` | `common/src/main/kotlin/opensamguk/common/constants/HanGateIndex.kt` 추적 중, 브랜치 diff 에 +28/-28 |

PR body 가 지목한 두 전제 위험은 **이 브랜치 상태에서 실제로 충족돼 있다.** `753b8d8d`(병종 인프라)는 이력에 있고, `HanGateIndex.kt` 생성물도 커밋돼 있어 그것에 의존하는 `HanGateRegionsTest` 가 Docker 없이 6/6 green 이다. 이 두 건은 통과 처리한다.

PR body 의 `OK (skipped=10)` 와 내 `skipped=0` 차이도 결함이 아니다 — `test_junguozhi_contract.py:56` 의 `@unittest.skipUnless(all(path.is_file() for path in SOURCE_REFRESH_INPUTS), ...)` 가 gitignored HHS corpus 유무로 클래스 전체(10건)를 가른다. 내 워크트리엔 corpus 가 있고 신선한 체크아웃/CI 엔 없다. 다만 이 사실 자체가 아래 F5 의 근거다.

## 통과시킨 것 — 잘된 부분

- **`UnitCatalogTest` 의 새 회귀(F6)가 정확히 옳은 모양이다.** Docker 없이 도는 순수 데이터 테스트이고, (1) `defaultCrewTypeId` 자신의 `reqConstraints` 가 비었는지 (2) 세트 안에 무제약 유닛이 하나라도 있는지 — 특정형과 일반형을 둘 다 건다. 기본값이 우연히 다른 유닛으로 재조정돼도 결함 클래스를 계속 잡는다. 원 결함(2006 의 `ReqTech 1000` 으로 신생 국가가 아무 병종도 못 뽑음)을 CI 가 실제로 도는 레인에서 고정한 것이 이 PR 의 가장 단단한 부분이다.
- **F4 자기 정정(2167 → 2000)이 옳은 판단이다.** 2167 군병은 `generic:false` / defence 130 인 사료 명명 부대라 보편 기본값으로 부적절하고, 2006 자신의 `evidence.quote` 가 선언한 설계 규칙과 정반대다. 실물 확인: `sets.han.defaultCrewTypeId = 2000`, 2000 은 tier 1 / `reqConstraints: []` / `reqTech` 없음.
- **하드코딩을 되박지 않고 제거한 방향.** `ScenarioBlankPlayerCommandIT` 의 `assertEquals(94, count("city"))` → `world_state.meta->>'map'` 로 `CityConstRegistry` 조회, `crewType:1100` → `UnitSetTable.defaultCrewTypeId(unitSetName)`. 맵/세트가 또 바뀌어도 안 깨진다. 옳은 방향이다.
- **`HanGateRegionsTest` B1 회귀가 vacuous 하지 않다.** 越巂 가 `夷` 를 갖고 `永昌` 이 아니라는 **전제 자체를 먼저 단언**한 뒤 행동을 단언한다. 태그가 사라지면 전제 단언이 먼저 터진다.
- **`build_unitset.py --check` 가 clean.** 커밋된 `units.json` 이 생성기 산출물과 바이트 일치한다 — 손으로 고친 흔적 없음.
- **`assertEquals(..., 1e-9)` 완화는 정당하다.** `x * crew / 100.0` 곱셈 순서 차이의 1 ULP 이고 공식 동일성은 유지된다. 초록을 만들려고 단정을 무르게 한 케이스가 아니다.
- **`ScenarioMapSeedIT` 기대값 재작성은 조작이 아니다.** 실물 대조: `HanCityConst.kt:17` `RawCity(1, "장안", "경", 7548, 140, 148, ...)` → pop 754800 / agri 14000 / comm 14800 과 정확히 일치. 손으로 지어낸 숫자가 아니다.

## 막는 지적

### F1 [BLOCKING] PR 제목과 body 가 이 브랜치에 없는 변경을 서술한다

PR #508 제목은 「han 기본 징병 병종을 tech 게이트 없는 **군병**으로 교체」이고, body 「변경 내용」도 `2006 → **2167(군병, 郡兵)**` 이라 못박는다. 그런데 브랜치 실물은 `2000`(민병대 도병)이다 — `fea53d71` 이 2167 을 되돌렸다.

```
$ python3 -c "import json;print(json.load(open('data/unitset/units.json'))['sets']['han']['defaultCrewTypeId'])"
2000
```

body 의 「변경 내용」·「검증」 절 전체가 2167 기준으로 쓰여 있고, 2167 이 왜 부적절한지(generic:false, defence 130) 설명하는 `fea53d71` 의 논거는 body 에 한 줄도 없다. **PR 을 body 로 리뷰하는 사람은 존재하지 않는 변경을 승인하게 된다.** CLAUDE.md 하드 룰 「never fabricate goldens/tests/commands · unverified = UNKNOWN, not guessed」의 취지 정면 위반이다. 코드가 아니라 문서 결함이지만, 리뷰 게이트가 문서를 진입점으로 쓰는 이상 블로킹이다.

**고칠 것:** PR 제목을 2000 기준으로 바꾸고, body 「변경 내용」/「검증」을 `fea53d71` 이후 상태로 갱신한다. `2167 → 2000` 재판단 논거를 body 에 옮긴다.

### F2 [BLOCKING] 생성기·검증기 없는 66,000 줄짜리 고아 데이터

이 브랜치는 아래를 커밋한다:

| 파일 | 줄 수 |
|------|-------|
| `data/curated/han/route-corridor-candidates-v1.json` | 53,547 |
| `data/curated/han/route-corridor-key-registry-v1.json` | 12,492 |
| `data/curated/han/external-world-candidates-v1.json` | 1,983 |

그런데 `6eab1f86`(「경로망 계약 기계를 이 PR 에서 분리한다」)가 `route_network_contract.py` 와 `test_tile_catalog_drift.py` 를 #518 로 떼어냈다. 실측:

```
$ grep -rn "route-corridor-candidates\|route_corridor\|route-corridor-key-registry\|external-world-candidates" \
    . --include="*.py" --include="*.kt" --include="*.ts" --include="*.tsx" --include="*.md" -l
docs/superpowers/research/2026-08-23-liuqiu-disputed-vs-runtime-adjacency-contradiction.md
docs/superpowers/research/2026-08-23-route-network-contract-pytest-30-failed.md
docs/superpowers/reviews/2026-08-23-han-city-const-gate-index-independent-critique.md
.ai/task.md
```

**코드 참조가 0건이다.** 이 브랜치 안에서 이 66k 줄을 재생성할 수도, 검증할 수도, 소비할 수도 없다. 분리 작업이 **증명을 떼어내고 산출물만 남겼다** — 정확히 반대로 갔어야 한다. 「이 PR 에서 분리했다」가 정당해지려면 데이터도 같이 #518 로 가거나, 최소한 이 브랜치에 `--check` 급 재현 경로 하나가 남아 있어야 한다. 지금 상태로 머지하면 `origin/main` 에 아무도 검증할 수 없는 66k 줄이 영구히 박힌다. 이건 rule 5(「Never fabricate or weaken evidence」)의 evidence-가용성 쪽 위반이다.

또한 `external-world-candidates-v1.json` 은 流求 를 `DISPUTED` 로 두는데 `han.json`/`HanCityConst.kt` 는 流求 에 런타임 해상 인접 간선을 확정으로 부여한다 — `aaad9f16` 커밋 메시지가 이 모순을 스스로 인정하고 미해결로 넘긴다. 검증기가 없으니 이 모순을 잡을 기계도 없다.

**고칠 것:** 세 파일을 #518 로 함께 옮기거나, 생성기/`--check` 를 이 PR 에 되돌려 붙이고 CI 에 건다.

### F3 [BLOCKING] 새 가드 `test_tile_kind_sanity.py` 는 오늘 절대 실패할 수 없다

PR body 는 이걸 신규 가드로 내세운다. 실물 대조:

```
$ python3 -c "import json,collections;print(collections.Counter(c['kind'] for c in json.load(open('data/map/han-tiles.json'))['cities']))"
Counter({'COUNTY': 958, 'COMMANDERY': 146, 'EXTERNAL_PLACE': 37, 'PROVINCE': 3})
```

**KINGDOM 이 0개다.** 테스트의 `offenders` 리스트는 구조적으로 항상 빈 리스트이고, `self.fail(...)` 분기는 도달 불가다. PR body 도 「지금 커밋된 산출물엔 KINGDOM 0개라 green」이라고 스스로 밝힌다 — 그런데 그건 통과 근거가 아니라 **이 테스트가 아무것도 안 지킨다는 자백**이다.

진짜 결함은 산출물이 아니라 `build_han_places.py` 의 `TIER` 원시 라벨 매칭(侯國/屬國/郡 오승격)인데, 이 테스트는 그 코드를 한 줄도 부르지 않는다. 실제로 빨개지는 회귀는 지금 쓴 것보다 **더 작다**: `安眾侯國` / `犍為屬國` / `樂安郡` 세 라벨을 `TIER` 분류기에 직접 먹이고 KINGDOM 이 아님을 단언하면 된다. 산출물 스냅샷 검사 대신 결함 있는 함수를 직접 부르는 쪽이 코드도 짧고 오늘 당장 결함을 잡는다.

덧붙여 이 산출물은 저장소 자신의 정본(`administrative-units.json`: COMMANDERY 85 / KINGDOM 20)과 어긋난 채(COMMANDERY 146 / KINGDOM 0) 게임에 서빙된다. `.ai/known-issues.md` 가 #524 로 이연했으니 이 PR 이 만든 결함은 아니지만, 그 위에 얹은 가드가 vacuous 라 이연 상태가 더 굳는다.

**고칠 것:** `TIER` 분류기를 직접 호출하는 red-able 회귀로 바꾼다. 산출물 스냅샷 검사는 그 뒤에 보조로 남겨도 된다.

## 막지는 않지만 고쳐야 할 지적

### F4 [MEDIUM] 象林蠻兵(2166)이 이 브랜치에서 뽑을 수 있는 城 2 → 0 으로 죽었다

커밋 메시지의 주장이 아니라 내가 직접 확인했다:

```
$ python3 -c "... requires/reqConstraints of 2166 ..."
2166 상림만병 {'commandery': '日南', 'tribe': '蠻'}
  [{'type':'ReqRegions','reqRegions':['日南']}, {'type':'ReqRegions','reqRegions':['蠻']}]
$ grep -n '日南' common/.../HanGateIndex.kt
757:        745 to setOf("交州", "日南"),
```

`日南` 태그를 가진 城 은 745 하나뿐이고 그 城 엔 `蠻` 이 없다. AND 복원 뒤 교집합이 공집합 → **2166 은 전 780성에서 징병 불가한 죽은 데이터**다. B1 의 AND 복원 자체는 옳고, 이 0 은 그 옳음이 드러낸 태그 커버리지 결함이다. 문제는 처리 방식이다:

- `aaad9f16` 은 「후속 이슈로 남긴다」고 쓰지만 **이슈 번호가 없다.**
- `.ai/known-issues.md` 는 같은 브랜치의 #523/#524 는 포인터로 남겼는데 이 건은 없다. 브랜치 자신의 관례와 불일치다.
- 어떤 테스트도 이 상태를 고정하지 않아, 조용히 잊힌다.

**고칠 것:** `.ai/known-issues.md` 에 항목 + 이슈 번호를 남긴다(코드 수정은 이 PR 밖으로 이연해도 좋다).

### F5 [MEDIUM] 생성·커밋 산출물 두 개의 드리프트 가드가 CI 에서 안 돈다

`.github/workflows/ci.yml:22-23` 이 도는 파이썬은 `tools/map/tests` 와 `tools/scenario/tests` 뿐이다.

- `data/unitset/units.json` — `build_unitset.py --check` 가 있고 로컬에서 통과하지만 **CI 어디서도 안 불린다.** 생성 산출물이 생성기와 조용히 갈라질 수 있다.
- `data/curated/han/administrative-units.json` — 드리프트 가드 `test_committed_catalog_is_the_exact_generator_output` 가 있지만 `@unittest.skipUnless` 로 gitignored HHS corpus 에 묶여 있어 CI 에선 클래스 전체(10건)가 통째로 스킵된다.

즉 이 브랜치가 손대는 두 「생성 후 커밋」 카탈로그 모두 드리프트 가드가 CI-inert 다. `--check` 한 줄을 ci.yml 에 추가하는 게 가장 싼 폐쇄다.

### F6 [MEDIUM] B1 수정이 규칙이 아니라 증상 하나를 하드코딩한다

```python
bucket = "tribe" if k == "tribe" else "_other"
```

`requires` 딕셔너리의 키들은 의미상 AND 인데, 이 코드는 `tribe` 하나만 떼어내고 나머지 지역 키(province/commandery/region/city/external)는 여전히 한 `ReqRegions` 로 뭉쳐 OR 로 평가된다. 오늘은 안 터진다 — 실측으로 비-tribe 키를 둘 이상 가진 유닛은 유주돌기(2100, `{province:幽州, adjacentTribe:[烏桓,鮮卑]}`) 하나뿐이고 그건 **의도된 OR** 다. 문제는 다음 사람이 `{commandery: X, city: Y}` 를 AND 의도로 적는 순간 같은 클래스의 결함이 조용히 되살아나고, 그걸 막는 단언이 없다는 것이다.

**더 짧고 안전한 모양:** 기본을 「키마다 `ReqRegions` 하나(=AND)」로 두고, OR 로 합칠 키만 명시 allowlist(`{"adjacentTribe"}` → 지역 그룹에 병합)로 둔다. 줄 수는 같고 fail-safe 방향이 반대가 된다.

### F7 [LOW] 「독립 오라클」이라는 표현이 실제보다 세다

`CityConstRegistryTest` 의 `assertEquals(780, CityConstRegistry.of("han").all().size)` 는 커밋 메시지가 「Docker 없이 독립 오라클」이라 부르지만, 실제로는 DB IT 에서 **같은 손으로 찍은 780 을 복사해 온 pin** 이다. 사료·생성기에서 독립적으로 유도된 값이 아니다. pin 으로서는 유효하고 있는 편이 낫지만 「오라클」은 아니다. 같은 커밋이 함께 넣은 **BFS 전역 연결성 테스트가 진짜 가치 있는 추가**다 — 그건 자기 자신과 비교하지 않는다. 인접 해시 상수(`88d14c49...`) 갱신은 의도(섬 郡治 5개 해상 간선)와 근거가 코드 주석에 남아 있어 rule 2 요건을 만족한다.

### F8 [LOW] 이 PR 의 가장 강한 증거가 CI 가 안 도는 레인에 있다

`ScenarioBlankPlayerCommandIT` / `ScenarioBlankUnificationIT` / `ScenarioMapSeedIT` 는 전부 `assumeTrue(dockerAvailable)` 로 스킵되고 Gradle 은 그때도 `BUILD SUCCESSFUL` 을 찍는다. PR body 가 이 함정을 스스로 지적한 건 좋다. 나도 Docker 를 띄우지 않아 이 세 IT 는 **재현하지 못했다 — UNKNOWN 으로 남긴다.** 다행히 핵심 주장(신생 국가가 기본 병종을 뽑을 수 있다)은 F6 의 `UnitCatalogTest` 가 Docker 없이 덮으므로 이 UNKNOWN 이 판정을 좌우하진 않는다. 다만 `armFoundingCrew` 헬퍼는 읽어서 확인했고, `foundAssaultCrewCost` 로 필요 병력을 채워줄 뿐 `che_건국` 의 제약 검사 자체는 우회하지 않는다 — 테스트 셋업이지 증거 약화가 아니다.

## 판정 근거 요약

전제 위험 두 건(753b8d8d, HanGateIndex/34177c3f)은 **실측으로 충족 확인**했고, unitset 핵심 수정은 정확하며 회귀도 옳게 걸렸다. 그 부분만 보면 clear 다.

막는 것은 그 주변이다. PR 을 body 로 읽으면 존재하지 않는 변경(2167)을 승인하게 되고(F1), 생성기 없이 66k 줄이 영구 커밋되며(F2), 신규 가드로 내세운 테스트는 구조적으로 실패 불가다(F3). 셋 다 「증거가 있다고 주장하지만 그 증거가 실제로는 아무것도 안 지킨다」는 같은 형태의 결함이고, 이 저장소의 rule 5 가 정확히 금지하는 것이다. 코드 자체를 되돌릴 필요는 없고 F1·F3 은 각각 문서 갱신과 테스트 한 개 재작성, F2 는 파일 이동 또는 `--check` 복원이면 닫힌다.

Verdict: fix-required
