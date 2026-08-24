# han-map-wave / 郡國志 파서 레인 독립 적대적 비평 — 통합 재판정 (최종)

Date: 2026-08-24 (1차 판정) / 재판정 1·2·3·4차 (같은 문서 in-place 재발행)

Scope: PR #550 브랜치 `work/opensamguk/junguozhi-parser-fix` 팁 `6ecb18af` 의 전체 diff (base `origin/main` = `3715e6d3`, 실측 42 files / +6,372 / −310) — app/ · assets/ · data/ · docs/ · logic/ · tools/ · web/. 1차 판정 대상이던 PR #508 `work/opensamguk/han-map-wave` 의 unitset·han.json 레인은 이 diff 에서 사라졌으므로 이력으로만 남긴다.

Reviewer: 코드 작성자가 아닌 독립 비평 에이전트. PR body·커밋 메시지·수정자 실측을 근거로 채택하지 않고 전부 직접 재실행했다. 각 「닫혔다」 주장은 수정자가 쓴 축과 **다른 축**으로 확인했고, 새 단언은 깨뜨려서 빨개지는 것을 본 뒤에만 통과시켰다. 판정 도중 브랜치가 세 번 움직였고(`1ea06386` → `ba0e509b` → `6ecb18af`), 그때마다 이전 실측을 버리고 새 팁에서 다시 쟀다.

**판정 요약:** 1차 블로커 4건, 2차 블로커 N1, 3차 블로커 B1·B2 를 **전부 닫았다.** 마지막 팁에서 마지막까지 의심한 것 — 「결함 기준선 테스트를 지운 것」 — 도 커버리지 손실이 아님을 RED 프로브로 확인했다. 남은 것은 코드가 아니라 **PR body 의 낡은 수치 하나**뿐이고, 이건 머지 전에 고쳐야 하지만 코드 블로커는 아니다. `cleared`.

> **게이트 기계에 대한 메모.** PR #547 이 `tools/agent-system/check.py` 를 지웠으므로 `cross-agent-critique` 규칙은 더 이상 기계적으로 막지 않는다. 이 판정은 게이트가 아니라 **내용**으로 내렸다.

---

## 머지 전에 반드시 고칠 것 (코드 아님, 문서)

### D1 PR body 의 수치가 현재 diff 와 다르다 — 낡은 숫자가 기록으로 남는다

body 는 세 번의 머지 이전 상태를 서술하고 있다. 내가 팁 `6ecb18af` 에서 실측한 값과 대조하면:

| body 의 주장 | 실측 | |
|---|---|---|
| 84개 파일 / +9,024 −332 | **42개 파일 / +6,372 −310** | 틀림 |
| `data/map/external-places.json` (+1,312) 를 싣는다 | **diff 에 없다** (`git diff --quiet origin/main HEAD --` 가 SAME-AS-MAIN) | 틀림 |
| 아이콘 자산 `web/{game,gateway}/public/{city,status}/**` 48개 | **city PNG 22개뿐** (`status/` 없음) | 틀림 |
| JVM 테스트 「boot IT 3개 · seed 2개」 | `ScenarioBlankPlayerCommandIT`·`ScenarioBlankUnificationIT` **2개뿐** (나머지는 `41b305d2` 로 main 복귀) | 틀림 |
| 연결성 기준선 = 도달 불가 {523 이주, 550 유구, 759 우산국, 770 주호, 780 야마일국} | **그 테스트는 이제 없다** — #552 가 SEA_LINKS 로 결함을 고쳤다 | 틀림 |
| RED 프로브 「5개 목록에서 780 제거 → 빨강」 | 그 단언이 존재하지 않으므로 이 프로브도 현재 코드를 서술하지 않는다 | 틀림 |

이 저장소는 「지어낸 수치가 스펙이 된다」로 이미 물렸다. 머지 커밋 본문이 되는 글이 없는 파일을 실었다고 말하고 없는 테스트를 근거로 든다면, 다음 사람이 그걸 근거로 삼는다. **머지 전에 body 를 위 실측으로 갱신해라.** 코드는 고칠 것이 없다.

---

## 4차 재판정 — 마지막 팁에서 새로 의심한 것

