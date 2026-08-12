# Review: OPENSAM-84/85 rich-text editor and sanitizer foundation

Scope: `app/game-api/build.gradle.kts`, `app/game-api/src/main/kotlin/opensamguk/gameapi/sanitize/HtmlSanitizer.kt`, `app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt`, `app/game-api/src/test/kotlin/opensamguk/gameapi/sanitize/HtmlSanitizerTest.kt`, `app/game-api/src/test/kotlin/opensamguk/gameapi/web/CommandControllerHtmlSanitizerTest.kt`, `logic/src/main/kotlin/opensamguk/logic/actions/intake/NationFinanceSetters.kt`, `logic/src/test/kotlin/opensamguk/logic/actions/intake/NationFinanceSettersTest.kt`, `web/game/components/RichTextEditor.tsx`, `web/game/components/SafeHtml.tsx`, `web/game/app/globals.css`, `web/game/__tests__/RichTextEditor.test.tsx`, `web/game/__tests__/SafeHtml.test.tsx`, `web/game/package.json`, and `web/game/pnpm-lock.yaml`
Verdict: cleared

## Independent review

A fresh read-only reviewer rechecked the full post-remediation diff. It found no CRITICAL, HIGH, MEDIUM, or LOW findings and explicitly cleared the following contracts:

- Plain text bypasses HTML parsing, preserving raw `&` and `<` rather than persisting entity-encoded text.
- Server sanitization allowlists only `b`, `strong`, `i`, `em`, `s`, `u`, color-only `span`, `br`, and `p`; scripts, event handlers, and unsafe style declarations are removed.
- PHP-derived raw limits apply before sanitize, precheck, reservation, and intent fingerprint: `setNotice.msg` 16,384 and `setScoutMsg.msg` 1,000 Unicode code points.
- The API boundary and daemon resolver both use Unicode code-point counts, so astral characters match PHP `mb_strlen` behavior.
- The editor counter has concrete surrogate-pair coverage and does not truncate composition input.

## PHP and HWE evidence

- `legacy/devsam-core/hwe/sammo/API/Nation/SetNotice.php:18-29` validates raw `msg` with `lengthMax 16384`; `:53-59` sanitizes in launch.
- `legacy/devsam-core/hwe/sammo/API/Nation/SetScoutMsg.php:17-28` validates raw `msg` with `lengthMax 1000`; `:52-54` sanitizes in launch.
- `legacy/devsam-core/vendor/vlucas/valitron/src/Valitron/Validator.php:329-350` uses `mb_strlen` when present.

## Verification

- `:logic:test --tests opensamguk.logic.actions.intake.NationFinanceSettersTest` — `BUILD SUCCESSFUL`; XML: 2 tests, 0 failures, 0 errors.
- `:app:game-api:test --tests opensamguk.gameapi.sanitize.HtmlSanitizerTest --tests opensamguk.gameapi.web.CommandControllerHtmlSanitizerTest` — `BUILD SUCCESSFUL`; XML: sanitizer 6 tests, controller 4 tests, all 0 failures/errors.
- `web/game`: focused Vitest — 5/5 passing; `pnpm typecheck` (`tsc --noEmit`) passed.
- `git diff --check` passed.

## Known environment notes

- The host lacks `corepack` and `php`; direct installed `pnpm` was used successfully. PHP behavior was established from checked-in source rather than an executable local PHP capture.
- The command wrapper intermittently emitted generic Fablize warnings despite terminal command success. Validation evidence above comes from direct terminal exits and JUnit XML, not those wrapper notices.
