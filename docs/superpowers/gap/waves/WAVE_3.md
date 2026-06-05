# WAVE 3 — read-DTO foundation (unblocks ALL FE-output)

## 목표
모든 read 페이지가 소비하는 game-api read DTO/엔드포인트를 PHP `GetFrontInfoResponse`/`GeneralListItemP0~P2`/`ChiefResponse`/`GetConstResponse` 등 grand-truth 응답 shape까지 보강한다 — WAVE 4(렌더)의 데이터 토대.

## 출처
- 인벤토리: `docs/superpowers/gap/READ_DTO_GAP.md`(§1~§15 roll-up), `docs/superpowers/gap/FE_OUTPUT_READ_GAP.md`(§1~§8 + 필드 카운트).
- GAP_AUDIT 섹션: `docs/superpowers/GAP_AUDIT.md` WAVE 3 (lines 178~184) — 3a~3e.
- PHP grand truth:
  - `legacy/devsam-core/hwe/ts/defs/API/Global.ts:105-227` (`GetFrontInfoResponse` global/general/nation/city/aux) + `:15-43` (`GetConstResponse`) + `:78-84` (`GetDiplomacyResponse`).
  - `legacy/devsam-core/hwe/ts/defs/API/Nation.ts:9-46` (`GeneralListItemP0`), `:48-86` (`GeneralListItemP1/P2`), `:95-134` (`RawGeneralListP0/1/2` envelope = env/troops/myGeneralID/permission), `:136-174` (`NationItem`/`NationInfoFull`/`NationNotice`).
  - `legacy/devsam-core/hwe/ts/defs/API/NationCommand.ts` (`ChiefResponse`) + `legacy/devsam-core/src/sammo/API/NationCommand/GetReservedCommand.php`.
  - `legacy/devsam-core/hwe/func.php:563-760` (`generalInfo` — injury/lbonus/troopInfo/age/atmosBonus/trainBonus/defenceTrain/special·specage/Lv bar/벌점), `:762-870+` (`generalInfo2` — 명성·계급 getHonor / 전투·계략·사관 / 승률·살상률 / dex1~5 getDexCall + `%.1fK` short), `:153-189` (`cityInfo`), `:190-...` (`myNationInfo`).
  - `legacy/devsam-core/hwe/sammo/GeneralBase.php:124-205` (컬럼 allow-list: dex1~5/special/special2/personal/specage/killturn/belong/age/defence_train는 `general` 컬럼; warnum/killnum/deathnum/killcrew/deathcrew/firenum은 `RankColumn` = `rank_data`).

## 완료/제외 (이미 닫힘 — 스펙에서 제외, 근거 file:line)
- **3e Map state/supply/capital**: `app/game-api/.../dto/MapPreviewDto.kt:35-46` `MapPreviewCity`가 `state`/`supply`/`isCapital`를 이미 carry, `CityReadEntity`가 `supply_state`(`CityReadRepository.kt:53`)·`front_state`(`:56`)·`region`(`:89`) 컬럼을 매핑. → **MapPreview의 state/supply/capital은 닫힘.** 남은 Map 갭은 **인게임 `GetMap` fog(`spyList`/`shownByGeneralList`)** 뿐 — 이는 WAVE 9(`9a`)로 이관(별도 fog 계산·첩보 테이블 필요, read-DTO foundation 범위 밖).
- **`general_turn.arg` 매핑**: `GeneralTurnReadEntity.arg`(`GeneralTurnReadRepository.kt:36-38`) 이미 jsonb로 매핑됨 → 장수 reserved `arg`는 닫힘. (3d의 `arg`는 **`nation_turn` 쪽만** 미매핑 — 아래 3d-task에 포함.)
- **`nation_turn.arg` 컬럼 존재**: `V1__baseline.sql:126` `arg jsonb`가 테이블에 있음 → 3d는 **읽기 엔티티에 컬럼 추가만** 필요, **마이그레이션 불요**.
- **GetMenu / Troops / Board / Tournament / InheritPoint / rankings 골격**: READ_DTO_GAP §8·§9·§14·§15b·15c에서 PRESENT — 제외. (rankings 비밀/파생 컬럼 보강은 WAVE 4 `4c` 슬라이스.)
- **PR #26 founding seam / P6·P7 완료분**: WAVE 0(데몬 seam)·P6 순수로직·P7 read 컨트롤러 골격은 별개 — 이 웨이브와 파일 disjoint. 본 웨이브는 **read DTO 필드 보강 + read 엔티티 컬럼 매핑 + 신규 read repo**만 다루며 데몬 write-path를 건드리지 않는다(one-daemon-write-rule 무관).
- **GeneralReadEntity 기존 컬럼**: picture/imageServer/crewTypeId/train/atmos/troopId/horse·weapon·book·item·experience·dedication·injury는 이미 매핑됨(`GeneralReadRepository.kt:41-116`) → 3a/3b의 다수 P1 필드는 **신규 컬럼 불요**, assembly만.
- **exp/level meta 접근자**: `GeneralMeta.kt:16-25`가 `leadership_exp`/`strength_exp`/`intel_exp`/`explevel`/`dedlevel` 이미 노출 → 3a 재사용.