수정자가 「#552 가 섬 결함을 실제로 고쳤으니 결함 기준선 테스트를 지웠다」고 보고했다. **테스트를 지우는 변경은 기본적으로 의심 대상**이므로 세 축으로 따로 확인했다.

**축 1 — 결함이 정말 고쳐졌는가 (데이터 축).** 테스트가 아니라 원본 데이터를 봤다. 팁의 `HanCityConst.kt` 에서 인접이 빈 城은 **0개**다(`grep -o 'RawCity(... listOf())'` → 출력 없음). 1차 판정 때 5개였다. 다섯 섬은 전부 간선을 얻었다:

```
RawCity(520, "이주",     … listOf("산음")),
RawCity(547, "유구",     … listOf("산음")),
RawCity(753, "우산국",   … listOf("실직국")),
RawCity(764, "주호",     … listOf("벽비리국")),
RawCity(774, "야마일국", … listOf("구야국")),
```

**축 2 — 그 간선이 진짜인가 (양방향성).** 이건 그냥 넘기면 안 되는 자리다. 나는 3차 RED 프로브에서 **단방향 간선은 BFS 도달성을 만들지 못한다**는 것을 직접 확인했다 — 섬에서 육지로만 거는 간선은 섬을 여전히 고립시킨다. 그래서 다섯 쌍의 역방향을 전수 확인했다: 산음→이주 · 산음→유구 · 실직국→우산국 · 벽비리국→주호 · 구야국→야마일국 **5/5 존재**. 진짜 양방향 간선이다.

**축 3 — 대체 커버리지가 공허하지 않은가 (RED 프로브).** main 의 `HanMapConnectivityTest` 가 「고립 城 0」을 든다는 수정자 주장을 그대로 믿지 않고 깨뜨렸다. `산음` 의 인접 목록에서 `"이주"` **한 방향만** 지웠다(섬 쪽 간선은 그대로 뒀다 — 축 2 의 함정을 그대로 재현한 것이다):

```
HanMapConnectivityTest > han 城 전체가 하나의 연결 성분이다(고립 城 0)()  FAILED
    org.opentest4j.AssertionFailedError at HanMapConnectivityTest.kt:33
CityConstRegistryTest  > Han numeric adjacency preserves the generated 774-city graph()  FAILED
```

**빨개진다.** 게다가 지워진 결함 기준선보다 **강하다** — 옛 섬 테스트는 「도달 불가 집합이 정확히 이 5개」를 봤으므로 6번째 城이 끊기면 잡지만, 지금 테스트는 「하나라도 끊기면」 잡는다. 값을 맞춘 삭제가 아니라 **단언이 참이 아니게 되어 지운 것**이고, 그 자리를 더 센 단언이 이미 덮고 있다. 정당하다.

`HanCityConst.kt` 는 프로브 전에 백업하고 복원해 **바이트 동일**을 확인했다(`shasum 8ee42033…`, `git diff --quiet` 통과).

**남긴 것 — 브랜치가 logic 에 더하는 유일한 코드는 이제 8줄이다.** `CityConstRegistryTest` 에 `han has exactly 774 cities (independent pin, not size-vs-itself)` 하나. 이것도 공허하지 않은지 봤다 — 城 하나를 지우자 **빨개진다**. 주석이 밝힌 존재 이유(인접 해시와 BFS 는 `han.all()` 을 자기 자신과만 비교하므로 임포터가 城을 누락·중복해도 둘 다 통과할 수 있다)도 코드를 읽어 확인했고 맞다. main 에 없는 독립 축이므로 남길 값어치가 있다.

## 4차 실측 — 팁 `6ecb18af`

**게이트 실행 결과는 exit code 가 아니라 XML 집계로 읽었다.**

| | tests | failures | errors | skipped | XML 최신 |
|---|---|---|---|---|---|
| `:logic:test` | 3,326 | 0 | 0 | 0 | 21:33 |
| `:common:test` | 256 | 0 | 0 | 0 | 21:29 |
| `:infra:test` | 246 | 0 | 0 | 0 | 21:49 |
| `:app:game-engine:test` | 941 | 0 | 0 | 1 | 21:45 |
| **합계** | **4,769** | **0** | **0** | **1** | |

유일한 skip 은 `LongSimReplayGateTest`「12 month structural replay matches PHP golden」로 이 PR 과 무관한 기존 골든 게이트다.

