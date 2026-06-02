# 계획서 — 프론트엔드 패러티 포팅 + 시나리오 시드

> 작성 2026-06-02 · 대상 P7(프론트) 완성 + P8(시드/배포) 일부
> 원칙: **`legacy/devsam-core`(PHP+Vue) = grand truth.** `hwe/ts/` Vue 소스가 실제 프론트 진실(`hwe/*.php`는 dist mount 셸). `devsam-core2026`(Vue/tRPC)는 **절반만 만들다 만 구조 힌트일 뿐, PHP가 이긴다.** 백엔드 패러티 규율(RNG/로그/반올림 byte-match)을 프론트에도 적용한다.

---

## 0. 현재 상태 (이 작업의 출발점)

- ✅ 백엔드 P0–P6 패러티 게이트 통과. 배포 CI/CD **완전 그린**, https://sam.peppone.dev LIVE (8컨테이너 healthy).
- ✅ gateway-api는 **자체 JWT/BCrypt 로컬 인증**(PHP Kakao OAuth에서 의도적 divergence — 어드민 peppone/비번도 로컬 인증 전제).
- ❌ **gateway 프론트** = `Scaffold OK` 3줄 플레이스홀더(로그인 없음).
- ⚠️ **game 프론트** = dark war-room 디자인 시스템·Shell·SSE는 있으나 페이지는 비충실 스캐폴드 + **인증 게이트 없음**.
- ❌ **백엔드 read API 대량 누락** (아래 §2) — 프론트가 호출하는 엔드포인트 다수가 미존재.
- ❌ **게임 데이터 0** — 시나리오 시드 메커니즘 미구현(데이터는 git-ignore된 `legacy/`에만).

---

## 1. 패러티 범위 — 페이지 인벤토리

### 1.1 게이트웨이 (인증/로비) — `hwe/ts/gateway/` 기준
| opensamguk 라우트(web/gateway) | PHP/ts 원본 | 내용 | 패러티 비고 |
|---|---|---|---|
| `/` 엔트런스 | `index.php`/`i_entrance`/`hwe/ts/gateway/entrance.ts` | 로그인 후 서버 목록 + 캐릭터 슬롯 | 메커니즘은 JWT, 화면 레이아웃 PHP 재현 |
| `/login` | `hwe/ts/gateway/login.ts` (+Kakao) | 로그인 | Kakao→**로컬 JWT**로 대체(divergence) |
| `/join` | `v_join.php`/`hwe/ts/gateway/join.ts` | 장수 생성(국가선택·유산·이미지레벨·이름차단·maxgeneral cap) | 데이터 규칙 패러티 |
| `/lobby` | `i_entrance` | 서버선택·캐릭터 로스터·맵 프리뷰 | |
| `/admin` | `_admin*.php`/`gateway/admin_*.ts` | 유저제재·역할·시나리오설치·서버제어 | role=ADMIN gate. peppone 어드민 |

### 1.2 메인 게임화면 + 척추 3종 (최우선) — `hwe/ts/PageFront.vue` + `processing/` + `components/`

**chrome 척추 = 헤더 + 메뉴 바 2개** (메인 프레임 `PageFront.vue` 구성):
1. **`GameInfo` (헤더)** — 게임상태: `{title} {serverName}{serverCnt}기`, 시나리오/확장·표준/가상·사실/NPC선택, **`{year}年 {month}月 ({turnterm}분 턴)`**, 접속자수, 턴당 갱신횟수, 장수수/제한, 토너먼트·경매·투표 알림. (※ `SammoBar`는 헤더 아님 — percent fill 게이지 위젯.)
2. **`GlobalMenu` (글로벌 메뉴 바)** — **서버 주도** 메뉴(`GetMenuResponse.menu: (MenuItem|MenuSplit|MenuMulti)[]`, Global API). 랭킹/전당/현황 등 글로벌 네비. 반응형 3배치 + 모바일 `GameBottomBar`.
3. **`MainControlBar` (메인 메뉴 바)** — 국가/개인 ~20버튼(아래 표), 레벨/권한 게이팅. `MainControlDropdown`(암행부/세력도시/감찰부) 포함.

`MainControlBar` 버튼 = **거의 유일한 링크 네비게이션**. 게이팅까지 패러티:

