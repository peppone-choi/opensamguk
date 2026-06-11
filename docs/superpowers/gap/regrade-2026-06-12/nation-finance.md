# /game/nation-finance (내무부) 재감사 — 2026-06-12

2026-06-11 감사(`PAGE_PARITY_AUDIT_2026-06-11.md` §2.19)가 P1-084 이후 truncated 된 부분의 완결 재감사.
READ-ONLY 측정 — 소스 무수정.

## 판정 기준

- **Grand truth (PHP)**: `legacy/devsam-core/hwe/v_nationStratFinan.php` (페이지 진입·staticValues 조립),
  `legacy/devsam-core/hwe/ts/PageNationStratFinan.vue` (렌더·계산식·액션),
  `legacy/devsam-core/hwe/sammo/API/Nation/Set{Notice,ScoutMsg,Rate,Bill,SecretLimit,BlockWar,BlockScout}.php` (7 setter),
  `legacy/devsam-core/hwe/func.php:390-435` (`checkSecretPermission`), `hwe/func_time_event.php:141-258` (수입 함수군),
  `hwe/sammo/Scenario/Nation.php:104-122` (시나리오 국가 초기값), `hwe/sql/schema.sql:123-128` (nation DDL 기본값).
- **현재 impl**: `web/game/app/game/nation-finance/page.tsx`,
  `app/game-api/.../controller/NationFinanceController.kt`, `app/game-api/.../dto/F4Dto.kt:241-360`,
  `web/game/types/game.ts:765-805`, `app/game-api/.../reserve/CommandWireMapper.kt`,
  `logic/.../actions/intake/NationFinanceSetters.kt`, `logic/.../actions/intake/SecretPermission.kt`,
  `app/game-engine/.../intake/NationFinanceSetterHandler.kt`.
- 등급: **P0** = 크래시/100% 실패 액션/기밀 누출/데이터 위조, **P1** = 데이터·권한·섹션 결손(기능 발산),
  **P2** = 의도적 연기(quarantine 문서화)·사소한 콘텐츠.
- 모든 finding은 직접 읽은 file:line 인용. 날조 없음.

---

## 1. P0 (4건 — 기존 open 2건 재확인 + 신규 2건)

### NF-P0-A [content] 외교관계 7컬럼 표 전체 부재 (= 기존 P0-54, 여전히 OPEN)
- legacy: `hwe/ts/PageNationStratFinan.vue:4-46` — 페이지 최상단 `외교관계` 표
  (국가명/국력/장수/속령/상태/기간/종료 시점 7컬럼, `diplomacyStateInfo`(hwe/ts/defs/index.ts:234-239 —
  0 교전/1 선포중/2 통상/7 불가침) 색상 + `joinYearMonth(year,month)+term` 종료 시점 계산, 자국 행은 `-`).
  데이터 조립: `hwe/v_nationStratFinan.php:45-72` (`cityCntList` GROUP BY + `dipStateList` diplomacy WHERE me + 자국 state=7/term=null).
- 현재: `web/game/app/game/nation-finance/page.tsx` — 섹션 자체가 없음(파일 전체에 외교관계/nationsList 렌더 0건).
  백엔드도 `NationFinanceController.kt:108` `nationsList = null`(W1-O 조립 대기), DTO 타입만 정본화
  (`F4Dto.kt:340-359` `NationFinanceNationItem`/`NationFinanceDiplomacyState`).
- 판정: **OPEN 유지**. FE 렌더 + BE 조립 둘 다 미착수.

### NF-P0-B [crash] income null 역참조 — 국가 소속자 전원 렌더 크래시 (신규)
- legacy: `v_nationStratFinan.php:77-115` — `getGoldIncome/getWarGoldIncome/getRiceIncome/getWallIncome`
  (`func_time_event.php:141,166,187,213`) + `getOutcome`(`func_time_event.php:239`)를 rate=100으로 **항상 라이브 산출**
  → `income`/`outcome`은 절대 null이 아니다.
