# OpenSamguk v2 2.5D 전술 전투와 스프라이트 설계

- Date: 2026-07-28
- Status: **PARTIALLY SUPERSEDED**
- Scope: 설계와 파일럿 스프라이트 생성만. 전투 코드, DB schema, API, UI 구현은 하지 않는다.
- Superseded by: `docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md`가 출시 시점, 런타임, 네트워크, 권한, 재접속, 규모, fallback을 대체한다. 2.5D 표현·formation 판정·지형·에셋 계약은 유지한다.

> **2026-07-30 승인 정본:** 전투는 V2 출시 필수이며 야전·공성·수전을 모두 포함한다. 런타임은 전용 battle-engine session actor, 통신은 단기 JoinTicket + WebSocket, 출시 기준은 진영당 16편제다. 아래의 game-engine scheduler·HTTP/SSE·오픈 후 rollout·1인 지휘 제안은 역사 기록일 뿐 실행 정본이 아니다.

## 1. 사용자 요청 원문

> "[$perfectpixel:perfectpixel] 오픈삼국의 전투 스프라이트와 2D 혹은 2.5D 전투 시스템을 만들어서 구현하고 싶어. 지금은 그냥 꽝 vs 꽝인데, 전략과 전술을 넣고 싶단 말이지. https://naver.me/5ECVUzKX 뭐 이런거라던지. 타일이나 2.5D 느낌 혹은 토탈워 비슷한 느낌을 내고 싶거든. 물어볼거 있으면 물어보고, 특히 웹을 써서 삼국지 시대의 무기, 복장 등도 확인하고, https://namu.wiki/w/토탈%20워:%20삼국/부대 여기라던지 https://steamcommunity.com/sharedfiles/filedetails/?id=1978468851 여기라던지."

> "일단 설계만 해둬."

> "스프라이트는 만들어 둬."

## 2. 결론

권장안은 **서버의 연속 좌표 전술 시뮬레이션은 유지하고, Three.js 정사영 장면에서 얕은 지형과 billboard 스프라이트를 쓰는 2.5D 표현**이다.

```text
Kotlin tactical kernel
  continuous integer coordinates + fixed tick + deterministic replay
                           |
                           v
Next.js battle client
  Three.js orthographic scene
  shallow terrain + 8-direction billboard sprites + tactical overlays
```

이 안은 기존 v2 정본의 핵심인 서버 권위, 연속 좌표, 대형 footprint, 전면 방향, 지휘거리, 사기, 보급, replay와 3D 공간/selection 계약을 보존한다. 3D 모델과 리깅만 초기 범위에서 제외한다. 타일은 지형 제작, 공간 분할, 길찾기, 미니맵에 사용하되 전투 판정의 유일한 좌표계로 만들지 않는다.

플레이어가 얻는 전략과 전술은 병사 한 명의 클릭 속도가 아니라 다음 결정에서 나온다.

1. 작전 목표와 진입 경로를 고른다.
2. 전장 배치, 전면 방향, 예비대, 퇴각선을 정한다.
3. 이동, 고수, 돌격, 사격, 엄호, 퇴각 명령을 예약한다.
4. 지형, 측후면, 사기, 피로, 지휘 연결, 보급 상태를 보고 재지휘한다.
5. 섬멸 외에 호송, 차단, 관문 방어, 퇴로 확보 같은 목표를 달성한다.
6. 결과가 도시, 장수 관계, 전공, 포로, 군량과 replay로 돌아간다.

## 3. 현재 코드와의 경계

### Observed

- v1 실전 경로는 `ReservedTurnHandler -> CheChulbyeong -> processWar -> ProcessWarNG/ConquerCity -> ChangeRecorder -> TurnRunService -> JdbcFlushExecutor`다.
- `processWar`는 전투 seed로 단일 `RandUtil`을 만들고 PHP draw 순서를 보존한다.
- 현재 프론트에는 `MapViewer`와 장수 목록 성격의 `battle-center`가 있지만 전술 전장이나 replay UI는 없다.
- v2 `Operation`, `BattleState`, `BattleReplay`는 문서 계약일 뿐 런타임 구현은 없다.
- `ReservedTurnHandler`, `ChangeRecorder`, `JdbcFlushExecutor`는 현재 v1 완결 작업의 활성 소유 파일이다.

