# 하드코딩 인벤토리 (HARDCODE_INVENTORY)

> 풀-패러티(0.9.0) 진입 게이트용 하드코딩/스텁/날조값 종합 대장.
> 스캐너 실측(web/game · web/gateway · app) findings를 file:line 근거로 종합.
> **PHP legacy = grand truth.** 날조 금지. 빌드 금지(읽기전용 감사).

- **현 git HEAD:** `698b945` (`698b945 Merge pull request #49 from opensamguk/codex/web-frontinfo-identity`)
- **브랜치:** `codex/npc-policy-default`
- **작성일:** 2026-06-07
- **legacy 정본 참조:** `legacy/devsam-core`(PHP grand truth), `legacy/devsam-core2026`(구조 2차 오라클). PHP가 모든 divergence에서 승.

## 합법값 제외 명시 (위반 아님)

본 대장은 **게임 상태/수치/표시값을 위조하는 하드코딩**만 위반으로 집계한다. 아래 카테고리는 hc 원칙상 **합법**이라 위반 표에서 제외했다(`GW-HC-04`, `GW-HC-05`는 참고용으로만 기록):

- **라우팅/구성 config** — `servers.json`의 라우팅 식별자(`id`/`name`/`gameUrl`/`gameApiUrl`)
- **패러티 상수** — PHP grand truth를 byte-faithful로 박은 상수(단, web 레이어에 박혀 정본 API를 우회하면 위반으로 본다 — 예: `D3-04`)
- **스타일/UI 텍스트** — 라벨·className·CSS
- **테스트 픽스처/골든**
- **백엔드 미구현 기능의 정직한 미완 표시** — `disabled` + "준비 중" 안내가 게임 상태를 위조하지 않고 단순히 "아직 없음"을 표시하는 경우(`GW-HC-04`)
- **로딩/에러/빈-세계 graceful degrade 폴백 UI** — read API 404/빈 데이터 시 crash 대신 폴백(`GW-HC-05`)

---

## 1) 요약 (영역별 위반 수)

| 영역 | 위반 수 | 참고(합법·기록용) |
|------|---------|-------------------|
| **web/game** | 4 | — |
| **web/gateway** | 0 | 2 (`GW-HC-04`, `GW-HC-05`) |
| **app** (game-api) | 7 | — |
| **합계** | **11** | 2 |

**위반 성격 분류:**
- 🔴 **상태 위조 / 날조값** (실제 데이터 대신 가짜): `SimulatorController .random()`(D3-01 app)
- 🟠 **본인 컨텍스트 미사용 + 디폴트 1 박음**: 2026-06-09 현재 web/game 활성 위반 없음. `D3-05/06/07(web)` 해소됨.
- 🟡 **값소스 부재 갭** (정본 영속 원천/엔진 미결합으로 0/null/false 고정 — 문서화된 BLOCKED): traffic/hall/emperor(D3-03 app), serverLocked(D3-04 app), iAction name/info(D3-07 app), compensation(D3-08 app)
- 🔵 **패러티 우회 / 인라인 중복** (정본 API·상수·resolver 대신 web/controller에 재구현): INHERIT_COSTS(D3-04 web), mapWidth/Height(D3-05 app), OFFICER_LEVEL_TEXT(D3-06 app)
- ⚪ **mutation 스텁** (read는 정상, write 경로만 미배선): MessagePanel(D3-08 web), npc-control setter(D3-09 web), coming-soon 감찰부(D3-01 web)
- 🟣 **dead export 잔재**: 현재 gateway 활성 위반 없음. `GW-HC-02`/`GW-HC-03`은 2026-06-09 제거됨.

---

## 2) 위반표

