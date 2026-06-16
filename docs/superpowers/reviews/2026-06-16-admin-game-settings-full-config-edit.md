# 어드민 게임 환경 설정 전체 구현 검토

**대상 PR**: `loop-admin-config-full-2026-06-16`  
**변경 범위**:
- `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt`
- `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminReadController.kt`
- `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/AdminWriteControllerTest.kt`
- `web/gateway/app/admin/page.tsx`

**목표**: `_admin1.php`의 남은 운영자 설정을 모두 노출/수정 가능하게 한다:
`운영자메세지`, `최대장수`, `최대국가`, `시작년도`, `시작시간`, `턴시간(turnterm)`.
기존에 구현된 `npcmode`, `block_general_create`와 함께 FE `입장 설정` 패널에서 일괄 편집.

## 변경 요약

1. **PATCH `/api/admin/game-settings` 확장**
   - 허용 키: `msg`, `npcmode`, `block_general_create`, `maxgeneral`, `maxnation`, `startyear`, `starttime`, `turnterm`.
   - `msg`는 `game_kv` (`table=game_env`, `namespace=global`, `key=msg`)에 JSON-encoded string으로 저장.
   - `turnterm`은 `world_state.config`와 `world_state.tick_seconds`를 함께 갱신; 응답에 `restartRequired: true`로 엔진 재시작 필요 표시.
   - 나머지 키는 `LinkedHashMap`으로 `world_state.config` 갱신(삽입 순서 보존).
   - ADMIN gate, 미지정 키 거부, 범위/형식 검증 유지.

2. **GET `/api/admin/game-settings` 확장**
   - `editableFields`에 8개 항목 모두 포함.
   - `msg`는 `game_env/global/msg` 우선, 부재 시 `config["msg"]` 폴백.
   - `turnterm`은 `config["turnterm"]` 우선, 부재 시 `tickSeconds / 60` 폴백.
   - `blockedWrites`에서 구현된 항목 제거; `중원정세추가`만 남김.

3. **FE `GameSettingsControl` 확장**
   - `type=text` (`msg`, `starttime`)은 문자열 그대로 전송.
   - `type=number`/`select`는 `parseInt` 후 전송.
   - `turnterm` 변경 시 "엔진 재시작 후 적용" 메시지 노출.

4. **테스트 확장**
   - `AdminWriteControllerTest` 13개: 기존 입장 설정 테스트 + `msg` 저장, `turnterm`/`tickSeconds` 갱신, `maxgeneral` 범위, `starttime` 형식.

## 검증

- `:app:game-api:test --tests AdminWriteControllerTest` — **13/13 green**.
- `web/gateway` `pnpm tsc --noEmit` — clean.
- `web/game` `pnpm tsc --noEmit` — clean.

## 패러티 영향

- **게임 로직/RNG/로그**: 변경 없음. 어드민 설정 변경은 다음 턴/입장 검증에만 영향.
- **골든 게이트**: 기존 devsam-baseline 골든에 영향 없음.
- **one-daemon-write rule**: `world_state` / `game_kv` JPA save는 어드민 서버 관리 config에 한해 허용(이전 커밋과 동일한 예외).

## Verdict: cleared

변경은 범위 한정적이며, 테스트 + 타입체크로 커버됨. 추가 보완 가능 사항:
- `starttime`은 형식만 검증; 실제 시뮬레이션 진행 로직에서 사용 여부는 별도 검증 필요.
- `turnterm` 변경 후 엔진 재시작은 수동; 추후 자동 `TurnDaemon` restart API가 생기면 연동.