### 설계 경계

- v1 `ProcessWarResult`를 실시간 전술 상태로 확장하지 않는다.
- v1 PHP 전투 RNG, 로그, 골든, 예약 링을 변경하지 않는다.
- v2는 별도 world profile, DB, route, bean, migration 경계를 지킨다.
- 전투 종료 결과만 campaign adapter가 `ChangeRecorder -> JdbcFlushExecutor` 단일 flush로 반영한다.
- 파일 소유권이 해제되기 전에는 v1 전투 및 flush 파일을 구현 대상으로 잡지 않는다.

## 4. 표현 방식 비교

| 안 | 장점 | 비용과 위험 | 판정 |
|---|---|---|---|
| 타일 기반 턴제/명령제 | 빠른 구현, 모바일 친화, 판정이 명료함 | 기존 실시간 fixed-tick 채택안과 충돌, 토탈워식 전면·측후면·대형 이동 감각이 약함 | 대체 모드 또는 QA fixture |
| **Three.js 정사영 2.5D + billboard sprite** | 기존 3D 공간 계약 유지, PerfectPixel 8방향 자산 사용, 얕은 지형과 미래 3D unit 혼용 가능 | atlas animation, 투명 sprite 정렬, 군집 가독성 설계 필요 | **권장** |
| 순수 2D renderer + 스프라이트 | 가장 단순한 asset/render pipeline, 높은 sprite batch 효율 | 기존 Three.js 지도/공간 계약과 renderer 이원화 가능성 | 정보 fallback 후보 |
| full 3D Total War형 | 카메라와 지형 표현이 풍부함 | 모델링, 리깅, LOD, 애니메이션, GPU와 네트워크 비용이 초기 목표를 압도함 | 수직 슬라이스 통과 뒤 재검토 |

이 2.5D는 별도 2D 게임이 아니라 3D ground plane 위에 카메라를 바라보는 스프라이트를 세우는 표현이다. 따라서 기존 제품 spec의 3D, `ContinuousTopology + REALTIME_FIXED_TICK`, spatial snapshot, replay 계약을 개정하지 않는다. 전투의 V2 출시 필수 전환과 ADR-LITE-019/021 일정 개정은 2026-07-30 승인 스펙이 정본이다.

## 5. 전술 상태 모델

전투 판정 단위는 개별 병사가 아니라 **formation**이다. 화면의 병사 스프라이트는 formation 상태를 보여주는 시각 샘플이며 충돌과 피해의 진실 원천이 아니다.

```text
BattleState
  tick
  objectiveState
  terrainVersion
  formations[]
  projectiles[]
  supplyNodes[]
  orderedEvents[]

FormationState
  formationInstanceId, commandGroupId, generalId
  formationTemplateId, recruitableVariantId, catalogRevision, cadreSlotId?
  role
  positionCm(x, y), facing4096
  frontageCm, depthCm
  strength, morale, cohesion, fatigue
  ammunition, carriedSupply
  commandLink, visibility
  currentOrder, queuedOrders[]
  status: STEADY | WAVERING | ROUTING | SHATTERED | WITHDRAWN
```

`catalogRevision`은 state와 replay에 pin한다. 같은 formation template이라도 replay가 참조한 catalog revision을 바꾸어 해석하지 않는다.

### 결정성

- 위치, 속도, 거리, 각도, 사기, 피로는 정수 또는 명시적 fixed-point만 사용한다.
- 권장 초기 좌표 해상도는 10cm, 방향은 0..4095, 시간은 200ms fixed tick이다.
- 동일 tick의 명령은 `(issuedAtTick, formationId, sequence)`로 정렬한다.
- RNG draw는 접촉, 명중, 사기 충격처럼 이름 붙은 판정 지점에서만 발생한다.
- 브라우저 프레임률, 네트워크 도착 시간, 애니메이션 프레임은 결과에 영향을 주지 않는다.
- replay hash는 wall-clock id와 timestamp를 제외한 명령, 판정 근거, RNG draw, ordered state diff, 결과 event로 만든다.
- tick 순서는 `명령 수락 -> 명령 전달 -> 이동 -> 접촉 -> 전투 pulse -> cohesion/morale/supply -> 목표 -> event/hash`로 고정한다.
- formation과 contact pair는 stable id로 정렬한다.
- v1의 one-shot `processWar_NG`를 실시간 tick 안으로 이식하지 않는다.