| ID | file:line | 박힌 값 | 대체할 정본 API/필드 | 영역 | 성격 |
|----|-----------|---------|----------------------|------|------|
| D3-01(web) | `web/game/app/game/coming-soon/page.tsx:18-19`, `web/game/lib/control-bar-config.ts:36,62` | `STUB='/game/coming-soon'` + 감찰부 href `?feature=감찰부`, "준비 중입니다" | 감찰부(v_audit) 실 read API + 페이지. coming-soon stub 제거 | web/game | ⚪ stub |
| D3-04(web) | `web/game/app/game/nation/page.tsx:43` | `INHERIT_COSTS = [0,200,600,1200,2000,3000]` | GameConst read API / InheritPointResponse가 비용 배열 제공. 정본 `hwe/sammo/GameConstBase.php:240 $inheritBuffPoints` | web/game | 🔵 패러티 우회 |
| D3-08(web) | `web/game/components/game/MessagePanel.tsx:89,97` | placeholder "서신 보내기 (서버 미지원)" disabled + "읽기 전용입니다" | game-api 메시지 전송/연락처 endpoint. 정본 `MessagePanel.vue`(SendMessage write + 연락처 selector) | web/game | ⚪ mutation stub |
| D3-09(web) | `web/game/app/game/npc-control/page.tsx:271,92-106` | "(설정 변경은 추후 지원)" + ControlBar disabled | NPC 정책 setter intake(POST) 배선 후 활성화. (표시 수치는 이미 API 소비 — 위반 아님, mutation stub만) | web/game | ⚪ mutation stub |
| D3-01(app) | `app/game-api/.../controller/SimulatorController.kt:14-22` | `winner = attackerId%2==1 ? attacker : defender`, `(100..500).random()`, `(50..300).random()`, `(3..10).random()`, log=고정 4줄 | `logic/war/processWar`(또는 BattleCommandContextBuilder) — `RandUtil(warSeed)` 정본. raw `.random()` 금지(RNG 규율 위반) | app | 🔴 위조 |
| D3-03(app) | `app/game-api/.../rank/RankReadService.kt:176,179-189,192` | `hallOfFame()=emptyList()`, `traffic()=0/empty`, `emperor()=emptyList()` | `general_access_log`·`hall`·`emperior`(통일사) 영속 원천(OQ-1/2/5) 도입 후 실 집계 | app | 🟡 값소스 부재 갭 |
| D3-04(app) | `app/game-api/.../controller/FrontInfoController.kt:386`, `dto/IdentityDto.kt:96-98` | `serverLocked = false` (interim) | PHP `SELECT plock FROM plock WHERE type='GAME'` 대응 plock 영속 원천 도입 후 실값 | app | 🟡 값소스 부재 갭 |
| D3-05(app) | `app/game-api/.../controller/GetConstController.kt:46-47` | `mapWidth=1000`, `mapHeight=714` (매직넘버) | `map/<code>.json`(MapJson.MapData.width/height, MapPreviewController가 이미 사용) 또는 GameConst 상수. 컨트롤러 리터럴 금지 | app | 🔵 패러티 우회 |
| D3-06(app) | `app/game-api/.../controller/GetConstController.kt:35-38` | `OFFICER_LEVEL_TEXT`(레벨-비의존 고정 맵) | `read/F4StateText.kt:73 officerLevelText(officerLevel, nationLevel)` (PHP `getOfficerLevelText` func_converter.php:522-565, 국가레벨별 변동). 단일 소스로 통합 | app | 🔵 인라인 중복 |
| D3-07(app) | `app/game-api/.../dto/GetConstDto.kt:105,107`, `controller/GetConstController.kt:148-162` | `name=null`, `info=null` 고정 (logic iAction 미결합) | `logic iAction buildXxxClass()->getName()/getInfo()` 인스턴스화 결합(현재 :logic 의존 BLOCKED) | app | 🟡 값소스 부재 갭 |
| D3-08(app) | `app/game-api/.../controller/ChiefCenterController.kt:210`, `dto/F4Dto.kt:207-208` | `compensation = 0` (중립 고정) | PHP `getCompensationStyle()`(보정 ▲/▼)를 `GeneralActionDefinition`에 포팅 후 실 flag | app | 🟡 값소스 부재 갭 |

---

## 3) 영역별 그룹

### 3.1 web/game (4건)

게임 인게임 프론트(`web/game`). 위반 성격이 세 갈래로 갈린다.

**A. 위조/패러티-우회 (1건) — 즉시 교정 가능, 백엔드 변경 적음**
- `D3-04(web)` nation 페이지 INHERIT_COSTS = PHP 패러티 상수를 web에 박음 → GameConst/InheritPoint read API가 비용 배열을 내려주도록 하고 소비. (web 레이어 박힘 = 정본 API 우회이므로 위반)

**해소됨 (5건, 2026-06-09)**
- `D3-02(web)` GameInfo "기타 설정: 자동" 제거. front-info `global.autorunUser.limit_minutes > 0`일 때만 legacy `AutorunInfo` 동치 텍스트 `자율행동` 렌더.
- `D3-03(web)` GameInfo tournamentTerm은 `web/game/lib/utilGame/calcTournamentTerm`(`clamp(turnterm,5,120)`) 사용.
- `D3-05(web)` nation: 현재 코드 기준 `nationId/generalId=1` 디버그 입력 없음. `api.myNationDetail()`/`api.inheritPoint()` identity read surface 사용.
- `D3-06(web)` mailbox: `useFrontInfo()`로 본인 `generalId`/`nationId`를 읽고, 개인=`generalId`·국가=`9000+nationId`·전체=`9999` 규칙으로 mailbox read/accept/decline 수행. number-input 제거.
- `D3-07(web)` tournament-admin: `DEFAULT_ADMIN_GENERAL_ID=1` 제거. `useFrontInfo()`의 본인 `generalId`와 `permission >= 2` 수뇌부 게이트를 사용. number-input 제거.