**깨졌던 모듈을 이번엔 반드시 돌렸다**(내 1차 실패의 재발 방지). 3차에 CI 를 깨뜨렸던 다섯 클래스 전부 Docker 로 실제 실행, `skipped=0`:

`ScenarioJsonTest` 15 · `ScenarioImporterIT` 22 · `ScenarioMapSeedIT` 8 · `ScenarioBlankPlayerCommandIT` 1 · `ScenarioBlankUnificationIT` 1 — 모두 failures 0 / errors 0 / **skipped 0**. `assumeTrue(dockerAvailable)` 로 도망친 초록이 아니다.

파이썬: `tools/map/tests` **Ran 130, OK** · `tools/scenario/tests` **Ran 252, OK (skipped=1)**.

**N1(신선한 체크아웃 CI) 재확인.** 이 팁에서 다시 쟀다 — gitignored 입력 3종(`data/corpus/`, `data/chgis-source/`, `data/map/junguozhi.json`)을 치우고 `ci.yml` 명령 그대로:

```
Ran 130 tests in 5.393s
OK (skipped=27)      ← 수정 전 나의 실측은 Ran 122 / FAILED (errors=1) / EXITCODE=1
```

입력 3종은 전부 원위치로 복원했다.

---

## 3차 블로커 — 둘 다 닫힘

### B1 브랜치가 main 대비 파일 5개를 지운다 → 닫힘

3차 시점(`1ea06386`)에서 브랜치는 main 보다 20커밋 뒤처져 있었고, 그 결과 `git diff --diff-filter=D --name-only origin/main..HEAD` 가 **삭제 5건**을 냈다 — main 이 방금 세운 가드 셋(`HardcodedHanCityIdCanaryTest.kt` @`8e7548e2` · `test_han_tiles_adjacency_matches_owner.py` · `test_han_tiles_owner_locality.py` @`80896505`)과 다른 PR 의 독립 비평문 둘. 그대로 머지하면 가드가 조용히 사라졌을 것이다.

머지 뒤 팁 `6ecb18af` 에서 재측정: **삭제 0건**(빈 출력). 다섯 파일 전부 `git diff --quiet origin/main HEAD --` 로 **SAME-AS-MAIN**. 파일이 살아 있는 정도가 아니라 main 과 한 글자도 다르지 않다. 닫혔다.

### B2 城 핀 780 이 main 의 774 와 어긋난다 → 닫힘

3차 시점 브랜치는 `han has exactly 780 cities` · 섬 baseline `{523,550,759,770,780}` · 인접 SHA `a6d93707…` 를 들고 있었고, main 은 이미 郡治 병합(`61fc608e`·`3f7c9466`·`bab0dcbf`)으로 774 축이었다. 774 데이터 위에서 780 단언이 도는 확정 RED 였다.

수정자가 「숫자를 갈지 않고 축을 옮겼다」고 보고한 중간 단계(`ba0e509b`, 섬 baseline 을 id 대신 이름으로 단언)를 나는 **데이터 축에서 독립 검증**했다 — 병합 전후의 `HanCityConst.kt` 를 직접 비교해 빈 인접을 가진 城이 `{523,550,759,770,780}` → `{520,547,753,764,774}` 로 **id 만 밀렸고 이름·좌표·능력치는 동일**함을 확인했다. 「결함은 그대로인데 핀만 빨개진 것」이라는 주장은 참이었고, 이름 축으로의 이동은 축 회피가 아니라 정당한 강화였다(다섯 이름이 파일 안에서 유일한 것도 확인했다 — 다만 han 에는 동명 城이 다수 있어 이 기법이 일반적으로 안전한 건 아니다. L4 참조).

그 뒤 #552 가 SEA_LINKS 를 넣어 결함 자체를 없애면서 이 테스트는 폐기됐고(위 4차 절), 팁의 상태는: `RawCity(` **774개** · 인접 SHA **`58b4c44b…`** · `han has exactly 774 cities` 핀 유지. 브랜치는 `HanCityConst.kt`·`han.json`·`HanGateIndex.kt` 를 **한 줄도 바꾸지 않는다**(diff 파일 목록에 0건) — 즉 main 이 생성한 값을 그대로 쓴다. 그리고 그 값들이 실제로 맞는지는 가정하지 않고 **테스트를 돌려서** 확인했다(인접 해시 테스트는 `HanCityConst` 에서 다시 계산해 리터럴과 대조한다). 닫혔다.

