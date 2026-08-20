# OPENSAM-220 — 액세스 토큰에서 표시 정보를 걷어낸다 (소비자 측)

Scope: app/game-api/ common/ — 게임 서버가 액세스 토큰 대신 `users` 행에서 표시 정보를 읽는다
Verdict: cleared

작성 레인과 분리된 컨텍스트에서 독립 리뷰어(opus)가 공격했다. 최초 판정은 **fix-required** 였고,
그 HIGH 지적이 이 PR 의 범위를 절반으로 자르게 만들었다. 아래에 지적과 조치를 모두 남긴다.

---

## 왜 하는가

사용자 지적에서 출발했다 — *"왜 jwt에 닉네임을? 그냥 아이디만 담으면 될텐데?"*

맞는 지적이고, 닉네임 하나에 그치지 않는다. 토큰이 싣던 7개 필드 중
`username`·`nickname`·`grade`·`picture`·`imgsvr` 다섯은 전부 **바뀌는 값**이다.
토큰은 발급 시점의 값을 박제하므로, 닉네임을 바꾼 사용자는 토큰이 만료될 때까지
게임 서버에서 옛 이름으로 보인다. 신원(`sub`)과 권한(`role`)만 토큰의 일이다.

## 무엇을 했는가

- `GameApiJwtVerifier` 가 `GatewayPrincipal(userId, role)` 만 돌려준다 — 표시 클레임을 **읽지 않는다**.
  구 토큰(표시 클레임이 실린)도 그대로 통과한다(여분 클레임은 무시).
- 표시 정보가 필요한 세 컨트롤러(`JoinController`·`SelectPoolController`·`PossessionController`)가
  `UserRepository` 에서 읽는다. 폴백 계약은 `member/MemberProfile.kt` 한 곳에 모았다.
- 계정 행이 없으면 셋 다 **401** 로 끊는다.

## 리뷰 지적 반영표

| 심각도 | 지적 | 조치 |
| --- | --- | --- |
| HIGH | 롤아웃 순서가 CI 배포와 충돌 — `deploy.yml:305` 이 `gateway-api` 만 재생성하고 `:324` 가 게임서버 `IMAGE_TAG` 를 고정한다. 게이트웨이만 신버전이 되면 구 game-api 의 `username ?: return null` 이 신 토큰을 **전부 거절**해 로그인한 모든 유저가 401 | **PR 을 둘로 쪼갰다.** 이 PR 은 소비자(game-api) 측만 바꾼다 — 게이트웨이는 표시 클레임을 **계속 싣는다**. 구·신 어느 조합에서도 깨지지 않는다. 발급을 끊는 것은 게임 서버를 모두 승격한 뒤의 후속 PR 이다 |
| MEDIUM | `PossessionController.userNick` 폴백이 계정 없는 유저의 `"7"` 을 `owner_name` 과 월드 로그에 **되돌릴 수 없게** 박는다 | 폴백 삭제. 행이 없으면 401 이고 커맨드는 발행되지 않는다. 나머지 두 컨트롤러와 규칙을 통일했다 |
| MEDIUM | 그 폴백을 유일한 테스트가 정답으로 굳혀놨다(`assertEquals("7", claim.userNick)`) | 정상 경로를 스텁해 `assertEquals("owner7", ...)` 로 바꾸고, 행이 없을 때 401 + `publishImmediate` 미호출을 거는 테스트를 추가 |
| MEDIUM | `MemberProfile` 폴백 4분기 중 1개만 스침 | `MemberProfileTest` 신설 — ADMIN/null-grade, 명시 grade, 범위 밖 grade, 공백 닉네임, imgsvr 양쪽 |
| MEDIUM | 구 토큰 검증의 `grade in 0..9` 범위 강제가 대체 없이 사라졌다 (DB 컬럼엔 제약 없음) | `toMemberProfile()` 에서 `coerceIn(0, 9)`. 검증 지점이 토큰에서 DB 읽기로 옮겨간 것뿐, 사라지지 않는다 |
| LOW | `getRoleFromToken` 데드코드, `loadUserById` 미스가 500, `AuthServiceTest` grade 단언 소실 | **전부 게이트웨이 측 지적 — 이 PR 범위 밖**이다. 후속 PR 에서 처리한다 |

## 무발견으로 확인된 것

- 인가 우회·권한 상승 없음. `role` 은 여전히 토큰이 싣고 필터가 읽는 값이며 판정 로직 불변.
- N+1 없음 — 컨트롤러당 `findById` 1회.
- one-daemon-write-rule 무관(읽기 경로만), 골든·RNG·로그 무관.

## 잔여 (후속 PR)

게이트웨이가 표시 클레임 발급을 끊는 일. **선행 조건은 모든 게임 서버가 이 커밋 이후 이미지로
승격되는 것**이다. 그 전에 머지하면 위 HIGH 가 그대로 재현된다.
