# OPENSAM-220 / #483 — gateway-api JWT 표시 클레임 발급 중단

- PR: https://github.com/peppone-choi/opensamguk/pull/520 (`work/opensamguk/jwt-issuer-claim-removal` → `main`) — **머지됨, `0bb5f522`(team-lead).** 배포 진행 중, 결과는 아래 참고.
- 독립 리뷰: `docs/superpowers/reviews/2026-08-24-opensam-220-jwt-claim-removal-independent-critique.md` (`code-reviewer` 서브에이전트, 자기 비평 아님) — **`cleared`**, LOW 2건은 즉시 반영(커밋 `a308dbfe`). `907bf4b9`에서 `tools/` 계약 테스트 변경까지 뮤테이션 테스트로 재검증하며 Scope 확장.

## 한 일

1. **발급 코드 자체에서 표시 클레임 제거** (플래그 아님) — `JwtTokenProvider.generateAccessToken`은 이제 `sub`/`iat`/`exp`/`token_type`/`role`(+RS256이면 `iss`/`aud`)만 서명한다. `GatewayProfileClaims.kt` 삭제, `GatewayJwtClaims`는 9개→4개로 축소돼 `GatewayJwtSecurity.kt`로 이동. 17개 소스/테스트/설정 파일 수정.
2. **선행조건 확인** (둘 다 통과):
   - 소비자 승격 — game-api/board-api 모두 `users` 행에서 표시 정보를 읽는다(b5145ae9/#481, `toMemberProfile`). JWT 검증 경로(`GatewayAccessTokenVerifier.toPrincipal()`)는 애초에 `GatewayPrincipal(userId, role)`만 만든다 — 표시 클레임을 소비한 적이 없다.
   - RS256 활성 — 코드/compose 기본값이 RS256이고, game-api/board-api의 기존 `InfoContributor`가 `rsa-audience-v1`을 보고한다.
3. **board-auth 회귀 충돌 없음 확인** — 별도 tracer가 진단 중인 "닉네임 변경 후 게시판 관리 인증 필요"는 `BoardControl.tsx` 라우팅 회귀로 무관 확인됨. "장수 생성 401"도 JWT 검증 경로와 무관. PR 본문에 단락으로 기록.
4. **gateway-api에 `InfoContributor` 추가** — game-api/board-api와 동일 포맷(`{"jwt":{"verifier":"rsa-audience-v1","publicKeySha256":<fingerprint|"unconfigured">}}`)으로 `/actuator/info`에 발급 키 지문 노출. 지문만 노출, 개인키·공개키 원문·legacy secret 절대 미노출. **두 부팅 시나리오 모두 실제 테스트로 확인**:
   - `LEGACY_HS256` + RS256 키 없음 → `"unconfigured"`, 기동 실패 없음.
   - `RS256` + legacy secret/accept-until 유지(**실제 2026-08-23 컷오버 후 프로덕션 형상**) → 지문 정상 노출, 기동 실패 없음.
5. **정리 함정 문서화** — `docs/operations/jwt-key-rollout.md`에 `JwtTokenProvider` init과 `GatewayAccessTokenVerifier.init()`(`GatewayJwtSecurity.kt:111-117`) 둘 다 전부-아니면-전무 짝 검사임을 명시. legacy 값을 하나만 지우면 세 서비스 모두 부팅 즉시 크래시.

## 검증

- 최종 리베이스 트리(`origin/main` `0220fe74` 위, `--rerun-tasks`, XML 기준): `common=253 gateway-api=204 game-api=560 board-api=53` → **1070 tests / 0 skipped / 0 failures / 0 errors**, `BUILD SUCCESSFUL in 11m 22s`.
- 독립 리뷰어가 별도 clone에서 **직접 재실행**해 동일 수치 재확인(`3m 54s`, `--no-daemon --no-build-cache --rerun-tasks`).
- `tools/agent-system/check.py --strict --base origin/main` — **완주함**(`.omo/` 없는 worktree, #513 아님), exit=1, finding 2건:
  - `cross-agent-critique` → 독립 리뷰 아티팩트 커밋으로 해소.
  - `parity-evidence` (common/src) → 휴리스틱 오탐으로 판단: `GatewayAccessTokenVerifierTest.kt`가 이미 `GatewayJwtClaims`의 모든 상수를 실사용하며 `verifyAccessToken`이 `GatewayPrincipal(userId, role)`만 반환함을 검증 중 — diff에 그 테스트 파일 자체가 없어서 미탐지된 것으로 보임. team-lead 판단 필요 시 참고.

## UNKNOWN — 프로덕션 런타임 미확인 (승인된 범위 내 시도, 우회 안 함)

team-lead가 승인한 읽기 전용 `/actuator/info` 확인을 시도했으나, 이 환경에서 프로덕션 접근 경로가 없다:
- `.github/workflows/deploy.yml`의 프로덕션 접촉 스텝은 `runs-on: [self-hosted, gcp-prod]` — GCP 프로덕션 VM 위에서 직접 실행되는 self-hosted 러너이며, 이 로컬 환경에서 도달 불가.
- 실행한 읽기 전용 확인 명령 전부(감사용 나열):
  ```
  grep -n "ssh\|host\|HOST\|server" .github/workflows/deploy.yml
  ls servers/
  cat ~/.ssh/config | grep -i "host "
  grep -rl "actuator/info\|actuator/health" projects/opensamguk-docker docs/opensamguk-docker
  grep -n "domain\|https://\|public.*IP\|gcp-prod\|self-hosted" projects/opensamguk-docker/README.md
  ```
- 결과: 실제 프로덕션 도메인/SSH 대상/deployer 토큰 없음(플레이스홀더 `sam.example.com`만 존재). 지문 값은 어디에도 출력하지 않았다(애초에 접근이 안 돼 값 자체가 없음).
- **지시대로 우회 시도 없이 여기서 멈춤.** `docs/operations/jwt-key-rollout.md`의 "완료 — 프로덕션은 2026-08-23부로 RS256이다" 문구를 실측 미확인으로 다운그레이드(커밋 `cc65ad9a`), 독립 리뷰어 제안대로 "코드 반영 완료 — 배포 실측 미확인"으로 추가 완화(커밋 `a308dbfe`, `CLAUDE.md` F0도 동일하게 수정).

## 배포 후 잔존 위험 확인 — 살아있는 구토큰의 소비자 (머지 전 마지막 확인)

머지 시점 이후에도 배포 전 발급된 구 access 토큰은 여전히 5개 표시 클레임(`username`/`nickname`/
`grade`/`picture`/`imgsvr`)을 담고 있다. 이 PR 배포 후 신규 발급 토큰에는 그게 없다 — 그래서
**어느 소비자든 토큰 payload에서 이 클레임들을 직접 꺼내 쓰면 배포 직후 화면이 빈다**는 우려로
`web/gateway`/`web/game`/`board-api`/`game-api` 전부를 grep 및 코드 추적했다.

- JWT 디코드 라이브러리(`jwt-decode`/`jwtDecode`/`jsonwebtoken`/`decodeJwt`) — 두 프런트 전체 0건.
- 백엔드 수동 payload/base64 접근 — 매치 3건 전부 `JwtTokenProvider.kt`의 JJWT `parseSignedClaims(token)
  .payload`이고, 꺼내는 값은 `expiration`/`subject`(userId)뿐. 표시 클레임 없음. `getUserIdFromToken`이
  쓰는 그 파서이고, 이후 `JwtAuthenticationFilter` → `CustomUserDetailsService.loadUserById(userId)` →
  DB `UserEntity`로 이어진다(`CustomUserDetails.nickname`도 `user.nickname ?: user.username`, DB 기원).
- `getClaim`/`claims.get`/`claims[` — board-api/game-api 전체 0건, gateway-api는 테스트 파일의
  `ROLE` 클레임 검증 1건뿐(5개 표시 클레임과 무관, 소비자 코드 아님).
- 프런트 데이터 경로: `web/gateway`·`web/game` 둘 다 `/api/auth/me` route handler가 access 쿠키를
  `Authorization: Bearer` 헤더로만 쓰고 gateway-api `GET /auth/me`(DB 기반) 응답 JSON을 그대로 통과.

**결론: 5개 표시 클레임을 토큰 payload에서 직접 읽는 소비자는 어디에도 없다.** 전부 DB 기반 API
호출 경로로 간다 — 배포 후 화면 공백 위험 없음.

**namespace 함정 기록** — `web/game`의 `generals/page.tsx`/`select-pool/page.tsx`/
`GeneralBasicCard.tsx` 등에서 나온 `.picture`/`.nickname` 매치는 **게임 장수(캐릭터) 초상 필드**
(`SelectPoolCard`/`JoinFormResponse.member` 같은 게임 도메인 타입, DB `general`/`member` 로우 기원)
이지 계정 JWT 표시 클레임이 아니다. 이름만 같은 별개 네임스페이스라 grep 하다 "매치 있음 → 위험"으로
오판하기 쉽다. 다음에 이 클레임 관련 grep을 할 때 걸러야 할 항목으로 남긴다.

## 절차상 참고 (판단 근거 투명 공개)

- `origin/main`이 `0220fe74`로 이동한 상태에서, "충돌 시 rebase 말고 fresh+cherry-pick" 지시가 있었지만, 충돌 여부를 먼저 `git diff --stat`로 확인한 결과 내 브랜치(JWT/백엔드만)와 새 main 커밋들(han-map/board-admin-proxy/프론트/문서만)이 완전히 disjoint임을 확인 후 일반 `git rebase origin/main`을 직접 실행 — 충돌 0건으로 완료. 지시의 문자 그대로는 아니지만("충돌 시"라는 조건이 애초에 성립 안 함) 취지(안 깨지고 CI 트리거되는 브랜치)는 동일하게 달성. team-lead가 이후 메시지에서 이 결과를 정상으로 확인함.

## 안 한 것 (지시대로)

- PR 머지 안 함 — team-lead 담당.
- `main` 푸시 안 함, `app/game-engine/`·`work/opensamguk/han-map-wave` 미접촉.
- `opensamguk-docker` 미편집(읽기만).
- `.env*`/시크릿 파일 미열람, 시크릿·키 원문·프로덕션 지문 값 미출력.
- 프로덕션 쓰기·재시작·compose 조작·env 변경·배포·RS256 실제 컷오버 실행 없음(컷오버는 team-lead가 별도 수행).
