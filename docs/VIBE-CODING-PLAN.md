# 🎮 OpenSam 바이브 코딩 플랜 v2

> **원칙**: 감사 보고서 믿지 말고 직접 코드 확인. 레거시에 있으면 반드시 포함. core2026에만 있으면 추가.

---

## 수정 대상 vs 참조 소스

| 폴더 | 역할 | 스택 | 액션 |
|------|------|------|------|
| **`backend/`** | 운영 백엔드 | Kotlin/Spring Boot (313 .kt) | ✏️ **수정** |
| **`frontend/`** | 운영 프론트엔드 | Next.js App Router (55 pages) | ✏️ **수정** |
| `legacy/` | 원본 레거시 | PHP (96 commands) | 👀 참조 |
| `core2026/` | TS 리라이트 | pnpm monorepo (384 .ts) | 👀 참조 |

---

## Phase 1: 백엔드 커맨드 패러티 🎯

**목표**: legacy PHP + core2026 TS의 모든 로직이 backend Kotlin에 동등하게 구현

### 1A. 장수(General) 커맨드 (56개 .kt)

**비교 방식**: legacy PHP ↔ core2026 TS ↔ backend Kotlin 3자 비교 → Kotlin 수정

| 배치 | 커맨드 (가나다순) | 수량 |
|------|-------------------|------|
| b1 | NPC능동, 강행, 거병, 건국, CR건국, 견문, 군량매매, 귀환, 기술연구, 농지개간, 내정특기초기화 | 11 |
| b2 | 단련, 등용, 등용수락, 랜덤임관, 모반시도, 모병, 무작위건국, 물자조달, 방랑, 사기진작, 상업투자 | 11 |
| b3 | 선동, 선양, 성벽보수, 소집해제, 수비강화, 숙련전환, 요양, 은퇴, 이동, 인재탐색, 임관 | 11 |
| b4 | 장비매매, 장수대상임관, 전투태세, 전투특기초기화, 접경귀환, 정착장려, 주민선정, 증여, 집합, 징병, 첩보 | 11 |
| b5 | 출병, 치안강화, 탈취, 파괴, 하야, 해산, 헌납, 화계, 훈련, CR맹훈련, 휴식, DomesticCommand | 12 |

### 1B. 국가(Nation) 커맨드 (38개 .kt)

| 배치 | 커맨드 | 수량 |
|------|--------|------|
| b6 | 감축, 국기변경, 국호변경, 급습, 몰수, 무작위수도이전, 물자원조, 발령 | 8 |
| b7 | 백성동원, 부대탈퇴지시, 불가침수락, 불가침제의, 불가침파기수락, 불가침파기제의, 선전포고, 수몰 | 8 |
| b8 | 의병모집, 이호경식, 종전수락, 종전제의, 증축, 천도, 초토화, 포상 | 8 |
| b9 | 피장파장, 필사즉생, 허보, cr_인구이동, Nation휴식 + event 연구 9개 | 14 |

### 1C. 백엔드 엔진/서비스 패러티

legacy + core2026에 있지만 backend에 부족한 영역:
- `backend/.../engine/` — 전투 엔진, 턴 엔진, AI
- `backend/.../engine/ai/` — NPC AI (GeneralAI.php → Kotlin)
- `backend/.../engine/war/` — 전투 시스템
- `backend/.../command/constraint/` — 제약 시스템
- `backend/.../service/` — 29개 서비스 vs legacy API 비교

### 산출물
- `docs/command-parity/batch{N}-report.md` — 각 배치별 패러티 리포트
- backend Kotlin 코드 직접 수정

---

## Phase 2: 프론트엔드 패러티 🖥️

**목표**: legacy PHP+Vue UI의 모든 정보/기능이 frontend Next.js에 존재

### 2A. 페이지별 기능 비교 (55 pages → frontend/ 수정)

**비교 방식**: legacy PHP+Vue ↔ core2026 Vue ↔ frontend Next.js 3자 비교 → Next.js 수정

