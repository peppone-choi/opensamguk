---
paths:
  - "AGENTS.md"
  - "CLAUDE.md"
  - "**/AGENTS.md"
  - "**/CLAUDE.md"
  - ".claude/rules/**"
  - "docs/development/agent-reference.md"
  - "docs/development/product-reference.md"
  - "tools/pr-loop/docs/pr-review-loop.md"
  - "tools/instructions/**"
---

# 지침 파일 관리

`AGENTS.md`의 「지침 파일」 절을 펼친 규칙이다. Codex는 이 파일을 자동으로 읽지 않으므로 지침 파일을 바꿀 때 직접 읽는다. 이 규칙은 명령 실행 승인을 대신하지 않는다.

## 대상

- 루트와 하위의 `AGENTS.md`·`AGENTS.override.md`·`CLAUDE.md`(`.claude/` 안의 `CLAUDE.md` 포함), `.claude/rules/**/*.md`
- 위 파일의 `@path` import, 루트·하위 `AGENTS.md`·`CLAUDE.md`가 직접 링크한 저장소 안 `.md` 문서(지금은 `docs/development/agent-reference.md`·`docs/development/product-reference.md`·`tools/pr-loop/docs/pr-review-loop.md`)

## 도구별 로딩 (확인한 공식 동작)

- Claude Code: `CLAUDE.md`가 있으면 `AGENTS.md`를 따로 읽지 않으므로 `CLAUDE.md` 첫 줄의 `@AGENTS.md` import를 유지한다. `.claude/rules/**/*.md`는 `paths`가 없으면 세션 시작 때, 있으면 맞는 파일을 열 때 읽는다. 일반 마크다운 링크는 자동으로 읽지 않는다.
- Codex: 저장소 루트에서 작업 디렉터리까지 디렉터리마다 `AGENTS.override.md` 또는 `AGENTS.md` 하나를 이어 붙인다(기본 합계 32KiB). `.claude/rules/`나 링크 문서는 자동으로 읽지 않는다. Codex의 `rules/*.rules`는 명령 승인 정책이며 지침 문서가 아니다.
- 그래서 두 도구가 모두 따라야 하는 규칙은 `AGENTS.md`에 두거나, `AGENTS.md`·작업 참고 표에서 해당 파일을 직접 읽으라고 연결한다.

## 작업 시작·완료 확인

1. `python3 tools/instructions/check_instructions.py`를 돌린다. 표준 라이브러리만 쓰며 몇 초 안에 끝난다.
2. 확인 항목: 지침 파일마다 200줄 이하, 링크·`@path` import 대상 존재, 각 규칙 파일 `paths`가 실제 파일과 하나 이상 맞음, `tools/instructions/protected-rules.tsv`의 보호 문구가 지정 파일에 그대로 있음, `AGENTS.md` 묶음 32KiB 이하.
3. `FAIL`이면 작업 전에 지침을 고치거나 총괄에게 알린다. `WARN`(백틱 경로 부재)은 내용을 확인해 결과에 적는다.
4. 결과 보고에 검사 결과 줄과 지침 파일 줄 수를 남긴다.

## 옮기기·줄이기

- 규칙을 빼거나 약화하지 않는다. 옮길 때는 원문을 그대로 옮기고, 합칠 때는 두 쪽의 세부 조건을 모두 남긴다.
- 루트는 모든 작업에 필요한 핵심과 참조만 둔다. 한 경로에만 필요한 규칙은 `paths`가 있는 `.claude/rules/` 파일로 옮기고, `docs/development/agent-reference.md` 표에 Codex용 링크를 단다.
- 옮긴 절의 제목은 기존 링크·anchor가 깨지지 않게 남긴다.
- 보호 문구를 옮기면 `protected-rules.tsv`의 파일 열을 같은 PR에서 고친다. 보호 문구 삭제는 사용자 결정이 있을 때만 한다.
- PR 본문에 이동 전후 대응표(원래 위치 → 새 위치), 검사 출력, 줄 수 목록을 붙인다.
- CI·브랜치 보호에 이 검사를 넣는 일은 별도 제안과 승인으로 한다.
