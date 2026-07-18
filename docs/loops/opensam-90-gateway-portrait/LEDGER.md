# OPENSAM-90 gateway portrait loop ledger

## Contract

- Baseline snapshot: current shared workspace before OPENSAM-90 production edits; unrelated `.ai/*` and `.codex/config.toml` changes are preserved.
- One hypothesis: gateway account/lobby divergence is caused by duplicated legacy-root builders and missing finite fallback; replacing them with the established `web/game/lib/portrait.ts` contract will move all seven frozen cases from red to green.
- Grader: the seven dedicated Vitest cases in `GOLDENSET.md`, then the unchanged gateway gates.
- Adopt: keep the candidate only if the dedicated score increases to `7/7`, targeted/full tests and typecheck/build pass, and no scoped requirement regresses.
- Revert: if the deterministic score is tied/lower or remains unmeasured, revert the production candidate to the pre-loop snapshot; never weaken the tests.
- Approval waiting: A2 independent verification remains outside this implementation lane; A4/A5 remain blocked.

## Environment baselines

- `vercel-react-best-practices` is referenced by `WORKING_SYSTEM.md` but absent from `.agents/skills/` and the surfaced skill catalog; installation/restore is outside this lane. Status: `채점대기`, repo patterns used instead.
- `python scripts/with_server.py --help` failed because `python` is absent. `python3 scripts/with_server.py --help` then found no repo-local helper. Recovery succeeded with `python3 .agents/skills/webapp-testing/scripts/with_server.py --help`.
- `corepack pnpm test` failed because `corepack` is absent. Direct `pnpm 10.33.0` is available and is the measured equivalent for this lane.
- Large batched document reads were truncated; recovery was one file or bounded line range per call. No product state changed.
- One read-only inventory command did not start due to a mistyped workspace path; the corrected command succeeded and no product state changed.
- One `apply_patch` attempt was rejected before applying because its absolute Korean workspace path was malformed; the repository-relative retry is the adopted path.
- The post-edit LSP hook duplicated `web/gateway` in its diagnostic path and returned `ENOENT`; package `pnpm typecheck` is the authoritative diagnostic for this lane.
- One read-only `rg` pattern used shell backticks inside a double-quoted argument, causing a harmless `frozen: command not found`; subsequent shell patterns avoid backticks.
- The first unmeasured `pnpm build` invocation remained alive after its wrapper stopped reporting. A process audit found only the two builds started by this lane; the exact orphan pair was stopped and the measured build continued to exit 0.
- Browser QA required three materially different recoveries: sandbox port bind failed with `listen EPERM`; escalated bind succeeded but bundled Chromium was absent; installed system Chrome launched, but the first unmocked `/account` navigation timed out after 60 seconds before `DOMContentLoaded`. No browser DOM/network/screenshot claim is made. Status: `채점대기`.
- Review-wave browser run 1 was deliberately interrupted (exit 130) because its `/api/` substring filter also captured existing app Sentry telemetry; responses had already returned 200 before the stop. The adopted harness restricts API evidence to `BASE_URL` and aborts Sentry in every context. The corrected run then exposed and fixed a keyword-only Playwright `arg=` mismatch. Two subsequent runs completed all five account scenarios but timed out waiting for the first lobby portrait, including after widening the basic-info route mock; repeated lobby real-Next browser coverage remains `채점대기` rather than silently retried.

## Measurements

| 바퀴 | 가설 | 점수 전->후 | 채점자 | 판정 | 원인 한 줄 |
|---:|---|---|---|---|---|
| 0-pre | 기존 테스트가 초상 계약을 잡는가 | UNKNOWN -> 기존 3/3 green, 전용 사례 0개 | `cd web/gateway && pnpm test` | 기준선 | 기존 account interaction 3건은 초상 `src`/fallback을 단언하지 않아 버그가 false-green이었다. |
| 0-invalid | 전용 테스트가 실제 동작까지 도달하는가 | 미채점 | targeted Vitest | 폐기 | lobby 5건이 Vitest JSX의 `React is not defined`로 assertion 전 error; test global만 보정했다. |
| 0 | 중복 builder/fallback 부재가 7개 계약 실패 원인인가 | 0/7 -> 0/7 | `pnpm exec vitest run __tests__/account-settings.interaction.test.tsx __tests__/lobby-portrait.test.tsx` | 기준선 확정 | 7 failed/3 passed; 모두 `d_shared`/`d_pic`, bare-code suffix, missing render, error fallback 차이에서 실패했다. |
| 1 | 두 화면을 game helper 동형 계약으로 통일하면 모든 divergence가 닫히는가 | 0/7 -> 7/7 | 같은 targeted Vitest | 채택 | 2 files/10 tests green; 전용 7건과 기존 account interaction 3건이 모두 통과했다. |
| 2 | 공백 입력 정규화와 canonical fallback guard가 review gap을 닫는가 | 0/3 -> 3/3 | helper + account + lobby targeted Vitest | 채택 | 유효 RED는 3 failed/13 passed였고 최소 수정 후 3 files/16 tests green이다. |

## Verification evidence