### 머지 해소 검증 (수정자 주장 재확인)

- `data/map/external-places.json` 충돌을 origin/main 쪽(魯國 `kind=KINGDOM`)으로 취했다 → **SAME-AS-MAIN 확인**. 제3의 상태가 생기지 않았다.
- `tools/assets/build_status_icons.py` add/add 충돌은 내용 동일·모드만 달랐다 → **blob `e4ec393f` 동일 + 모드 `100755` 동일** 확인.
- `HanGateRegionsTest.kt` 충돌을 main 쪽(테스트 3개 살아있는 판본)으로 취했다 → 이 파일은 이제 **diff 에 아예 없다**(= main 과 동일). 확인.
- 수정자가 인용한 커밋 `24fc0e3f` 는 이 저장소에 **존재하지 않는다**(`git cat-file -t` 실패). 실제 커밋은 `ba0e509b` 였다. 결과에는 영향이 없으나 — 보고된 SHA 를 그대로 믿으면 안 된다는 사례로 적어 둔다.

---

## 내가 놓쳤던 것 — 잔재 결함 클래스 (자인)

`41b305d2` 가 고친 결함은 **내가 1차 재판정에서 잡았어야 했다.** `f381da8a` 가 han 시나리오 레인을 뺄 때 **데이터(시나리오 JSON 16개)만 빼고 그 데이터를 전제로 고쳐 둔 테스트 3파일을 안 뺐고**, 그래서 CI jvm 4건이 깨졌다(run 32717387946): `ScenarioJsonTest`(:20, :33) · `ScenarioImporterIT`(:1637) · `ScenarioMapSeedIT`(:88).

**내 실패의 정확한 형태:** 나는 `git diff --name-only` 로 이 세 파일이 diff 에 있다는 걸 **출력까지 받아 놓고**, Kotlin 테스트 중 둘만 열어 보고 「되돌리며 잃은 것은 없다」고 결론냈다. 그리고 `:logic:`·`:common:` 만 돌렸다 — **깨진 두 모듈을 아예 안 돌렸다.** 레인 제거를 검증할 때 봐야 할 것은 「뺀 파일」이 아니라 「뺀 것의 **소비처**」인데 그걸 전수로 훑지 않았다. 「exit 0 은 게이트의 증거가 아니다」를 남에게 지적해 놓고, 정작 **안 돌린 모듈의 초록을 가정**했다. 그래서 이번 판정에서는 네 모듈을 전부 돌리고 XML 로 집계했다(위 표).

### 잔재 전수 조사 — 팁에서 재확인

| 뺀 것 | 소비처 잔재 | 판정 |
|---|---|---|
| 시나리오 JSON 16개의 `mapName: "han"` | `infra/src/main/resources/scenario/` 에 `"map": "han"` **0건**. 문제의 3파일 전부 **SAME-AS-MAIN** | 닫힘 |
| `route-corridor-*` 회랑 데이터 | `*.kt/*.ts/*.tsx/*.py/*.yml` 전체에서 **0건** | 잔재 없음 |
| `SEA_LINKS`(당시) | 코드 참조 0건. #552 로 다시 들어왔고 지금은 main 소유 | 해소 |
| `HanGateRegionsTest` 의 B1(애뢰 노수) assertion | 파일 자체가 main 판본으로 복귀. 그 불변식은 `UnitCatalogTest` 가 `reqRegions == listOf("永昌")` 로 다른 축에서 덮는다 | 커버리지 손실 0 |

app/game-engine 에 남은 `"han"` 참조들(`HanAiLifecycleReplayIT` scenario_1010 · `PrecheckFullCrossCallSiteTest` `byId(419)=="석"` 등)은 전부 **main 소유**이고 main 의 재번호(421→419)와 정합한다 — 이 PR 의 잔재가 아니다.

---

## 1차 재판정 — 블로커 4건 (전부 닫힘)

### F1 han.json 해상연결 레인 드리프트 → 닫힘