## foundation-first 빌드 순서
Tier-0(공유 read 인프라, **먼저, 순차**) → Tier-1(DTO 정의 확장, 파일별 disjoint, 병렬) → Tier-2(컨트롤러 assembly + 게이트, 병렬).

- **Tier-0 (creator, 단독·선행)**
  - **F0-rankdata**: `RankDataReadRepository`(신규 `@Entity` `rank_data` read 매핑 + `findByGeneralId`/`findByGeneralIdIn`). 3a generalInfo2(전투통계)와 3b P1(warnum 등) 둘 다 consume. **반드시 두 consumer보다 먼저.**
  - **F0-meta**: `GeneralMeta.kt`에 누락 meta/컬럼 접근자 추가 — `dex1~5`/`special`/`special2`/`personal`/`specage`/`specage2`/`killturn`/`belong`/`age`/`defence_train`/`recent_war`. (PHP는 `general` 컬럼이나 opensamguk V1엔 전용 컬럼 없음 → meta 라이드; 단 이미 `GeneralReadEntity` 컬럼으로 존재하는 것은 컬럼 추가로 처리하고 OQ-2 참조.)
  - **F0-display**: `GeneralDisplayDeriver`(신규, game-api util) — injury 색/텍스트(위독>60/심각>40/중상>20/경상>0/건강), age 색, atmosBonus/trainBonus 부호, lbonus, defenceTrain(999=수비안함), getDexCall/`%.1fK` short, getHonor(experience/dedication), winRate/killRate(`round(...,2)`). **PHP `func.php` 산식 byte-faithful 이식.** 3a/3b가 공통 consume.
- **Tier-1 (DTO 정의, 파일 disjoint, 병렬)**
  - `IdentityDto.kt`(FrontGlobalInfo/FrontGeneralInfo/FrontNationInfo/FrontCityInfo/FrontInfoResponse + GameConstResponse 확장) — **F0-meta/F0-display 시그니처에 의존.**
  - `F4Dto.kt`(ChiefReservedResponse/ChiefPost/ChiefReservedTurn + PublicGeneral 확장 + 신규 P1/P2 row + 신규 ChiefCommandItem) — **F0 의존.**
  - `AuctionDto.kt`/`BettingDto.kt`/`MessageDto.kt`(보강 — 독립).
- **Tier-2 (컨트롤러 assembly + 게이트, 병렬)**: 각 DTO를 채우는 컨트롤러 본문 + 컨트롤러 단위테스트.

## 태스크 분해 표

