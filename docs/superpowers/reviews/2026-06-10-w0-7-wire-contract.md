# W0-7 비판 리뷰 — wire 계약 widen (2026-06-10)

대상 브랜치: `w0/7-wire-contract`
대상 작업 (커밋 da24707 + 본 세션 회수분):
- `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt` — `DiploRespondLetter` 변형 신규 + `MakeGeneral` 유산 4필드/전콘 widen (da24707)
- `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommandResult.kt` — `DIPLO_LETTER_TYPES`에 `diploRespondLetter` 합류 (da24707)
- `common/src/test/kotlin/opensamguk/common/wire/IntakeWaveW07WireTest.kt` — 라운드트립/하위호환/셀렉터 (da24707)
- `app/game-api/.../reserve/CommandWireMapper.kt` — intakeCodes + toCommand 4코드 widen (`diploRespondLetter`/`appoint`/`kick`/`changePermission`)
- `app/game-api/.../reserve/CommandWireMapperTest.kt` — 4코드 라운드트립 테스트
- `app/game-engine/.../run/TurnDaemonCommandDispatcher.kt` — W0-7 명시적 deny-with-log 스텁 (silent-drop 금지) + MakeGeneral 유산/전콘 게이트
- `app/game-engine/.../intake/W07DenyStubDispatchTest.kt` — deny 스텁 디스패치 검증 5케이스

미션: PAGE_PARITY_AUDIT_2026-06-10.md §5 W0-7 — wire-contract widen ONLY.
갭 매핑: diplomacy(P0-16) / join(P0-29·30) / my-boss(P0-39~42) / my-cities(P0-47).
엔진 핸들러 구현은 W1 에이전트(G: respond-letter, K: MakeGeneral 유산·전콘, N: 인사부) 소관 —
그 접점 파일(`TurnDaemonCommand.kt`/`CommandWireMapper.kt`)은 본 W0-7에서 단일 widen.

## 공격 벡터별 판정

### A1. PHP POST 인자명 verbatim 위조 여부 — 3 엔드포인트 전수 재검증
2026-06-10 legacy/devsam-core 직접 재인용:
- `hwe/j_diplomacy_respond_letter.php:16-18` — `Util::getPost('letterNo','int')` /
  `getPost('isAgree','bool',false)` / `getPost('reason','string','')`. 변형 필드명/기본값
  (`isAgree=false`, `reason=""`) 일치. trim은 PHP `:53`에서 수행 → 엔진 소관 명시 일치.
- `hwe/j_myBossInfo.php:16-19` — `action`/`officerLevel`/`destGeneralID`/`destCityID`.
  mapper 키 `destGeneralID`/`destCityID`/`officerLevel` verbatim. **무혐의.**
- `hwe/j_general_set_permission.php:11-12` — `isAmbassador('bool')`/`genlist('array_int')`.
  mapper 키 verbatim. **무혐의.**

### A2. 기본값 시멘틱 발산 — 부재 인자
- `isAgree` 부재 → false: PHP 기본값 그대로. **무혐의.**
- `reason` 부재 → `''`: PHP 기본값 그대로. **무혐의.**
- `letterNo` 부재 → 0 (PHP는 null → `:38-43` '올바르지 않은 입력입니다.' die): wire는 0으로
  실어 보내고 invalid-deny는 엔진 몫. 현재 스텁이 전건 deny하므로 오늘은 발산 불가능.
  **W1 G 의무**: letterNo<1 deny를 PHP 문자열로 구현할 것. (스텁 마커 사유로 가시 추적됨.)
- `destCityID` 부재 → 0: PHP `getPost(...,'int')` null도 `if (!$destCityID)` falsy 게이트
  (`j_myBossInfo.php:331-337` 도시임명 분기 내)와 동치. **무혐의.**
- `isAmbassador` 부재 → false: PHP `:32 if($isAmbassador)` truthy 분기 — null≡false≡auditor
  경로. **동치, 무혐의.**
- `genlist` 부재 → 빈 배열: PHP `:51 if(!$genlist)` — null≡[]≡(reset 후 success 종료).
  **동치, 무혐의.**

### A3. appoint 단일 코드 콜랩스 — 도시임명(P0-47)/수뇌임명(P0-39~42) 분기 유실 의혹
PHP도 단일 엔드포인트 `j_myBossInfo.php action=임명`이 `officerLevel`로 분기한다:
`:330-352` `2<=officerLevel<=4` → `do도시임명(:135)` / `:355` `5<=officerLevel<12` →
`do수뇌임명(:77)`. wire 단일 `appoint`(destGeneralID/destCityID/officerLevel 동봉)는 PHP 형상
그대로의 미러 — 분기는 엔진(W1 N). 추방은 별도 action(`:379` → `do추방 :189`) → 별도 `kick`
코드. **구조 일치, 무혐의.**

### A4. 듀얼 키 fallback (`destGeneralId`/`targetGeneralIds`) — 비-PHP 키 수용
mapper 기존 정착 패턴(10례: `targetGeneralId ?: generalID`, `voteId ?: voteID`,
`msgID ?: msgId`, `destNation ?: destNationId` 등 — CommandWireMapper.kt:186-291)과 동일한
FE-관용 수용. PHP 키가 항상 1순위이고 wire 변형 필드는 PHP 시멘틱 그대로 — 패러티 비위반.
**무혐의 (정착 패턴).**