## 6. 깊이를 만드는 최소 규칙

초기 버전은 능력치 수를 늘리기보다 서로 연결되는 여섯 축을 만든다.

### 6.1 전면과 측후면

- formation은 명확한 전면 arc와 footprint를 가진다.
- 전면 창벽은 기병 돌격을 저지하지만 측면과 후방에는 약하다.
- 측후면 충격은 즉시 대미지 배수보다 cohesion과 morale을 먼저 깎는다.
- 부대 겹침과 무리한 회전은 대형 질서를 낮춘다.

### 6.2 사기와 붕괴

- 입력: 최근 사상자, 측후면 피격, 가까운 아군 붕괴, 지휘관 거리, 피로, 보급, 목표 진행도.
- 전이: `STEADY -> WAVERING -> ROUTING -> SHATTERED`.
- rally는 지휘 연결, 안전 거리, 낮은 피로, 최근 추격 여부를 근거로 한다.
- 숨은 매 프레임 랜덤과 무한 aura 중첩은 금지한다.

### 6.3 피로와 속도

- 걷기, 달리기, 돌격, 교전, 경사 이동이 피로를 소모한다.
- 정지 또는 저강도 이동에서만 회복한다.
- 피로 임계값은 속도, cohesion 회복, 사기 저항, 명령 수행 지연을 바꾼다.

### 6.4 지휘와 명령 지연

```text
OrderIssued(tick)
  -> messenger/command delay 계산
  -> OrderActivated(tick + delay)
  -> FormationResponded 또는 OrderRejected
```

- 지연은 거리, 지휘 연결, 장수 통솔, 대형 질서, 시야와 신호망의 정수 함수다.
- 북, 깃발, 전령, 부장은 장식이 아니라 지휘 반경과 지연의 가시적 근거다.
- 새 명령이 기존 명령을 덮는 규칙과 만료 규칙을 replay에 남긴다.

### 6.5 지형과 보급

- 첫 지형은 도로, 평지, 숲, 얕은 물/나루, 경사 band, 관문만 연다.
- terrain chunk는 이동 비용, cover, 시야, 피로, 대형 가능 폭을 제공한다.
- 보급은 전투 중 매초 줄어드는 장식 수치가 아니라 사격 탄약, 장기 교전, 원군 투입, 퇴각선에 연결한다.

### 6.6 목표

첫 목표는 기존 실행 계획대로 **보급 마차 호송/차단** 하나로 고정한다.

```text
호송 측: convoy가 출구까지 도달하고 최소 보급량을 보존
차단 측: convoy를 정지시키거나 보급량을 임계치 아래로 감소
공통: 퇴각선을 잃거나 사기가 붕괴하면 조기 종료 가능
```

이후 관문 방어, 고지 점유, 퇴로 확보, 지휘부 압박을 같은 objective state machine으로 추가한다.

## 7. 첫 4개 RecruitableVariant

정확한 역사 진형명을 날조하지 않고 기능 중심 이름을 쓴다. 기존 문서의 선형/종대/방진 중 `방진`은 나폴레오닉 규칙과 혼동될 수 있으므로 삼국지 초기 roster에서는 제외한다.