| id | 변경 파일(disjoint) | 무엇을 (PHP 출처 file:line) | 게이트 (테스트 클래스 · 골든 Y/N) | 선행 |
|----|----|----|----|----|
| **T0a** | `app/game-api/.../read/RankDataReadRepository.kt`(신규) | `rank_data`(`V1__baseline.sql:162-168`) read @Entity + `findByGeneralId(Int)`/`findByGeneralIdIn(List)`. 타입키 = warnum/killnum/deathnum/killcrew/deathcrew/firenum(`GeneralBase.php:135-145` getRankVar). game-api JPA read-only(§7). | `RankDataReadRepositoryIT` · N | — |
| **T0b** | `logic/.../domain/GeneralMeta.kt` | dex1~5/special/special2/personal/specage/specage2/killturn/belong/age/defence_train/recent_war 접근자 추가(`GeneralBase.php:157-165`). meta int/double 헬퍼 재사용. | `GeneralMetaTest` · N | — |
| **T0c** | `app/game-api/.../read/GeneralDisplayDeriver.kt`(신규) | injury 색·텍스트(`func.php:615-631`), age 색(`:639-646`), atmosBonus/trainBonus 부호 마크업(`:656-674`), lbonus calcLeadershipBonus(`:573-578`), defenceTrain 999(`:676-680`), getDexCall+`sprintf('%.1fK')`(`:809-819`), getHonor experience/dedication(`generalInfo2:771-785`), winRate/killRate `round(.,2)`(`:766-767`) → **PhpRound half-away**. | `GeneralDisplayDeriverTest` · N | — |
| **T3a-1** | `app/game-api/.../dto/IdentityDto.kt` | `FrontGeneralInfo`에 picture/imageServer/exp(L/S/I_exp)/explevel/dedlevel/train/atmos/crewTypeId/troop/horse·weapon·book·item/특기(special·special2·personal)/specage·specage2/killturn/age/defence_train/lbonus/refreshScore 필드, `FrontNationInfo`에 type{raw,name,pros,cons}/topChiefs/population{cityCnt,now,max}/crew{generalCnt,now,max}/bill/taxRate/notice/diplomaticLimit/strategicCmdLimit/impossibleStrategicCommand/prohibitScout·War/onlineGen, `FrontGlobalInfo`에 ~25 header gate(extendedGeneral/isFiction/npcMode/joinMode/startyear/develCost/noticeMsg/onlineUserCnt/apiLimit/auctionCount/isTournamentActive/isTournamentApplicationOpen/isBettingActive/isLocked/tournamentType·State·Time/genCount/generalCntLimit/serverCnt/lastVote/lastVoteID/lastExecuted), `FrontCityInfo`에 officerList(2/3/4 name·npc)/nationInfo{id,name,color}. shape=`Global.ts:142-227`. | `IdentityDtoShapeTest`(컴파일+필드존재) · N | T0b |
| **T3a-2** | `app/game-api/.../controller/FrontInfoController.kt` | global{} 실제 채움(`world_state`/카운트/토너·베팅·경매 상태 read), general{} P1 전체(T0b meta + T0a rank + T0c deriver), nation{} type·topChiefs(officer_level 12/11 일반 조회)·population·crew 집계, city{} officerList(`countByCityId`+officer 조회), **`recentRecord` 실제 채움**(현재 하드코딩 `emptyList()` `:67,:135` → 최근 record 피드 read; 소스 OQ-1). 출처 `Global.ts:105-227`. | `FrontInfoControllerTest` · N | T3a-1,T0a,T0c |
| **T3b-1** | `app/game-api/.../dto/F4Dto.kt`(PublicGeneral + 신규 row DTO) | `PublicGeneral`(P0) thicken: npc/injury/explevel/dedlevel/honorText/officerLevelText/dedLevelText/killturn/picture/imgsvr/age/specialDomestic/specialWar/personal/belong/lbonus/reservedCommand/autorun_limit/city/troop(`Nation.ts:9-46`). 신규 `GeneralRowP1`(refreshScore/specage/specage2/L·S·I_exp/dex1~5/experience/dedication/officer_city/defence_train/crewtype/crew/train/atmos/turntime/recent_war/horse·weapon·book·item/warnum/killnum/deathnum/killcrew/deathcrew/firenum, `Nation.ts:48-84`) ⊃ P0, `GeneralListEnvelope`(env{year,month,turntime,turnterm,killturn,autorun_user}+troops[]+myGeneralID+permission, `Nation.ts:95-134`). | `F4DtoShapeTest` · N | T0b |
| **T3b-2** | `app/game-api/.../controller/GeneralsController.kt` | P0 thicken(reservedCommand=GeneralTurnReadRepository, special/age/explevel from T0b/T0c) + 신규 authed `/api/generals/detailed`(permission tier P1/P2, principal nation 매칭→permission, env/troops/myGeneralID 봉투) + **PHP 15-sort 재현**(국가/통솔/무력/지력/명성/계급/관직/삭턴/벌점/Lv/성격/내특/전특/병종/병사 — `a_genList` 정렬). PHP 8.0 stable sort(보조 comparator 금지). | `GeneralsControllerTest` · N | T3b-1,T0a,T0c |
| **T3c** | `app/game-api/.../controller/MyController.kt`(+ MyCitySummary/MyNationDetail DTO in IdentityDto.kt — **T3a-1과 같은 파일이므로 T3a-1에 흡수, 별 family**) | 게이지 now/max + holder names: city 패널 agri/comm/secu/def/wall/pop을 `[now,max]`로(이미 DTO에 *Max 존재 — 컨트롤러는 통과만), 민심 trust + 태수/군사/종사 holder name 추가(`cityInfo` `func.php:153-189`), nation 총주민·총병사 now/max 집계. **DTO 변경은 T3a-1로 통합**(IdentityDto co-widen 방지) → 이 task는 **MyController assembly만**. | `MyControllerTest` · N | T3a-1 |
| **T3d-1** | `app/game-api/.../read/NationTurnReadRepository.kt` | `NationTurnReadEntity`에 `arg jsonb`(`V1__baseline.sql:126`) 컬럼 매핑 추가(현재 미매핑) — `MetaJsonConverter` 사용, insertion-order 보존. | `ReadRepositoryIT`(arg 디코드) · N | — |
| **T3d-2** | `app/game-api/.../dto/F4Dto.kt`(ChiefReserved* + 신규 ChiefCommandItem) — **T3b-1과 같은 파일 → 같은 family(F4Dto creator) 순차** | `ChiefReservedTurn`에 `arg` 추가, `ChiefPost`에 holderName/turnTime/npcType/officerLevelText, 신규 `ChiefCommandCategory{category,values:[ChiefCommandItem{action,name,reqArg,possible,info,compensation}]}`, `troopList:Map<id,name>`, lastExecute/date/year/month/turnTerm/isChief/autorunLimit/mapName/unitSet. shape=`ChiefResponse`(NationCommand.ts)+`GetReservedCommand.php`. | (T3b-1과 동일 게이트) · N | T3b-1 |
| **T3d-3** | `app/game-api/.../controller/ChiefCenterController.kt` | reserved `arg` 통과(T3d-1), post holder(officer_level별 일반 조회 name/npc/turnTime), troopList(TroopReadRepository), **command palette**(수뇌 발령 가능 명령 카탈로그 — `CommandRegistry`/intakeCodes의 nation-command 메타에서 possible/reqArg 산출). 출처 `GetReservedCommand.php`. | `ChiefCenterControllerTest` · N | T3d-2,T3d-1 |
| **T3e-1** | `app/game-api/.../dto/IdentityDto.kt`(GameConstResponse 확장) — **T3a-1과 같은 파일 → 같은 family 순차** | `GameConstResponse`에 gameUnitConst(`GameUnitConst`/`GameUnitDetail` from :common)·cityConstMap(region/level 텍스트)·iActionKeyMap·version 번들 추가. shape=`Global.ts:15-43`. | (T3a-1 family 게이트) · N | — |
| **T3e-2** | `app/game-api/.../controller/GlobalMenuController.kt`(/const 본문) | `/api/const`가 `:common` GameUnitConst/GameConst/도시 region·level 텍스트를 직렬화(`GlobalMenuController.kt:31-32,108-114` CONST 확장). | `GlobalMenuControllerTest` · N | T3e-1 |
| **T3e-3** | `app/game-api/.../dto/AuctionDto.kt` + `.../controller/AuctionController.kt` | `AuctionResponse`에 hostName/startBidAmount·finishBidAmount/highestBid{amount,date,generalID,generalName}/remainCloseDateExtensionCnt/availableLatestBidCloseDate/isCallerHost/isCallerHighestBidder/obfuscatedName/remainPoint/recentLogs + resource/unique 분기. shape=`Auction.ts`. | `AuctionControllerTest` · N | — |
| **T3e-4** | `app/game-api/.../dto/BettingDto.kt` + `.../controller/BettingController.kt` | 신규 `BettingInfo{id,type,name,finished,selectCnt,isExclusive,reqInheritancePoint,open·closeYearMonth,candidates:Record<SelectItem{title,info,isHtml,aux}>,winner}` + bettingDetail/myBetting/remainPoint/totalAmount. shape=`Betting.ts`. | `BettingControllerTest` · N | — |
| **T3e-5** | `app/game-api/.../dto/MessageDto.kt` + `.../controller/MailboxController.kt` | `MessageResponse` src/dest를 `MsgTarget{id,name,nation_id,nation,color,icon}` 객체로, `option{action,invalid,deletable,overwrite,hide,silence,delete}`/msgType/sequence/latestRead{private,diplomacy}/last5min/allowButton 추가. shape=`Message.ts`. | `MessageDtoTest`+`MailboxControllerTest` · N | — |