- Targeted GREEN: `pnpm exec vitest run __tests__/account-settings.interaction.test.tsx __tests__/lobby-portrait.test.tsx` -> 2 files, 10 tests passed.
- Typecheck: `pnpm typecheck` -> `tsc --noEmit`, exit 0.
- Full gateway suite: `pnpm test` -> 2 files, 10 tests passed.
- Review targeted GREEN: helper/account/lobby -> 3 files, 16 tests passed, exit 0.
- Review full gateway suite: `pnpm test` -> 3 files, 16 tests passed, exit 0 (duration 11.39s).
- Review typecheck: `pnpm typecheck` -> `tsc --noEmit`, exit 0.
- Review production build: `pnpm build` -> `EXIT_CODE=0`, compile success, static pages `18/18`, `/account` and `/lobby` present.
- Production build: measured `pnpm build` -> `EXIT_CODE=0`, compile success, static pages `18/18`, route table includes `/account` and `/lobby`.
- Build warnings, unchanged by this lane: inferred workspace root from multiple lockfiles, missing Sentry global error handler, native account `<img>` lint warning, unrelated admin `useEffect` dependency warning, webpack cache big-string warnings.
- Browser: `채점대기` after server-ready/system-Chrome run timed out on unmocked `/account`; account/lobby authenticated DOM `src`, request status, and screenshots are unmeasured. Closest rendered evidence is the jsdom React suite above.
- Review browser remeasurement: production Next + system Chrome deterministically passed account `normal`, `missing`, `whitespace`, `image-server-1`, and `404`; each API/image response was recorded, and `404` recorded `missing.png=404` followed by `default.jpg=200`. Five account screenshots were written under this loop directory. The first lobby scenario repeatedly timed out before a portrait became visible, so the requested two-route matrix and live probes remain `채점대기`; harness exit 1.

## Approval waiting

- A2 verifier verdict and authenticated browser fixture/control: pending; browser navigation boundary is recorded above.
- A4 commit/push/PR and A5 deploy: not approved.

## Second review fix wave

- Marker RED: `pnpm exec vitest run __tests__/portrait.test.tsx` -> 1 file, 2 failed / 4 passed, exit 1. A relative-base simulation assigned canonical fallback twice, while an initially canonical failing default recorded no attempt.
- Marker GREEN: explicit `data-portrait-fallback-applied` per-element state -> 1 file, 6/6 passed, exit 0. An unrelated nested `default.jpg` still falls back once; a failing canonical default retries once and then stops.
- Full gateway: 3 files, 18/18 passed, exit 0, duration 7.63s. Typecheck: `tsc --noEmit`, exit 0. Production build: exit 0, compile success, static pages 18/18, `/account` and `/lobby` present.
- Isolated pre-edit lobby diagnostic: final URL was `/login?next=%2Flobby`; `/api/auth/me` and `/api/server-basic-info/alpha` were never requested, expected portrait count was 0, and the captured HTML was the login page. `middleware.ts` protects `/lobby` by cookie presence, unlike `/account`. Root cause: the browser fixture mocked client auth but omitted the `sam_access` cookie required before client code runs.
- Adopted harness-only fix: install a synthetic `sam_access` cookie in each isolated context after the Sentry abort route and before page creation. Added independent `--route`/`--skip-live` controls and replaced the fixed 300 ms delay with a DOM condition requiring the expected `src`, `complete`, and positive `naturalWidth`.
- Independent production-Next lobby matrix: normal, missing, whitespace, image-server-1, and 404 all passed, harness exit 0. Each screenshot and API/image response was recorded; 404 evidence is `missing.png=404` followed by `default.jpg=200`. Diagnostic artifacts: `opensam-90-lobby-diagnostic.html` and five `opensam-90-20260717-lobby-*.png` screenshots.
- Existing server-map/server-log requests returned 404 in the backend-free browser fixture but did not affect the scoped portrait matrix. Account was not rerun in this time-boxed second wave; its first-wave five-scenario production evidence remains green. Optional unmocked live probes remain `채점대기`.

## Final fallback fix

- The explicit element marker was rejected because it survived later `src` changes. Superseding RED: focused helper suite 2 failed / 4 passed, exit 1. The same element fell back only once across source A then source B, and a relative `default.jpg` resolving to the canonical CDN URL was redundantly reassigned once.
- Adopted non-sticky guard: resolve `DEFAULT_PORTRAIT` and `currentSrc || src` against `image.ownerDocument.baseURI`; stop only on exact canonical URL equality, otherwise assign the resolved canonical URL. No marker survives a source change.
- Focused GREEN: 1 file, 6/6 passed, exit 0. It proves A -> fallback, B -> fallback again, fallback self-error stop, relative CDN/base resolution, and noncanonical nested `default.jpg` -> canonical fallback exactly once.
- Full gateway: 3 files, 18/18 passed, exit 0, duration 11.85s. Typecheck: `tsc --noEmit`, exit 0. Production build: exit 0, compile success, static pages 18/18, `/account` and `/lobby` present.
- Independent production matrices against that build: account 5/5, exit 0; lobby 5/5, exit 0. Both recorded normal, missing, whitespace, image-server-1, and failed-load DOM/request evidence; each failed-load recorded `missing.png=404` then `default.jpg=200`. Optional unmocked live probes remain `채점대기` and were not rerun.
