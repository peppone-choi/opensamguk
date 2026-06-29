# 2026-06-29 command/calendar/map UI review

## Scope

- Command reservation persistence and reserved-turn display.
- Nation letter/message UI defaults.
- 36-turn calendar phase propagation through logs, map, lobby, admin, and main UI.
- Scenario map selection and neutral-city tooltip handling.
- Main screen and admin game settings UI polish.
- Recruit unit filtering and unavailable-unit display.

## Implementation Claim

- Reserved commands now keep a registry-derived command name in the queued turn brief, and the reserved command read path repairs old blank or rest-like briefs for non-rest actions.
- Monthly events are gated to the phase-1 month boundary so `상순`, `중순`, `하순` no longer run monthly hooks three times.
- Game date data now carries `year`, `month`, and `phase`/`phaseText` through API responses and visible log/info surfaces.
- Map preview and in-game map use the scenario map code from world config/meta where available.
- Neutral cities no longer show a nation row in lobby/login or main map hover tooltips.
- Main UI now keeps a 36-turn table visible, removes the expand/collapse path, adds refresh, aligns the city/nation/general cards, and uses `오픈삼국` branding.
- Recruit fields show only recruitable units by default, with an explicit full-list toggle and disabled unavailable rows.

## Verification

- `web/game`: `PATH=/usr/local/bin:$PATH pnpm exec tsc --noEmit` passed.
- `web/gateway`: `PATH=/usr/local/bin:$PATH pnpm exec tsc --noEmit` passed.
- `web/game`: `NODE_ENV=test PATH=/usr/local/bin:$PATH pnpm test MainRecordZone PartialReservedCommand SelectRecruitField MessagePanel DiplomacyPage.command` passed: 5 files, 6 tests.
- `app:game-engine`: targeted `MonthBoundaryLoopTest` passed in the implementation run.
- `app:game-api`: targeted `MapPreviewControllerTest`, `AdminReadControllerTest`, and `FrontInfoControllerTest` passed on serial rerun in the implementation run.
- `tools/agent-system/check.py --format json` returned `ok: true`.
- Sanity grep found no remaining `SELECT id, year, month, text` query pattern in the touched game-api log read paths.

## Review Notes

- The current work intentionally follows the user-approved 36-turn `상/중/하순` model, which extends the legacy 12-step month-only calendar. PHP byte parity is therefore not the source of truth for the added phase field itself.
- The gateway map tooltip fix is covered by direct code inspection and typecheck rather than a browser screenshot in this pass.
- `tools/agent-system/check.py` warned about docs/evidence drift before this review artifact was added; this file records the evidence for the current slice.
- A full `tools/parity/gate.sh backend` run was not repeated for the whole tree in this pass; narrower backend tests and frontend typechecks/tests were used because the reported issues were command reservation, date propagation, map/admin/main UI, and recruit UI surfaces.

## Result

Cleared with the narrow verification above. Remaining risk is browser-level visual regression on the live composed pages, especially admin and main layout at small widths.
