# OPENSAM-103 스펙 독립 적대적 리뷰 — v2 콘텐츠 대체 cutover 스펙

- **reviewer:** `reviewer-103-spec` (독립 — 이 산출물을 작성하지 않음)
- **review date:** 2026-07-17
- **artifact under review:** `docs/superpowers/specs/2026-07-17-v2-content-replacement-cutover-spec.md`
- **artifact SHA-256 (초판, R1):** `763e83c91d08a955421b8d24ffb5e73ede2282d99fe0cf673261ee209a2a7785`
- **artifact SHA-256 (재검증, R2):** `5422f0e8b83136a58993a7bebfd1fe3678e2a6c9e8d87434ab308b1c3102d624`
- **review basis:**
  - 계약 `docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md` §8·§3·D5·§13
  - `.ai/decisions.md` ADR-LITE-010(L88-96)/ADR-LITE-011(L98-106)
  - `docs/superpowers/research/2026-07-17-opensam-102-rtk14-rtk8r-map-source-coverage.md`
  - `docs/superpowers/research/2026-07-17-opensam-102-map-coordinate-ledger.csv`
  - `infra/src/main/kotlin/opensamguk/infra/seed/MapJson.kt`, `ScenarioJson.kt`
- **R1 VERDICT:** `fix-required` (fix-required 2, note 2)
- **LATEST VERDICT (R2, 2026-07-17):** `cleared` (fix-required 0; F1·F2·N1 해소, N2 non-blocking note 잔존) — 상세 §7

---

## 1. 리뷰 범위 (exact scope)

계약 §8 acceptance criteria 1–11(8은 계약 §13 item 6이 supersede) 전수 + engine-semantics 불변 경계·goldens freeze·projection 계약·101 좌표·RIGHTS WARN·105 defer·§13 amendments·사실 citation spot-check(MapJson.kt/ScenarioJson.kt/CSV/ADR)·diff hygiene. 다른 lane 파일(tools/rtk-faces, nginx, portrait.ts, ProfileIcon* 등)은 리뷰 대상 아님(다른 티켓).

## 2. 증거 검증 (사실 spot-check — 모두 실측)

| 인용 | 스펙 위치 | 실측 결과 | 판정 |
|---|---|---|---|
| CSV SHA-256 `d0a6…c5c5`, header 1 + data 101 | §7 L103 | `shasum` 일치, 데이터 101행 | ✅ |
| 좌표 분류 46 CITY / 40 PORT / 10 GATE / 5 ETHNIC = 101 | §7 표 L105-111 | CSV `base_kind` 집계 46/40/10/5 | ✅ |
| entity 46 CITY + 55 SMALL_BASE | §7 L111 | CSV `entity_type` 46/55 | ✅ |
| PK.png SHA-256 `dfe5…0a89`, native `4181×4191` | §7 L113 | CSV col17/18/19 전행 동일, research L37/L246 일치 | ✅ |
| 5 이민족 거점 `parent_city=[UNKNOWN]` (烏桓·鮮卑·羌族·山越·南蛮) | §8 L119 | CSV ETHNIC 5행 전부 `UNKNOWN`, 이름 일치 | ✅ |
| 맵 plaque `羌` ↔ canonical `羌族`, `鄴`=`ｷﾞｮｳ`, `下邳`=`下ヒ`, `秣陵/建業` | §6.3 L94 | research §13 L281-282, §5 L134 일치 | ✅ |
| `MapCityCoord(id:Int,name,x:Double,y:Double)` | §5.1 → MapJson.kt:9 | L9 정확 일치 | ✅ |
| `MapCityDetail(… x:Double?,y:Double?, *Max, *Init, connections:List<Int>)` | §5.1 → MapJson.kt:11-31 | L11-31 정확 일치 | ✅ |
| `loadMap`/`loadCityDetails` classpath+MetaJson(insertion-order) | §5.1 → MapJson.kt:44-97 | L44/L65-97, doc L19-21 insertion-order codec | ✅ |
| `loadMapCities` `Double→Int` truncate | §5.1/§5.3 → ScenarioJson.kt:216-217 | L216 `c.x?.toInt()` L217 `c.y?.toInt()` | ✅ |
| `x/y` client-display, city 테이블 미영속 | §5.1 → ScenarioJson.kt:369-371 | L369-370 주석 정확 | ✅ |
| ScenarioGeneral 14+2 슬롯, 정치/매력 인덱스 14/15 | §5.1 → ScenarioJson.kt:25-44,143-170 | G_POLITICS=14/G_CHARM=15, decodeGeneral L143-170 | ✅ |
| `Scenario.map: Map<String,Any?>` untyped metadata | §5.1 → ScenarioJson.kt:287 | L287 정확 | ✅ |
| 도시당 12 경제값 = 6 `*Max` + 6 `*Init`, `UNAVAILABLE` | §5.3/§12 → MapJson.kt:18-29 | L18-23 6 *Max + L24-29 6 *Init = 12, research §7 L161 `UNAVAILABLE` | ✅ |
| ADR-LITE-010 Decision/Alternatives(신규 병행 기각)/user 방향선언 | §2 | decisions.md L92/94/93 일치(단 note 2 참조) | ✅ (note 2) |
| ADR-LITE-011 = AI 에셋 생성 + 비주얼 현대화 + 에픽 112 | §2 관련 | decisions.md L98-106 일치 | ✅ |
| raw artifact 격리 경로 `/Users/apple/.codex/visualizations/…/opensam-102/…` | §11.2 | research §13 L246 일치 | ✅ |