| 버튼 | 링크(PHP) | 게이팅 |
|---|---|---|
| 회의실 | `v_board.php` | myLevel≥1 |
| 비밀회의실 | `v_board.php?isSecret` | permission≥2 |
| 병부(부대) | `v_troop.php` | myLevel≥1·nationLevel≥1 |
| 외교부 | `t_diplomacy.php` | showSecret |
| 인사부 | `b_myBossInfo.php` | myLevel≥1 |
| 내무부 | `v_nationStratFinan.php` | showSecret |
| 사령부 | `v_chiefCenter.php` | showSecret |
| NPC정책 | `v_NPCControl.php` | showSecret |
| 장수목록 | `b_genList.php` | showSecret |
| 토너먼트 | `b_tournament.php` | |
| 세력정보 | `b_myKingdomInfo.php` | myLevel≥1 |
| 도시정보 | `b_myCityInfo.php` | myLevel≥1·nationLevel≥1 |
| 세력장수 | `v_nationGeneral.php` | myLevel≥1 |
| 중원정보 | `v_globalDiplomacy.php` | |
| 현재도시 | `b_currentCity.php` | |
| 전투센터 | `v_battleCenter.php` | |
| 유산관리 | `v_inheritPoint.php` | |
| 내정보&설정 | `b_myPage.php` | |
| 경매장(금쌀/유니크) | `v_auction.php` | dropdown |
| 베팅 | `b_betting.php` | |

메인화면 본체(`PageFront.vue`): GameInfo(헤더) + GlobalMenu(메뉴바) + `MapViewer`(맵) + `PartialReservedCommand`(명령/턴 예약 패널) + `City/Nation/GeneralBasicCard`(정보 카드) + `MainControlBar`(메뉴바) + `MessagePanel`(메시지) + 모바일 `GameBottomBar`. 명령 제출 = `v_processing`(처리). 맵은 원본도 city dots뿐(실 타일 렌더 없음) → opensam-images CDN 자산으로 신규 구현.

### 1.3 서브 페이지 (PHP hwe/ 전수)
- **랭킹 `a_*`**: bestGeneral(15+지표 정렬), genList(15정렬·페이지네이션), kingdomList, emperior(+detail), hallOfFame, npcList, traffic(갱신로그)
- **내정보 `b_*`**: myPage(설정·아이템·페널티), myGenInfo, myBossInfo(인사부·관직임명), myCityInfo, myKingdomInfo, currentCity, betting, tournament
- **국가/뷰 `v_*`**: chiefCenter(사령부), battleCenter(전투기록), troop(부대편성·장비), auction(금쌀/유니크), board(회의실/비밀), vote(설문), inheritPoint(유산), globalDiplomacy(중원맵), nationBetting, nationGeneral, nationStratFinan(내무부·세금/정책/재정), NPCControl, history(연감)
- **기타**: t_diplomacy(외교부·외교서신), c_tournament, battle_simulator(전투시뮬)

### 1.4 권한 모델 (패러티 필수)
`officer_level`(0=무, 5–12=수뇌 직위), `permission`(대사/감찰), `checkSecretPermission()`. 모든 게이팅·읽기전용(setReadOnly) 패턴을 그대로 재현.

---

## 2. 백엔드 read API 갭 (프론트 선행 차단 요소)

opensamguk 현 API:
- **game-api**: `GET /api/auctions[/{id}[/bids]]`, `GET /api/bettings/...`, `GET /api/mailbox/...`, `GET /api/messages/{id}`, `GET /api/diplomacy/{nationId}`, `POST /api/messages/{id}/{accept|decline}`, `POST /api/command/{code}`, `POST /api/simulate-battle`(stub), `GET /sse/turn`, `GET /health`
- **gateway-api**: `POST /auth/{register|login|refresh}`, `GET /auth/me`, `GET /admin/turn-daemon/status`

**누락(프론트가 호출하지만 미존재)** — 빈 화면의 진짜 원인:
- 랭킹 전부: `/api/rankings/{best-generals,emperor,emperor/{id},generals,kingdoms,npcs,hall-of-fame,traffic}`
- 내정보: `/api/my-page`, `/api/my-generals`, `/api/my-cities`, `/api/my-boss`, `/api/my-nation-detail`, `/api/city/{id}`, `/api/generals`
- 토너먼트: `/api/tournament`, `/api/tournament-admin`

→ **이 read 컨트롤러들을 game-api에 먼저 구현**해야 프론트 페이지가 실제로 동작. (`GetXxx.php` PHP를 출처로, P7 read API 패턴 따라.)

---

## 3. 시나리오 시드 (게임이 안 비도록)