| RecruitableVariant (parent FormationTemplate) | 역할 | 강점 | 약점 | 대표 명령 |
|---|---|---|---|---|
| 징발 창병 (parent `FormationTemplate`: 징발 창병) | line-holder, anti-charge | 전면 저지, 좁은 길 방어 | 측후면, 피로, 낮은 숙련 | `HOLD`, `BRACE`, `ADVANCE` |
| 노수대 (parent `FormationTemplate`: 노수대) | ranged pressure | 준비된 일제사격, 접근 저지 | 재장전, 근접전, 탄약 | `VOLLEY`, `HOLD_FIRE`, `WITHDRAW` |
| 경기 창기병대 (parent `FormationTemplate`: 경기병대) | scout, shock, pursuit | 정찰, 측면 돌입, 패주 추격 | 창벽, 숲, 장기 교전 | `SCOUT`, `FLANK`, `CHARGE`, `DISENGAGE` |
| 수송 호위대 (parent `FormationTemplate`: 수송호위대) | support, escort | 보급 보호, 진로 유지 | 낮은 화력, 기동 제약 | `ESCORT`, `SCREEN`, `REROUTE`, `ABANDON_LOAD` |

첫 대형 template은 `MARCH_COLUMN`, `BATTLE_LINE`, `BRACED`, `LOOSE_ORDER`, `ESCORT_SCREEN` 다섯 개면 충분하다.

### 전체 roster와의 관계

위 4개는 기술 증명용이지 v2 최종 병종 목록이 아니다. v1의 34/47개 numeric roster도 v2 정본으로 승격하지 않는다.

- 채택된 baseline인 72개 named core와 48개 근거 대기 예산은 `2026-07-13-v2-troop-building-content-catalog.md`가 정의한다.
- **PROPOSED — v2 roster catalog:** [2026-07-29-v2-expanded-recruitable-unit-catalog.md](2026-07-29-v2-expanded-recruitable-unit-catalog.md)를 따른다. 제안 모델은 이름 있는 `FormationTemplate` parent 80개와 `RecruitableVariant` row 105개, 미래 확장을 위한 `BUDGET_ONLY` `CatalogBudgetSlot` 48개로 구성한다. 48개 budget slot은 parent가 아니며 `NAMED`가 될 때까지 name/ID/template/variant/asset이 없다. `ACTIVE` 전에는 asset/claim/fixture를 준비한다.
- **PROPOSED — runtime identity:** runtime `FormationState`/formation은 parent template만이 아니라 구체적인 `recruitableVariantId`와 고정된 `catalogRevision`을 참조해야 한다. 모델 계보는 `FormationTemplate` → deduplicated `VariantRecipe` → `RecruitableVariant` → `FormationState`다.
- **PROPOSED — variant boundaries:** 전투 중 variant switching은 없다. 장비 품질·훈련·병력·사기·피로는 계속 `FormationState` field이며 variant를 만들지 않는다.
- **PROPOSED — visual ownership:** 활성 variant마다 compositional visual recipe가 있고 animation chassis는 공유한다. parent는 tradition icon/banner/base art를 소유한다.
- v1 numeric ID는 migration crosswalk에만 남고 v2 runtime ID와 sprite ID의 정본이 되지 않는다.

## 8. 2.5D 클라이언트

### 장면 계층

```text
terrain chunks
  -> roads/water/cover decals
  -> objective and supply nodes
  -> formation shadows
  -> animated unit sprites, y-depth sorted
  -> banners, officers, drums
  -> command lines, facing arcs, ranges
  -> fog of war and UI
```

- 카메라는 정사영에 가까운 isometric pan/zoom을 사용한다.
- terrain은 타일 atlas로 만들 수 있지만 formation 좌표는 연속값이다.
- 8방향은 simulation facing을 가장 가까운 시각 방향으로 양자화해 고른다.
- y-depth 정렬은 렌더 순서일 뿐 판정 순서가 아니다.
- 클릭은 sprite alpha가 아니라 formation footprint와 selection hull을 사용한다.
- 화면에는 가까운 거리에서 여러 병사, 중거리에서 축약 병사, 원거리에서 깃발/formation token만 보이는 3단 LOD를 둔다.

### 렌더러

기본 renderer는 기존 공간 계약과 이어지는 Three.js다.