리뷰어 지정 spot-check("12 unknown = 6 *Max + 6 *Init")는 `MapJson.kt:18-29`에서 정확히 확인됨.

## 3. 계약 §8 acceptance criteria 판정

| # | 기준 | 스펙 근거 | 판정 |
|---:|---|---|---|
| 1 | status `PROPOSED` + ADR context/decision/alternatives/consequences/cutover/rollback | §1 L14, §2, §9, §10 | PASS |
| 2 | divergence content layer only; RNG·rounding·log·order·flush immutable | §3.2 L45-49 (5요소 모두 명시) | PASS |
| 3 | devsam fixtures/goldens baseline freeze, 재작성 금지, 별도 승인 | §4 L55-58 | PASS |
| 4 | ScenarioJson/MapJson↔PhysicalPlace, C001/B001, versioned v1 Int map, 방향/version/unknown | §5, §6 | PASS |
| 5 | 정확히 101 좌표, 5 이민족 `[UNKNOWN]`, RIGHTS WARN, native-pixel≠world | §7, §8 (§2 검증 완료) | PASS |
| 6 | full adjacency·12값 미확인 → 105 deferred, placeholder/default/reverse edge 금지 | §12 L171-176 | PASS |
| 7 | cutover 실패/parity mismatch → 이전 content version/source rollback + 관측점 | §10.3 L149-154, §10.4 L156-160 | PASS |
| 8 | **superseded(§13 item 6):** 전 시나리오 집합, pilot 고정·시나리오간 승인게이트 없음, per-scenario rigor 유지, default 불변 | §9 body L127-130, §15 L208 | **부분 위반 — F1/F2** |
| 9 | raw/intermediate artifact repo 밖/gitignored만, tracked/구현 없음 | §11 L164-167, §14 | PASS |
| 10 | `PROPOSED` → reviewer 검토로 `APPROVED` 전환 불가, user-only | §1.2 L15 | PASS |
| 11 | 티켓 diff = 지정 spec 외 builder/runtime/default/Jira 변경 0 | §14 + git 검증(§4) | PASS |

기준 8은 §9 본문·§15 row 8에서는 새 규칙을 올바르게 반영했으나, 그 supersession이 §1.3·§10.1로 **전파되지 않아** 두 leftover 모순이 남았다(§4 findings).

## 4. Findings