## 병렬화 그룹 (disjoint worktree family — 같은 파일 co-widen 금지)
- **G-FOUND (Tier-0, 단독·선행, 3 task 병렬 가능 — 서로 disjoint 파일)**: T0a(RankDataReadRepository.kt 신규) · T0b(GeneralMeta.kt) · T0c(GeneralDisplayDeriver.kt 신규). 세 파일 disjoint → 동시 발사. **G-FOUND 완료 후 나머지 모두 시작.**
- **G-IDENTITY (creator→consumer 순차, 같은 `IdentityDto.kt` co-widen)**: T3a-1 → T3e-1 → (그 다음 consumer 병렬: T3a-2, T3c, T3e-2). `IdentityDto.kt`를 T3a-1·T3e-1·T3c가 모두 건드릴 수 있으므로 **DTO 편집은 T3a-1·T3e-1 두 task로 한정**(T3c는 컨트롤러만) — DTO 변경 순차(T3a-1 먼저, T3e-1 다음), 컨트롤러 consumer는 DTO 머지 후 병렬.
- **G-F4DTO (creator→consumer 순차, 같은 `F4Dto.kt` co-widen)**: T3b-1 → T3d-2 → (consumer 병렬: T3b-2, T3d-3). T3b-1·T3d-2가 `F4Dto.kt` co-widen이므로 **순차**(T3b-1 먼저), 이후 컨트롤러 consumer 병렬. T3d-1(NationTurnReadRepository.kt)은 disjoint → G-F4DTO와 동시.
- **G-AUCTION**: T3e-3(AuctionDto.kt + AuctionController.kt) — 독립, 병렬.
- **G-BETTING**: T3e-4(BettingDto.kt + BettingController.kt) — 독립, 병렬.
- **G-MESSAGE**: T3e-5(MessageDto.kt + MailboxController.kt) — 독립, 병렬.

