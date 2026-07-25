# OPENSAM-6 diplo letter respond — independent review

**Verdict:** cleared (orchestrator re-verify 2026-07-24)
**PR:** https://github.com/peppone-choi/opensamguk/pull/317
**Branch:** `codex/op-6-diplo-respond` @ `b86d7566` (rebased on main after OPENSAM-13)

## Scope checked
- `api.commands.diploRespondLetter` wrapper posts letterNo/isAgree/reason.
- LetterCard shows 승인/거부 only when `state=='proposed' && src.nationID != myNationId`.
- Approve confirm; reject prompt with 50-char reason; cancel = no request.
- Submit path uses `submitCommandAndAwaitResult` (engine deny surfacing).
- Mailbox REST accept/decline untouched.

## Gates
- web (game)/web (gateway)/jvm: CI pass on PR after rebase.
- agent-system was red only for missing this critique + docs-drift; product tests green.

## Residual UNKNOWN
- Live browser QA not run.
- legacy hwe/ts path may be gitignored on worker machines.
