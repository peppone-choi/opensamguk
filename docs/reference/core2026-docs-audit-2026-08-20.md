# devsam/core2026 문서 감사

> 상태: 외부 참고 원천 감사  
> 마지막 검토: 2026-08-20  
> 원본 commit: `2a73f80b` (devsam/core2026 origin/main 현재 HEAD)

## 조사 범위

사용자가 지정한 [Gitea docs](https://storage.hided.net/gitea/devsam/core2026/src/branch/main/docs)를
공개 Git으로 확인했습니다. 원본에는 Markdown 34개와 VitePress 설정이 있으며 사용자, 관리자, 운영, 아키텍처,
개발자 문서로 분리돼 있습니다.

로컬 `legacy/devsam-core2026` 워킹트리는 이 origin/main보다 1234 커밋 뒤진 stale 상태입니다 — 이 감사와
아래 갭 목록은 로컬 워킹트리가 아니라 origin/main 기준입니다. `docs/architecture/legacy-engine-*.md`는
origin/main에서 이미 삭제된 구판이므로 인용하지 않습니다.

## 채택한 패턴

- 독자별 문서 포털과 역할별 시작점
- “예약 가능”과 “실행 성공”을 구분하는 사용자 설명
- 시간·턴·월 경계를 독립 문서로 설명하는 방식
- 관리자 메뉴별 책임과 권한 표
- 배포, 초기화, 게임 취소의 데이터 경계를 분리하는 방식
- 배포 전후 체크리스트와 실패/미검증을 pass에서 분리하는 규칙
- command registry에서 사용자 카탈로그를 생성하는 방향(후속 기획, 이번 개편에서는 미구현)

## 그대로 가져오지 않은 내용

원본은 TypeScript/Vue/Prisma/PM2/Caddy 구조입니다. 현재 저장소는
Kotlin/Spring Boot/JDBC/Next.js/Docker/nginx이므로 다음은 복사하지 않았습니다.

- `GatewayOrchestrator`, worktree/PM2 profile 배포 절차
- tRPC/Prisma 경로와 Node 빌드 명령
- core2026의 capability 이름과 `/gateway/admin/servers/:profileName/*` route
- core2026의 장수/국가 예턴 수와 command 수를 현재 값처럼 단정하는 문장
- Kakao 기반 특수 계정 복구 절차

## 원본에서 발견한 갭

- 초보자 튜토리얼, FAQ, 용어집과 승리/시즌 종료 설명이 부족합니다.
- command catalog는 비용·정확한 실패 효과·로그 예시까지 설명하지 않습니다.
- 최초 설치, 정기 백업, restore rehearsal, 모니터링·장애 runbook이 독립 문서가 아닙니다.
- `game-api-direct-mutation-journal-inventory.md`는 서두의 86개와 표의 87개가 충돌합니다.
- 저장소 밖 `report/`와 `ref-core2026-mapping.md`를 참조해 단독 문서 세트로는 증거가 완결되지 않습니다.
- 아키텍처 경계 문서 + 자동검사 쌍이 없습니다 — core2026의 `docs/architecture/package-boundaries.md`는
  패키지 경계 규칙을 자동검사(lint/CI)와 짝지어 문서화하는데, opensamguk에는 대응하는 단일 문서가 없고
  아키텍처 테스트 존재만 여러 문서에 흩어져 언급됩니다.
- ADR-LITE-042 이후 잔존 PHP 호환 흔적을 원장 형식으로 추적하는 문서가 없습니다 — core2026의
  `docs/ref-compatibility-shims.md`처럼 호환 shim을 제거 대상 목록으로 관리하는 문서가 필요합니다.
- 게임 시각(턴/월) vs 운영 벽시계 경계를 설명하는 독립 문서가 없습니다 — core2026의
  `docs/architecture/game-clock.md`는 이 경계를 전용 문서로 분리하는데, opensamguk은 관련 규칙이
  `docs/agent/architecture.md` 등에 산재해 있을 뿐 대응 문서가 없습니다.

## opensamguk 반영 결과

| 원본 범주 | opensamguk 문서 |
|---|---|
| 플레이어 가이드 | `docs/user/` |
| 관리자 콘솔·릴리스 운영 | `docs/admin/` |
| 문서 포털 | `docs/README.md` |
| 제품 정본·로드맵 지도 | `docs/design/` |
| 검증 정책 | 기존 `docs/agent/verification.md`, `scripts/agent/verify-changes.sh` 유지 |

## 추적 가능한 외부 상태

- GitHub 미러는 확인했습니다.
- 연결된 Atlassian 사이트는 `dingco-4-team.atlassian.net`뿐이었고 실제 OPENSAM 사이트
  `pepponechoi-jira.atlassian.net`은 커넥터 권한 밖이었습니다.
- 따라서 이 감사와 새 로드맵에서 Jira live 상태는 `UNKNOWN`이며 GitHub issue URL을 교차 참조로 사용합니다.