→ **disjoint 병렬 family 수 = 5** (G-FOUND, G-IDENTITY, G-F4DTO, G-AUCTION, G-BETTING, G-MESSAGE 중 G-IDENTITY/G-F4DTO는 G-FOUND 선행 의존; G-AUCTION/G-BETTING/G-MESSAGE는 G-FOUND와도 무관하게 완전 독립). 동시 실행 가능한 disjoint family family group = **5** (G-IDENTITY, G-F4DTO, G-AUCTION, G-BETTING, G-MESSAGE — G-FOUND는 이들의 선행 단일 family).

## 패러티 주의점
- **PHP 골든 불요(NO)**: 본 웨이브는 RNG draw/전투 로그가 아닌 **read 응답 shape/파생-텍스트 패러티**. 게이트는 컨트롤러/디라이버 단위테스트로 PHP `func.php` 산출 문자열·수치를 검증(골든 캡처 불요). 단 **파생 산식은 byte-faithful**:
  - **Rounding(half-away)**: refreshScoreTotal `round(.,-1)`(`func.php:648`), winRate/killRate `round(.,2)`(`:766-767`), getHonor 경계 → **`PhpRound`(phpRound/setRound)**, `Math.round`/`kotlin.math.round` 금지. dex% `dex/dexLimit*100` clamp 100은 산술(round 아님).
  - **로그/텍스트 byte-parity**: injury(위독/심각/중상/경상/건강), age 색, atmos/train `(+n)`/`(n)` 부호 마크업, defenceTrain "수비 안함"/"수비 함(훈사N)", dex short `sprintf('%.1fK', dex/1000)`(반올림 규칙 = printf round-half-even이 아니라 PHP `sprintf` → **PhpRound와 별개, sprintf 동작 그대로**; OQ-3), 태수/군사/종사 라벨(`{cityName} {officerLevelText}`), officerLevelText/honorText 전부 PHP와 동일 문자열.
  - **insertion-order**: nation/city `meta`·`conflict`·officerList·topChiefs·command palette는 `LinkedHashMap`/삽입순 유지(`MetaJsonConverter`가 이미 insertion-ordered). topChiefs는 11/12 키 순, officerList는 2/3/4 키 순 PHP와 동일.
  - **truncate vs round**: belong "N년", killturn "N 턴", crew 등 정수는 `toInt`(truncate). exp bar `getLevelPer`/`expStatus`는 PHP 산식 그대로.