- `OrthographicCamera`로 정사영 지휘 시점을 만든다.
- terrain chunk와 route는 낮은 높이의 mesh/decal로 둔다.
- unit representative는 `Sprite`/`SpriteMaterial` 또는 atlas UV를 갱신하는 instanced billboard로 표시한다.
- atlas는 같은 texture source를 공유하고 frame별 offset/repeat 또는 custom instance UV로 재생한다.
- transparent sprite 정렬에 판정을 의존하지 않고 formation별 render order와 depth 정책을 명시한다.
- 순수 2D PixiJS renderer는 WebGL 불가 fallback 또는 별도 정보 모드의 후보로만 남긴다.

초기 성능 예산:

- 첫 시나리오는 약 8 formations, 플레이어 직접 지휘는 최대 3-4개다.
- 1080p 데스크톱에서 32 formations, cosmetic sprite 약 1,000개를 load gate로 둔다.
- 전술 overlay를 켠 상태에서 p95 frame 16.7ms 이하를 목표로 한다.
- simulation snapshot은 5Hz, 화면 보간은 render frame에서 수행한다.
- 모바일 30fps와 2,000 sprite 확대는 V2-8 별도 gate로 둔다.

### 네트워크

> **SUPERSEDED:** 아래 HTTP/SSE와 game-engine scheduler 제안은 채택하지 않는다. 전용 battle-engine, WebSocket, battle별 session epoch/actor 계약은 2026-07-30 승인 스펙 §§5–15를 따른다.

- 첫 수직 슬라이스는 기존 구조를 재사용해 `HTTP order intake + SSE battle snapshot/event`로 시작한다.
- 5Hz snapshot과 200ms tick에서 p95 명령 활성화 지연을 측정한다.
- SSE가 병목이라는 실측 전에는 WebSocket 의존성을 추가하지 않는다.
- 명령은 1초 단위 window에 넣고 formation당 최대 3개를 예약한다.
- UI는 `QUEUED -> DISPATCHED -> RECEIVED -> ACTIVE`를 보여줘 지휘 지연을 네트워크 오류처럼 느끼지 않게 한다.
- 별도 `@Scheduled(200ms)` writer를 만들지 않고 world-owned 단일 scheduler가 battle tick을 stable battle id 순서로 실행한다.
- battle event/checkpoint는 5 tick 단위로 v2 flush payload에 묶고, 최종 replay와 campaign 정산은 원자적으로 확정한다.

## 9. PerfectPixel 자산 계약

### 생성 대상

| Bundle | 핵심 상태 | 방향 세트 |
|---|---|---|
| `han-levy-spearman` | `idle-combat`, `stab` | `walk` 8방향 |
| `han-crossbowman` | `idle-combat`, `shoot`, `reload` | `walk` 8방향 |
| `han-light-cavalry` | `idle-combat`, `charge-attack` | `run` 8방향 |
| `han-supply-escort` (candidate/quarantined) | `idle-combat`, `guard/screen` | `walk` 8방향 |
| `han-wagon-convoy` (PLANNED; NOT GENERATED) | objective/platform: `move`, `damaged`, `abandoned`, `destroyed` | route-facing states |

PerfectPixel의 `sprite-sheet.png`와 `sprite-sheet.json`을 import 쌍으로 쓰고, 원본 `manifest.json`, 상태별 GIF/APNG, 개별 PNG frame을 QA와 수정용으로 보존한다.

### 생성 완료 결과

2026-07-28에 `pixel` 스타일, 256px cell로 네 bundle을 생성했다. 아래 score는 PerfectPixel 생성기의 상태별 자기평가이며, 별도 역사 고증 승인 점수가 아니다. `han-supply-escort`의 기존 ignored pilot은 실제로 `carry`와 baggage를 포함하므로 production 자산으로 채택하지 않고 candidate/quarantined로 production 재생성한다. #18은 guard/screen 전투 인력이며 production action은 `guard/screen`이어야 한다.