### 3.1 사실
- 엔진(`InMemoryTurnWorld`)은 **부팅 시 DB만 읽음**(`WorldStateReadRepository`). 시나리오 JSON을 런타임에 로드하지 않음. `world_state.scenario_code`는 정보용.
- 최소 플레이 가능: `world_state` 싱글톤 + nation ≥1 + city ≥1 + general ≥1(+`general_turn` 링버퍼). `game_kv`는 비어도 됨(RehydrateService가 obfuscatedNamePool 부트스트랩).
- 시드 대상 테이블: `world_state, nation, city, general, general_turn, troop, diplomacy, rank_data, ng_games`.
- PHP 설치 흐름: `ResetHelper::clearDB()` → `buildScenario()` → `Scenario($rng, code)` JSON 로드 → `game_env` KV 25+필드 + nation/city/general/general_turn/rank_data/event 채움.
- 시나리오 JSON(`legacy/devsam-core2026/resources/scenario/*.json`): `title, startYear, const, map, nation[], general[], cities[], diplomacy[], events[]`.

### 3.2 ⚠️ 핵심 함정 (조사 에이전트 권고의 결함)
"PHP install → pg_dump → SQL INSERT" 단순 권고는 **스키마 불일치**를 간과한다: PHP 스키마(`storage`/`plock`/...)는 opensamguk 스키마(`world_state`/`game_kv`/...)와 다르다. PHP 덤프를 그대로 넣을 수 없다.

### 3.3 패러티 수준 구분
- **(A) 최소 플레이 시드** (비-strict): 시나리오 JSON 값을 opensamguk 스키마 행으로 매핑해 직접 INSERT. 프론트 개발 빨리 unblock. RNG 파생 필드(배치 등)는 근사.
- **(B) 패러티-충실 시나리오 빌더** (strict, 진짜 목표): PHP `Scenario::build()` 로직(JSON + RNG draw 순서 + 배치)을 **Kotlin으로 포팅**하거나 PHP 골든 캡처 후 opensamguk 스키마로 변환. 백엔드 패러티 규율과 동일.

### 3.4 권고 접근
- **방식: Kotlin 시나리오 임포터** (`infra` 또는 `game-engine` 부트), 빈 DB일 때만 1회 실행. JSON(grand truth 값) → opensamguk 엔티티 매핑 → JDBC INSERT.
  - SQL 마이그레이션 시드 대비 장점: 시나리오 교체 유연, 스키마 매핑을 코드로 명시, 엔진과 동일 모듈.
  - 단계: (A) 최소 임포터로 먼저 플레이 가능화 → (B) PHP `Scenario::build` 패러티 골든으로 draw-for-draw 검증·보정.
- 대안(SQL V10 시드)은 변경 잦으면 부담 + 스키마 매핑 수작업 → 보류.

---

## 4. 단계 (로그인부터 점진적 — 사용자 지시)

| 단계 | 내용 | 게이트 |
|---|---|---|
| **F0 게이트웨이 인증** | web/gateway: 엔트런스/로그인/회원가입/로비 + 어드민. `hwe/ts/gateway/` 화면 충실 재현, JWT 연결(서버사이드 route handler + httpOnly 쿠키). `AdminSeeder`로 peppone(role=ADMIN) 자동생성. | 실제 로그인→로비→게임입장 동작 |
| **F1 시나리오 시드** | §3 최소 임포터 → fresh DB가 nation/city/general 채워진 플레이 가능 상태. | `general/city/nation > 0`, 엔진 부팅·턴 진행 |
| **F2 메인화면 + 메뉴 척추** | web/game: 메인(`v_processing`) 화면 + MainControlBar **20버튼+게이팅** + Shell 네비. | 메뉴 전수·게이팅 PHP 일치 |
| **F3 read API + 랭킹/내정보** | game-api read 컨트롤러(§2 갭) + web/game `a_*`/`b_*` 페이지 충실화. | 페이지가 실데이터 렌더, PHP 대조 |
| **F4 액션 페이지** | chiefCenter/battleCenter/troop/auction/board/vote/diplomacy/inherit/NPC/tournament/simulator. | 페이지별 PHP 대조 |
| **F5 turnkey + docs** | 정본 compose 리포 커밋 + .env.example(로컬+EC2) + CLAUDE/AGENTS/README(한글). | `git pull && docker compose up`로 자동설치 |

각 단계 = `hwe/ts/` 원본 컴포넌트 정독 → Next.js 재구현 → 구조/라벨/게이팅/동작 PHP 대조(패러티 게이트).

---

