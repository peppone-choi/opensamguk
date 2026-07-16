# Collaboration Protocol — 다중 에이전트 협업 규약

Claude Code, Codex, 기타 에이전트가 같은 저장소에서 일할 때의 계약. 등록부는 `.ai/ownership.md`.

## 핵심 규칙

1. **single-writer-per-file.** 한 파일은 한 시점에 한 에이전트만 수정. 타 에이전트 소유 파일은 read-only.
2. **작업 전 ownership 등록, 종료 시 해제.** `.ai/ownership.md`에 행 추가/갱신.
3. **브랜치/worktree 격리.** 병렬 구현은 각자 git worktree(코드 격리). 같은 파일을 co-widen하는 작업은 병렬 금지 — foundation-first, creator-then-consumer(정본: `CLAUDE.md` §How phases are built).
4. **결과 전달은 `.ai/handoff.md`**(+ 장기 이력은 `docs/superpowers/SESSION_HANDOFF.md`). 다음 에이전트가 대화 기록 없이 재개 가능해야 한다.
5. **추측의 사실 승격 금지.** 한 에이전트의 추론/미검증 주장을 다른 에이전트가 근거 없이 사실로 인용하지 않는다. cross-agent critique는 구현자의 결론을 재사용하지 말고 PHP 증거·테스트를 직접 확인한다(`WORKING_SYSTEM.md` §Cross-agent critique).
6. **타 에이전트 변경 덮어쓰기 금지.** 충돌 발견 시 덮어쓰지 말고 ownership 충돌로 보고.
7. **리뷰는 별도 레인.** 작성자와 승인자는 같은 컨텍스트가 아니어야 한다. `fix-required`가 남으면 merge/ship 금지.

## 병렬화 적합 / 부적합

| 적합 | 부적합 |
|---|---|
| 코드 조사, 독립 테스트, 문서 검토, 서로 다른 모듈 리뷰, 로그 분석, disjoint 파일의 독립 명령 포팅 | 동일 파일 수정, 순서 의존 구현, 공유 확장점(`CommandWireMapper.kt`·`TurnDaemonCommand.kt`·`ChangeRecorder` 채널·flush step) 동시 확장, 같은 마이그레이션 동시 수정, 요구사항 미확정 기능 |

## stale ownership 해제

- `Updated at` 기준 오래됨 + 해당 worktree/branch에 활동 없음(커밋·파일 mtime·출력 파일 동결)이면 죽은 에이전트로 판정 후보. **사람 확인 후** 행 해제 + 워크트리 잔존물 회수(선례: `docs/superpowers/SESSION_HANDOFF.md` 2026-06-10 §4).

## 제공자별 표면

- Claude Code: `.claude/agents/*`, `.claude/skills/*`, `.claude/commands/*`(진입점 — 절차 정본은 이 디렉터리와 `docs/superpowers/`).
- Codex: `.codex/agents/*.toml`, `AGENTS.md`. **동일한 Runbook·`scripts/agent/` 스크립트를 사용한다** — Claude 전용 표면에만 정책을 두지 않는다.
