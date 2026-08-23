# 독립 리뷰 — PR #520 "Stop issuing display claims in gateway access JWTs" (OPENSAM-220, closes #483)

- 리뷰어: 독립 리뷰어 서브에이전트 (작성 패스와 분리된 별도 lane, self-critique 아님)
- 대상: `work/opensamguk/jwt-issuer-claim-removal` @ `cc65ad9a` (base `origin/main` = `0220fe74`)
- 리뷰 환경: 작성자 worktree가 아닌 **별도 신규 clone** (`git@github.com:peppone-choi/opensamguk.git`, scratch 디렉터리)
- 날짜: 2026-08-24

Scope: PR #520 `work/opensamguk/jwt-issuer-claim-removal` (OPENSAM-220/#483 JWT display-claim removal + InfoContributor) — covers app/, common/, .github/workflows/, tools/
Verdict: cleared

## 판정: **cleared** (blocking 이슈 없음)

CRITICAL/HIGH 없음. 아래 LOW 4건 + INFO 1건은 머지 차단 사유가 아니다.

---

## 1. 클레임 제거의 정확성 — 확인됨

`app/gateway-api/src/main/kotlin/opensamguk/gateway/security/JwtTokenProvider.kt:88-98`

```kotlin
fun generateAccessToken(userId: Long, role: String): String {
    val now = clock.instant()
    val builder = tokenBuilder(userId, GatewayJwtClaims.ACCESS_TOKEN, properties.accessExpiration, now)
        .claim(GatewayJwtClaims.ROLE, role)
    return sign(builder)
}
```

- 표시 클레임 분기(`if (properties.includeProfileClaims) { ... }`) 및 `includeProfileClaims` 프로퍼티 자체가 삭제됨. 플래그가 아니라 코드에서 사라졌으므로 config로 되살릴 수 없다 — 의도대로다.
- `generateRefreshToken`은 `sub`/`iat`/`exp`/`token_type`(+RS256일 때 `iss`/`aud`)만 담는다. 원래 표시 클레임이 없었고 지금도 없다.
- 역방향 리더(`getProfileFromAccessToken`, `getUsernameFromToken`)도 함께 삭제됨.
- `common/.../GatewayJwtSecurity.kt:19-25` 의 `GatewayJwtClaims`는 정확히 `TOKEN_TYPE`/`ACCESS_TOKEN`/`REFRESH_TOKEN`/`ROLE` 4개다. `GatewayProfileClaims.kt`는 파일째 삭제.

**전 저장소 grep (`.git` 제외) — 코드 잔재 0건:**

```
$ grep -rn "GatewayProfileClaims\|includeProfileClaims\|INCLUDE_PROFILE_CLAIMS\|include-profile-claims" .
docs/operations/jwt-key-rollout.md:33      (히스토리 서술)
docs/superpowers/reviews/2026-08-21-...md  (과거 리뷰 문서)
docs/superpowers/reviews/2026-08-20-...md  (과거 리뷰 문서)
.github/workflows/deploy.yml:369           (제거 사실을 설명하는 주석)
$ grep -rn "getProfileFromAccessToken\|getUsernameFromToken" .
(0건)
```

main/test 소스, `application*.yml`, `docker-compose*.yml`, `.env.example` 모두 클린. 남은 4건은 전부 산문(과거 문서 + 설명 주석)이며 참조가 아니다.

**표시 클레임을 토큰에서 다시 읽으려는 코드도 없음:**

```
$ grep -rnE 'claims?(\[|\.get\()\s*"(username|nickname|grade|picture|imgsvr)"' --include=*.kt --include=*.java .
(0건)
$ grep -rniE "jwt_?decode|jwtDecode|atob\(|decodeJwt|parseJwt" --include=*.ts --include=*.tsx --include=*.js --include=*.vue .
web/game/e2e/v2-space-fps.spec.ts:121   (JWT 무관 — 바이너리 픽셀 디코딩)
```

