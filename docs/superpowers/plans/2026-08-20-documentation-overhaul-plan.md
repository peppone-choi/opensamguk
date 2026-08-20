# 문서 전면 개편 계획

> 상태: 실행 중  
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

- 내부 Markdown 링크 존재 검사
- 구 정본 문구와 현행/계획 혼동 검사
- `git diff --check`
- `scripts/agent/verify-changes.sh --run`
- 작성자와 분리된 독립 문서 리뷰

