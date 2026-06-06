# SESSION HANDOFF — 2026-06-07 (세션4: B1 장수생성 RNG 코어 골든 게이트 + RNG 커널 패러티 수정)

다음 세션은 이 문서부터. 핵심은 git log. (세션3 이하 §0.5~.)

> **세션4 완료(커밋 1개, `6954552`, branch=`parity-final`, 미push)** — B1 장수생성(Join)의 **RNG 코어**를 PHP grand truth(`Join.php:225-392` 인라인 create-general)에서 draw-for-draw 충실 포팅 + 실 PHP 골든으로 게이트 마감. 사용자 핵심요구(진입 플로우)의 패러티-하드 코어 진척.
> - **신규**: `logic/world/MakeGeneral.kt`(순수 로직 draw 산출) + `golden/entrance/장수생성-fixtures.json`(실 PHP 캡처 14픽스처/180드로우, 천재 3 자연발생, N∈{3,4,5}, sha256 `535ddb9c…`, 2회 byte-identical) + `tools/php-golden/capture_join.php` + `MakeGeneralGoldenTest`(seedString byte-equal + 드로우-포-드로우 method/result/choiceIndex + DRBG 커서 byte-exact + 전체 outcome) + `JoinDrawRecorder` + 연구문서 `research/2026-06-06-join-grand-truth.md`.
> - **커널 패러티 버그 발견+수정(이 게이트가 노출)**: `common/rng/RandUtil.choiceUsingWeight` 가 `jsKeyOrder`(core2026 **TS** 오라클의 Object-key 순=정수키 오름차순 우선) 적용 → PHP `foreach` **삽입순**과 발산. `SpecialityHelper` 의 `pAbs["0"]` 센티넬(문자열키 뒤 정수형 키)에서만 발현(전투/내정 특기 선발 오류). **insertion order로 수정**. 문자열/오름차순-정수 키 콜러는 insertion==jsKeyOrder라 byte-불변 — 전수 grep + **:common 192 / :logic 1865 / :app:game-engine 297(AI 174/174) ALL GREEN** 으로 무회귀 증명. `choiceMap` 은 jsKeyOrder 유지(자체 PHP 골든 게이트 전까지, 발산 콜러 없음 — **잠재 동일버그, 백로그**). PHP 기록기 `RandUtilDrawRecorder.php` choiceUsingWeight 이중기록도 동반 픽스.
> - 어드버서리얼 패러티 리뷰 **SAFE**(6차원 클린, 날조/약화 0).
>
> **다음(B1 잔여 = write-seam + 인테이크 + FE — 세션4 종료시 미착수, 정밀 스코프 ↓§0.6 A)**: 새 장수 INSERT 경로는 **net-new**(ChangeRecorder `createdGenerals` 채널 ❌ + JdbcFlushExecutor general-create ⏳ + `TurnDaemonCommand.MakeGeneral` ❌ + dispatcher 핸들러 ❌). one-daemon-write-rule 하 createGeneral 데몬 핸들러가 `MakeGeneral.draw()` 소비 → general/30×general_turn/rank_data INSERT. **flush 변경 = prod 턴-freeze 리스크 → 반드시 real-Postgres IT 로 닫고 push**. 거병/건국(CMD-FOUNDING) nation-create 가 가장 가까운 analog 템플릿. (write-seam 인베스티게이션은 세션4 말 타임아웃 — 다음 세션 신규 컨텍스트로.)

---