프론트엔드는 애초에 토큰을 디코딩하지 않는다(토큰은 httpOnly 쿠키에만 보관). 표시 정보는 `AuthResponse.user`(`AuthService.toResponse`, `AuthService.kt:164-172`)로 그대로 내려가므로 프론트 회귀 경로가 없다.

## 2. 전제 1 (소비자 승격) — 확인됨

- `b5145ae9` (#481) 은 `origin/main` 의 조상임을 확인 (`git merge-base --is-ancestor` 통과).
- game-api: `app/game-api/src/main/kotlin/opensamguk/gameapi/member/MemberProfile.kt:16-23` — `UserEntity.toMemberProfile()` 은 전적으로 DB row 기반. 호출부 `JoinController.kt:335`, `SelectPoolController.kt:42` 모두 `users.findById(...)` 선행.
- board-api: `BoardJwtAuthenticationFilter.kt:23-26` 은 `verifier.verifyAccessToken` → `principal.userId` → `userRepository.findById` → `BoardUserDetails(user)`. `BoardUserDetails.nickname` 은 `user.nickname ?: user.username` (DB). `GatewayBoardService.kt:213-215` 의 작성자 표시(name/picture/imageServer)도 `users` 조회.
- `GatewayPrincipal` 은 `(userId, role)` 두 필드뿐이고, `GatewayAccessTokenVerifier.toPrincipal()`(`GatewayJwtSecurity.kt:158-162`)은 `token_type`/`role`/`sub`만 읽는다. **JWT 검증 경로가 표시 클레임을 소비한 적이 없다** — 즉 발급 중단은 소비자 관점에서 무해하다.
- `GatewayAccessTokenVerifier`/`GatewayJwtClaims` 를 참조하는 앱 모듈은 gateway/game-api/board-api 뿐 — `game-engine` 은 게이트웨이 JWT를 검증하지 않는다.

## 3. InfoContributor — 포맷 일치 및 노출 범위 확인됨

세 서비스 모두 문자 그대로 동일한 shape:

| 파일 | 코드 |
| --- | --- |
| `app/gateway-api/.../JwtTokenProvider.kt:194-202` | `mapOf("verifier" to "rsa-audience-v1", "publicKeySha256" to (publicKeyFingerprint ?: "unconfigured"))` |
| `app/game-api/.../GameApiJwtVerifier.kt:48-56` | 동일 |
| `app/board-api/.../BoardApiJwtVerifier.kt:27-35` | 동일 |

- 노출되는 값은 `GatewayJwtKeys.rsaPublicKeyFingerprint(publicKey)` 문자열 **하나뿐**이다. 개인키·공개키 원문·legacy HMAC secret은 어떤 경로로도 실리지 않는다 (`JwtTokenProvider.kt:41-58` 의 `privateKey`/`legacyKey`는 `private val` 이고 `contribute` 는 이들을 참조하지 않는다).
- 세 서비스 `application.yml` 모두 `management.endpoints.web.exposure.include: health,info` 이므로 실제로 노출된다 (dead code 아님).
- **지문이 실제 서명키와 묶여 있는가?** RS256 모드에서는 init 블록의 `GatewayJwtKeys.requireMatchingKeyPair(privateKey, publicKey)` (`JwtTokenProvider.kt:77`)가 키쌍 일치를 강제하므로, 보고되는 지문은 실제 서명 개인키와 대응하는 공개키의 지문이다. 3자 대조가 의미 있는 값을 비교한다 — 이 부분은 잘 설계됐다.

## 4. 부팅 실패 없음 주장 — 실제 실행으로 확인됨

`JwtTokenProviderTest.kt` 에 요구된 두 형상이 모두 실재하고 실제로 통과한다:

- `actuator info does not fail startup when no RSA key pair is configured` (`:129`) — `legacyProperties()` = `LEGACY_HS256` + RS256 키 없음 → 생성자 예외 없음, `publicKeySha256 == "unconfigured"`.
- `RS256 with retained legacy fallback (current production shape) boots and reports the fingerprint` (`:140`) — `rsaProperties().apply { includeLegacyFallback() }` = RS256 + legacy secret + 두 accept-until 유지 → 생성자 예외 없음, 지문 정확.
- `access tokens never carry display claims` (`:86-107`) 는 `claims.keys` **전량**을 `setOf("sub","iat","exp","token_type","role","iss","aud")` 와 등치 비교한다. 새 클레임이 하나라도 새면 즉시 깨진다 — 회귀 잠금으로 적절.

XML 실측 (source 읽기만이 아니라 실행):

```
$ ./gradlew --no-daemon --no-build-cache --rerun-tasks :common:test :app:gateway-api:test
BUILD SUCCESSFUL in 3m 54s
17 actionable tasks: 17 executed
```

`app/gateway-api/build/test-results/test/TEST-opensamguk.gateway.security.JwtTokenProviderTest.xml`
→ `tests="10" skipped="0" failures="0" errors="0"`, 위 3개 테스트 이름이 모두 XML에 존재.

## 5. Paired-require 함정 문서 — 동작 서술은 정확, 줄 번호는 stale

문서(`docs/operations/jwt-key-rollout.md`, "정리 함정")가 주장하는 all-or-nothing 동작을 코드로 교차 검증했다:

`JwtTokenProvider.kt:80-85`
```kotlin
require(legacyKey != null || (legacyAccessAcceptUntil == null && legacyRefreshAcceptUntil == null))
require(legacyKey == null || (legacyAccessAcceptUntil != null && legacyRefreshAcceptUntil != null))
```
→ secret만 지우면 첫 번째, accept-until 하나만 지우면 두 번째가 터진다. gateway-api 기준 "세 값 동시" 서술 정확.

`common/src/main/kotlin/opensamguk/common/auth/GatewayJwtSecurity.kt:111-117`
```kotlin
require((legacyKey == null) == (legacyAcceptUntil == null))
require(publicKey != null || legacyKey != null)
```
→ game-api/board-api 기준 "두 값 동시" 서술 정확. `require` 실패는 `IllegalArgumentException` → 빈 생성 실패 → 컨테이너 부팅 실패 → compose 재시작 루프. 서술이 "그럴듯한 소리"가 아니라 실제 동작이다.

**LOW-1 (문서 정확도):** `docs/operations/jwt-key-rollout.md:26` 은 이 검사를 `common/src/main/kotlin/opensamguk/common/auth/GatewayJwtSecurity.kt:103-109` 로 인용하는데, **같은 PR이** 파일 상단에 8줄짜리 `GatewayJwtClaims` object를 넣으면서 해당 블록이 **111-117** 로 밀렸다. 인용이 정확히 8줄 stale하다. → `:111-117` 로 고치거나, 줄 번호 대신 심볼명(`GatewayAccessTokenVerifier.init`)만 인용하면 앞으로 안 썩는다.

## 6. 프로덕션 런타임 UNKNOWN — 정직함, 다만 제목은 아직 과주장

`jwt-key-rollout.md` Stage 2 3번 항목의 현재 문구는 정직하다: "팀 보고 기준 … 알려져 있다", "`/actuator/info` 실측을 시도했으나 … **실측 미확인**이다", "코드/유닛테스트로 확인한 '이 형상이면 안전하다'는 사실뿐". 근거 없이 단정하지 않으며 확인 방법까지 남겼다. 이 부분은 **과주장 아님**.

**LOW-2 (과주장, 더 보수적으로 쓸 것):** 같은 문서의 섹션 제목은 여전히 단정형이다.

- `docs/operations/jwt-key-rollout.md:31` — `## Stage 3: 표시 클레임 발급 중단 (OPENSAM-220/#483, 완료 — 2026-08-23)`
- `CLAUDE.md:135` — `표시 클레임 … 은 발급하지 않는다(OPENSAM-220/#483, 완료)`

이 PR은 아직 머지되지 않았고 배포되지 않았다. 지금 문구는 (a) 코드 반영 완료와 (b) 프로덕션 반영 완료를 구분하지 않는다 — 본문 3번에서 애써 분리한 바로 그 구분이다. 그리고 `2026-08-23` 은 배포일이 아니라 **작성일**이다. 제안: `(코드 반영 완료 — 배포 실측 미확인)` 로 낮추고 날짜는 머지/배포 확인 뒤 채워라. 본문이 정직한 만큼 제목도 같은 기준을 지켜야 한다.

## 7. 테스트 커버리지 — 독립 재현 완료

작성자가 보고한 수치를 신뢰하지 않고 신규 clone에서 직접 돌렸다.

```
$ ./gradlew --no-daemon :common:test :app:gateway-api:test :app:game-api:test :app:board-api:test
BUILD SUCCESSFUL in 6m 24s   (EXIT=0)
```

XML 집계 (`build/test-results/test/TEST-*.xml` 전량 파싱):

| 모듈 | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- |
| common | 253 | 0 | 0 | 0 |
| app/gateway-api | 204 | 0 | 0 | 0 |
| app/game-api | 560 | 0 | 0 | 0 |
| app/board-api | 53 | 0 | 0 | 0 |
| **TOTAL** | **1070** | **0** | **0** | **0** |

PR 본문의 `common=253 gateway-api=204 game-api=560 board-api=53 → 1070/0/0/0` 과 **정확히 일치**한다. 첫 실행에서 `:common:test` 가 `FROM-CACHE` 로 잡혔기 때문에 `--no-build-cache --rerun-tasks` 로 `:common:test` + `:app:gateway-api:test` 를 강제 재실행해 캐시 없이도 동일 결과임을 재확인했다.

**LOW-3 (커버리지 공백):** 전량 클레임 잠금(`access tokens never carry display claims`)은 RS256 경로에만 있다. `LEGACY_HS256` 발급 경로(`iss`/`aud` 없는 claim set)에는 동등한 잠금이 없다. 지금은 제거가 무조건적이라 실질 위험이 낮지만, legacy 발급 경로가 살아 있는 동안은 한 줄 추가로 막을 수 있다.

**LOW-4 (테스트 의미 약화):** `NicknameChangePostgresIT` 에서 `tokenNickname` 비교 두 줄이 사라져, 이제 응답 본문 값만 검증한다. 토큰에 닉네임이 없어졌으니 삭제 자체는 불가피하다. 다만 이 IT의 원래 가치(동시 닉네임 변경 경합에서 "응답과 토큰이 어긋나지 않는다")가 사라졌으므로, 대체 불변식(예: 응답 닉네임 == 그 시점 DB 닉네임)을 넣어두면 테스트가 계속 값을 한다.

## 8. 그 밖에 본 것

**INFO (보안, 차단 아님):** `app/gateway-api/.../SecurityConfig.kt:45` 은 `.requestMatchers("/actuator/**").permitAll()` 이다. 즉 새 `jwt.publicKeySha256` 은 인증 없이 노출된다. 노출되는 값은 **공개**키의 SHA-256 지문이라 비밀 유출은 아니고, game-api/board-api가 이미 같은 값을 같은 방식으로 노출하고 있으므로 새로운 노출 등급이 생기지도 않는다. 운영 관점에서 "키 회전 시점을 외부에서 관측 가능"하다는 점만 인지하면 된다. (프로덕션 nginx가 실제로 `/actuator` 를 외부로 여는지는 이 저장소의 nginx 설정으로 판단할 수 없다 — 저장소 ≠ 프로덕션.)

**deploy.yml 게이트 단순화 — 안전함.** `.github/workflows/deploy.yml:369-374` 에서 `include_profile_claims` 분기가 빠지고 `[[ "$jwt_signing_mode" == "RS256" ]]` 만 남았다. 기본값이 `RS256` 이고 제거된 조건은 `|| include==false` 였으므로 게이트가 **좁아지지 않는다**(RS256이면 여전히 `JWT_PUBLIC_KEY` 를 강제).

**프로덕션 `.env` 잔존 변수 — 부팅 안전.** 프로덕션 `.env` 에 `JWT_INCLUDE_PROFILE_CLAIMS=true` 가 남아 있어도, compose가 더 이상 컨테이너로 전달하지 않고 `GatewayJwtProperties` 에 해당 바인딩이 없으며 `ignoreUnknownFields=false` 도 아니다 → 미사용 env로 무시된다. 부팅 실패 경로 없음.

**로직/제거 누락 없음.** `AuthService.toGatewayProfile()` 의 grade 폴백(`grade ?: if (role=="ADMIN") 6 else 1`)이 사라졌지만, 동일 폴백이 `MemberProfile.kt:20` (`+ coerceIn(0,9)`)에 이미 존재하고 gateway의 `UserResponse` 에는 grade 필드가 없다 → 잃어버린 동작 없음.

**`check.py --strict` 의 `parity-evidence` 지적에 대하여:** `common/src` 변경이 같은 diff 안의 테스트와 매핑되지 않는다는 지적은 이 건에 한해 휴리스틱 false-positive로 보인다. `common/src/test/kotlin/opensamguk/common/auth/GatewayAccessTokenVerifierTest.kt` 가 남은 4개 상수를 전부 사용하고 `verifyAccessToken` 이 `GatewayPrincipal(userId, role)` 만 만들어냄을 이미 고정하고 있으며, 위 실측에서 253/0/0/0 으로 통과한다. 다만 이는 리뷰어 의견이지 게이트 면제 결정이 아니다 — 최종 판단은 팀 리드 몫으로 남긴다.

## 잘한 점

- 플래그가 아니라 코드에서 제거했다. "다음 사람이 다시 켠다"는 판단은 옳고, 문서(`jwt-key-rollout.md:33`)에 그 이유까지 남겼다.
- 전량 claim-set 등치 비교(`claims.keys == setOf(...)`)는 부분 검증보다 훨씬 강한 회귀 잠금이다.
- 확인 불가능한 사실을 확인된 것처럼 쓰지 않고, 시도한 경로와 막힌 이유까지 적어 별도 커밋(`cc65ad9a`)으로 문구를 낮췄다. 리뷰어 입장에서 가장 신뢰가 가는 대목이다.
- InfoContributor를 기존 두 소비자와 문자 그대로 동일한 shape으로 맞춰, 3자 대조가 파싱 분기 없이 가능하다.
- 부팅 실패 함정을 "고쳤다"가 아니라 "문서화했다" — 이 검사들은 의도된 안전장치이므로 완화가 아니라 운영 절차로 다루는 것이 맞다.

## 요약 판정

| 항목 | 결과 |
| --- | --- |
| CRITICAL | 0 |
| HIGH | 0 |
| MEDIUM | 0 |
| LOW | 4 (LOW-1 stale 줄 인용, LOW-2 "완료" 과주장, LOW-3 LEGACY 경로 claim 잠금 부재, LOW-4 IT 불변식 소실) |
| INFO | 1 (`/actuator/**` permitAll — 기존 패턴과 동일, 신규 노출 아님) |

**verdict: cleared.** LOW-1/LOW-2는 문서 한 줄씩이라 머지 전에 같이 고치면 좋지만, 코드 정확성·보안·테스트 어느 축에서도 차단 사유가 아니다.

---

## 부록 A — 후속 커밋 재검토 (2026-08-24, `8a06f555` 이후)

원 리뷰 이후 브랜치에 붙은 커밋들을 같은 clone에서 다시 확인했다. Scope에 `tools/` 를 추가한 근거가 A-1이다.

### A-1. `7f42dde7` — `tools/ops/jwt_rollout_contract_test.py` (**검토 완료, 문제 없음**)

`origin/main` 대비 이 파일의 **전체** 변경은 정확히 assert 2줄 교체 + 설명 주석 3줄이다:

```diff
-assert 'include_profile_claims="${include_profile_claims:-true}"' in workflow
-assert '[[ "$jwt_signing_mode" == "RS256" || "$include_profile_claims" == "false" ]]' in workflow
+assert 'include_profile_claims' not in workflow
+assert '[[ "$jwt_signing_mode" == "RS256" ]]' in workflow
```

- **실제 `deploy.yml` 과 일치하는가 — 예.** `deploy.yml:374` 가 `if [[ "$jwt_signing_mode" == "RS256" ]]; then` 이고, `grep -n "include_profile_claims" .github/workflows/deploy.yml` → 0건. (369-370행 한글 주석에는 대문자 env 이름 `JWT_INCLUDE_PROFILE_CLAIMS` 만 남아 있어 소문자 셸 변수명 assert와 충돌하지 않는다.)
- **다른 assert가 느슨해졌는가 — 아니오.** 파일 전체 diff가 위 5줄뿐이다. 순서 검사(`board_restart < capability_gate < gateway_restart`), `jwt_signing_mode="${jwt_signing_mode:-RS256}"`, `expected_jwt_public_key_sha256=`, `publicKeySha256`, compose의 `JWT_PRIVATE_KEY` 경계 검사, web 계층 비밀 미노출 검사, 두 verifier의 `"publicKeySha256"` 검사 — 전부 그대로다. 제거된 두 줄은 은퇴한 플래그를 참조하던 **유일한** assert 두 개였다.
- **새 assert가 tautology인가 — 아니오. 뮤테이션 테스트로 확인했다.** `deploy.yml` 의 게이트 조건을 옛 2변수 형태로 되돌린 사본으로 돌리자 테스트가 `AssertionError`, exit 1 로 죽는다. 원본 복원 후 재실행은 `PASS ... exit 0`. 즉 두 assert 모두 실제로 문다.

```
$ python3 tools/ops/jwt_rollout_contract_test.py
PASS: JWT consumer-first rollout, key identity, and asymmetric boundary are gated   (exit 0)
$ # deploy.yml 게이트를 옛 형태로 되돌린 사본에서
mutated-run exit: 1   AssertionError
```

`assert 'include_profile_claims' not in workflow` 는 되레 계약을 **강화**한다 — 플래그 분기가 다시 들어오는 것을 막는 negative guard다. 은퇴한 플래그를 떼어낸 것 외에 계약이 약해진 지점은 없다.

### A-2. `a308dbfe` — 원 리뷰의 LOW-1/LOW-2 반영 (**확인**)

- LOW-1: `jwt-key-rollout.md` 의 인용이 `GatewayJwtSecurity.kt:103-109` → `:111-117` 로 수정됨. 현재 파일의 실제 위치와 일치.
- LOW-2: Stage 3 제목이 `(… 완료 — 2026-08-23)` → `(… 코드 반영 완료 — 배포 실측 미확인)` 로, `CLAUDE.md` 도 같은 취지로 완화됨.

### A-3. `fd6332fa` — 검증측/서명측 분리 (**요청보다 더 보수적, 좋음**)

Stage 2 3번 항목이 "검증측(board-api/game-api)은 RS256 실측 확인, **서명측(gateway-api)의 실제 `JWT_SIGNING_MODE` 는 미관측**" 으로 쪼개졌다. 내가 요구한 것보다 한 단계 더 정확한 구분이다. 다만 여기 새로 들어온 "2026-08-23 `docker exec ... curl .../actuator/info` 3회" 라는 **실측 주장 자체는 이 리뷰어가 재현할 수 없다**(프로덕션 접근 경로 없음) — 문구가 관측 범위와 미기록 항목(지문 값)을 명시하고 있어 과주장은 아니지만, 이 한 줄은 리뷰어 검증이 아니라 작성자 보고로 남는다.

### A-4. `542cf6f8` — 작성자가 이 리뷰 문서에 `Scope:`/`Verdict:` 앵커 줄을 추가함 (**내용 왜곡 없음**)

`check.py` 의 `cross-agent-critique` 게이트가 요구하는 형식 줄이며, 추가된 `Verdict: cleared` 는 이 리뷰어의 실제 판정과 일치하고 Scope 목록도 실제 검토 범위와 일치했다. 이번 커밋에서 `tools/` 를 추가한 것은 **A-1을 실제로 검토했기 때문**이지 정규식을 통과시키기 위해서가 아니다.

**부록 판정: cleared 유지.** 새 LOW/HIGH 없음.