- **flush-delta / one-daemon-write-rule**: 본 웨이브는 **전부 read(game-api JPA read-only, §7 정당)** — ChangeRecorder/JdbcFlushExecutor write-path 무관. 신규 `RankDataReadRepository`/`NationTurnReadEntity.arg`는 read 전용. 데몬 write 경로(architecture-test) 위반 없음 — 검증으로 game-api 모듈 한정 확인.
- **permission tiering(3b)**: P0(공개)⊂P1(같은 국가)⊂P2(군주/참모 secret). `GeneralResolver.derivePermission`/principal nationId 매칭으로 산출 — P2 secret 필드(refreshScore 등)는 permission≥2에서만 노출(PHP `GeneralListItem` union `:90-93`). 비인증/타국은 P0만.

## 오픈 질문 (스펙 단계 미결)
- **OQ-1 (recentRecord 소스)**: PHP `GetRecentRecordResponse`(history/global/general `[number,string][]` + flush flags, `Global.ts:93-101`)의 opensamguk 영속 테이블이 무엇인지 확정 필요. `history` 테이블(`V1__baseline.sql:173 hall`/이후 history)·general_record 존재 여부 확인 후 채움. 미존재 시 zero-fill(rankings HallRecord 선례, READ_DTO_GAP §9처럼 "documented empty, NOT fabricated")로 처리하고 backlog 기록.
- **OQ-2 (dex/war/special 저장 위치)**: opensamguk V1 `general` 테이블에 dex1~5/special/special2/personal/specage/killturn/belong/age/defence_train 전용 컬럼이 있는가(GeneralRowMapper 확인) 아니면 전부 `meta` 라이드인가? GeneralMeta는 exp/level만 노출 — 나머지가 컬럼이면 `GeneralReadEntity`에 컬럼 추가, meta면 T0b 접근자. (T0b는 meta 가정으로 작성 — 실제 row mapper 확인 후 컬럼/메타 결정.) warnum 등은 `rank_data` 확정(T0a).
- **OQ-3 (sprintf 반올림)**: dex short `%.1fK`의 마지막 자리 반올림이 PHP `sprintf`(C printf, round-half-to-even 경향)와 일치해야 — Kotlin `String.format("%.1f")`도 half-even이므로 대체로 일치하나 경계값(예: x.x5K) 한 케이스 PHP 캡처로 확인 권장(골든 아닌 spot-check).
- **OQ-4 (header gate 소스)**: `isTournamentActive`/`isBettingActive`/`auctionCount`/`onlineUserCnt`/`develCost`/`generalCntLimit`/`genCount`/`lastVote` 각각의 opensamguk 영속/계산 소스(world_state meta? betting/auction 테이블 count? online은 세션 부재) — 일부는 실데이터, 일부는 OQ-1식 documented-default. WAVE 3에서 채울 수 있는 것/WAVE 4·8로 미루는 것(토너먼트 active는 WAVE 8 엔진 의존) 분리 필요.
- **OQ-5 (command palette 소스)**: 3d 수뇌 command palette의 possible/reqArg/compensation/info를 `CommandRegistry`/intakeCodes 메타에서 산출 가능한지(precheck 평가 포함 여부). precheck 평가가 필요하면 game-api precheck 서비스 재사용, 단순 카탈로그면 정적 메타. WAVE 5a(예약 에디터)와 중복 회피 — WAVE 3은 **read-only 카탈로그**까지만.
