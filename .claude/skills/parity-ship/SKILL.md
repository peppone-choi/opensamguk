---
name: parity-ship
description: Opt-in historical workflow for shipping an explicitly requested frozen-regression parity batch through final gates and review. Never required for new product work; merge/deploy still requires explicit human approval.
---

# parity-ship

Ship an explicitly selected batch of closed historical parity work. This opt-in name is preserved for compatibility under ADR-LITE-042; it does not make PHP parity a general release prerequisite. **A green build is NOT shipped until the shared stack is healthy and any running game server has the required post-deploy evidence.**

## Hard preconditions (refuse to proceed otherwise)

- Work is logically complete (no `TODO`/stub left in the diff for this batch).
- No golden/test was weakened to go green. If a selected historical parity gap exists, it must be **quarantined with proof** and logged, never papered over. (CLAUDE.md product and regression discipline.)
- You are on the **phase branch**, never committing straight to `main`.

If any fails, STOP and report — do not "fix" by editing a golden or relaxing an assertion.

## Ordered steps

### 1. Full gate suite — BLOCK on any red
Run the canonical backend gate from the **repo root** with Java 21:

```bash
tools/parity/gate.sh backend
```

The gate verifies test XML; pay special attention to the **`*GoldenTest` / `*ReplayGateTest`** classes — those are the draw-for-draw parity gates; a single one red = NOT shippable. Testcontainers ITs that **skip** for Docker-unavailable are fine; **failed** ITs are not. Any red ⇒ STOP, hand the failure back, do not continue.

For a parity batch, run the game frontend proof as well:

```bash
cd web/game && pnpm typecheck && pnpm test
```

Run only the extra changed-scope checks that apply: `:app:gateway-api:test` for gateway API work,
`cd web/gateway && pnpm typecheck` for web-gateway work, and `./tools/smoke.sh` for compose/nginx
or full-stack changes. Any changed UI flow also requires browser observation with the routed
`webapp-testing`/Playwright workflow; typecheck and unit tests alone are not UI behavior proof.

### 2. Adversarial parity review on the diff
Spawn the **parity-reviewer** agent (or invoke the configured review workflow) against `git diff <parent>...HEAD`. It must clear, with focus on:
- RNG draw order/count/args (one `RandUtil(warSeed)` threaded, never re-seeded),
- `PhpRound` half-away (no `Math.round` / `kotlin.math.round`),
- selected historical Korean log evidence + log-order == execution-order,
- ChangeRecorder delta only (no inline DB writes; daemon never touches JPA for writes),
- LinkedHashMap insertion-order preservation.

Unresolved HIGH findings ⇒ STOP.

### 3. Commit logical units
One logical commit per task, with English code comments (identifiers stay English and game-content/parity-string literals retain PHP content). Commit only when separately authorized. **Every** commit message ends with the trailer — verbatim:

```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

### 4. Push branch + open PR
After explicit authorization to push, push the phase branch and open a PR via `gh`. Base = the **parent phase branch** for a stacked diff, or `main` if this phase lands directly. Opening or updating a PR does not promote a game server.

### 5. SAFETY GATE — explicit human go (one-way door)
Before any merge/push to `main`, surface this to the human and WAIT for an explicit "yes, deploy":

> Merging to `main` fires `.github/workflows/deploy.yml`: it publishes GHCR images, synchronizes the `opensamguk-docker` control repository on its self-hosted runner, and refreshes the shared stack. Existing `servers/<id>.env` image pins remain unchanged. Confirm to proceed.

No implicit consent. No "I'll just merge it." If unsure, stay on the PR.

### 6. Merge → shared-stack refresh
On explicit go, merge the approved PR to `main`. CI (`deploy.yml`, on `push: branches:[main]`) then:

- builds/tests JVM work and publishes versioned GHCR API images;
- builds/publishes the web images;
- runs on the EC2 self-hosted runner, synchronizes `opensamguk-docker`, and recreates the shared deployer, gateway, and nginx services in the shared-stack order;
- leaves every running `servers/<id>.env` `IMAGE_TAG` and `WEB_GAME_TAG` unchanged. Server promotion, reset, re-seed, and a new season are separate, explicit operations.

This repository's `docker-compose.production.yml` and `scripts/deploy.sh` are compatibility-only surfaces. Do not substitute a direct/manual deployment path for the approved shared-stack runbook.

### 7. Post-deploy VERIFY — the real ship gate
A green `/actuator/health` is **NOT** "shipped". The deployer must additionally confirm both ops rules:

- **Rule A (nginx LAST):** nginx resolves upstream container names at startup. Recreate/reload it after shared upstreams are healthy so it resolves current container addresses and does not serve stale-DNS 502s.
- **Rule B (turn-advance):** health can be green while a running game server is not progressing. Confirm `world_state.current_year` / `current_month` advances over an appropriate interval before declaring that server healthy. An intentionally empty admin-created server is the documented exception.

Only when shared health passes and each running game server has visibly advanced its clock (or the intentional empty-server invariant is confirmed), with no 502, is the batch **shipped**. Report final state: merged SHA, deployed images, health results, and the applicable world-clock or empty-server evidence.

## Safety gates (summary)
- Red gate suite or red `*GoldenTest`/`*ReplayGateTest` ⇒ never ship.
- Weakened golden/test or fabricated value ⇒ never ship (quarantine-with-proof only).
- Merge/push to `main` or any production action ⇒ only on explicit human go.
- Shared-stack refresh preserves per-server pins; promotion is separate.
- Not "shipped" until the relevant shared health and running-server/empty-server evidence is verified.
