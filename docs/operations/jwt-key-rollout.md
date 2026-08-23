# JWT 비대칭 키 롤아웃

JWT 발급자는 `opensamguk-gateway`로 고정한다. gateway-api만 RS256 개인 키를 받고, game-api와 board-api는 공개 키만 받는다. 액세스 토큰 audience는 `gateway-api`, `game-api`, `board-api` 세 개이고 리프레시 토큰 audience는 `gateway-api`만이다.

## Stage 1: 소비자 먼저

1. RSA 키 쌍을 생성하고 개인 키는 공유 스택 gateway-api에만, 공개 키는 모든 API에 배포한다.
2. gateway-api는 `JWT_SIGNING_MODE=LEGACY_HS256`을 유지한다.
3. 세 API에 기존 HS256 값을 `JWT_LEGACY_SECRET`로 넣는다. `JWT_LEGACY_ACCESS_ACCEPT_UNTIL`은 예정한 Stage 2 시각보다 최소 15분 뒤의 절대 UTC 시각이어야 한다. gateway-api의 `JWT_LEGACY_REFRESH_ACCEPT_UNTIL`은 Stage 2보다 최소 7일 뒤이어야 한다.
4. board-api와 모든 실행 중 game-api를 `jwt.verifier=rsa-audience-v1` 빌드로 승격한다. 이 단계에서는 기존 토큰과 RS256 토큰을 모두 검증할 수 있지만, 기존 토큰은 cutoff를 넘겨 만료할 수 없다.

Stage 1 소비자 승격이 끝나지 않았다면 gateway 서명 모드나 표시 클레임을 바꾸지 않는다.

## Stage 2: 발급자 전환

1. `JWT_SIGNING_MODE=RS256`으로 바꾸는 배포는 board-api를 먼저 재시작한다.
2. 배포 workflow는 board-api와 등록된 모든 game-api가 실행 중인지 확인하고 `/actuator/info`에서 `rsa-audience-v1`과 공개 키 DER의 SHA-256 identity를 검증한다. 중지됐거나 marker가 없거나 공유 스택의 `JWT_PUBLIC_KEY`와 identity가 다른 서버가 하나라도 있으면 gateway-api 재시작 전에 배포를 중단한다. 등록 env가 남아 있는 중지 서버도 예외 없이 차단 대상이다.
3. 게이트가 통과한 뒤 gateway-api를 RS256 발급자로 재시작한다. **팀 보고 기준 — 프로덕션이 2026-08-23부로 `JWT_SIGNING_MODE=RS256`으로 전환됐다고 알려져 있다.** 이 문서를 쓴 시점 기준 이 저장소 체크아웃에서 `/actuator/info` 실측을 시도했으나 프로덕션 접근 경로(도메인·SSH·deployer 토큰)를 찾지 못해 **실측 미확인**이다 — 코드/유닛테스트로 확인한 "이 형상이면 안전하다"는 사실뿐이며, 배포된 프로덕션이 실제로 이 형상인지는 별도 읽기 전용 확인이 필요하다. `JWT_LEGACY_*` 값은 아래 전환 창이 끝날 때까지 (제거하지 않고) 그대로 유지하는 것으로 알려져 있다.
4. 15분 액세스 전환 창이 끝나면 game-api·board-api에서 `JWT_LEGACY_SECRET`과 `JWT_LEGACY_ACCESS_ACCEPT_UNTIL`을 제거해 공개 키만 남긴다. 7일 리프레시 전환 창이 끝나면 gateway-api에서도 모든 `JWT_LEGACY_*` 값을 제거한다. **이 제거는 반드시 세 값(secret + 두 accept-until, gateway-api 기준) 또는 두 값(secret + accept-until, game-api/board-api 기준)을 한 번에 지워야 한다 — 아래 "정리 함정" 참고.**
5. gateway-api도 game-api/board-api와 동일한 `InfoContributor` 포맷(`{"jwt": {"verifier": "rsa-audience-v1", "publicKeySha256": <fingerprint|"unconfigured">}}`)으로 `/actuator/info`에 발급 키 지문을 노출한다(`JwtTokenProvider.contribute`) — 세 서비스의 `publicKeySha256`을 직접 3자 대조할 수 있다. 지문만 노출하며 키 원문·legacy secret은 절대 싣지 않는다. `JWT_SIGNING_MODE=LEGACY_HS256`이고 RS256 키가 비어 있어도 `publicKeySha256`이 `"unconfigured"`로 안전하게 나올 뿐 기동은 절대 실패하지 않는다(unit test로 고정: `JwtTokenProviderTest`).

### 정리 함정 — legacy 값은 전부 같이 지워야 한다

`JwtTokenProvider`의 init 요구조건(`legacyKey == null ⇔ 두 accept-until 모두 null`)과 `GatewayAccessTokenVerifier.init()`
(`common/src/main/kotlin/opensamguk/common/auth/GatewayJwtSecurity.kt:103-109`, `(legacyKey == null) == (legacyAcceptUntil == null)`)은
둘 다 **전부 아니면 전무(all-or-nothing)** 짝 검사다. `JWT_LEGACY_SECRET`이나 accept-until 중 **하나만** 지우고 재배포하면
gateway-api·game-api·board-api 모두 즉시 부팅 예외로 죽는다(compose 재시작 루프). 4번 단계에서 값을 지울 때는 반드시
관련된 값을 **동시에** 지워라 — 하나씩 순차 배포하지 마라.

## Stage 3: 표시 클레임 발급 중단 (OPENSAM-220/#483, 완료 — 2026-08-23)

Stage 1/2 게이트(모든 game-api 소비자가 `users` 조회 경로로 승격됨 — b5145ae9/#481; RS256이 코드·compose 기본 활성 경로)가
모두 통과한 뒤, `JWT_INCLUDE_PROFILE_CLAIMS` 플래그를 끄는 대신 발급 코드 자체에서 표시 클레임(username/nickname/
grade/picture/imgsvr)을 제거했다 — 플래그가 있으면 다음 사람이 다시 켠다. 액세스 토큰은 이제 `sub`/`iat`/`exp`/
`token_type`/`role`(+RS256일 때 `iss`/`aud`)만 담는다. 표시 정보가 필요한 게임 서버·게시판은 `users` 행을 읽는다
(`opensamguk.gameapi.member.toMemberProfile`). 되돌릴 수 없는 변경이므로 롤백은 이전 이미지 재배포로만 가능하다.

## 중단 조건

- 게임 서버 하나라도 asymmetric verifier marker를 보고하지 않음
- 등록된 game-api가 중지되어 live verifier를 확인할 수 없음
- board-api가 공개 키 검증자로 기동하지 않음
- 소비자가 공유 스택과 다른 공개 키 identity를 보고함
- 기존 access/refresh 만료가 각 cutoff를 넘음
- game-api 또는 board-api 컨테이너에 빈 값이 아닌 기존 서명 비밀이 남음