### F1 — `fix-required` (leftover 단일 파일럿/순차 시나리오 staging)
`v2-content-replacement-cutover-spec.md:16` (§1.3): "`APPROVED`는 "1차 시나리오 파이프라인 착수"만 여는 것이지 **후속 시나리오**·builder·CDN·deploy·rights release를 함께 여는 것이 아니다."
- 이 문장은 "1차 시나리오"와 "후속 시나리오"를 순차적으로 여는 프레임 = 계약 §13 item 6이 제거한 "단일 파일럿 고정 + 한 번에 하나씩 확장" 규칙의 잔존이다. §9 본문("전 시나리오 집합 대상", L127)·§9.3(삭제된 규칙, L129)와 정면 모순.
- 계약 §13 item 6(L569)은 "이 항목은 §8 acceptance criterion 8과 **§10의 해당 rg anchor 요건보다 우선한다**"고 명시 — leftover는 계약 우선순위 위반.
- 근본원인: amendment가 §9/§15에는 적용됐으나 §1.3에 미전파. 수정: "1차 시나리오 … 후속 시나리오" 순차 프레임을 제거하고, `APPROVED`가 여는 것은 (rights/deploy/CDN 제외한) **전 시나리오 집합 파이프라인 착수**임을 §9와 일치시킬 것.

### F2 — `fix-required` (leftover per-scenario 사용자 승인 precondition + 잘못된 §9 인용)
`v2-content-replacement-cutover-spec.md:136` (§10.1 preconditions): "이 스펙 status `APPROVED`(§1) + **해당 시나리오 개별 사용자 승인(§9)**."
- precondition이 여전히 "시나리오별 개별 사용자 승인"을 요구하며 그 근거로 `(§9)`를 인용한다. 그러나 §9.3(L129)은 "시나리오 간 사용자 승인 게이트"를 **삭제된 규칙**으로 명시했고, 계약 §13 item 6은 "시나리오 사이의 사용자 승인 게이트 … 제거한다"고 지시했다. 즉 §10.1은 §9가 삭제한 요건을 §9를 근거로 요구하는 자기모순.
- 계약 §13 item 6이 "§10의 해당 rg anchor 요건보다 우선"하므로 §10.1의 이 precondition은 반드시 갱신·삭제됐어야 함. implementer가 §10.1을 읽으면 §9가 없다고 한 per-scenario 승인에서 blocked됨(실행 불능).
- 수정: `해당 시나리오 개별 사용자 승인(§9)` 항목을 제거하고, 유지되는 per-scenario 게이트(mapping·compare·rollback, §6/§10.2/§10.4)만 precondition으로 남길 것.

### N1 — `note` (§8 "96개 row parent 판정" 부정확)
`v2-content-replacement-cutover-spec.md:119` (§8.1): "나머지 96개 row의 parent는 linked detail-page `地域データ`로 판정됐다."
- CSV 실측: 46 CITY 행의 `parent_city`는 `NOT_APPLICABLE`(도시 자신이 parent)이고, `地域データ`로 parent가 판정된 것은 PORT 40 + GATE 10 = **50행**뿐이다. research §13 L240도 port/gate 한정으로 서술. "96개 row의 parent 판정"은 46개 도시를 detail-page-판정 대상으로 오기재해 과장.
- load-bearing 주장(5 이민족 = UNKNOWN 보존)은 정확하므로 non-blocking. 정확히는 "50개 small-base(port/gate)의 parent는 `地域데이터`로 판정, 46개 도시는 `NOT_APPLICABLE`"로 서술 권장.