> **세션3 완료(커밋 6개, branch=`parity-final`, 미push)**:
> - **게이트 ALL GREEN**: common 192 / logic 1864 / infra 78 / game-engine 297 / game-api **177**(+3 새 테스트) / web/game·web/gateway tsc CLEAN.
> - `d18388c` A — 9 event_*연구 parity(게이트 9/9). `16861c5` E — **K1** `GET /api/server-basic-info`(BE+GameConst defaultStat*+gateway fan-out route). `c27ba51` F — **K2** 로비 라이브 진입 상태머신(§5.4 하드코딩 #1-5 제거). `62b573b` C — 진입 레이아웃/맵 폭. `3240c7d` B+D — W4 FE 5스트림 **+ 메인화면 크래시 근본수정**.
> - **🔴 크래시(§2) 근본해소**: my-page 평면응답을 nested MyPageData로 오독 → `data.general` undefined → `.name` throw 였음(단순 null-가드 아닌 **shape mismatch**). front-info(GetFrontInfo nested 정본)로 교체, nation/city null-safe.
>
> **다음**: (a) **풀 배포**(parity-final→main push → deploy.yml, 턴 되감김 감수 승인됨) → prod 헬스+턴전진+크래시 재현 확인. (b) **B1-B3**(장수생성 Join/빙의/선택 — PHP 골든 동반, §5.2) + 진입 3버튼 분기(현재 미등록은 게임 진입 통합). (c) chief-center ChiefCommandReserve 제출 end-to-end 배선 검증(UI는 커밋됨, intake 왕복 미검증). (d) Tier4 #15 나머지 15(5 계략+9 misc+cr_인구이동).

---

## 0.5 세션3 상세 (완료)
- **검증**: 세션2 미검증분 `:app:game-api:test` → 174 green 확인. 이후 K1 추가로 177(ServerBasicInfoControllerTest 3).
- **크래시 근본수정**: `web/game/app/game/page.tsx` MyPageContent를 `api.frontInfo()`(FrontInfoResponse nested) 소비로 재작성. `api.myPage`/`MyPageData`(평면↔nested 불일치)는 더 이상 메인이 안 씀. `lib/types` FrontGeneralInfo/FrontNationInfo를 실 JSON 필드로 widening.
- **K1**: `ServerBasicInfoController`(permitAll+optional principal, FrontInfoController 패턴) + `ServerBasicInfoDto` + `GameConst.defaultStatTotal/Min/Max`(+NPC변형·chiefStatMin, d_setting verbatim) + GetConst 노출 + gateway `app/api/server-basic-info/[id]/route.ts`(sam_access Bearer 포워딩).
- **K2**: `web/gateway/app/lobby/page.tsx` ServerRow가 서버별 fan-out으로 진입 상태머신(me→입장/full→등록마감/else→미등록+진입버튼) + 라이브 서버정보. servers.json=라우팅만.
- **배포 완료 + prod 검증(2026-06-06)**: `dd4e970..5e2244d` main 머지 → deploy.yml **success**(build-jvm/web×2 green, GHCR→EC2 롤링). prod 검증: 컨테이너 전부 up(크래시루프 0), 엔진 rehydrate(generals=678) + **TurnDaemonRunner 루프 진입 + 턴 전진 181/3→181/6**, K1 `/api/server-basic-info/{main,bbae}` 라이브(main nationCnt=2·bbae=21·defaultStatTotal=165), front-info nested 라이브.
- **🟠 nginx 회귀 캐치+핫픽스**: K2 로비 fetch `/api/server-basic-info/[id]`가 nginx `/api/` catch-all→game-api(Spring) 404로 빠져 **전 서버 '폐쇄 중' 회귀**였음. 라이브 박스 `/home/ubuntu/opensamguk/docker/nginx/default.conf`에 server-map 패턴 location 블록 2개(두 server stanza) 삽입+graceful reload로 즉시 해소. 레포 canonical `infra/nginx/default.conf`도 갱신(이 커밋, **parity-final에만** — main push=재배포·턴되감김이라 다음 배치와 함께). 박스 docker/nginx는 미추적(레포 docker/nginx 부재).
- **빼섭(bbae) 입장 ✅검증(E2E)**: `?server=bbae`→middleware `sam_server` 쿠키→`/api/game/[...]` route handler→`resolveGameApiUrl('bbae')`→`bbae-api:18080`. 증명: `Cookie: sam_server=bbae`로 `/api/game/api/front-info`가 **year 191/scenario_1030/nationCount 24**(bbae) 반환, 쿠키 없으면 181/1010(main). prod env `SERVER_REGISTRY_JSON=[{"id":"bbae","gameApiUrl":"http://bbae-api:18080"}]`. 크래시 수정도 bbae 동일 적용(front-info nested). (basic-info nationCnt=21=`level>0`=devsam 로비 정본 / front-info nationCount=24=`id!=0` — 메트릭 차이지 버그 아님.)

## 0.6 백로그 (다음 세션 — 통합)

### A. 진입(엔트런스) 잔여 — §5 (골든 동반, 사용자 핵심요구 연속)
- **B1 장수생성**(`API/General/Join.php`):
  - ✅ **RNG 코어 done(세션4, `6954552`)** — `logic/world/MakeGeneral.draw()` draw-for-draw + 실 PHP 골든 게이트(14/14). 천재 전투특기는 `SpecialityHelper.pickSpecialWar` 재사용. `defaultStat*`는 GameConst/GetConst 노출 완료(세션3).
  - ⬜ **write-seam(net-new, Tier-0 — B2/B3/founding 도 소비)**: ① `ChangeRecorder.createdGenerals` 채널 신설(brand-new INSERT, generalPatches=UPDATE와 별개) ② `InMemoryTurnWorld.createGeneral`(id 할당=max+1, TurnGeneral 빌드, nation=0 재야) ③ `JdbcFlushExecutor` general-create flush step(general INSERT + 30×general_turn 휴식 + rank_data per RankColumn — 컬럼셋은 `infra/seed/ScenarioImporter.insertGenerals` 참조) + general_access_log ④ **real-Postgres flush IT**(insert→flush→행 검증; 거병/건국 nation-create IT 패턴 미러). **flush=prod 턴-freeze 리스크 → IT 선결 후 push.** 상수: gold/rice=1000, killturn=6, crewtype=1100, officer_level=0, betray(relYear≥4→2). ⑤ 누락 GameConst: `DEFAULT_CREWTYPE`(1100)/`killturn`(6)/`retirementYear`(80) 추가.
  - ⬜ **intake**: `TurnDaemonCommand.MakeGeneral` wire variant + `CommandWireMapper` intakeCode + game-engine `TurnDaemonCommandDispatcher` 핸들러(즉시 실행, 턴-reserved 아님) + game-api 인테이크 컨트롤러(`CommandReserveService` Model-B 즉시 데몬커맨드 경로). seed = `serializeSeed(hiddenSeed,"MakeGeneral",userID,now-string)`.
  - ⬜ **FE**: `PageJoin` 폼(장수명/전콘/성격/통무지 합≤defaultStatTotal·각[Min,Max]/조절버튼). gateway route handler 프록시 + (새 route handler면) nginx location 함정 주의.
  - 정본: `Join.php` + 연구문서 `research/2026-06-06-join-grand-truth.md`(드로우 순서·INSERT 필드·로그 순서 정독).
- **B2 장수빙의**(`select_npc.php`): PossessionController claimable/claim 골격 절반 존재 → npc 2→1 + killturn=6/defence_train=80/permission=normal/aux + 토큰풀(general npc=2, weight pow(allStat,1.5), 5장, validUntil) + claim 술어 정확히 `owner<=0 AND npc=2 AND no=pick`. → 골든.
- **B3 장수선택**(`select_general_from_pool.php`): 14장 템플릿 pool(selectPool 시드)+token + GeneralBuilder.build(통무지/전콘/성격 caps 내 커스텀,killturn=5,재야 random공백)+swap. → 골든.
- **진입 3버튼 분기**: 현재 미등록=게임 진입(CharacterClaim) 통합 → B1-B3 완료 시 `canCreate=!(block&1)`/`canSelectNPC=npcMode가능`/`canSelectPool=npcMode선택생성` 3버튼 분리(entrance.ts L270-279).
- **nginx**: B1-B3가 새 gateway Next route handler 추가 시 `infra/nginx/default.conf` 두 stanza + 라이브 박스 `docker/nginx/default.conf` location 블록 또 필요(자동화 검토). 함정 = `/api/` catch-all→game-api 404.

### B. 검증/배선 잔여
- **chief-center ChiefCommandReserve 제출 intake 왕복 end-to-end 미검증**(UI는 배포됨; 9 event_연구 등 chief turn-reserved 제출이 실제 엔진까지 도달하는지 로그인 세션으로 확인).
- **/game 메인 크래시·로비 비주얼**: 구조적 해소 확인(front-info nested 라이브), 로그인+빙의 세션 실측은 미완.
- **nginx canonical** `infra/nginx/default.conf`(server-basic-info 블록)는 **parity-final에만**(`b8b58b6`) — main push=재배포·턴되감김이라 다음 배치와 함께 main 반영. 라이브 박스는 핫픽스 적용됨.

### C. 패러티 로드맵 (PARITY_LEDGER §8)
- **Tier4 #15**: 24 중 9 done(세션3), **15 남음** = 5 계략(che_화계/파괴/탈취/선동/첩보, RNG-bearing) + 9 misc General(강행/접경귀환/숙련전환/전투태세/모반시도/특기초기화×2/단련/등용수락) + cr_인구이동. (parity-wave ring/deterministic 보정 스크립트로.)
- Tier2 #7·#8 W8 토너먼트 · Tier3 #12·#14 입국건국/NPC풀 · Tier1 #4·#6 checkStatistic(디퍼).
- **gateway-api `JwtTokenProviderTest.generate and validate access token()` CI flaky**(시계성, deploy.yml 비차단 — dd4e970·세션3 둘 다 배포 성공). 토큰 만료/시각 단언 수정 필요.

### D. 운영 백로그
- **빼섭 보급-동결 버그** 미수정(`doNPC구출발령` 빈 supplyCities→RandUtil.choice throw, 상류 1030 보급 발산). 가드=band-aid. 현재 빼섭 엔진은 가동 중(year 191 전진).
- 매 main 배포 = 엔진 recreate→DB스냅샷 rehydrate로 **턴 되감김**(세션3 본섭 181로). doc-only도 main push 금지(parity-final에 모아 배치 배포).

## 0. 사용자 핵심 원칙 (이 세션 확립 — 반드시 준수)

1. **하드코딩 금지.** 모든 표시값 = **실제 API + 기능의 결과**여야 한다. 정적 placeholder/박힌 상태값 = 위반. (PHP 정본 패러티값 상수는 fabrication 아님 — 그건 OK.)
2. **PHP가 grand truth.** 진입 플로우도 devsam `hwe/ts/gateway/entrance.ts` + `v_join.php`/`select_npc.php`/`select_general_from_pool.php`를 **화면+기능까지 정독**해 충실 재현. 추측 금지.
3. 자율 머지+배포 OK(CI green 선결). 주석 한글, 식별자/wire/패러티로그 영문. 커밋 끝 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## 1. 미커밋 인벤토리 (43파일, branch=`parity-final`)

### A. parity-wave batch1 — 9개 `event_*연구` (Tier4 #15 batch1) ✅ GREEN
- **상태**: `:logic:test` **216 suites / 1864 tests / 0 fail / 0 error**. 9 GoldenTest 전부 PASS(게이트 직접 재실행 확인). 리뷰어 C6(fabrication) CLEAN — 9 fixture 전부 실 PHP 캡처, 약화 테스트 0. 9코드 `turnReservedC3Codes`에 있고 `intakeCodes`/`GENERAL_COMMAND_CODES`엔 없음(올바름).
- **파일**: `logic/.../actions/nation/Event{Geukbyeong,Muhui,Sangbyeong,Hwaryuncha,Wonyungnobyeong,Daegeombyeong,Hwasibyeong,Eumgwibyeong,Sanjeobyeong}Yeongu.kt`(9 resolver) + `logic/.../golden/Event{극병,대검병,무희,산저병,상병,원융노병,음귀병,화륜차,화시병}연구GoldenTest.kt`(9) + `golden/p2/event_*연구-fixtures.json`(9 — disk엔 9개 다 있음, git엔 2개만 ?? 표시 → **커밋 전 `git add -A` 확인**) + `tools/php-golden/capture_event_wonyungnobyeong.php`.
- **공유파일 widening**: `CommandRegistry.kt`(9 import+when), `CommandWireMapper.kt`(turnReservedC3Codes에 9코드).
- **성격**: 9개 deterministic(rng 미draw, draw_count=0). 효과=gold/rice 차감 + nation aux[can_*사용]=1 + exp/ded +5*(preReqTurn+1) + 3로그 + inherit active_action+1. 2 cost tier: {23턴,100k}×5 / {11턴,50k}×4. 상태: PORT_MISSING→**FE_MISSING**(백엔드 게이트 closed; FE는 chief-center 제출=별도, §5 K2와 묶임).

### B. W4 FE 5스트림 + 리뷰픽스(B1/H1/H2) — web typecheck CLEAN, **game-api test 미검증**
- **web/game**: `chief-center/page.tsx`(+신규 `ChiefCommandReserve.tsx` = 사령부 명령예약 제출 UI, 21 chief-reserved 커버) · `Gauge.tsx`(신규)+`page.tsx`/`GeneralBasicCard.tsx`/`NationBasicCard.tsx`(도시 now/max 게이지; nation/general은 max부재→now-only 비날조) · `nation-finance/page.tsx`(setRate/setBill/... 세터 제출) · `generals/page.tsx`(컬럼 정렬; 명성=explevel·계급=dedlevel 버킷, raw 아님) · `world-log/`(신규 페이지)+`lib/api.ts`(worldLog()) · `lib/types.ts`/`types/game.ts`.
- **백엔드 곁수정(B1/H1 픽스)**: `ChiefCenterController.kt`(precheck 주입→chief 팔레트 possible/reason 실값, AvailableCommandsController 미러) · `F4Dto.kt`(ChiefCommand.reason 추가) · `GeneralsController.kt`+`F4Dto.kt`(PublicGeneral 버킷 enrich: explevel/honorText/dedlevel/dedLevelText/bill — 공개표면 원시 exp/ded/금/쌀 비노출=프라이버시) · `F4ReadControllersTest.kt`(계약 갱신).
- **⚠️ 미검증**: B1/H1이 `F4ReadControllersTest.kt` + game-api DTO를 바꿈 → **`:app:game-api:test` 안 돌림**(사용자 인터럽트). **커밋/배포 전 필수**: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --rerun-tasks`(ctx_execute 경유, XML 검증).

### C. 진입화면 레이아웃/맵 픽스 (사용자 prod 리뷰 피드백) — typecheck CLEAN
- `web/game/components/Shell.tsx`: 좌측 `<Sidebar/>` 제거(너비부족 — 사용자요청). 네비=Header(상단)+BottomNav(하단)+GameChrome GlobalMenu.
- `web/game/app/globals.css`: `.shell-main > * { max-width:1000px; margin:auto }`(로그인/로비와 동일 1000px 중앙).
- `web/gateway/components/MapPreview.tsx`: `ICON_SCALE=0.72`(인게임 MapViewer와 맵 아이콘 모양 통일 — 로그인/로비/메인 3맵 동일형, 데이터만 상이).

## 2. 🔴 미해결 크래시 (배포 전 고쳐야)
`TypeError: undefined is not an object (evaluating 'm.name')` — **빙의 직후/메인 진입**(사용자 확인). minified prod라 정확 스택 미확보. **유력 근본**: factionless(재야) 장수 진입 시 nation null → 무가드 `.name`. `MyController.myPage`가 `nationName=orElse(null)`/`cityName=orElse(null)` 반환(L44-45). 후보: `web/game/app/game/page.tsx`(MyPageContent `nation.name`/`city.name`/`general.name` 무가드 L96/134/162 — **단 MyPageData 타입은 non-null이라 tsc 미포착**) OR myPage 응답 형태 불일치. **수정**: 진입경로 `.name` 전부 null-safe 가드(factionless nation/city null 정상 상태). 배포 후 재현 확인(소스맵 스택 받으면 정확 특정).

## 3. 검증 명령 (host gradle는 ctx_execute(language:shell) 경유 — subagent는 gradle 불가)
```
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --rerun-tasks            # 1864 green (A 확인됨)
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --rerun-tasks     # B 미검증 — 필수
cd web/game && pnpm exec tsc --noEmit    # CLEAN
cd web/gateway && pnpm exec tsc --noEmit # CLEAN
```
XML 검증(exit code 불신): `logic/build/test-results/test/TEST-*.xml`(Korean 클래스명은 `#xxxx` 유니코드 이스케이프 → python ET 파싱). 파서 스니펫은 git log 이 세션 ctx_execute 참조.

## 4. 워크플로 스크립트 (resume용)
- parity-wave(event_연구): `~/.claude/projects/.../workflows/scripts/parity-wave-eventresearch.js` (run wf_72c25b9d-574, 완료).
- W4 FE: `.../scripts/w4-fe.js` (run wf_6faa2149-ebe, 완료).
- 제네릭 parity-wave 원본(intakeCodes 오가정 — 다음 batch엔 ring/deterministic 보정 필요): `.../scripts/parity-wave-wf_1f65b77d-105.js`. **교훈**: subagent 게이트러너는 host gradle 못 돌림(ctx_execute 미보유) → 게이트는 **메인 컨텍스트에서 ctx_execute로 직접** 재실행해 XML 확인.

## 5. ⭐ 메인 산출물 — 진입(엔트런스) 플로우 정본화 (사용자 핵심요구, 빌드 착수)

### 5.1 devsam 정본 state machine (`hwe/ts/gateway/entrance.ts`)
서버 목록(`j_server_get_status.php` → `{color,korName,name,exists,enable}`) → 서버별 `j_server_basic_info.php` → `{reserved?, game, me}`. per-row 분기(순서):

| 상태 | 조건 | 렌더 |
|---|---|---|
| reserved/가오픈 | `reserved` 존재 | 오픈일시·시나리오·turnterm·**npcMode**·defaultStatTotal. game=null |
| **입장** | `me && me.name`(유저가 이 서버에 장수 보유) | picture(64px)+이름+`<a href="{serverPath}/">입장</a>` |
| **등록마감** | no me && `userCnt>=maxUserCnt` | "- 장수 등록 마감 -" |
| **미등록+액션** | no me && 여석 | "- 미등록 -" + 3버튼(독립 게이팅) |

미등록 3버튼: **장수생성**(`v_join.php`, `canCreate=!(block_general_create&1)`) · **장수빙의**(`select_npc.php`, `npcMode=='가능'`=1) · **장수선택**(`select_general_from_pool.php`, `npcMode=='선택 생성'`=2). 빙의(npc=1)·선택(npc=2)은 서버모드 상호배타, 생성은 직교. `.n_country`=isUnited(§천하통일§/§이벤트§/`<N국 경쟁중>`/-가오픈중-).

### 5.2 기능 정본 (3 진입수단)
- **장수생성**(`API/General/Join.php`): RNG=`RandUtil(LiteHashDRBG(serialize(hiddenSeed,'MakeGeneral',userID,now)))`. draw: 천재 `nextBool(0.01)` → **city=`rng->choice(SELECT city WHERE level∈[5,6] AND nation=0)`(공백지, fallback 전 lv5-6)** → bonus stat `choiceUsingWeight([lead,str,int])`×`nextRangeInt(3,5)` → age=`20+bonus*2-nextRangeInt(0,1)` → affinity `nextRangeInt(1,150)` → turntime. INSERT general(owner,nation=0 **재야**,city=random공백,officer_level=0,gold/rice=default,killturn=6,personal/special...) + general_access_log + 30×general_turn(휴식) + rank_data. 폼(PageJoin.vue): 장수명/전콘/성격/통무지(합≤defaultStatTotal,각 Min~Max)/조절버튼/유산옵션.
- **장수빙의**(`select_npc.php`+`j_get_select_npc_token.php`+`j_select_npc.php`): 토큰풀(general npc=2, weight `pow(allStat,1.5)`, 타유저 토큰 예약분 제외, 5장, validUntil). claim UPDATE 술어 **정확히** `owner<=0 AND npc=2 AND no=pick`(affected 0=충돌). 세팅: owner/owner_name, **npc 2→1**, killturn=6, defence_train=80, permission='normal', aux(+pickYearMonth). 기존 NPC 몸 그대로 빙의(스탯 미빌드).
- **장수선택**(`select_general_from_pool.php`+`j_get_select_pool.php`+`j_select_picked_general.php`): npcmode=2. 14장 **템플릿** pool(`pickGeneralFromPool`, RNG `selectPool` 시드). `GeneralBuilder.build`로 신규 general 빌드(통무지/전콘/성격 옵션 caps 내 커스텀, killturn=5, NPCType=0, aux next_change, 재야 random공백). swap=`j_update_picked_general`.

### 5.3 `j_server_basic_info` 데이터계약(키스톤)
`me`(`SELECT name,picture FROM general WHERE owner=userID` → 있으면 picture 해석, 없으면 null) + `game`{isUnited, npcMode(0/1/2→불가/가능/선택생성), year, month, scenario, maxUserCnt(=maxgeneral), turnTerm, opentime, starttime, join_mode, fictionMode, block_general_create, userCnt(`general npc<2`), npcCnt(`general npc>=2`), nationCnt(`nation level>0`), defaultStatTotal}.

### 5.3b K1 구현 노트 (이 세션 데이터소스 확인 — 빌드는 사용자 중단지시로 미착수)
game-api 안에 `ServerBasicInfoController`(신규) — 보안: `GameApiSecurityConfig.kt:42-44` 패턴대로 `.requestMatchers("/api/server-basic-info").authenticated()`(devsam `Session::requireLogin`). 데이터:
- **game{}**: `world.findAll().firstOrNull()` → `currentYear`/`currentMonth`/`tickSeconds`(÷60=turnTerm)/`scenarioCode`/`config`(jsonb). config 방어read(FrontInfoController.`intOrNull`/`boolOrNull` 패턴 복제, 날조금지): `npcmode`/`join_mode`/`fiction`/`maxgeneral`(=maxUserCnt)/`isunited`/`startyear`/`title`/`block_general_create`/`opentime`/`starttime`. **주의(§2 BLOCKED)**: 데몬이 config에 일부 키 미기재 → 부재 시 null/0(FrontInfoController도 동일 한계 — block_general_create/opentime는 현재 미기재일 수 있음, 시드 ScenarioImporter 확인要).
- **userCnt(npc<2)** = `generals.count() - generals.countByNpcStateGreaterThan(1)` (또는 countByNpcState(0)+countByNpcState(1)). **npcCnt(npc≥2)** = `generals.countByNpcStateGreaterThan(1)`. **nationCnt(level>0)** = `nations.findAll().count{it.id!=0}`(FrontInfo 패턴) 또는 level>0 필터. (`GeneralReadRepository.countByNpcState/countByNpcStateGreaterThan` 존재.)
- **me**: `GeneralOwnerRepository.findByUserId(userId)`→generalId→`GeneralReadRepository`로 name+picture(imgsvr 해석). 없으면 null. npcState 컨벤션: 0=PC,1=빙의됨,≥2=순수NPC.
- **defaultStatTotal/Min/Max**: GameConst.kt에 **없음**(grep 0) → 추가 + GetConst 노출 필요(B1에서).
- gateway: `web/gateway/lib/serverRegistry.ts` per-server fan-out + route-handler 프록시로 각 서버 game-api `/api/server-basic-info` 호출(`MapPreview`의 `/api/server-map/[id]` 패턴 동일).

### 5.4 하드코딩 위반 감사 (이 세션 "철저히 규명")
| # | 하드코딩 | 위치 | 정본 |
|---|---|---|---|
|1| `status:"running"`,`turnterm:60` 정적 | `web/gateway/config/servers.json` | basic-info game.isUnited/turnTerm |
|2| 캐릭터칸 `"- 미 등 록 -"` 전서버 고정 | `lobby/page.tsx:66` | me 유무 결과 |
|3| `enterable=running&&gameUrl`→항상 입장 | `lobby/page.tsx:57,71` | me 있을때만 입장, 없으면 생성/빙의/선택/마감 |
|4| `(${turnterm}분 턴)` 정적 | `lobby/page.tsx:63` | 라이브 turnTerm |
|5| year/month·userCnt/max·npcCnt·nationCnt·isUnited·npcMode 전부 없음 | lobby | basic-info game{} 실데이터 |
|6| 계정관리 `disabled "준비중"` | `lobby/page.tsx:89` | 실 계정 API |

**합법 config(위반 아님)**: 서버목록 id/name/gameApiUrl/gameUrl(라우팅) = devsam ServConfig도 config. 위반은 **상태**를 정적으로 박은 것.

### 5.5 빌드 플랜 (하드코딩0, 키스톤 우선) — **K1부터 착수**
| wave | 산출물 | 게이팅 |
|---|---|---|
|**K1**| game-api `GET /api/server-basic-info`(game{}+me, 실쿼리) + gateway route per-server fan-out | read, 골든불요 |
|**K2**| `lobby/page.tsx` state machine 재구성(entrance.ts 충실, 전값 basic-info 결과). servers.json=라우팅만. + chief-center 제출(W4 ChiefCommandReserve 연결, 21 chief커맨드 FE) | read |
|**B1**| 장수생성 Join intake+daemon(MakeGeneral RNG draw-for-draw) + PageJoin 폼 + `defaultStatTotal/Min/Max` GameConst | **PHP 골든** |
|**B2**| 빙의 deferred 절반 완성(npc 2→1+필드+로그 intake) + 토큰/가중pick(pow^1.5)/5장 | 골든 |
|**B3**| 장수선택 pool+token+GeneralBuilder build/swap | 골든 |

**이미 있음(재사용)**: `GeneralOwnerRepository.findByUserId`(me 판정), `PossessionController`(claimable/claim 골격, deferred 절반), `serverRegistry.resolveGameApiOrigin`(멀티서버 fan-out), route-handler httpOnly 프록시, `GetConstController`(personality/specialWar — defaultStatTotal은 미노출, 추가요). 정본 파일목록: `legacy/devsam-core/hwe/{j_server_basic_info,v_join,select_npc,j_select_npc,j_get_select_npc_token,select_general_from_pool,j_get_select_pool,j_select_picked_general}.php` + `hwe/ts/{gateway/entrance.ts,PageJoin.vue,select_npc.ts,select_general_from_pool.ts}` + `hwe/sammo/API/General/Join.php` + `i_entrance/j_server_get_status.php`.

## 6. 배포 (사용자=풀 배포 지금 승인, 턴 되감김 감수)
- 전부 FE/read → prod 반영=배포 필요. main push → `deploy.yml`(gradlew build 풀테스트 → GHCR :svc-latest → SSH 롤링 up -d). **엔진 force-recreate→DB스냅샷 rehydrate로 라이브 턴 되감김**(알려진 비용). 사용자 ON prod 인지함.
- **배포 전 순서**: (1) `:app:game-api:test` 검증 (2) 크래시(§2) 가드 (3) 풀빌드 green (4) 논리단위 커밋 (5) push/배포 후 prod 헬스+턴전진+크래시 재현 확인.
- EC2 `3.37.232.176` ssh `-i ~/.ssh/id_ed25519 ubuntu@`. 라이브 컨테이너=`opensamguk-{db(user/db=samguk),game-engine,game-api,redis,game-frontend,gateway-frontend}` + 빼섭 `opensamguk-bbae-*`. 맵라우트=`/api/map/preview`.

## 7. 이전 세션 흡수 (완료)
parity-final→main 배포(W3 read-DTO·맵아이콘축소·reseed). 본섭(1010)+빼섭(1030) 재시딩 완료. 빼섭 보급-동결 fix(`dd4e970` — 도시소유 nation.cities 기준). 입구/nginx 영구화(#35). 상세 git log. **빼섭 보급fix 배포후 1030 재시딩+엔진기동 검증 = 미완 잔여**(이전 핸드오프 §1).

## 8. 잔여 패러티 로드맵 (PARITY_LEDGER.md)
- Tier4 #15: 24 PORT_MISSING 중 **9 event_연구 done(이 세션)**, 15 남음 = 5 계략(che_화계/파괴/탈취/선동/첩보, RNG-bearing) + 9 misc General(강행/접경귀환/숙련전환/전투태세/모반시도/특기초기화×2/단련/등용수락) + cr_인구이동. parity-wave 추가 배치(ring/deterministic 보정한 스크립트로).
- Tier2 #7·#8 W8 토너먼트 · Tier3 #12·#14 입국건국/NPC풀 · **Tier5 #17 W4 FE(이 세션 5스트림 done, chief 제출 연결은 K2)** · Tier1 #4·#6 checkStatistic(디퍼).
- **진입 플로우(§5)는 로드맵 외 신규 — 사용자 prod 리뷰서 발생. 하드코딩 제거 = 운영품질 게이트.**
