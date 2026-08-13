# Board/editor acceptance audit — OPENSAM-80/81/82/87

Date: 2026-08-13
Branch: `codex/board-editor-acceptance`
Scope: `app/gateway-api/`, `web/gateway/`, and acceptance evidence under `docs/`; `web/game/` source is read-only.
Verdict: quarantined-with-proof
Proof: the implementation, fixture-browser evidence, and fresh serialized-JVM gate are complete; ticket closure remains held only at the authenticated-browser boundaries described below.

## Acceptance verdict

| Ticket | Verdict | Evidence / exact remaining gap |
| --- | --- | --- |
| OPENSAM-80 (#222) | `HOLD` | The real Next fixture flow now proves fixture login, Korean Tiptap post creation, formatted detail rendering, comment append, and a 390 px no-overflow frame. Full AC still requires one authenticated combined-stack run through the real httpOnly-cookie proxy and ADMIN pin; fixtures are not credentials. |
| OPENSAM-81 (#223) | `HOLD` | Real Next fixture UI proves ADMIN pin and confirmed delete with zero console errors. Fresh gateway-api XML proves non-admin pin 403 and non-owner delete 403, but the issue's authenticated browser permission matrix is still absent. Fixture UI plus MockMvc backend evidence is not one authenticated end-to-end run. |
| OPENSAM-82 (#224) | `PASS` | The ADR, spike, dependency-ordered follow-up plan, loop ledger, and independent remediation review are merged and satisfy the explicitly documentation-only AC. No browser or production storage claim is made. |
| OPENSAM-87 (#229) | `HOLD` | Real Next fixture browser proves mailbox/diplomacy Tiptap writers and SafeHtml rendering; focused suite is 18/18. Authenticated command persistence/result evidence remains absent, so fixture evidence is not promoted to daemon acceptance. |
| OPENSAM-78 parent (#220) | `HOLD` | OP80 and OP81 remain held even though OP79 is closed and OP82 passes. |
| OPENSAM-83 parent (#225) | `HOLD` | OP87 remains held and OP88 (#230) is an open child outside this lane. |

## Real Next fixture evidence

- Gateway board: `gateway-matrix.json`, desktop/mobile detail captures. Assertions:
  login redirect to `/board/write`, formatted Korean post round-trip, comment append,
  `scrollWidth == clientWidth == 390`, zero console errors.
- Gateway admin: `gateway-admin-matrix.json`, desktop capture. Assertions: pin status
  transition and confirmed delete removal, zero console errors.
- Game editor: `game-editor-matrix.json`, mailbox desktop plus diplomacy desktop/mobile
  captures. Assertions: real Tiptap mounts, plain diplomacy summary, SafeHtml retains
  strong/emphasis and removes hostile image markup, 390 px no overflow.
- Game fixture SSE requests intentionally received JSON and produced MIME warnings in
  the captured JSON. These are fixture-harness noise, not a claim of zero game console
  errors; the functional assertions and screenshots remain valid.

## Automated evidence

- Gateway RED: `/tmp/opensam-board-acceptance-red.json` failed because no `굵게`
  control existed and the writer was a textarea.
- Gateway GREEN: full `web/gateway` Vitest: 40 suites, 146 tests, 0 failures;
  `pnpm typecheck` and `pnpm build` exit 0. Focused writer regression is 3/3,
  including semantic-empty HTML and the backend-compatible UTF-16 10,000 limit.
- OP87 focused: 9 suites, 18 tests, 0 failures; `web/game pnpm typecheck` exit 0.
- `git diff --check`: clean.
- Gateway JVM RED: a fresh 188-test run completed with 8 failures because Jackson
  rejected omitted non-null `contentFormat` before the Kotlin constructor default
  could preserve legacy clients. The wire field is now nullable and the service
  resolves omitted/explicit null to `PLAIN_TEXT`.
- Gateway JVM GREEN after repair: `BUILD SUCCESSFUL in 10m 16s`; fresh XML contains
  32 suites / 188 tests / 0 failures / 0 errors / 0 skips. Board mutation is 10/10
  and board update is 7/7. `./gradlew --stop` reported no daemon and the scoped
  Gradle/Kotlin process scan was clear.

## Independent review

An independent read-only reviewer initially returned `CLEARED`, but the fresh JVM
RED withdrew that runtime conclusion. It statically cleared the narrow nullable
wire-field/service-fallback repair and independently reran the focused writer suite
(3/3), gateway typecheck, and `git diff --check`. After the post-fix JVM gate turned
green, the terminal reviewer cleared the implementation/diff with no remaining code
finding. The no-attribute Jsoup
safelist, post-clean semantic-blank rejection, matching frontend/backend UTF-16
length contract, and ticket HOLD/PASS classifications remain independently reviewed.

## Tooling baselines

- The advertised project `webapp-testing` skill body was absent in this isolated
  worktree. The installed Playwright QA workflow and repository `os-e2e` rules were
  used; this is documented rather than treated as product evidence.
- Fablize repeatedly emitted generic tool-failure notifications even when direct
  commands returned usable outputs. Direct exit codes, JSON reports, screenshots,
  and test counts are the acceptance evidence.
- CodeGraph was unavailable because this worktree has no `.codegraph/` index; normal
  repository search was used per AGENTS.md.