- 현재 BE: `NationFinanceController.kt:94-95` `income = null, outcome = null` (P0-52 BLOCKED — 위조 0 제거는 옳음).
- 현재 FE 타입: `web/game/types/game.ts:798-799` `income: NationFinanceIncome; outcome: number;` — **비-nullable로 선언**
  (tsc가 null 가능성을 못 봄).
- 현재 FE 런타임: `page.tsx:134` `const { income, outcome, ... } = data;` → `page.tsx:142`
  `const incomeGoldCity = (income.gold.city * policy.rate) / 100;` — income이 null이므로
  **`TypeError: Cannot read properties of null (reading 'gold')` 즉시 throw** → 국가 소속자(정상 경로) 전원 에러 화면.
- 판정: P0-51은 "중첩 shape 재구축으로 크래시 해소"로 기록됐으나(§2.19), BE가 null을 채우고 FE가 비-nullable
  역참조를 유지해 **크래시가 그대로 잔존**한다. shape 수정과 별개로 FE 가드(또는 P0-52 income 배선)가 필요.

### NF-P0-C [actions] setBlockWar 100% guaranteed-deny — 잔여횟수 소스가 영구 0 (신규)
- legacy: `SetBlockWar.php:55-58` — `nation_env` KV `available_war_setting_cnt`를 라이브 read,
  `<= 0`이면 "잔여 횟수가 부족합니다.", 성공 시 즉시 차감(`:60-66`).
- 현재 엔진: `NationFinanceSetterHandler.kt:90` — `nationEnv(nation)["available_war_setting_cnt"] ?: 0`,
  `nationEnv()`는 `nation.meta["nation_env"]` read(`:138-139`). 그런데 이 키를 채우는 코드가 **0곳**:
  - `engine/boot/WorldSnapshotLoader.kt` — `nation_env` 테이블 로드 없음(`SELECT ... FROM nation` meta만, :94-108; 파일 내 nation_env 검색 0건).
  - `infra/seed/ScenarioImporter.kt` — nation_env 시드 없음(grep 0건).
  - `ChangeRecorder.recordNationEnvKv`(`ChangeRecorder.kt:377-378`)는 **flush 델타 전용** — in-memory `nation.meta["nation_env"]`를 갱신하지 않음. flush 타깃도 별도 `nation_env` 테이블(`JdbcFlushExecutor.kt:729-748`)이고, 이 테이블을 read하는 코드는 repo 전체에서 flush 자신뿐(`grep "FROM nation_env"` 히트 1건 = JdbcFlushExecutor).
  - 월틱 `MonthlyPostUpdateHook.kt:147-149`의 Q10 적립도 같은 flush-전용 채널 + `:107` `TODO: read existing available_war_setting_cnt KV when a read path is available`.
- 결과: availableCnt는 어떤 엔진 런에서도 항상 0 → **전쟁 금지 설정 버튼은 매번 "잔여 횟수가 부족합니다." deny**.
  부수: 설령 값이 있어도 성공 후 in-memory 미갱신이라 동일 업타임 내 차감이 보이지 않는 이중 버그
  (`:93-97` — meta["war"]만 world에 반영, KV는 recorder만).
- 같은 채널(`nation.meta["nation_env"]`)을 읽는 `AiTurnAdapter.kt:1619-1621`(NPC 외교 원조)도 동일 공백 — 영향 반경 메모.

### NF-P0-D [security/permission] read API 권한 게이트 전무 + 타국 재정 열람 가능 (신규)
- legacy: `v_nationStratFinan.php:27-34` — `checkSecretPermission($me)`(func.php:390-435) **< 1이면 페이지 자체 거부**
  ("권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다."), `< 0`(무소속)도 거부. 조회 대상은 항상
  `$me['nation']`(`:37-38`) — **자국만**. 내무부의 gold/rice/세율/지급률/secretlimit/정책은 기밀(사관년도 게이트) 데이터다.
- 현재: `NationFinanceController.kt:50-64` — 인증만 통과하면 임의 `{id}`의 국가 row를 조회·반환.
  `resolved.nationId == id` 비교는 `editable` 산정(`:79`)에만 쓰이고 **read 자체는 무게이트**:
  타국(적국 포함)의 국고/군량/세율/정책/기밀권한년수를 아무 장수나(평장수·재야 포함) GET 가능.
  permission(0..4) 게이트도 없음 — `SecretPermissionReader`(read 정본 헬퍼, `gameapi/read/SecretPermissionReader.kt`)가
  존재함에도 이 컨트롤러는 미사용(import 0건).
