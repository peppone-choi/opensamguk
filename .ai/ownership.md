# Work Ownership

병렬 에이전트(Claude Code, Codex, Gemini 등)의 파일 소유권 등록부. 규칙은 `docs/agent/collaboration-protocol.md`가 정본.

| Agent | Task | Branch/worktree | Owned files | Status | Updated at |
|---|---|---|---|---|---|
| (없음) | — | — | — | — | — |

## 사용 규칙 (요약)

1. **single-writer-per-file**: 한 파일은 한 시점에 한 에이전트만 수정한다. 다른 에이전트 소유 파일은 읽기만.
2. 작업 시작 전 이 표에 행을 추가하고, 종료/중단 시 Status를 갱신·해제한다.
3. 공유 확장점(`CommandWireMapper.kt`, `common/wire/TurnDaemonCommand.kt`, `ChangeRecorder` 채널, `JdbcFlushExecutor`)은 **병렬 소유 금지** — foundation-first, creator-then-consumer 순차.
4. 병렬화는 독립 조사·테스트·리뷰·문서화·disjoint 파일 구현에만.
5. stale 판정: `Updated at`이 오래됐고 해당 branch/worktree에 활동이 없으면(커밋/파일 mtime), 사람 확인 후 행을 해제한다. 죽은 에이전트 워크트리 회수 절차는 `docs/superpowers/SESSION_HANDOFF.md` 이력 참조.
