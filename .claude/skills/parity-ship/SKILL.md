---
name: parity-ship
description: Ship a batch of CLOSED parity work through the full gate suite and adversarial review. Only an explicit human go can authorize a main merge or production action. The main workflow refreshes the shared opensamguk-docker stack while preserving per-server image pins; it does not silently promote a game server. Invoke as /parity-ship when the user explicitly asks to ship/deploy a coherent, test-green batch.
---

# parity-ship

Ship a batch of closed parity work. The contract: **a green build is NOT shipped until the shared stack is healthy and any running game server has the required post-deploy evidence.** A main merge builds GHCR images and refreshes the shared `opensamguk-docker` stack; per-server version pins remain unchanged unless an explicitly approved promotion changes them.

## Hard preconditions (refuse to proceed otherwise)

- Work is logically complete (no `TODO`/stub left in the diff for this batch).
- No golden/test was weakened to go green. If a parity gap exists, it must be **quarantined with proof** + logged to the phase backlog — NOT papered over. (CLAUDE.md parity rule 5.)
- You are on the **phase branch**, never committing straight to `main`.

If any fails, STOP and report — do not "fix" by editing a golden or relaxing an assertion.

## Ordered steps

### 1. Full gate suite — BLOCK on any red
Run every backend module from the **repo root** with Java 21. The host can route Gradle through a context-mode wrapper, so the XML/output evidence below—not exit status alone—is authoritative:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --rerun-tasks \
  :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test 2>&1 | tail -60
```

**Verify by TEST XML, not exit code** (`task-notification` exit 0 is unreliable). Confirm `BUILD SUCCESSFUL` in the tail AND inspect `*/build/test-results/test/*.xml` for `failures="0" errors="0"`. Pay special attention to the **`*GoldenTest` / `*ReplayGateTest`** classes — those are the draw-for-draw parity gates; a single one red = NOT shippable. Testcontainers ITs that **skip** for Docker-unavailable are fine; **failed** ITs are not. Any red ⇒ STOP, hand the failure back, do not continue.

### 2. Adversarial parity review on the diff
Spawn the **parity-reviewer** agent (or invoke the configured review workflow) against `git diff <parent>...HEAD`. It must clear, with focus on:
- RNG draw order/count/args (one `RandUtil(warSeed)` threaded, never re-seeded),
- `PhpRound` half-away (no `Math.round` / `kotlin.math.round`),
- Korean log byte-parity + log-order == execution-order,
- ChangeRecorder delta only (no inline DB writes; daemon never touches JPA for writes),
- LinkedHashMap insertion-order preservation.

Unresolved HIGH findings ⇒ STOP.

### 3. Commit logical units
One logical commit per task, with Korean code comments (identifiers stay English and parity-string literals retain PHP content). Commit only when separately authorized. **Every** commit message ends with the trailer — verbatim:

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