- 판정: 회의실 누출(P0-08)과 동급의 기밀 누출 — **P0**. (FE 측 진입 게이트 부재는 NF-P1-D로 분리.)

---

## 2. P1 (4건)

### NF-P1-A [permission] P1-084 재개 — editable에 `permission==4`(ambassador) 누락, SecretPermissionReader 미배선
- 2026-06-11 감사는 P1-084를 **[FIXED]**("W0-3 (#74) SecretPermissionReader 정본화 … 엔진/FE 게이트 일치 완료")로
  기록했으나, **nation-finance 컨트롤러에는 배선되지 않았다**:
  - legacy: `v_nationStratFinan.php:128` `editable = ($me['officer_level'] >= 5 || $permission == 4)`.
  - 현재: `NationFinanceController.kt:79` `editable = resolved != null && resolved.nationId == id && resolved.officerLevel >= 5`
    — ambassador 분기 없음. 파일에 `SecretPermissionReader` import 0건. 주석 `:38-39` 스스로 "P1-084 …
    W1-O가 닫는다"고 미완을 자인.
  - `SecretPermissionReader.kt:30` 의도된 소비처 목록에 "P1-084 nation-finance: editable = officer_level>=5 ∥ permission==4" 명시 — 헬퍼는 완성·테스트(`SecretPermissionReaderTest.kt`)됐지만 소비처 미연결.
- 영향: officer_level<5 ambassador(외교특임)가 legacy에선 전 설정 버튼 사용 가능, 현재는 버튼 미노출(FE `page.tsx:138`
  `editable = data.editable` 게이트) → 기능 차단. 엔진 측 게이트(`SecretPermission.financeSetterDenyReason`,
  `SecretPermission.kt:128-133`)는 PHP 2줄(`SetRate.php:46-52`)과 일치하므로 **API로 직접 쏘면 되는 비대칭**.
- 감사 대장의 [FIXED] 표기는 diplomacy/mailbox/board/chief-center에만 유효 — nation-finance 행은 **OPEN으로 정정 필요**.

### NF-P1-B [data] nationMsg/scoutMsg/warSettingCnt.remain 라운드트립 여전히 불능 (P0-53의 nation_env 절반)
- legacy: `v_nationStratFinan.php:129-130` `nationNotice['msg']`/`scout_msg`, `:150` `available_war_setting_cnt` —
  모두 `nation_env` KV 라이브 read.
- 현재: setter는 정상 flush(`NationFinanceSetterHandler.kt:40-49,61,95` → `JdbcFlushExecutor.kt:736-748`
  nation_env 테이블)되지만 **read 경로가 없다**: `NationFinanceController.kt:59` `remain = null`,
  `:106-107` `nationMsg = null, scoutMsg = null` (game-api에 nation_env read repo 부재 — `FROM nation_env` 검색
  히트는 infra flush 1건뿐).
- 영향: 수뇌가 국가 방침/임관 권유문을 저장해도 GET이 영원히 null → FE textarea(`page.tsx:74-75` `res.nationMsg ?? ''`)는
  항상 빈값 = **저장 데이터가 소실된 것처럼 보임**. 전쟁 금지 잔여횟수도 영구 공란(`page.tsx:282` `{warSettingCnt.remain}`).
- 2026-06-11 §2.19의 "P0-53 [FIXED] … setter→flush→GET 반영"은 **meta 절반(rate/bill/secretlimit/war/scout)에만 참** —
  nation_env 절반은 미닫힘. NF-P0-C와 같은 뿌리(nation_env read 채널 부재)이며 W1-O 배선 대상.

### NF-P1-C [data] 시드가 rate/bill/secretlimit/war/scout를 미기록 → 전 국가 policy null (신규)
- legacy: 시나리오 국가 생성 시 `hwe/sammo/Scenario/Nation.php:112-115` `'bill'=>100, 'rate'=>15, 'scout'=>0, 'war'=>0`
  명시 INSERT + `secretlimit`은 DDL 기본 3(`hwe/sql/schema.sql:126`) — **PHP에선 게임 시작 직후에도 policy 값이 항상 존재**.