### A5. silent-drop 금지 계약 — dispatcher `else -> null` 구멍
widen된 4변형 + MakeGeneral 유산/전콘 옵션이 `else -> null`로 떨어지면 인테이크는 받고 엔진은
무반응(영구 유실)이었다. 회수분이 명시 deny로 닫음:
- `DiploRespondLetter` → `DiploLetterResult(ok=false)` + WARN 로그
- `Appoint`/`Kick`/`ChangePermission` → `GeneralBoolResult(ok=false)` + WARN 로그
- `MakeGeneral` 유산 4필드/imgsvr 동봉 시 → `MakeGeneralFail` + WARN — 현 핸들러로 흘리면
  **포인트 미차감 일반 생성**(PHP `Join.php:233-248`은 `inheritRequiredPoint` 가산 후
  `유산 포인트가 부족합니다` 게이트+차감)이라는 silent 발산이므로 게이트가 정당. 옵션 없는
  기존 페이로드는 기존 핸들러 경로 보존(테스트가 핸들러 사유 '공백지가 없습니다.'로 증명).
`UNSUPPORTED_REASON`은 **PHP 패러티 문자열이 아님이 주석으로 명시**되어 있고 W1 핸들러가
대체한다 — 패러티 문자열 위조 아님. **무혐의.**

### A6. 결과 셀렉터 커버리지 — 새 type 문자열의 직렬화 누락 의혹
`TurnDaemonCommandResult.kt:522-526` `BOOLEAN_OK_TYPES`에 `changePermission`/`kick`/`appoint`
원소 기존재(0a44a0f 원형 corpus), `:541-542` `DIPLO_LETTER_TYPES`에 `diploRespondLetter` 합류
(da24707). 스텁 결과 4종 모두 셀렉터 도달 가능 — `IllegalArgumentException(unknown type)` 불가.
**무혐의.**

### A7. MakeGeneral widen (da24707) — Join.php 대조
- 유산 4필드 인자명 verbatim: `Join.php:142-145` (`?? null` — null=미사용 동치) ✓
- 검증 규칙 출처: `:74-78` (`in availableSpecialWar` / turntimeZone 0..59 / `in CityConst` /
  integerArray) — 엔진 W1 K 소관으로 명시 ✓
- 전콘 게이트 `:379-385` (`show_img_level>=1 && grade>=1 && picture!="" && pic`)는 member가
  gateway DB 소속이라 컨트롤러가 resolved `picture`/`imgsvr`(`$face`/`$imgsvr`)를 싣는 설계 —
  엔진이 읽을 수 없는 데이터를 wire로 옮기는 유일한 충실 경로. 기본값 null=default.jpg/0 ✓
- 전 필드 기본값 null → 구 페이로드 하위호환 (IntakeWaveW07WireTest가 증명) ✓
**무혐의.**

### A8. intakeCodes 경계 — NB 가드 침범 여부
`join`(REST-only)/`buildNationCandidate`(NationController 직발행)는 intakeCodes 밖 유지 —
NB 주석 그대로. 새 4코드는 전부 데몬커맨드 변형 보유 + dispatcher 결과 보장. **무혐의.**

### A9. 테스트 충실도
- mapper 4코드: PHP 인자명으로 라운드트립 + 부재-기본값 케이스(isAgree/destCityID/
  destGeneralID/genlist) 검증.
- W07DenyStubDispatchTest: publisher↔consumer 동일 envelope 코덱 경유(실제 XADD 페이로드
  형상), deny 비변이(장수 수 불변), dispatchAll 순서·무유실, MakeGeneral 옵션 5변종 전건
  deny + 무옵션 핸들러 경로 보존(스텁 사유 부정 단언).
약점: dispatcher WARN 로그 자체는 단언하지 않음(로그 캡처 어서션 부재) — deny 결과 반환이
주 계약이고 로그는 보조 가시성이므로 수용. **무혐의 (관찰 1건).**

## 잔여 의무 (W0-7 범위 외, 기추적)

- W1 G: `handleRespond` 구현 시 letterNo<1 → '올바르지 않은 입력입니다.', 권한
  `checkSecretPermission>=4`('권한이 부족합니다. 수뇌부가 아닙니다.'), state='proposed' 필터,
  승인 `:78-93`(activated+서명+prev_no 체인 replaced)/거부 `:96-109`(cancelled+aux.reason)/
  메시지 2채널 `:112-133`.
- W1 N: `do수뇌임명`/`do도시임명`/`do추방`/set-permission 본체 + PHP deny 문자열 대체.
- W1 K: MakeGeneral 유산 소비(포인트 차감 `Join.php:233-248`, 천재 생성, 도시/턴존/보너스
  스탯 반영) + 전콘 적용 — 게이트 해제.
- FE 제출 배선(P0-39~42/P0-47/P0-16 페이지) — W1 이후.

**Verdict: cleared**
