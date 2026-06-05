# WAVE 9 — public read endpoints + remaining views + admin parity

## 목표
long-tail read 완결: PHP `j_get_city_list`(public 도시일람)와 fog 포함 인게임 `getWorldMap`(`Global/GetMap`)을 game-api에 이식하고, 남은 `v_*.php`→Next 페이지(인게임 map)를 커버하며, admin/install 엔드포인트가 design-replaced(Flyway+ScenarioImporter+daemon)로 유지됨을 문서화한다(포팅 X).

## 출처
- 인벤토리: `docs/superpowers/gap/API_GAP.md`(§A11 `Global/GetMap`·`GetCachedMap`·`GetNationList` PARTIAL/MISSING, §B `j_get_city_list`/`j_map`/`j_map_recent`/`j_install*`/`j_autoreset`/`j_raise_event` 줄 196~228, §C `v_cachedMap`/`v_join` 줄 253~254), `docs/superpowers/gap/READ_DTO_GAP.md`(§1 GetMap fog/supply/state/region/spy MISSING, §4 public city list MISSING, §3 `SimpleNationObj`/`NationStaticItem` 필드).
- GAP_AUDIT 섹션: `docs/superpowers/GAP_AUDIT.md` WAVE 9 (줄 222~226) — 9a public city-list + full in-game `GetMap` fog, 9b 남은 `v_*`→Next + gateway install page, 9c admin/install design-replaced 문서화.
- PHP grand truth(legacy/devsam-core):
  - `hwe/func_map.php:52-170` (`getWorldMap` — 정확한 출력 구조: `startYear/year/month/cityList/nationList/spyList/shownByGeneralList/myCity/myNation/version/result`), `:3-17` (`MapRequest` — `neutralView`/`showMe` 인자), `:144-148` (cityList tuple = `[city,level,state,nation,region,supply]` 전부 `Util::toInt`), `:123-131` (nationList tuple = `[nation,name,color,capital]`), `:98-121,150-155` (spyList = `nation.spy` decode `{cityNo:remainMonth}` + userGrade≥5는 전 도시 1), `:133-142` (`shownByGeneralList` = `select distinct city from general where nation=myNation`).
  - `hwe/sammo/API/Global/GetMap.php:15-37` (validateArgs: `neutralView`/`showMe` in [0,1]; `REQ_LOGIN | REQ_READ_ONLY`; `getWorldMap` 위임).
  - `hwe/sammo/API/Global/GetCachedMap.php:23,48-105` (`NO_SESSION`, 600s 파일캐시, `neutralView=true/showMe=false`, `+history(getGlobalHistoryLogRecent 10)`+`theme`).
  - `hwe/j_get_city_list.php:32-38` (public 도시일람 = `getAllNationStaticInfo()` + `cityArgsList=['city','nation','name','level']` + `select city,nation,name,level from city`; 비로그인 10초 쓰로틀).
  - `hwe/func.php:38-82` (`getNationStaticInfo`/`getAllNationStaticInfo` — `NationStaticItem`={nation,name,color,type,level,capital,gennum,power}; 중립(0)={재야,#000000,...,gennum 1,power 1}).
  - `hwe/ts/defs/index.ts:243-263` (`MapResult` 와이어 타입 = compact tuple + `CachedMapResult & {theme,history}`), `:265-...` (`SimpleNationObj`).
  - `hwe/install.php:1-24` (admin-only reset/install — userGrade<5 + ACL gate; opensamguk에선 design-replaced).

## 완료/제외 (이미 닫힘 — 스펙에서 제외, 근거 file:line)
- **MapPreview city state/supply/capital**: `app/game-api/.../dto/MapPreviewDto.kt:36-43`의 `MapPreviewCity`가 `state`/`supply`/`isCapital`를 이미 carry하고 `MapPreviewController.kt:121-123`가 `city.frontState`/`city.supplyState!=0`/`capitalIds` 매핑 완료. `CityReadEntity`가 `front_state`(`CityReadRepository.kt:56`)·`supply_state`(`:53`)·`region`(`:89`) 컬럼을 이미 매핑 → **city tuple 6필드 중 state/supply/region의 데이터 원천은 닫힘.** 9a의 `GetMap`은 이를 재사용한다(신규 컬럼 불요).
- **city `region`**: `CityDetailController.kt:33-34`(`region`)+`CityReadRepository.kt:89-90`이 이미 노출 → 9a cityList의 region은 읽기만 통과.
- **MapPreview 10분 캐시 + dims(map/`che`.json ×10/7 1000×714)**: `MapPreviewController.kt:51-74,145-152`가 이미 완성 — 9a in-game `GetMap`은 **별 엔드포인트**(`/api/map`)로 추가하며 lobby `/api/map/preview`(게이트웨이 전용)는 그대로 둔다. **MapPreview를 재작성/co-widen 하지 않는다.**
- **identity 해석(JWT principal→소유 general)**: `GeneralResolver.kt:46-61`(principal→general/nationId/officerLevel/permission) + `FrontInfoController.kt:49-56`의 `@AuthenticationPrincipal userId: Long?` + `?generalId=` transition fallback 패턴이 완성 → 9a `GetMap`의 `myCity`/`myNation`/`showMe` 게이트는 이 패턴 재사용(신규 인증 인프라 불요).
- **`world_state.config/meta`의 `startYear`**: `WorldStateReadRepository.kt:37-43`의 `config`/`meta` jsonb가 이미 매핑되고 startYear가 거기 거주(`WorldStateReadEntity` 주석 `:14-16`) → 9a의 `startYear` 노출은 읽기만(신규 컬럼 불요).
- **`nation.spy`(정찰 잔여개월)**: opensamguk엔 전용 `spy` 컬럼이 **없고** `nation.meta`의 `"spy"` 키로 라이드된다(`PreUpdateMonthly.kt:52-60,165-174`가 `{cityNo:remainMonth}`로 decode/decay). `NationReadEntity.meta`(`NationReadRepository.kt:50-52`)가 이미 jsonb 매핑 → 9a spyList는 **meta에서 읽기만** 필요(마이그레이션·신규 컬럼 불요). **단 시나리오 1010 seed가 spy를 비워두므로 정상 출력 = 빈 맵**(OQ-1 참조).
- **admin/install REST(`j_install*`/`j_install_db`/`j_autoreset`/`j_raise_event`/`Global/ExecuteEngine`/`Admin/BanEmailAddress`/`Misc/UploadImage`)**: `API_GAP.md:175,183-184,225-228`가 이미 "design-replaced by Flyway+ScenarioImporter+AdminSeeder+자율 daemon, MISSING-as-REST지만 design-OK"로 판정. **포팅하지 않는다** — 9c는 문서화 task로만 닫는다(코드 변경 0).
- **gateway 로비/조인/admin 페이지 골격**: `web/gateway/app/admin/page.tsx`·`web/gateway/app/join/`·`web/gateway/app/lobby/`가 F0에서 이미 존재 → 9b는 **인게임 map 페이지(web/game) 신규**만 다루고 gateway는 "install page = design-replaced" 문서화로 충분(신규 gateway 페이지 불요, OQ-3).
- **public city-list 외 read DTO 보강(Front/General P1·P2/Chief/Auction/Betting/Message/Const)**: WAVE 3에서 처리 — 본 웨이브 범위 밖, 파일 disjoint.
- **`j_map_recent`/`GetCachedMap` 별도 캐시 엔드포인트**: `GetCachedMap`은 `getWorldMap(neutralView=true,showMe=false)+history` 래퍼일 뿐(`GetCachedMap.php:84-105`) → 9a `GetMap`이 닫히면 `?neutralView=1&showMe=0` 호출로 동등하게 커버. **별 캐시 파일스토리지는 이식하지 않는다**(opensamguk은 in-game `GetMap`을 호출시 계산 + 선택적 in-process 캐시; recent_map 파일캐시는 design 제외, OQ-2).

## foundation-first 빌드 순서
Tier-0(공유 read 확장점, **먼저·순차**) → Tier-1(DTO 정의) → Tier-2(컨트롤러 assembly + 게이트) → Tier-3(FE 페이지) → Tier-4(문서).

- **Tier-0 (creator, 단독·선행)**
  - **F0-distinctCity**: `GeneralReadRepository`에 `findDistinctCityIdByNationId(nationId): List<Int>`(또는 `@Query("select distinct g.cityId from GeneralReadEntity g where g.nationId=:n")`) 추가 — `shownByGeneralList` 산출(`func_map.php:135-138` `select distinct city from general where nation=%i`). game-api JPA read-only(§7). 9a `GetMap` consumer가 의존.
- **Tier-1 (DTO 정의, 파일 disjoint)**
  - `WorldMapDto.kt`(신규) — `WorldMapResponse`(`MapResult` 와이어 패러티) + compact 옵션. `MapPreviewDto.kt`와 **별 파일**(co-widen 금지). 9a-controller가 의존.
  - `CityListDto.kt`(신규) — public 도시일람 `CityListResponse{nations:List<NationStaticItem>, cityArgsList, cities}` + `NationStaticItem`. 9a-citylist consumer가 의존.
- **Tier-2 (컨트롤러 + 게이트, 병렬)** — 9a-getmap / 9a-citylist 두 컨트롤러는 disjoint 파일 → 병렬.
- **Tier-3 (FE, web/game)** — 인게임 map 페이지(9b) — BE 9a 머지 후.
- **Tier-4 (문서, 코드 0)** — 9c admin design-replaced 문서화 + GAP_AUDIT/PARITY_LEDGER 업데이트.

## 태스크 분해 표

| id | 변경 파일(disjoint) | 무엇을 (PHP 출처 file:line) | 게이트 (테스트 클래스 · 골든 Y/N) | 선행 |
|----|----|----|----|----|
| **T0** | `app/game-api/.../read/GeneralReadRepository.kt` | `findDistinctCityIdByNationId(nationId: Int): List<Int>` 추가(`@Query` distinct cityId). `shownByGeneralList` 원천(`func_map.php:135-138`). 결과 id-ascending 정렬(PHP는 무순서지만 deterministic 위해 정렬 — insertion-order 무관). | `ReadRepositoryIT`(distinct city by nation) · N | — |
| **T1a** | `app/game-api/.../dto/WorldMapDto.kt`(신규) | `WorldMapResponse{result:Boolean, version:Int(=0), startYear, year, month, cityList:List<MapCityTuple>, nationList:List<MapNationTuple>, spyList:Map<Int,Int>, shownByGeneralList:List<Int>, myCity:Int?, myNation:Int?}`. cityList tuple = `[city,level,state,nation,region,supply]`(6 Int), nationList tuple = `[nation,name,color,capital]`(`func_map.php:144-148,123-131`). 와이어 타입 = `index.ts:243-258` `MapResult`. **tuple 직렬화는 `List<Int>`/`List<Any>` 배열 형태로 PHP compact tuple과 동형.** | `WorldMapDtoShapeTest`(컴파일+필드존재) · N | — |
| **T1b** | `app/game-api/.../dto/CityListDto.kt`(신규) | `CityListResponse{nations:List<NationStaticItem>, cityArgsList:List<String>(=["city","nation","name","level"]), cities:List<List<Any>>}` + `NationStaticItem{nation,name,color,type,level,capital,gennum,power}`(`func.php:38-82`, `j_get_city_list.php:32-38`). cities tuple 순서 = cityArgsList 순서(`[city,nation,name,level]`). | `CityListDtoShapeTest` · N | — |
| **T2a** | `app/game-api/.../controller/WorldMapController.kt`(신규, `@RequestMapping("/api/map")` — `MapPreviewController`와 **다른 클래스**, path `GET /api/map`) | `getWorldMap` 본문 이식(`func_map.php:52-170`): ① principal→general 해석(`GeneralResolver.resolve`, `FrontInfoController` 패턴), ② `?neutralView=&showMe=` 인자(기본 false; `GetMap.php:18-19` in[0,1] 검증), ③ `myCity`(showMe시 general.cityId else null)/`myNation`(neutralView시 null else general.nationId)(`:78-96`), ④ cityList = `city` 전 행 `[id,level,frontState,nationId,region,supplyState]` `Util::toInt` 동형, ⑤ nationList = `nation`(중립 0 제외 PHP는 포함—`select * from nation` 전부; **PHP는 nation 테이블 전 행, 0 미존재시 미포함**) `[id,name,color,capitalCityId?:0]`, ⑥ spyList = myNation 있으면 `nation.meta["spy"]` decode `{cityNo:remainMonth}` else `{}`; userGrade≥5(어드민)면 전 도시 1(`:150-155` — opensamguk userGrade 소스 OQ-4), ⑦ shownByGeneralList = T0 distinct city(myNation 없으면 `[]`), ⑧ startYear = `world_state.config/meta["startYear"]`(WorldEnvBuilder 경유 또는 직접 read). 빈 world ⇒ 200 result=true 빈 cityList. **READ-ONLY, write-path 무관.** | `WorldMapControllerTest`(MockMvc standalone, mock read repo — `MapPreviewControllerTest` 패턴) · N | T0,T1a |
| **T2b** | `app/game-api/.../controller/CityListController.kt`(신규, `GET /api/cities`) | public 도시일람(`j_get_city_list.php:32-38`): `nations` = 전 nation static(`getAllNationStaticInfo` 동형 — id/name/color/type_code/level/capital/gennum(meta)/power) **+ 중립(0) 행**(`func.php:53-66` 재야 entry), `cityArgsList=["city","nation","name","level"]`, `cities` = `city` 전 행 `[id,nationId,name,level]`. 비로그인 10초 쓰로틀(`:18-30`)은 **divergence로 생략**(OQ-5 — opensamguk read는 무인증 공개, F1/MapPreview 선례). public read(no auth). 빈 world ⇒ 200 빈 cities. | `CityListControllerTest`(MockMvc standalone) · N | T1b |
| **T3** | `web/game/app/game/map/page.tsx`(신규) + `web/game/app/game/map/*`(컴포넌트) | 인게임 world map 페이지 — `GET /api/map`(fog: spyList/shownByGeneralList/myCity/myNation) 소비, `MapPreview`(gateway lobby) 대비 게임 클라용. `v_cachedMap.php`(`API_GAP.md:253`)→Next 커버. che 베이스맵 + 도시점(state/supply/capital 아이콘) + 내 장수 소재 도시 하이라이트(shownByGeneralList) + 정찰 도시 표시(spyList). **read 렌더만**(mutation 없음). | (FE — 빌드/타입체크 게이트, 자동 단위테스트 없음; manual QA) · N | T2a |
| **T4** | `docs/superpowers/GAP_AUDIT.md`(WAVE 9 섹션 줄 222~226) + `docs/superpowers/gap/API_GAP.md`(§A11/§B admin 행) | 9c: admin/install(`j_install*`/`j_install_db`/`j_autoreset`/`j_raise_event`/`Global/ExecuteEngine`/`Admin/BanEmailAddress`/`Misc/UploadImage`)가 Flyway+`ScenarioImporter`+`AdminSeeder`+자율 daemon으로 **design-replaced** 확정 — "do not port" 명시 + 9a/9b 닫힘 표시. **코드 변경 0.** | (문서 — 게이트 없음) · N | T2a,T2b,T3 |

## 병렬화 그룹 (disjoint worktree family — 같은 파일 co-widen 금지)
- **G-GETMAP (creator→consumer 순차)**: T0(`GeneralReadRepository.kt`) → T1a(`WorldMapDto.kt` 신규) → T2a(`WorldMapController.kt` 신규) → T3(`web/game/app/game/map/`). 네 파일 disjoint이나 데이터 의존 체인(distinct→DTO→controller→FE) → **순차**.
- **G-CITYLIST (creator→consumer 순차, G-GETMAP와 완전 disjoint)**: T1b(`CityListDto.kt` 신규) → T2b(`CityListController.kt` 신규). G-GETMAP와 동시 발사 가능(파일·데이터 무공유; T0의 distinctCity는 city-list가 쓰지 않음).
- **G-DOCS (최후, 단독)**: T4 — 모든 BE/FE 머지 후 문서화(코드 0). 게이트 없음.

→ **동시 실행 가능 disjoint 병렬 family 수 = 2** (G-GETMAP, G-CITYLIST). G-DOCS는 두 family 완료 후 단발 문서 task(병렬 카운트 제외).

## 패러티 주의점
- **PHP 골든 불요(NO)**: 본 웨이브는 RNG draw/전투 로그가 없는 **read 응답 shape 패러티**. 게이트 = 컨트롤러 MockMvc 단위테스트로 와이어 tuple 순서·필드·fog 게이트 분기를 검증(골든 캡처 불요).
- **Util::toInt(truncate-toward-zero)**: cityList tuple의 6값 전부 PHP `Util::toInt`(`func_map.php:147`) — opensamguk City 필드는 이미 Int이므로 통과하나, region/state/supply가 String/Double로 오면 truncate. `Math.round` 금지(패러티). PhpRound 불필요(정수 통과).
- **insertion-order**: spyList(`Map<Int,Int>`)는 `nation.meta["spy"]` decode 순서 보존(`LinkedHashMap`) — `func_map.php:106-111` `explode('|')` 순서 또는 `Json::decode` 키 순서. 재키잉 금지(PHP는 cityNo 키 raw 순서). cityList/nationList는 `city`/`nation` 테이블 행 순서(PHP는 무 ORDER BY → DB 자연순서; opensamguk은 **id-ascending 정렬로 deterministic 고정** — divergence이나 표시 전용, 패러티 영향 없음. OQ-6).
- **fog 게이트 분기(byte-faithful)**: `myCity`/`myNation`/`spyList`/`shownByGeneralList`의 4분기 게이트(`neutralView`/`showMe`/userGrade≥5/myNation 유무)를 `func_map.php:78-155` 순서대로 정확히 이식 — 한 분기라도 어긋나면 fog 노출이 틀어진다(보안 패러티: 적국 도시를 spyList 없이 노출하면 안 됨).
- **중립국 처리 차이**: `getWorldMap` nationList는 `select * from nation`(중립 0 nation 테이블에 없으면 미포함, `func_map.php:124`) — `MapPreview`가 중립을 `nations[]`에서 제외(`MapPreviewController.kt:129`)하는 것과 **동일 정책**. 반면 `j_get_city_list`의 `nations`는 `getAllNationStaticInfo`로 **중립(0)을 명시 포함**(`func.php:53-66`) — 두 엔드포인트가 다르므로 9a/9b를 혼동하지 말 것.
- **one-daemon-write-rule 무관**: 두 컨트롤러 전부 game-api JPA read-only(§7) — `ChangeRecorder`/`JdbcFlushExecutor` write-path 미접촉, 데몬 무관.

## 오픈 질문
- **OQ-1 (spy seed)**: 시나리오 1010 seed가 `nation.meta["spy"]`를 비워두므로 인게임 `GetMap` spyList는 정상적으로 `{}`(빈 맵)으로 나온다 — 정찰 명령(`che_정찰` 등)이 머지/실행되기 전엔 fog가 항상 비어있음. 이게 W9 게이트에서 "정상"인지(빈 spy로 200+비fog), 아니면 정찰 명령 포팅을 선행 의존으로 묶을지 확인 필요. (현 판단: 빈 spy = 정상 출력, 정찰 명령은 PARITY_LEDGER 별 트랙.)
- **OQ-2 (cached map 파일스토리지)**: `GetCachedMap`의 Nette FileStorage 600s 파일캐시(`GetCachedMap.php:54-104`)를 이식하지 않고 `GetMap(?neutralView=1)` + (선택) in-process 캐시로 대체한다는 design 가정이 맞는지. recent_map history(`getGlobalHistoryLogRecent 10`)+theme 병합이 FE에서 필요하면 `GetMap`에 옵션 인자로 추가할지.
- **OQ-3 (gateway install page)**: 9b의 "gateway install page"가 신규 UI(설치 마법사)를 요구하는지, 아니면 install이 design-replaced(Flyway+seed 자동)이므로 "install page 불요" 문서화로 충분한지. (현 판단: 자동 시드라 install 마법사 불요 — 9b는 인게임 map 페이지만 신규.)
- **OQ-4 (userGrade 소스)**: `func_map.php:150` `userGrade>=5`(어드민 전 도시 fog 해제)의 opensamguk userGrade 원천 — gateway JWT에 grade claim이 있는지, 아니면 admin은 별 게이트(role=ADMIN)로 매핑할지. 없으면 이 분기는 보수적으로 skip(일반 fog만)할지.
- **OQ-5 (도시일람 쓰로틀)**: `j_get_city_list`의 비로그인 10초 쓰로틀(`:18-30`)을 의도적 divergence로 생략하는 게 확정인지(MapPreview/F1 선례상 무인증 공개 read). rate-limit이 운영상 필요하면 별 cross-cutting으로 빼는지.
- **OQ-6 (테이블 행 순서)**: cityList/nationList를 PHP 무-ORDER-BY(DB 자연순서) 대신 id-ascending으로 고정하는 divergence가 표시 전용이라 패러티 무해하다고 보는데, 와이어 byte-parity를 엄밀히 따지는 골든이 생기면 PHP DB insert 순서를 재현해야 할 수 있음(현재 게이트는 골든 없음 → id-asc로 충분).
