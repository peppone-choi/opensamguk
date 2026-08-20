# Documentation overhaul independent review

> Date: 2026-08-20
> Reviewer: independent `fable-deep-reasoner` agent
> Base HEAD: `32376c42737ec73ca52ca4ee4a646e8367c2f8d0`
> Scope: documentation overhaul working-tree diff and new `docs/user`, `docs/admin`, `docs/design` files

## Review focus

- current code routes and administrator APIs versus manuals
- ADR-LITE-041/042 authority and current/planned separation
- GitHub issue references and Jira `UNKNOWN` handling
- destructive reset/delete guidance and recovery preconditions
- relative Markdown links and top-level documentation consistency

## Findings and resolution

The first pass returned `fix-required` for six issues:

1. remaining PHP-oracle requirements in current `CLAUDE.md` workflow rules
2. Han world transition incorrectly described as v2-only
3. reset/delete guidance without an executable restore runbook
4. missing unification and season-end user guidance
5. “36 turns daily” wording instead of an annual 36-phase calendar
6. generated command catalog described as adopted although it remains follow-up work

The second pass found a further stale `CLAUDE.md` roadmap block that still required PHP capture, described frontend pages as
future work, and named the non-existent `docker-compose.prod.yml`.

All findings were corrected. The final pass reported no actionable findings.

## Evidence inspected

- `.ai/decisions.md` ADR-LITE-041/042
- `logic/src/main/kotlin/opensamguk/logic/world/CheckEmperior.kt`
- `logic/src/main/kotlin/opensamguk/logic/world/InvaderEndingAction.kt`
- `web/gateway/app/admin/page.tsx`
- `web/game/app/game/`
- `app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt`
- `.github/workflows/reset-game-server.yml`
- `.github/workflows/deploy.yml`
- `docker-compose.production.yml`
- bounded `Rehydrate*IT` tests

The reviewer also observed zero missing scoped relative Markdown links and a clean `git diff --check` on the final review
snapshot.

Verdict: cleared