**B. mutation 스텁 (3건) — read는 정상, write 경로 백엔드 필요**
- `D3-01(web)` coming-soon: 감찰부(v_audit)만 잔존 stub. 실 read API+페이지로 대체.
- `D3-08(web)` MessagePanel: 서신 전송/연락처 API 부재 → disabled. game-api send endpoint 정본화 후 활성화.
- `D3-09(web)` npc-control: 설정 setter 미배선 disabled. (표시 수치는 이미 API 소비 — mutation stub만 위반)

### 3.2 web/gateway (0건 위반 + 2건 참고)

게이트웨이 로비/어드민(`web/gateway`).

**해소됨 (3건, 2026-06-09)**
- `GW-HC-01` ServerBoard 탭 status 뱃지 제거. 서버 상태는 lobby row의 live `/api/server-basic-info` 응답(`game.isUnited`)만 사용한다.
- `GW-HC-02` `ServerEntry.turnterm` 제거. 턴텀은 live `server-basic-info game.turnTerm`만 사용한다.
- `GW-HC-03` `SERVER_STATUS`/`competingLabel`/`timelineYear`/`timelineUsers` dead export 제거.

**참고용 (합법, 위반 아님)**
- `GW-HC-04` lobby 계정관리 / admin 회원관리·게임환경 "PLACEHOLDER 준비 중" disabled → 백엔드 미구현 기능의 정직한 미완 표시. 게임 상태 위조 아님. 백엔드 구현 시 실 핸들러로 교체.
- `GW-HC-05` ServerLog / MapPreview "준비 중" → read API 404/빈-세계 graceful degrade 폴백 UI. 정본 데이터 200 시 자동 실데이터 렌더.

### 3.3 app (game-api, 7건)

백엔드 read API 컨트롤러. 두 갈래.

**A. 위조/날조 (1건) — 정본 엔진 결합으로 교정**
- `D3-01(app)` SimulatorController: 전투 결과 전부 `.random()` 날조 + 고정 4줄 로그. **RNG 규율 정면 위반** (raw `.random()`은 `RandUtil(LiteHashDrbg)` 우회). `logic/war/processWar` 결합 필수.

**해소됨 (1건, 2026-06-09)**
- `D3-02(app)` NpcPolicyController: 인라인 defaultPolicy 제거. `logic/ai/AutorunNationPolicy.DEFAULT_POLICY`가 PHP `AutorunNationPolicy::$defaultPolicy`(`reqNationGold=10000`, `reqNationRice=12000`, PHP 부재 키 제외)를 단일 소스로 직렬화한다.

**B. 인라인 중복 / 매직넘버 (2건) — 정본 resolver·리소스로 통합**
- `D3-05(app)` GetConstController mapWidth=1000/mapHeight=714 매직넘버 → `map/<code>.json` 또는 GameConst.
- `D3-06(app)` GetConstController OFFICER_LEVEL_TEXT 인라인(레벨-비의존) ↔ 정본 `F4StateText.officerLevelText`(국가레벨별 변동)와 불일치 → 단일 소스 통합.

**C. 값소스 부재 갭 (4건) — 영속 원천/엔진 결합 BLOCKED, 문서화됨**
- `D3-03(app)` RankReadService traffic/hall-of-fame/emperor → `general_access_log`·`hall`·`emperior`(OQ-1/2/5) 테이블 부재로 0/빈 고정.
- `D3-04(app)` FrontInfoController serverLocked=false → plock 테이블 부재로 interim 고정.
- `D3-07(app)` GetConstDto iAction name/info=null → logic iAction 미결합(:logic 의존 BLOCKED).
- `D3-08(app)` ChiefCenterController compensation=0 → getCompensationStyle 미포팅.

> **갭(🟡) 4건은 "지금 고칠 수 있는 위반"이 아니라 "값소스 부재로 채울 수 없는 BLOCKED 항목"이다.** 영속 원천(plock/access_log/hall/emperior 테이블) 또는 :logic 결합이 선행 조건. 본 대장에 위반으로 집계하되 우선순위는 마지막 단계.

---

## 4) "전면 구현 시 일괄 수정" 순서 제안

의존성 선후 + ROI(고치는 비용 대비 위조 제거 효과) 기준. 앞쪽일수록 싸고 효과 큼.