| Bundle | Sheet | Animation | 생성 score | 육안 QA |
|---|---:|---:|---|---|
| `han-levy-spearman` | 1536x2560 | 10 | idle 65, stab 59, walk 65-67 | 긴 창과 세로 방패, 찌르기, 8방향 실루엣이 구분됨 |
| `han-crossbowman` | 1536x2816 | 11 | idle 65, shoot 63, reload 67, walk 64-66 | 쇠뇌, 사격, 재장전이 프레임에서 구분됨 |
| `han-light-cavalry` | 1536x2560 | 10 | idle 65, charge 63, run 62-64 | 말과 기수 비례, 돌격과 8방향 진행이 일관됨 |
| `han-supply-escort` (ignored pilot; candidate/quarantined) | 1536x2560 | 10 | idle 63, carry 66, walk 64-66 | 기존 pilot의 carry/baggage 사실 기록이며 production 승인 아님 |

- 네 manifest 모두 요청한 animation과 frame 수를 가지며, 서쪽 계열은 동쪽 원본을 의도적으로 mirror한 결과다.
- 각 bundle에는 `base.png`, `manifest.json`, `sprite-sheet.png`, `sprite-sheet.json`, `frames/`, `gif/`, `apng/`가 있다.
- 실제 검수 경로는 `build/perfectpixel/v2-battle/<bundle>/`이고 `build/`는 git-ignore이므로 설계 승인 전 저장소 자산으로 승격하지 않는다.
- 이 결과는 renderer sandbox용 파일럿이다. 최종 채택 전에는 실제 96/64/36px LOD, 진영색 overlay, 군집 배치에서 다시 판독성을 채점한다.
- `han-wagon-convoy`는 호송/차단 objective의 별도 platform asset으로 **PLANNED / NOT GENERATED** 상태다. `move`, `damaged`, `abandoned`, `destroyed` 상태가 없으면 convoy slice는 asset completeness를 통과할 수 없다.
- 채택 자산은 manifest hash, 비어 있지 않은 `sourceRefs`, expected frame/direction manifest, 64px QA를 모두 가져야 한다. ignored build artifact와 생성기 self-score는 승인 증거가 아니다.

### 런타임 메타데이터

PerfectPixel manifest 옆에 다음 프로젝트 메타데이터를 둔다.

```json
{
  "schema": 1,
  "period": "late-eastern-han-three-kingdoms",
  "region": "generic-han-core",
  "historicalConfidence": "mixed",
  "anchor": {"x": 0.5, "y": 0.92},
  "renderHeightPx": {"near": 96, "mid": 64, "far": 36},
  "directionOrder": ["n", "ne", "e", "se", "s", "sw", "w", "nw"],
  "formationTemplateId": "formation.conscript_spear",
  "recruitableVariantId": "variant.conscript_spear.line_spear",
  "catalogRevision": "2026-07-29-v2-expanded-recruitable-unit-catalog",
  "sourceRefs": ["https://www.kyuhaku.jp/en/exhibition/exhibition_s56.html"]
}
```

- collision, range, footprint는 이미지 alpha나 frame 크기에서 계산하지 않는다.
- faction 색은 전신 tint 대신 띠, 깃발, 방패 문양 overlay로 분리한다.
- 서로 다른 병종도 동일한 발 anchor와 지면 scale을 가져야 한다.
- 원본 frame은 256px여도 런타임은 96/64/36px LOD로 표시한다.
- 바이너리는 `build/perfectpixel/v2-battle/`에서 검수하고, 채택 뒤 별도 asset repo/CDN으로 승격한다. 메인 repo에는 커밋하지 않는다.

## 10. 역사 아트 지침

가장 강한 기준은 규슈국립박물관의 동한-삼국 발굴품 전시 도록이다.