## 5. 디자인 시스템
- web/game `globals.css` dark war-room 토큰(gold/crimson/jade, Pretendard, 간격/타이포/애니/브레이크포인트) + 컴포넌트(Shell/Header/Sidebar/BottomNav/GameCard/GameTable/StatusBadge/CommandModal/Toast) 재사용.
- gateway에도 동일 토큰 확장. 폰트 Pretendard CDN.
- 모바일: <1024px Sidebar→BottomNav, <767px 모바일.

## 6. core2026 구조 힌트 (참고만, 복붙 금지)
- 유용: 튜플팩 배열(cityList/nationList) → computed map 분해, 명령 디스패치 분리, 탭/모달 추상화, 세션 라이프사이클.
- 불신: LoginView/대부분 뷰 stub, AdminView 1500줄 비대, 맵뷰어 실렌더 없음, 로그인 mutation TODO.

## 7. 리스크 / 미결정
1. **시나리오 시드 패러티 수준** — 최소(A) 먼저 vs 빌더(B) 포팅 시점. (F1은 A, 이후 B 보정 권고)
2. **인증 divergence** — JWT 로컬(Kakao 제거)을 패러티 예외로 확정. (백엔드 이미 그렇게 구현됨)
3. **read API 분량** — §2 갭이 큼. F3가 가장 무거움.
4. **맵 렌더** — PHP/core2026 모두 실 타일 렌더 미완. 신규 구현 필요(자산: opensam-images CDN).

## 8. 즉시 다음 (F0 착수 시)
- web/gateway 인증 프론트(엔트런스/로그인/회원가입/로비/어드민) — `hwe/ts/gateway/` 정독 후 JWT 어댑트.
- `AdminSeeder` 커밋 + 서버 `.env`에 `ADMIN_USERNAME=peppone`/`ADMIN_PASSWORD` 설정 → 배포 시 peppone 생성.

## 9. 결정 로그 (2026-06-02, 실행 중 확정)
- **맵 프리뷰 유지**(F0 드롭 취소). 서버마다 **10분 캐싱**된 맵을 로비에 표시. 방식: **데이터+클라 SVG** — game-engine이 10분마다 맵 스냅샷 JSON(도시 좌표·국가색·레벨)을 캐시(Redis) → game-api `GET /api/map/preview` 서빙 → 로비 `<MapPreview>`가 opensam-images CDN **추상 게임맵 베이스** 위에 클라이언트 SVG로 도시점 렌더(국가색 dot, hover=도시명/레벨). 좌표 출처 = **시나리오 `scenario/map` 게임 x/y**(원작 배치, lon/lat 아님).
- **커맨드 인자 입력 = 모달 우선**. 레거시는 인자 필요 커맨드가 별도 페이지로 이동(`v_processing`). opensamguk은 **가능하면 모달**(web/game `CommandModal` 재사용/확장)로 in-place 입력 처리. 진짜 멀티스텝/복잡 입력만 전용 페이지 유지. F2(메인화면 MainControlBar)·F4(액션 페이지) 빌드 시 적용.
- **gateway-api 예외 핸들러 추가**(`GlobalExceptionHandler`): BadCredentials→401, IllegalArgument→400(메시지), 검증→400. 프론트 로그인/가입 에러 한글 패러티 위해 필수(기존엔 메시지 없는 500).
- **`/join` = 계정 가입만**. 장수 생성(국가/유산/이미지레벨)은 web/game P7 페이지(F0 아님).
- **인증 토큰 = httpOnly 쿠키**(`sam_access`/`sam_refresh`), Next route handler가 gateway-api 프록시(동일출처 → CORS 불필요). 브라우저 JS에 토큰 미노출.
- **F1 분리 + 피벗 발견(Q4)**: scenario_1010 = 2국·24도시·**678장수**(스탯 [5]통솔[6]무력[7]지력), 24도시는 JSON에 없음 → `CityConstBase $initCity`서 전사. **prod game-engine에 DB→WorldSnapshot 로더/@Bean InMemoryTurnWorld 없음** → 엔진 부팅은 healthy하나 실제 턴 진행 불가(WorldSnapshot은 테스트만 수동생성). game-api read = JPA로 Postgres 직접 → **F1a(ScenarioImporter 시드)만으로 프론트 unblock**. **F1b(WorldSnapshotLoader + @Bean boot 배선 + boot IT)도 이번 세션에 함께 빌드**(사용자 지시) → 엔진 턴 진행. 상세 resolved 문서 `docs/superpowers/research/2026-06-02-F1-build-resolved.md`.