「테스트가 초록이 됐다」가 아니라 **diff 표면**으로 확인했다. `git diff --name-only origin/main...HEAD` 에 `infra/.../map/han.json` · `HanCityConst.kt` · `HanGateIndex.kt` · `tools/scenario/build_han_world.py` · `infra/.../scenario/*.json` 이 **한 건도 없다**. 레인이 고쳐진 게 아니라 존재하지 않는다. (해상 간선은 예고대로 자기 PR #552 로 갔고 main 에서 왔다.)

### F2 생성기 없는 고아 데이터 2.4MB → 실질 닫힘

전 파일형 `git grep` 재확인: `route-corridor` 는 코드 확장자 전체에서 0건. 잔류한 `external-world-candidates-v1.json`(59KB)은 `provenance.input.sha256` 이 추적 중인 `data/map/external-places.json` 실측 해시(`33cd7fbc…d20fe`)와 **일치**하고, `reviewState: PENDING` · `runtimeActivation: NOT_CLAIMED_BY_W1_DATA_CONTRACT` 로 런타임 비활성 판정 대장임을 선언하며 research 2건이 流求 `DISPUTED` 근거로 인용한다. 재생성 산출물이 아니라 대장이므로 막지 않는다. (LOW: `provenance.generator` 가 저장소에 없는 `build_route_corridor_candidates.py` 를 가리킨다 — #518 이관 사실을 적어라.)

### F3 `test_tile_kind_sanity.py` 진공 → 닫힘, 1차 처방은 철회

스크래치패드 사본의 `han-tiles.json` 에서 `上党郡` 을 COMMANDERY→KINGDOM 으로 승격시키자 분포 핀과 승격 회귀가 **둘 다** 빨개졌다. 도달 불가 분기는 없어졌고 동어반복도 아니다.

**1차 처방(「`TIER` 분류기에 `安眾侯國`/`犍為屬國`/`樂安郡` 을 직접 먹여라」)은 실행 불가능한 지시였고 철회한다.** `build_han_places.py:56-62` 의 `TIER` 는 縣名이 아니라 CHGIS `TYPE_CH` 타입 필드로 조회하고, 그 표에서 `'侯国'` 은 이미 `('COUNTY', 5)` 이며 `属国` 은 키 자체가 없다(→ drop). 이름을 먹여도 `None` 이다. `han-tiles.json` 은 재생성하지 않았으므로 docstring 의 「13개 KINGDOM 중 9개 오승격」 수치는 **UNKNOWN**.

### F4 PR body 가 diff 를 서술하지 않음 → 당시엔 닫힘, 지금 다시 낡음

1차 지적 당시 body 는 없는 변경(2167)을 서술하고 파서 밖 레인을 숨겼다. 수정 후 body 는 스스로 「제목은 파서 레인이지만 diff 는 파서 밖 레인이 함께 들어 있다」로 시작하고 레인 표를 diff 그대로 옮겼으며, 그 시점 수치가 내 측정과 일치했다. **레인 은폐라는 원래 결함은 닫혔다.** 다만 이후 세 번의 머지로 수치가 다시 어긋났고 그것이 위 **D1** 이다.

---

## 2차 블로커 — N1 (닫힘)

`TestNanyangYuyangNoteReferenceRegression` 만 형제 픽스처 클래스 12개와 달리 `bj.county_lexicon` 을 백업만 하고 교체하지 않아, 신선한 체크아웃에서 `main()` → `build_junguozhi.py:728` → `:123 open(…dbf)` 로 `setUpClass` 가 죽었다(`ci.yml:27` exit 1). 그 클래스 docstring 은 정확히 반대(「`data/chgis-source/**` 없이도 CI 에서 항상 돈다」)를 주장하고 있었다. 내 첫 실행이 초록이었던 건 내 워크트리에 gitignored 입력이 있었기 때문이다.

`47200e08` 이 `bj.county_lexicon = lambda: frozenset()` 등으로 막았고, 나는 **수정자와 다른 축 둘로** 확인했다.

- **입력 은닉 축**(수정자는 별도 워크트리를 썼다): 위 4차 실측대로 `OK (skipped=27)` / exit 0. `grep -c '^ERROR:'` = 0.
- **빈 lexicon 이 회귀를 공허하게 만드는가**(수정자는 파서 코드의 「有」 가드를 껐다 — 같은 자리를 같은 방식으로 두 번 보는 건 독립 확인이 아니다): 나는 **파서 코드를 한 글자도 안 건드리고 입력에서** 「有小長安」을 「，小長安」으로 바꿨다. 빈 lexicon 에서도 「長安」이 가짜 縣으로 튀어나온다 → `assertNotIn('長安', names)` 를 지키는 것은 lexicon 이 아니라 「有」 가드다. **부재 단언이 빈 집합 위를 지나가지 않는지도 같이 걸었다** — 정상 상태에서 이 픽스처의 南陽郡 블록은 縣 37개·城數 체크섬 PASS 1/1·RESOLVED_POINT 27 이고 宛·冠軍·新野·章陵·湖陽·育陽 이 전부 있으며, 이 이름집합은 실제 산출물 `data/map/junguozhi.json` 의 南陽郡 37개와 **완전히 일치**한다.

---

## 선언된 한계 — 열려 있으나 이 PR 의 블로커가 아닌 것

지우지 말 것. 다음 판정자가 이 자리에서 다시 읽어야 한다.

### L1 `build_junguozhi.py:745` 의 `except FileNotFoundError: pass` — 주석이 사실과 다르다

위험 자체는 없다. 다른 축으로 확인했다 — `data/map/external-places.json` 을 치우고 map 테스트를 돌리면 **4건이 빨개진다**(`test_county_name_baseline` 3건 + `test_reign_era_restoration_note_does_not_become_fake_counties`). 삼킨 예외가 조용한 품질 저하로 이어지지 않고 이름집합 기준선이 잡는다. 남는 건 문서 결함이다 — 주석은 「아직 안 만들어졌을 뿐」이라 쓰지만 실제로는 **추적 중이고 사실상 필수**이며 없으면 5개 屬國 앵커가 죽는다.

### L2 이 PR 의 간판 증거(縣 이름집합 기준선)는 CI 에서 항상 스킵된다

`test_county_name_baseline.py:130` 의 `@unittest.skipUnless(CORPUS.exists() and DBF.exists() and OUT_PATH.exists(), …)` 로 클래스 전체가 CI 에서 스킵된다(신선한 체크아웃 시뮬레이션에서 `skipped=27` 실측). `county_name_baseline.json`(32郡 / 소실 59 / 가짜 60) 대조는 **로컬 전용 축**이고 CI 가 실제로 지키는 파서 커버리지는 인라인 픽스처 회귀들뿐이다. 막지 않는 이유: skip 사유 문자열이 ADR-LITE-039 를 인용하며 「CI 에서는 항상 skip 된다(실패 아님)」를 **스스로 명시**하고, 기준선 JSON 도 `한계_卷110_112`·`한계_字形`(±10)을 파일 안에 적어 수치를 임계값으로 오독하지 않게 막는다.

### L3 南陽郡에 가짜 縣 2개가 남아 있고, 새 기준선이 그 郡을 덮지 않는다

커밋 산출물의 南陽郡 縣 37개 안에 **「山，」**(桐柏大復山 오분절)과 **「西」**(隨西有斷蛇丘 오분절)가 가짜 縣으로 들어 있고 실제 縣 涅陽·陰·酇·鄧 쪽에 병합 결손이 있다. **城數 체크섬은 37=37 로 PASS 한다** — 이 PR 자신이 문제 삼는 상쇄 형태다. `county_name_baseline.json` 의 32郡에 **南陽郡이 없다**는 것도 확인했다. 블로커로 세지 않는 이유: 이 PR 이 만든 결함이 아니고, 산출물은 gitignored 이며, 기준선이 부분 기준선임을 스스로 선언한다. 다음 기준선 확장의 1순위로 남긴다.

### L4 이름 축 단언은 일반적으로 안전하지 않다

B2 에서 섬 baseline 을 id → 이름으로 옮긴 것은 정당했고 그 다섯 이름은 유일하다. 그러나 han 에는 **동명 城이 다수 존재한다**(강·경·경릉·고성·곡성 …). 이름 축이 id 축보다 항상 낫다고 일반화하면 언젠가 동명 城에서 해상도를 잃는다. 그 테스트는 지금 없어졌으므로 실害는 없지만, 같은 기법을 다른 곳에 옮겨 쓸 때 이 점을 확인해라.

### L5 `ScenarioBlankPlayerCommandIT` 의 주석 전제가 거짓이다

`:93` 의 「전 시나리오 han 통일로 scenario_0 의 map 이 che(94개 도시)에서 han(780개 도시)으로 바뀌었다」는 `f381da8a` 가 그 전환을 되돌렸으므로 **사실이 아니다**(scenario_0 엔 `map` 키가 없다 → `DEFAULT_MAP_NAME`= che → 94). 게다가 780 이라는 수치도 이제 낡았다. 바로 아래 코드가 맵을 동적으로 읽으므로 동작은 옳고 IT 도 초록이라 LOW 다. 다만 N1 이 「docstring 이 현실과 반대」였던 것과 같은 계열이니 문장을 사실에 맞춰라.

### L6 재현하지 못한 것 / 이 PR 밖

- `data/unitset/units.json` 의 `build_unitset.py --check` 는 CI 어디서도 안 불린다 — 이 PR 밖 레인이지만 드리프트 가드가 CI-inert 인 상태는 그대로다.
- 1차 통합 실행 중 **딱 한 번** `test_anchor_selection.py::TestSeatCorrectionDirection::test_no_commandery_is_left_without_an_anchor` 가 빨갛게 나왔다. 이후 단독·통합 합계 6회 이상 전부 초록이었고 팁에서도 130건 OK 다. 같은 시각 내가 그 파일을 치웠다 되돌리는 프로브를 돌린 자리라 **내 프로브가 만든 경합**으로 본다. 재현되지 않았으므로 원인은 **UNKNOWN** 이고 이 PR 의 결함으로 세지 않는다.
- `:infra:compileKotlin` 이 한 번 `Internal compiler error`(kotlin `jarfs` mmap)로 죽었다. 내가 gradle 을 동시에 두 개 돌린 직후였고, 데몬을 내리고 재실행하니 재현되지 않았다(이후 `:infra:test` 246건 초록). **환경 결함**으로 판단하며 소스 결함이 아니다.
- 파일명이 `…-pr508-…` 인데 대상 PR 은 #550 이다. **지금 이름을 바꾸지 마라** — 제자리 재발행이어야 이전 판정이 대체된다.

---

## 판정 근거 요약

1차 블로커 넷, 2차 N1, 3차 B1·B2 를 전부 닫았고, 각각 수정자와 **다른 축**으로 확인했다 — han.json 레인은 diff 에서의 **부재**로, 회랑 데이터는 **전 파일형 grep 과 해시 앵커 대조**로, 진공 테스트는 **산출물을 오염시켜 빨개지는 것을 보고**, N1 은 **입력 은닉**과 **입력에서 「有」 제거**로, B1 은 **삭제 목록이 빈 것 + 다섯 파일의 SAME-AS-MAIN 동등성**으로, B2 는 **테스트가 아니라 원본 데이터의 빈 인접 0건 + 역방향 간선 5/5**로. 1차 F3 의 처방이 `TIER` 조회 키를 오독한 것이었음도 확인해 철회했다.

가장 오래 의심한 「결함 기준선 테스트 삭제」는 **축 셋으로 따로 확인해** 정당한 것으로 판단했다 — 결함이 데이터에서 실제로 사라졌고, 그 간선이 (내가 앞서 찾아 둔 단방향 함정을 피해) 진짜 양방향이며, 대체 단언이 단방향 파괴에도 빨개지고 옛 단언보다 넓다. 값을 맞춘 삭제가 아니다.

내가 1차에 놓쳤던 잔재 결함(레인을 뺄 때 데이터만 빼고 테스트를 안 뺀 것)은 `41b305d2` 로 닫혔고, 그것이 마지막 하나였음을 소비처 전수 조사로 확인했다. 이번엔 **깨졌던 두 모듈을 포함해 네 모듈 전부**를 돌렸고, exit code 가 아니라 XML 집계로 읽어 **4,769건 / failures 0 / errors 0**, Docker IT 다섯 클래스 **skipped 0** 을 실측했다.

남은 것은 코드가 아니라 **PR body 의 낡은 수치**(D1) 하나다. 그건 머지 전에 고쳐야 하지만 코드 블로커가 아니므로 판정을 막지 않는다. L1~L6 도 막지 않는다.

Verdict: cleared