- 현재: `infra/seed/ScenarioImporter.kt`에 rate/bill/secretlimit/scout/war meta 시드 없음(grep 0건) →
  `NationFinanceController.kt:96-102` 방어적 read가 전부 null → FE `:255` `{policy.rate}%`는 "%"만,
  계산식 `:142` `null * null / 100 = 0`(JS null→0 강제)로 세금 0 표시.
- 영향: NF-P0-B(크래시)를 가드로 피해도 **예산표·정책 전체가 공란/0** — setter를 한 번이라도 누르기 전까지.
  "미기재→null(날조 금지)" 규약 자체는 옳으나, 근본 원인은 **시드가 PHP 초기값(실측 가능한 골든)을 빠뜨린 것** —
  null 규약의 적용 대상이 아니라 시드 결손.

### NF-P1-D [permission/content] FE 진입 게이트 부재 — permission<1 거부 분기 없음 (신규)
- legacy: `v_nationStratFinan.php:28-34` — `permission < 0` → "국가에 소속되어있지 않습니다.",
  `permission < 1` → "권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다." (페이지 차단).
- 현재: `page.tsx:119-130` — 재야(`!nid`)만 "국가에 소속되어있지 않습니다." 처리. 사관년도 미달 평장수
  (officer_level 1, belong < secretlimit → permission 0)도 전체 재정 데이터 열람 가능.
- NF-P0-D(BE 게이트)와 한 쌍 — BE에 secretPermission 게이트가 들어가면 FE는 거부 메시지 분기만 추가하면 됨.

---

## 3. P2 (5건)

### NF-P2-A [content] TipTap 리치텍스트 → plaintext textarea (의도적 연기 — 유지 확인)
- legacy: `PageNationStratFinan.vue:57-62, 76-82` TipTap(HTML 리치텍스트) + 높이 트래킹(`:383-409`).
- 현재: `page.tsx:190-197, 209-216` plaintext textarea — 주석 명시 "TipTap deferred — spec OQ-3"(`:33`).
- 리치텍스트로 저장된 legacy HTML 방침문 표시 호환은 P0-53 read 배선 시 함께 결정 필요.

### NF-P2-B [logic] htmlPurify 새니타이즈 quarantine (문서화 확인)
- legacy: `SetNotice.php:56` `WebUtil::htmlPurify($msg)` (HTMLPurifier HTML5 외부 라이브러리).
- 현재: `NationFinanceSetters.kt:42-44, 104-108` — falsy 가드만 충실 재현, 새니타이즈는 P8 quarantine 명시. 적합.

### NF-P2-C [content] increaseRefresh("내무부") + checkLimit 게이트 부재
- legacy: `v_nationStratFinan.php:14, 21-25` — `increaseRefresh` 카운트 + `checkLimit(refresh_score)`(func.php:113-135)
  ≥2면 `printLimitMsg` 후 종료. 사이트 전반 refresh 제한 시스템이 opensamguk에 미이식(이 페이지 한정 아님).

### NF-P2-D [actions] setBlockWar 응답 `availableCnt` 즉시 반영 불가 (구조 노트)
- legacy: `PageNationStratFinan.vue:510-511` — `result.availableCnt`로 remain 즉시 갱신(동기 API).
- 현재: 인테이크는 202-async(`CommandWireMapper.kt` → Redis XADD) + `page.tsx:330` `onReserved → fetchData()` refetch.
  refetch 자체는 합리적 대체이나 GET remain=null(NF-P1-B)이라 현재는 무의미. NF-P1-B 해소와 함께 재검.

### NF-P2-E [content] `추가 설정` 자리표시 행 부재
- legacy: `PageNationStratFinan.vue:233` `<div>추가 설정</div>` (BottomBar 직전). 현재 page.tsx 부재. 사소.

---

## 4. 닫힌 항목 검증 결과

