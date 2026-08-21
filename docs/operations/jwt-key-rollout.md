# JWT 비대칭 키 롤아웃

JWT 발급자는 `opensamguk-gateway`로 고정한다. gateway-api만 RS256 개인 키를 받고, game-api와 board-api는 공개 키만 받는다. 액세스 토큰 audience는 `gateway-api`, `game-api`, `board-api` 세 개이고 리프레시 토큰 audience는 `gateway-api`만이다.

## Stage 1: 소비자 먼저

1. RSA 키 쌍을 생성하고 개인 키는 공유 스택 gateway-api에만, 공개 키는 모든 API에 배포한다.
2. gateway-api는 `JWT_SIGNING_MODE=LEGACY_HS256`, `JWT_INCLUDE_PROFILE_CLAIMS=true`를 유지한다.
3. 세 API에 기존 HS256 값을 `JWT_LEGACY_SECRET`로 넣는다. `JWT_LEGACY_ACCESS_ACCEPT_UNTIL`은 예정한 Stage 2 시각보다 최소 15분 뒤의 절대 UTC 시각이어야 한다. gateway-api의 `JWT_LEGACY_REFRESH_ACCEPT_UNTIL`은 Stage 2보다 최소 7일 뒤이어야 한다.
4. board-api와 모든 실행 중 game-api를 `jwt.verifier=rsa-audience-v1` 빌드로 승격한다. 이 단계에서는 기존 토큰과 RS256 토큰을 모두 검증할 수 있지만, 기존 토큰은 cutoff를 넘겨 만료할 수 없다.

Stage 1 소비자 승격이 끝나지 않았다면 gateway 서명 모드나 표시 클레임을 바꾸지 않는다.

## Stage 2: 발급자 전환

1. `JWT_SIGNING_MODE=RS256`으로 바꾸는 배포는 board-api를 먼저 재시작한다.
2. 배포 workflow는 board-api와 등록된 모든 game-api가 실행 중인지 확인하고 `/actuator/info`에서 `rsa-audience-v1`과 공개 키 DER의 SHA-256 identity를 검증한다. 중지됐거나 marker가 없거나 공유 스택의 `JWT_PUBLIC_KEY`와 identity가 다른 서버가 하나라도 있으면 gateway-api 재시작 전에 배포를 중단한다. 등록 env가 남아 있는 중지 서버도 예외 없이 차단 대상이다.
3. 게이트가 통과한 뒤 gateway-api를 RS256 발급자로 재시작한다. 이 전환에서는 `JWT_INCLUDE_PROFILE_CLAIMS=true`를 유지한다.
4. 15분 액세스 전환 창이 끝나면 game-api·board-api에서 `JWT_LEGACY_SECRET`과 `JWT_LEGACY_ACCESS_ACCEPT_UNTIL`을 제거해 공개 키만 남긴다. 7일 리프레시 전환 창이 끝나면 gateway-api에서도 모든 `JWT_LEGACY_*` 값을 제거한다.
5. 모든 소비자 승격 게이트가 계속 통과하는 상태에서만 `JWT_INCLUDE_PROFILE_CLAIMS=false`로 바꾸어 OPENSAM-220 표시 클레임을 제거한다.

## 중단 조건

- 게임 서버 하나라도 asymmetric verifier marker를 보고하지 않음
- 등록된 game-api가 중지되어 live verifier를 확인할 수 없음
- board-api가 공개 키 검증자로 기동하지 않음
- 소비자가 공유 스택과 다른 공개 키 identity를 보고함
- 기존 access/refresh 만료가 각 cutoff를 넘음
- game-api 또는 board-api 컨테이너에 빈 값이 아닌 기존 서명 비밀이 남음
