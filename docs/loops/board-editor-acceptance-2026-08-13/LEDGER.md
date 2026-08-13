# Board and editor acceptance ledger

| Round | Hypothesis | Before -> after | Grader | Verdict | Root cause |
| --- | --- | --- | --- | --- | --- |
| 0 | Merged board/editor work already satisfies the ticket text. | OP80 Tiptap AC: 0/1 | source contract + focused RED | rejected | The gateway board still used a plain textarea and escaped all markup as text. |
| 1 | A gateway-local StarterKit editor plus server safelist closes the missing rich-text contract without changing comments or game surfaces. | focused gateway writer 0/1 -> 1/1; full gateway 144/144 | Vitest + typecheck | adopted | The missing seam was isolated to the gateway post writer and gateway post sanitizer. |
| 2 | Real Next fixture matrices can prove the browser surfaces while remaining explicit about backend authority. | no durable browser matrix -> board/admin/mailbox/diplomacy matrix | Playwright against ports 3100/3101 | adopted | Earlier evidence mixed browser-shaped component tests with real browser observations. |
| 3 | Explicit wire format preserves legacy plain-text callers while allowing sanitized Tiptap HTML. | tag-shaped heuristic -> `PLAIN_TEXT` default / `RICH_HTML` opt-in; semantic-empty accepted -> 400 | independent review + focused regression | adopted | Inferring intent from markup broke the existing escaped-plaintext contract and allowed empty sanitized bodies. |
| 4 | A non-null Kotlin constructor default preserves omitted JSON fields. | fresh gateway JVM: 188 tests / 8 failures -> nullable wire field plus service fallback -> 188/188, 0 failures/errors/skips | Spring/Jackson integration RED/GREEN + static review | adopted | Jackson rejected the omitted enum before the constructor default applied, turning legacy create/update requests into 400. |

Approval pending: none for this acceptance audit. Merge and deploy remain prohibited.
