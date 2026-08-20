# OPENSAM-208 — board-api 분리 리뷰

Scope: .github/workflows/, app/, infra/, tools/, web/

Verdict: quarantined-with-proof
Proof: board 코드·테스트·로컬/호환 배선은 검증했고 외부 공유 compose 승인 전까지 자동 활성화만 닫았다.

구현과 분리된 독립 리뷰어가 전체 diff, 인증 경계, 마이그레이션, 라우팅, 배포 표면을 공격적으로 확인했다. 최초 판정은 **fix-required**였다. 레포 안에서 발견된 사항은 수정했고, 별도 운영 레포의 승인이 필요한 프로덕션 활성화는 차단 상태로 남겼다.

## 리뷰 지적 반영

| 심각도 | 지적 | 조치 |
| --- | --- | --- |
| BLOCKER | 자동 배포는 별도 `opensamguk-docker/docker-compose.shared.yml`을 사용하는데 현재 `board-api`와 `BOARD_API_URL`이 없다. board를 빼고 신규 gateway/web만 활성화해도 기존 board endpoint가 사라져 장애가 난다. | workflow는 board 이미지까지 build/push한 뒤, 외부 compose의 `board-api` service와 `BOARD_API_URL`을 선행 검증한다. 둘 중 하나라도 없으면 `IMAGE_TAG` 변경·gateway/web 재생성 전에 중단해 기존 운영 스택을 보존한다. |
| MAJOR | 호환 배포 스크립트가 board-api를 기동·검증하지 않았다. | `scripts/deploy.sh`의 기동 목록과 내부 `:8083/actuator/health` 검증에 추가했다. |
| MINOR | `tools/smoke.sh`와 모듈/라우팅 문서가 새 서비스를 모른다. | smoke에 board health wait를 추가하고 `README.md`, `AGENTS.md`, `CLAUDE.md`의 모듈·포트·라우팅·JWT 설명을 갱신했다. |
| MINOR | JWT 역할 claim과 현재 DB 역할이 다를 때 권한 원천을 잠그는 회귀가 없다. | ADMIN claim/현재 DB USER의 pin 요청이 403임을 추가했다. 권한은 토큰 claim이 아니라 요청 시점 users 행에서 온다. |

## 확인 증거

- `:app:board-api:test --rerun-tasks`: 52 tests, failures/errors/skips 0. PostgreSQL Testcontainers 마이그레이션 IT 포함.
- `:app:gateway-api:test --rerun-tasks`: 160 tests, failures/errors 0. gateway 게시판 코드·테스트·보안 matcher 제거 후 결과.
- Next board proxy: 8 tests 통과, `pnpm typecheck` 통과.
- 새 gateway/board boot jar + 빈 PostgreSQL/Redis: gateway 중지 중 board health/read 200, gateway 재시작 후 gateway/board health·기존 post read 200, 로그인 200, comment write 201.
- 로컬·production compose config, nginx 문법, `git diff --check` 통과.

## 잔여

- 프로덕션 자동 활성화는 `opensamguk-docker` 별도 변경·승인 전까지 fail-closed로 차단된다. 이미지 build/push는 완료하지만 운영 `IMAGE_TAG`와 기존 gateway/web 컨테이너는 바꾸지 않는다.
- 호환용 `docker-compose.production.yml` + `scripts/deploy.sh`는 board를 기동·검증하지만, 실제 배포는 승인 범위 밖이라 수행하지 않았다.