### N2 — `note` (§2 ADR-LITE-010 Context 재서술 + Alternatives 추가 항목)
`v2-content-replacement-cutover-spec.md:25,27` (§2): §2 Context는 원 ADR-LITE-010 Context(decisions.md L93: wikiwiki 무장 얼굴 633×900·스탯, san14db Wayback 958/1000 초상/스탯 근거)를 **지도 좌표 근거(101 native-pixel)**로 치환했고, Alternatives에 원 ADR에 없는 "devsam 콘텐츠 유지(기각)"를 추가했다.
- 스펙은 §2를 "이 cutover 계약이 참조하는 요약(원본이 정본)"으로 명시(L21)하고, 사용자 방향선언 인용("슬슬 기존 devsam(체섭)의 그늘에서 벗어나야")은 verbatim 일치하며 Decision도 verbatim이다. 계약 §8 criterion 1은 각 요소의 **존재**만 요구하므로 PASS. Context 치환은 이 cutover(지도/시나리오)에 더 적합한 요약이나 원 ADR Context의 충실 복제는 아니므로 투명성 차원의 note.

## 5. 통과 확인된 경계 (defect 없음)

- **engine-semantics 불변 경계(§3.2):** RNG draw 순서/횟수/args, `PhpRound`(half-away)·`toInt`·damage clamp `ceil`, 한글 로그 byte(로그 순서=실행 순서), side-effect+insertion order(`LinkedHashMap`·PHP 8.0+ stable sort), `ChangeRecorder`→`JdbcFlushExecutor` + one-daemon-write — 5요소 모두 immutable 명시. airtight.
- **goldens freeze(§4):** 새 content가 기존 golden 재작성 금지, byte 변경은 별도 사용자 승인, 충돌 시 golden 아닌 content 조정/abort — CLAUDE.md parity discipline 5와 정합.
- **projection 계약(§5/§6):** 단방향 forward v1(Int id)→v2(stable string id), 역생성 없음, mandatory versioned, unknown fail-closed 제외, C001/B001 stable ID(display name 분리), versioned v1 Int map(map version·scenario 스코프, 이름 join 금지).
- **101 좌표·RIGHTS WARN·native≠world(§7/§8):** 실측 일치. RIGHTS WARN은 load-bearing, 어떤 절도 해제 안 함, rights release는 LEGAL 게이트 별도 판정. §13(d)도 재확인.
- **105 defer(§12):** full adjacency 미확정 + 12값 UNAVAILABLE → deferred, reverse edge 자동 생성/placeholder/default 발명 전면 금지. §13(b)의 교차 RTK 비교·hexmap datafication을 UNKNOWN 보충의 **유일 승인 증거경로**로 두되 §13(c) UNKNOWN 보존·105 defer 유지.
- **§13 amendments quote/cite:** 사용자 원문 3건("이참에 지도도 RTK14로 맞춰…비교하고 보충해", "일단 RTK14 지도 원본 이미지는 데이터화 시켜. 헥스맵이니까", "그리고 황건의난으로 고정하면 어떡하냐") 모두 계약 §13 원문의 verbatim(부분/생략표시 `…`)이며, 계약 경로 인용 정확. 단 §13 item 6의 supersession이 §1.3/§10.1에 미전파(F1/F2).
- **status PROPOSED / user-only APPROVED(§1):** reviewer clearance ≠ 승인 명시. 본 clearance도 status를 바꾸지 않는다.
- **diff hygiene:** 103 lane이 추가한 파일은 지정 spec 1개뿐. 여타 untracked(tools/rtk-faces=97, portrait.ts/nginx=93, ProfileIcon*/V30=90·91·92·94, 91b/113/rtk-series research=타 lane)은 형제 티켓 소유로 103 결함 아님.

## 6. 결론

사실 citation(코드 file:line·CSV·ADR)은 전수 정확하고, engine 불변 경계·goldens freeze·projection·101/RIGHTS WARN·105 defer·user-only approval은 모두 견고하다. 그러나 계약 §13 item 6의 "단일 파일럿 해제 + per-scenario 승인 게이트 제거" supersession이 §9/§15에는 반영됐으나 **§1.3(L16)와 §10.1(L136)에 미전파되어 두 개의 자기모순이 남았다**(F1: 순차 시나리오 staging 잔존, F2: per-scenario 사용자 승인 precondition 잔존 + 잘못된 §9 인용). 리뷰 지시가 명시한 "per-scenario user approval 또는 single-pilot ordering을 여전히 요구하는 문장"에 해당하므로 defect다.