| 배치 | 영역 | 레거시 소스 | Next.js 대상 | 수량 |
|------|------|------------|-------------|------|
| f1 | Auth | `hwe/index.php`, `ts/gateway/` | `frontend/src/app/(auth)/` | 3p |
| f2 | Lobby | `hwe/v_join.php`, `ts/v_join.ts`, `select_npc` | `frontend/src/app/(lobby)/` | 4p |
| f3 | Main + General/City | `PageFront.vue`, `b_genList.php`, `b_currentCity.php` | `frontend/src/app/(game)/` | 6p |
| f4 | Nation + Superior + MyPage | `b_myKingdomInfo.php`, `b_myBossInfo.php`, `b_myPage.php` | `frontend/src/app/(game)/` | 6p |
| f5 | Commands + Processing | `ts/processing/*.vue`, `PartialReservedCommand.vue` | `frontend/src/app/(game)/commands,processing/` | 2p |
| f6 | Map + Diplomacy + Board | `v_cachedMap.php`, `v_globalDiplomacy.php`, `v_board.php` | `frontend/src/app/(game)/` | 3p |
| f7 | Battle Center + Simulator + Troop | `v_battleCenter.php`, `battle_simulator.php`, `v_troop.php` | `frontend/src/app/(game)/` | 3p |
| f8 | Auction + History + Inherit + Vote | `v_auction.php`, `v_history.php`, `v_inheritPoint.php`, `v_vote.php` | `frontend/src/app/(game)/` | 4p |
| f9 | Chief + NPC Control + Tournament | `v_chiefCenter.php`, `v_NPCControl.php`, `b_tournament.php` | `frontend/src/app/(game)/` | 3p |
| f10 | Nation Mgmt pages | `v_nationGeneral.ts`, `PageNationStratFinan.vue` | `frontend/src/app/(game)/` | 6p |
| f11 | Messages + Betting + Traffic + NPC List + Best Generals | various | `frontend/src/app/(game)/` | 5p |
| f12 | Admin pages | admin-specific | `frontend/src/app/(admin)/` | 8p |
| f13 | Dynasty + Emperor + Hall of Fame + Spy | various | `frontend/src/app/(game)/` | 5p |

### 2B. 백엔드 API 커버리지
- legacy `j_*.php` API → backend Kotlin controller 매핑
- 프론트엔드가 필요로 하는데 backend에 없는 API 식별 → 구현

### 2C. core2026 전용 기능
- core2026 Vue 프론트엔드에만 있는 기능 → Next.js에 추가

---

## Phase 3: 디테일 ✨

**목표**: 이미지, UI 세부사항, 한글 메시지 보강

### 3A. 이미지/리소스 (frontend/ 수정)
- 장수/도시/아이템/국기 이미지 경로 확인 & 정상 로드
- legacy 이미지 매핑 → frontend public/ 또는 CDN

### 3B. UI 디테일 (frontend/ 수정)
- 국가별/등급별 색상 코딩
- 툴팁, 정렬 옵션, 페이지네이션
- 반응형/모바일 대응

### 3C. 한글 메시지 & 로그 (backend/ + frontend/ 수정)
- 게임 로그 메시지 패러티
- 에러 메시지 한글화
- 커맨드 실행 결과 메시지 일치

---

## Phase 4: E2E 통합 테스트 🧪

**목표**: 게임 시작 → 천하통일까지 정상 진행

### 4A. NPC AI 완성 (backend/ 수정)
- `legacy/hwe/sammo/GeneralAI.php` → `backend/.../engine/ai/` Kotlin 포팅
- Autorun 정책 (GeneralPolicy, NationPolicy)
- 턴 자동 실행

### 4B. 시나리오 테스트
- 사실 모드 시나리오 로드
- NPC AI 자동 진행 → 천하통일 도달 확인

### 4C. Playwright E2E (frontend/e2e/ 확장)
- 로그인 → 로비 → 게임 입장 → 명령 실행 → 결과 확인
- 기존 `frontend/e2e/` 테스트 확장

---

## 실행 전략

### 바이브 코딩 루프
```
1. 에이전트 5명 스폰 (동시 제한 5)
2. 각자 legacy+core2026 읽기 → backend/frontend Kotlin/Next.js 수정
3. 결과 수집 → 다음 배치 투입
4. 반복
```

### 에이전트 태스크 템플릿 (Phase 1)
```
참조:
  - legacy/hwe/sammo/Command/General/{cmd}.php
  - core2026/packages/logic/src/actions/turn/general/{cmd}.ts
수정 대상:
  - backend/game-app/src/main/kotlin/com/opensam/command/general/{cmd}.kt
→ 3자 비교 후 Kotlin 수정 + 리포트 작성
```

### 에이전트 태스크 템플릿 (Phase 2)
```
참조:
  - legacy/hwe/{page}.php + legacy/src/ts/{page}.ts + legacy/src/ts/{Page}.vue
  - core2026/app/game-frontend/src/views/{View}.vue
수정 대상:
  - frontend/src/app/(game)/{page}/page.tsx
→ 3자 비교 후 Next.js 수정 + 리포트 작성
```

### 진행 현황

| Phase | 상태 | 배치 |
|-------|------|------|
| **1A 장수 커맨드** | 🔄 진행 중 | b1~b5 (56개) 돌리는 중 |
| **1B 국가 커맨드** | ⏳ 대기 | b6~b9 (38개) |
| **1C 엔진/서비스** | ⏳ 대기 | |
| **2A 프론트엔드** | ⏳ 대기 | f1~f13 (55+ pages) |
| **2B API 커버리지** | ⏳ 대기 | |
| **3 디테일** | ⏳ 대기 | |
| **4 E2E 테스트** | ⏳ 대기 | |
