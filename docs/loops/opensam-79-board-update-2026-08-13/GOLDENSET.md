# OPENSAM-79 board-post update API acceptance set

## Status

Frozen for this scoped task by the explicit user authorization to implement, validate,
commit, push, and prepare a PR. This is a gateway-account community feature, so the
product contract below replaces a PHP golden fixture.

## Source of truth

- `docs/superpowers/research/2026-08-12-opensam-79-gateway-community-board-contract.md:5-8`
  defines this as a new gateway surface rather than a port of the game-world board.
- `docs/superpowers/research/2026-08-12-opensam-79-gateway-community-board-contract.md:40-56`
  fixes the public-read, authorization, capability, and plain-text rendering contract.
- `legacy/devsam-core/hwe/ts/components/BoardArticle.vue:1-98` is read-only legacy
  context for the game-world comment flow, not an implementation oracle for this
  account-global route.

## Deterministic acceptance cases

1. `PATCH /board/posts/{postId}` updates a live post with an authenticated owner or
   ADMIN and returns its normal post representation.
2. Anonymous and non-owner non-admin callers are rejected; an ADMIN may update another
   account's post.
3. A deleted post is rejected without exposing or reviving its historical content.
4. `NOTICE` remains ADMIN-only on update as well as creation.
5. Updated title stays plain text and updated content follows the existing escape-plus-
   normalized-`<br>` sanitizer contract.
6. Public reads remain public and continue to vary the personalized `canDelete`
   representation by `Authorization`.

## Graders

- Focused RED/GREEN: `GatewayBoardPostUpdateSecurityTest`.
- Regression: `:app:gateway-api:test --tests 'opensamguk.gateway.board.*'` with fresh
  XML inspected for zero failures and errors.
- PR readiness: `git diff --check` and
  `python3 tools/agent-system/check.py --strict --base origin/main`.

## Non-goals

No schema migration, frontend route, game-world board behavior, daemon path, or shared
`.ai/*` state is changed.