**R1 VERDICT: `fix-required`** (fix-required 2: F1 §1.3, F2 §10.1 / note 2: N1 §8, N2 §2)

---

## 7. 재검증 delta 리뷰 (R2, artifact SHA `5422f0e8…d624`)

lane-103 수정본 delta 재검증. SHA-256 `5422f0e8b83136a58993a7bebfd1fe3678e2a6c9e8d87434ab308b1c3102d624` (team-lead 제시값과 일치, `shasum` 확인).

| finding | 수정 위치 | R2 실측 | 판정 |
|---|---|---|---|
| **F1** §1.3 leftover 순차-시나리오 staging | spec:16 재작성 | "`APPROVED`가 여는 것은 v2 대체 콘텐츠 **전 시나리오 집합의 cutover 파이프라인 착수**(§9)이며 — 특정 시나리오를 privileged pilot로 두거나 시나리오 간 승인 게이트를 두지 않는다 — builder·CDN·deploy·rights release는 함께 열지 않는다." "1차/후속 시나리오" 순차 프레임 제거, §9와 일관, rights/deploy 여전히 closed | **RESOLVED** |
| **F2** §10.1 leftover per-scenario 승인 게이트 | spec:136 재작성 | "이 스펙 status `APPROVED`(§1). **시나리오별 개별 사용자 승인은 요구하지 않는다**(계약 §13 item 6이 시나리오 간 승인 게이트를 제거). per-scenario 게이트는 아래 기술 조건(mapping·compare·rollback 관측, §6/§10.2/§10.4)만 남는다." 잘못된 (§9) 인용 제거, 계약 §13 item 6 근거로 교정, §9.2 per-scenario rigor와 일관 | **RESOLVED** |
| **N1** §8.1 "96개 row parent" 부정확 | spec:119 교정 | "46개 CITY row는 `parent_city=NOT_APPLICABLE`(도시 자신이 parent)이고, `地域データ`로 parent가 판정된 것은 PORT 40 + GATE 10 = 50개 small-base row다." CSV 실측(46/50/5) 일치, 5 이민족 UNKNOWN 불변 | **RESOLVED** |
| §11/§14 ordinal 문구 | spec:166/194 | "1차·후속 시나리오" → "어떤 시나리오도"/"시나리오 실제 구현"으로 중립화 | OK |
| **N2** §2 ADR-LITE-010 Context 재서술 | spec:25,27 | 미변경 — `note`(non-blocking)이며 §2는 "요약(원본 정본)" 명시라 criterion 1 PASS 유지. `cleared` 차단 아님 | note 잔존 |

**신규 모순 없음:** L129(삭제된 규칙 목록)·L136(수정본)·L208(§15 row 8 superseded)의 "시나리오 간 승인 게이트" 언급은 모두 **제거된 규칙을 서술**하는 것이지 요구가 아님. §1.3 재작성이 §3~§12 다른 절과 충돌 없음.

**회귀 없음:** 20/20 anchor 존재(`ADR-LITE-010`…`OPENSAM-105` 전부 ≥1). 수정 diff는 §1.3·§8.1·§10.1·§11·§14만 건드렸고, 이전 PASS 항목(engine 불변 §3.2, goldens freeze §4, projection §5/§6, 101 좌표 §7, RIGHTS WARN §8.2, 105 defer §12, user-only APPROVED §1.2)의 본문은 미변경. §10.1의 나머지 preconditions(stable ID map·좌표 image version·rights·golden freeze)도 기술 게이트로 유지.

**R2 판정:** F1·F2·N1 모두 해소, 신규 모순·회귀 없음. `fix-required` 0.

**LATEST VERDICT: `cleared`** (R2 / SHA `5422f0e8…d624`; N2는 non-blocking note). clearance는 문서 품질 판정일 뿐 — status는 `PROPOSED` 유지, APPROVED 전환은 사용자만 가능.
