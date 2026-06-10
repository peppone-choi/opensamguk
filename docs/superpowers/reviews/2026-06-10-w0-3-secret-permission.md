# W0-3 Secret Permission Foundation Review

Verdict: cleared

## Scope (audit `PAGE_PARITY_AUDIT_2026-06-10.md` §5 W0-3)

- `logic/src/main/kotlin/opensamguk/logic/actions/intake/SecretPermission.kt` (정본화)
- `app/game-api/src/main/kotlin/opensamguk/gameapi/read/SecretPermissionReader.kt` (신설)
- `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/DiplomacyController.kt` (수렴)
- `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/GeneralLogController.kt` (수렴)
- 테스트: `SecretPermissionTest`(logic 20), `SecretPermissionReaderTest`(13), `F4ReadControllersTest`/`GeneralLogControllerTest` 핀 추가

## Problem

read API 표면의 `checkSecretPermission`(0..4/-1)이 **4벌 사본**으로 분열돼 있었다:
DiplomacyController/GeneralLogController는 officer_level-only 축소판(+stale "BLOCKED" 주석),
MailboxController/ContactController는 각자 풀 포팅. logic `SecretPermission`은 정본 후보였으나
PHP와 3중 발산: penalty를 `meta["penalty"]`에서 읽고(전용 jsonb 컬럼이 원천), 키가
`no_chief`(PHP는 camelCase `noChief`), 판정이 `== true`(PHP는 truthy), 그리고
`checkSecretMaxPermission` 상한이 placeholder(4 고정)였다.

## Adversarial review

1. **"BLOCKED" 주장 전수 검증** — 감사 지시대로 원천 부재를 실측. 결과: **부재 아님.**
   - `permission`(ambassador/auditor) → `general.meta["permission"]` (CheHaesan/CheDeungyongSurak 기록).
   - `penalty` → 전용 jsonb 컬럼 `V1__baseline.sql:99` + `General.penalty` 필드 + `GeneralRowMapper:64,95,130` 왕복.
   - `belong` → `general.meta["belong"]` (CheImgwan/CheGeobyeong 기록, GeneralListController 소비).
   - `secretlimit` → `nation.meta["secretlimit"]` (CheGeobyeong founding + NationFinanceSetters 기록).
   → **BLOCKED 분기 0개.** 전 분기 LIVE. (감사 P1-031의 stale-BLOCKED 지적과 일치.)
2. **penalty 키 camelCase 증거** — `hwe/sammo/Enums/PenaltyKey.php`: `NoTopSecret='noTopSecret'`,
   `NoChief='noChief'`, `NoAmbassador='noAmbassador'`. 기존 logic의 `no_chief`는 어떤 writer도
   없는 죽은 키였다(grep 전수: writer 0) — 교정이 기존 데이터를 깨지 않음을 확인.
3. **read 표면 checkSecretLimit 분기** — PHP read 호출자는 모두 기본 인자(true):
   `j_diplomacy_get_letter.php:33`, `API/Nation/GetGeneralLog.php:60`. 에러 문구
   '…사관년도가 부족합니다'(GetGeneralLog.php:74)가 belong<secretlimit 분기의 텍스트 증거.
   엔진 intake 호출자(Board/Troop/DiplomacyLetter/재정 세터)는 종전대로 false — 시그니처/기본값
   보존으로 무영향(엔진 313 테스트 green으로 확인).
4. **meta 키 부재 복원값** — 시드 1010은 belong/secretlimit 미기록. PHP 신설 행의 DDL 기본값으로
   복원: belong=1(`hwe/sql/schema.sql:57`), secretlimit=3(`schema.sql:126`). 0-기본을 쓰면
   `0>=0`으로 officer_level 1 전원에게 권한 1이 부여돼 **권한 인플레이션**(P0-08류 누출 방향) —
   기각하고 DDL 복원 채택. nation 행 부재(불능 상태)도 동일 기본값(보수 방향).
5. **기존 핀 테스트 vs PHP** — 변경 전후 판정 차이가 나는 픽스처는 두 부류뿐:
   (a) lv1 외교권자 detail 마스킹(P1-031 — 종전이 WRONG, func.php:413-414로 교정 핀 추가),
   (b) lv1 사관년도 충족 열람(func.php:421-427 — 종전이 WRONG, 허용 핀 추가).
   기존 deny 핀(`일반 장수는 수뇌부 권한 부족 에러`)은 DDL 복원값(1<3)으로 동일 결과 — 문구 byte 불변.
6. **lazy secretLimit** — PHP는 officer_level==1 폴스루에서만 DB 조회(func.php:422-424).
   공급자 람다로 동일 평가 시점 보장 + lv5에서 throw하는 테스트로 게으름을 핀.
7. **하드코딩 점검** — 신규 상수는 전부 PHP file:line 증거 부착(DDL 기본 1/3, PenaltyKey 문자열,
   상한 1/1/2). 발명값 없음.
8. **W1 경계** — Mailbox/Contact/Board 파일은 W1 D/L 에이전트 소유라 미접촉(공동 확장 금지 원칙).
   두 컨트롤러의 자체 포팅은 정본과 산식 일치(checkSecretLimit 미가동 차이만 — 그쪽 PHP 호출자
   검증과 함께 W1에서 헬퍼로 수렴). 소비처 규약은 `SecretPermissionReader` KDoc에 감사 ID별 명시:
   P0-08 board / P0-15 diplomacy canWrite>=4 / P0-34·35 mailbox / P1-023 chief-center /
   P1-084 nation-finance editable(officer_level>=5 ∥ permission==4).

## Known follow-ups (W0-3 범위 밖, 날조 아님 — 기록)

- `constraints` noPenalty 프리셋이 `meta["penalty"]`를 읽는 흔적(PresetsPureTest:181) — 전용 컬럼
  원천과 불일치 가능성. 별도 감사 필요(엔진 intake 영역).
- Mailbox/Contact의 사본 제거는 P0-34·35 담당 W1 에이전트가 수행(이 PR은 파운데이션만).

## Evidence

- `:logic:test` 2109 tests / 0 failures / 0 errors (XML 합산, `--rerun-tasks`).
- `:app:game-api:test` 272 / 0F / 0E (XML 합산, `--rerun-tasks`) — 신규 reader 13 + 컨트롤러 핀 2 포함.
- `:app:game-engine:test` 313 / 0F / 0E — SecretPermission 엔진 소비자(BoardHandler/TroopHandler/
  DiplomacyLetterHandler) 무회귀.
- TDD red 증빙: SecretPermissionTest는 구현 전 컴파일/판정 red(raw 오버로드 부재 + no_chief/메타
  penalty 판정) 확인 후 green.