| 항목 | 2026-06-11 기록 | 재검 결과 |
|---|---|---|
| P0-51 중첩 shape 재구축 | FIXED (#75, 439d0a8) | **부분 유효** — DTO 중첩 구조(`F4Dto.kt:261-292`)는 legacy `staticValues`(php:126-154)와 구조 일치 ✅. 그러나 nullable 채움 ↔ FE 비-nullable 타입(`types/game.ts:798-799`) 재발산으로 크래시 잔존 → NF-P0-B |
| P0-53 read 스토어/키 정합 | FIXED (#75) | **절반만 닫힘** — meta 절반(rate/bill/secretlimit/scout/war)은 setter `writeNationMeta`(`NationFinanceSetterHandler.kt:128-135`) ↔ read `metaInt`(`NationFinanceController.kt:96-103`) 동일 키로 라운드트립 ✅ (`F4ReadControllersTest.kt:452-498` 커버). nation_env 절반(방침/권유문/잔여횟수)은 미닫힘 → NF-P1-B |
| P1-084 editable 게이트 | FIXED (#74) | **미닫힘 — 정정 필요** → NF-P1-A. 헬퍼(`SecretPermissionReader.kt`)와 logic 정본(`SecretPermission.kt`)은 PHP `checkSecretPermission`(func.php:390-435)과 분기 일치 ✅이나 nation-finance 컨트롤러 미배선 |
| 7 setter 인테이크 wire | (P0-02/F4-C2 계열) | **유효** ✅ — `CommandWireMapper.kt:46-54` intakeCodes 7종 + `:148-170` 타입 매핑. 검증 범위·deny 문자열 PHP byte-일치: rate 5..30(`SetRate.php` Validator ↔ `NationFinanceSetters.kt:29-30`), bill 20..200, secretlimit 1..99, msg 16384/1000, "권한이 부족합니다."(2줄 게이트, `SecretPermission.kt:128-133` = `SetRate.php:46-52`), "잔여 횟수가 부족합니다."(`SetBlockWar.php:57` = `NationFinanceSetters.kt:89`), "임관 설정을 바꿀 수 없도록 설정되어 있습니다."(`SetBlockScout.php:55` = `:99`) — 단 setBlockWar는 NF-P0-C로 런타임 100% deny |
| 예산표 계산식/라벨 | (F4 read) | **유효** ✅ — `page.tsx:142-176` = Vue computed 체인(`PageNationStratFinan.vue:411-433`) 동식·동라벨(현 재/단기수입/세 금/수입/지출/국고 예산, Math.floor 동일). 단 입력이 null이라 NF-P0-B/NF-P1-C에 가려짐 |
| warSettingCnt.inc/max 상수 | (W0-2) | **유효** ✅ — `GameConst.kt:308-309` (10/2) = `GameConstBase.php:228-229` |
| SetNotice KV insertion order | (F4-C2) | **유효** ✅ — `NationFinanceSetterHandler.kt:43-48` `linkedMapOf(date,msg,author,authorID)` = `SetNotice.php:54-59` 배열 순서 |

## 5. 권고 닫기 순서 (W1-O 묶음)

1. **nation_env read 채널 신설**(NF-P0-C·NF-P1-B 공통 뿌리): game-api `NationEnvReadRepository` + 엔진 boot 로드
   (또는 `WorldSnapshotLoader`가 nation_env 테이블→`nation.meta["nation_env"]` 머지) + `recordNationEnvKv` 시 in-memory 동기 갱신.
2. **컨트롤러 게이트**(NF-P0-D·NF-P1-A·NF-P1-D): `SecretPermissionReader` 배선 — read는 자국+permission≥1,
   editable은 `officerLevel>=5 || permission==4`.
3. **시드 보강**(NF-P1-C): `ScenarioImporter`에 `rate=15, bill=100, secretlimit=3, scout=0, war=0`
   (`Scenario/Nation.php:112-115` + `schema.sql:126` 출처 명기).
4. **income 파이프라인 조립**(P0-52) 전까지 FE null 가드(NF-P0-B) 선행 — 가드는 위조가 아니라 BLOCKED 표기.
5. P0-54 nationsList 조립 + FE 외교관계 표(NF-P0-A).