- 창병: 긴 목제 장대와 납작한 철제 창날. 소박한 튜닉/바지/두건, 일부만 경갑. 전원 중장갑을 피한다.
- 방패: 촉 지역 3세기 `Gou Xiang`처럼 세로로 길고 중앙이 돌출되며 위아래가 연장된 실루엣을 후보로 쓴다.
- 노수: 목제 몸통과 작은 청동 trigger, 짧은 철촉 bolt. 반복쇠뇌, 크랭크, 후대식 winch를 기본병에 쓰지 않는다.
- 기병: 소형 말, 단순 굴레와 안장천, 경갑 기수. 전면 마갑, 판금, 과장된 등자, 거대한 청룡언월도는 피한다.
- 수송호위: 수레와 기병 호위, 곡물 자루/상자/항아리, 흑/적 2단 술 장대를 조합한다.
- 북, 깃발, 전령은 소수 지휘 요소로 사용한다.
- 무덤 벽화와 명기는 실루엣 근거이지 표준 편제 숫자나 적재량의 정량 근거가 아니다.
- 진 병마용, 당/송/명식 갑주, 로맨스 전용 특수무기를 삼국 표준 장비로 섞지 않는다.

근거:

- [Kyushu National Museum, Three Kingdoms exhibition](https://www.kyuhaku.jp/en/exhibition/exhibition_s56.html)
- [Kyushu National Museum, illustrated list of works](https://www.kyuhaku.jp/exhibition/img/s_56/exhibition_s56-en.pdf)
- [National Palace Museum, Wei crossbow trigger dated 228](https://theme.npm.edu.tw/selection/Article.aspx?lang=2&sNo=04009341)
- [The Met, Han crossbow trigger](https://www.metmuseum.org/art/collection/search/61049)
- [The Met, Han horse and cavalry rider](https://www.metmuseum.org/art/collection/search/44410)
- [The Met, Eastern Han war drum](https://www.metmuseum.org/art/collection/search/61044)
- [Henan Museum, Han pictorial brick battle scene](https://english.chnmus.net/en/collection/details.html?id=418102576617446891)

## 11. 참고 게임에서 가져올 것과 버릴 것

가져올 것:

- 장수별 retinue를 명령 집단으로 묶는 구조.
- 역할이 분명한 소수 병종.
- formation, 전면/측후면, 사기, 피로, 지형, 추격.
- 섬멸 외 목표와 예비대.
- 명령 지연과 보고를 설명 가능한 event로 만드는 것.

버릴 것:

- Total War의 3장수 x 6부대 고정 수치를 그대로 복사.
- 오행 기반 거대한 상성 배수와 과도한 tier stat inflation.
- 개별 병사 물리, 실시간 flocking, 클라이언트 판정.
- TROM mod의 모집 제한과 수치를 역사 또는 공식 정본으로 취급.
- 영웅 한 명이 부대 전체를 지우는 기본 규칙.

출처:

- [Total War: Three Kingdoms licensed manual](https://www.feralinteractive.com/en/manuals/threekingdomstw/1.0/steam/?access=zooevrj6xb)
- [Total War Academy, retinues](https://academy.totalwar.com/3k-retinues/)
- [Total War manual, morale](https://r2enc.totalwar.com/en/manual/single-player/0087_enc_page_battle_play_phase_conflict_morale/)
- [Steam TROM guide, community-authored](https://steamcommunity.com/sharedfiles/filedetails/?id=1978468851)

참고 링크 접근 상태:

- Naver 링크의 글 제목과 외부 링크는 확인했다. 글은 "머그삼국지 추억에서 시작한 실시간 공성 웹게임" 베타 모집이고 `neo-wars.com`으로 연결되지만, 게임 본문은 Google 로그인 뒤라 기능 근거로 사용하지 않았다.
- NamuWiki 부대 페이지는 웹 수집기와 브라우저 정책에서 접근되지 않아 근거로 사용하지 않았다.
- Steam TROM 문서는 읽혔지만 커뮤니티 mod 설계이므로 영감으로만 사용했다.

## 12. 구현 순서

> **SUPERSEDED:** 아래 P0–P4는 역사적 초안이다. 공통 전투 기반의 실제 foundation-first 순서는 2026-07-30 승인 스펙 §23과 후속 implementation plan을 따른다.

### P0. Asset/renderer sandbox

- 생성된 4개 bundle을 독립 전장 sandbox에 로드한다.
- 카메라 pan/zoom, picking, 8방향 전환, y-depth, faction overlay, 3단 LOD를 검증한다.
- 서버나 v1 전투 코드와 연결하지 않는다.

### P1. V2-B0 deterministic kernel

- integer `BattleState`, `FormationState`, `OrderIntent`, `BattleEvent`를 만든다.
- 창병 1 vs 경기병 1 fixture로 이동, 전면 돌격, 측면 돌격, 사기 붕괴를 검증한다.
- renderer 없이 같은 명령과 seed가 같은 replay hash를 만드는지 먼저 증명한다.

### P2. V2-4A replay spine

- `Operation -> BattleSession -> approach/field/aftermath -> campaign result`를 연결한다.
- read-only replay timeline과 canonical hash gate를 만든다.

### P3. V2-4B 호송/차단 수직 슬라이스

- 4 formation, 1 route, 1 convoy, 2 objective만 사용한다.
- `MOVE`, `HOLD`, `BRACE`, `VOLLEY`, `CHARGE`, `WITHDRAW`, `COMMIT_RESERVE`를 연다.
- SSE snapshot과 HTTP order intake로 실제 브라우저에서 끝까지 플레이한다.

### P4. 확장

- 관문 방어, 숲/나루, 원군, 협동 지휘, 공성, 시가전은 수직 슬라이스 이후 하나씩 연다.
- full 3D는 동일 kernel/replay 위의 대체 renderer로만 재평가한다.

## 13. 수용 게이트

- v1 battle golden, RNG draw, 로그, 예약 링에 diff 0.
- 동일 initial snapshot + ordered commands + seed를 100회 실행해 replay body/hash diff 0.
- renderer를 끄고도 전투 결과가 동일함.
- formation의 측면 돌격, 피로 누적, 지휘 단절, 보급 차단이 각각 다른 replay reason을 남김.
- 호송/차단을 섬멸 없이 승리할 수 있음.
- 32 formations/약 1,000 animated sprites/전술 overlay에서 1080p p95 frame 16.7ms 이하.
- PerfectPixel 각 상태가 expected frame 수와 일치하고 score 50 이상. 미달 상태는 그대로 채택하지 않음.
- 브라우저 AC: 배치 -> 명령 -> 붕괴/퇴각 -> 목표 종료 -> replay -> campaign 변화가 한 흐름에서 관측됨.

## 14. 사람 결정 필요

> **RESOLVED / SUPERSEDED:** 아래 질문은 2026-07-30 승인 인터뷰로 해소됐다. 승인 결과는 전용 battle-engine, 실시간+제한정지, 사람당 편제 1개, 총지휘관+위임 장교, 32편제 출시 기준, 야전·공성·수전 V2 출시 필수, 결정적 resume+deadline headless fallback이다. 세부 정본은 승인 스펙을 따른다.

1. 전투를 현 로드맵대로 v2 오픈 후에 둘지, ADR-LITE-019/021을 개정해 오픈 범위로 당길지.
2. Three.js 정사영 billboard 2.5D를 기본 surface로 승인할지.
3. 전투 조작을 계속 흐르는 fixed-tick + 1초 예약 명령 window로 할지.
4. 첫 시나리오를 약 8 formations, 직접 지휘 3-4개로 제한할지.
5. 화면 밀도를 소수 미니어처형으로 할지, formation당 12-24명 군집형으로 할지.
6. 장수를 formation 안의 지휘관 sprite로만 둘지, 선택적 결투/영웅 규칙을 열지.
7. 역사 고증과 연의 상징성의 기본 비율을 어디에 둘지.
8. 첫 시나리오를 계획대로 호송/차단으로 확정할지, 관문 방어를 먼저 보여줄지.
9. 첫 출시는 1인 지휘 + AI 위임으로 고정하고 협동 지휘를 뒤로 미룰지.
10. crash 시 checkpoint + order stream으로 결정적 resume하고 자동 결산은 금지할지.

권장 기본값은 `v2 오픈 후`, `Three.js 2.5D`, `계속 흐르는 fixed-tick + 1초 예약 window`, `8개 중 3-4개 직접 지휘`, `12-24명 군집`, `장수는 지휘관`, `고증 70/연의 30`, `호송/차단`, `1인 지휘`, `결정적 resume`다.
