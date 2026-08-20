# 문서 전면 개편 계획

> 상태: 완료
> 사용자 요청: Gitea core2026 문서, GitHub 이슈, Jira 티켓을 근거로 Markdown 문서를 전면 개선하고 사용자·관리자 매뉴얼을 만든다.

## 범위

1. 외부 Gitea 문서 구조와 설명 패턴을 감사한다.
2. 현재 코드 route/API, 승인 ADR, GitHub/Jira 추적 상태와 대조한다.
3. `docs/user`, `docs/admin`, `docs/design`, `docs/reference`의 안정 계층을 만든다.
4. README·AGENTS·프로젝트 개요의 ADR-LITE-042 이전 패러티 정본 문구를 정리한다.
5. 링크, 문서 정합성, Agent OS 검증과 독립 리뷰를 수행한다.

## 비범위

- 제품 코드, 스키마, 골든, 레거시 수정
- GitHub/Jira 티켓 생성·편집·상태 전이
- commit, push, PR, merge, deploy
- 실제 서버 리셋·삭제·승격·운영 데이터 접근

## 검증

- 내부 Markdown 링크: 변경 Markdown 17개, 상대 링크 39개, 누락 0
- GitHub: 인용 이슈 20개의 번호·제목·상태 확인
- Jira: 실제 OPENSAM 사이트가 커넥터 권한 밖이므로 live 상태 `UNKNOWN`
- `git diff --check`: clean
- untracked Markdown whitespace: 0
- `tools/agent-system/check.py --strict --base origin/main --format json`: `ok: true`, findings 0
- `scripts/agent/verify-changes.sh --run`: 최초 1회 실행에서 `docs/admin/README.md`의 trailing whitespace와
  EOF 빈 줄을 탐지해 실패; 두 항목 수정 후 해당 whitespace 검사와 strict check를 통과
- 독립 리뷰: `docs/superpowers/reviews/2026-08-20-documentation-overhaul-review.md`, `Verdict: cleared`

문서 전용 변경이므로 백엔드 Gradle, 프론트 typecheck/test, Docker, 브라우저 검증은 실행하지 않았다.
