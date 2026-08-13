# OPENSAM-86 board rich-text integration evaluation contract

## Scope

Replace only the in-game meeting/secret-room board article authoring and article/comment display surfaces with the existing `RichTextEditor` and `SafeHtml` components. Do not modify either shared component, the intake API, daemon result path, or PHP-derived deny strings.

## Oracle evidence

- `legacy/devsam-core/hwe/ts/PageBoard.vue:5-31, 129-169` establishes the meeting/secret-room article form, title `maxlength="250"`, empty-title-and-body client guard, and reload-after-success behavior.
- `legacy/devsam-core/hwe/ts/components/BoardArticle.vue:18-24` and `BoardComment.vue:8-16` establish article/comment display positions.
- `legacy/devsam-core/hwe/j_board_article_add.php:33-75` and `j_board_comment_add.php:32-81` establish server-owned validation, trim, permission, and persistence behavior.
- `legacy/devsam-core/hwe/sql/schema.sql:157-182` establishes `TEXT` storage for article and comment content; only the legacy title/comment UI carries a `maxlength="250"` attribute.

## Approved OPENSAM-86 divergence

HWE itself uses a textarea and escaped Vue interpolation for board text. [Epic #225](https://github.com/peppone-choi/opensamguk/issues/225) explicitly authorizes the minimal shared Tiptap profile, existing text-column HTML storage, game-api Jsoup sanitation, and plain-text fallback; [OPENSAM-86 / #228](https://github.com/peppone-choi/opensamguk/issues/228) narrows that approved divergence to the board article editor and article/comment `SafeHtml` sinks. PHP/HWE therefore remains the oracle for board placement, limits, empty/permission/result behavior, and lifecycle—not for the newly approved rich-format rendering.

## Acceptance checks

1. The new article body is authored through the existing `RichTextEditor`; title remains a 250-character plain-text input and the existing CommandModal/intake result path is untouched.
2. Stored article HTML and stored comment HTML are rendered through the existing `SafeHtml`; existing plain text remains readable through its compatibility escaping/newline behavior.
3. Meeting room and secret room selection, blocked-reason rendering, command names/arguments, empty guards (including TipTap's empty paragraph and HTML-only whitespace), and on-reserved reset/reload semantics remain unchanged.
4. Focused Vitest, `pnpm typecheck`, browser-shaped verification when locally runnable, an independent review, strict diff checking, and `scripts/agent/verify-changes.sh --run` are reported with executed/unexecuted status.

## Guardrails

- No golden or legacy writes.
- No new board length limit: the article content remains a `TEXT` payload, and the existing comment input retains `maxLength={250}`. The editor's displayed `65535` counter follows the legacy MySQL `TEXT` storage ceiling and does not introduce client-side rejection.
- The project has a reusable component/token layer but no tracked `DESIGN.md`; this functional integration reuses existing components/styles without introducing a new design system or raw style tokens.