### 단계 1 — 순수 프론트 교정 (백엔드 변경 0, 즉시) — 완료
백엔드 의존 없이 web 레이어만 수정. 정본 함수/필드가 이미 존재.
1. ✅ `D3-03(web)` GameInfo tournamentTerm → `clamp(turnterm,5,120)` (한 줄)
2. ✅ `D3-02(web)` GameInfo "자동" → `global.autorunUser` 동적 AutorunInfo
3. ✅ `GW-HC-02` servers.json turnterm + serverRegistry.ts ServerEntry.turnterm 삭제 (dead field)
4. ✅ `GW-HC-03` constants.ts dead export 4개 삭제
5. ✅ `GW-HC-01` ServerBoard 탭 뱃지 제거. 서버가 없으면 로그인/로비 맵·로그·서버탭 전체 미렌더.

### 단계 2 — useFrontInfo 본인 컨텍스트 배선 (web/game) — 완료
`useFrontInfo()`/`front-info`로 디폴트 id 1 + number-input을 본인 컨텍스트로 교체.
6. ✅ `D3-05(web)` nation 페이지 — 현재 코드 기준 디폴트 id 1 입력 없음 확인.
7. ✅ `D3-06(web)` mailbox 페이지 — 개인/9000+nationId/9999 규칙 + accept/decline mutation의 본인 id
8. ✅ `D3-07(web)` tournament-admin 페이지 — 본인 generalId + permission gate

### 단계 3 — 정본 상수/resolver 단일 소스화 (BE read API) — 3건
백엔드에서 정본 정책/상수/resolver를 단일 소스로 직렬화. web의 박힌 상수는 이 API를 소비하도록 후속 전환.
9. ✅ `D3-02(app)` NpcPolicyController → `AutorunNationPolicy.DEFAULT_POLICY` 직렬화 (날조 키 제거)
10. `D3-06(app)` GetConstController OFFICER_LEVEL_TEXT → `F4StateText.officerLevelText` 통합
11. `D3-05(app)` GetConstController mapWidth/Height → `map/<code>.json` / GameConst
12. `D3-04(web)` nation INHERIT_COSTS → 단계 3에서 노출된 GameConst/InheritPoint read API 소비 (BE 노출 후 web 전환)

### 단계 4 — 엔진/정책 결합 (위조 제거 핵심) — 1건 + 1건 부분
가장 무겁지만 가장 큰 위조 제거. RNG 규율 위반 해소.
13. `D3-01(app)` SimulatorController → `logic/war/processWar`(`RandUtil(warSeed)`) 결합. raw `.random()` 전면 제거. **패러티 RNG 규율 위반이므로 단계 4 중 최우선.**

### 단계 5 — mutation 경로 배선 (BE write endpoint 필요) — 3건
read는 이미 정상. write intake/endpoint 추가 후 프론트 활성화.
14. `D3-09(web)` npc-control setter intake(POST) → ControlBar 활성화
15. `D3-08(web)` MessagePanel send/연락처 endpoint → 입력 활성화
16. `D3-01(web)` 감찰부(v_audit) read API + 페이지 → coming-soon stub 제거

### 단계 6 — 값소스 부재 갭 해소 (영속 원천/결합 선행 — BLOCKED) — 4건
영속 테이블 또는 :logic 결합이 선행 조건. 데이터 모델 작업이라 마지막.
17. `D3-04(app)` plock 테이블 → serverLocked 실값
18. `D3-08(app)` getCompensationStyle 포팅 → compensation 실 flag
19. `D3-07(app)` logic iAction 결합 → name/info 실값
20. `D3-03(app)` access_log/hall/emperior 테이블 → traffic/hall/emperor 실 집계 (가장 무거운 데이터 모델 작업)

---

### 부록 — 우선순위 한눈에

| 단계 | 항목 수 | 핵심 | 비용 | 위조 제거 효과 |
|------|---------|------|------|----------------|
| 1 순수 프론트 | 5 | 정본 함수/필드 이미 존재 | 매우 낮음 | 중(상태 위조 GW-HC-01 포함) |
| 2 useFrontInfo | 3 | 본인 컨텍스트 배선 | 낮음 | ✅ 완료(id 1 고정 제거) |
| 3 BE 단일소스 | 3 | 정본 상수/resolver 직렬화 | 중 | 중(D3-02 app 완료) |
| 4 엔진 결합 | 1 | processWar 결합 | 높음 | **매우 높음(RNG 위반)** |
| 5 mutation | 3 | write endpoint | 중~높음 | 낮음(read 정상) |
| 6 값소스 갭 | 4 | 영속 테이블/결합 BLOCKED | 매우 높음 | 중(0/null/false 실값화) |

**즉효 권장:** 단계 1+2 (8건, 백엔드 거의 무변)로 위반 20→12 감소. **위조 우선:** `D3-02(app)` 날조 키는 해소됨. 남은 최우선 위조 항목은 `D3-01(app)` RNG 위반이다.
