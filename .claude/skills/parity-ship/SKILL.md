---
name: parity-ship
description: Ship and deploy a batch of CLOSED parity work to live. Runs the full gate suite, adversarial parity review, commits logical units, opens a PR, and — only on explicit human go — merges to main (which AUTO-DEPLOYS to sam.peppone.dev) then verifies prod health AND turn-advance. Invoke as /parity-ship when the user says "ship", "deploy this", "land and deploy", "이거 배포", "메인에 머지/배포". Do NOT invoke for in-progress work, exploratory builds, or a single uncommitted edit — only for a coherent, test-green batch ready for production.
---

# parity-ship

Ship + deploy a batch of closed parity work. The contract: **a green build is NOT shipped until prod `/health` AND world-clock turn-advance are both verified.** Pushing/merging to `main` AUTO-DEPLOYS to live `sam.peppone.dev` — treat that as a one-way door behind a human gate.

## Hard preconditions (refuse to proceed otherwise)

- Work is logically complete (no `TODO`/stub left in the diff for this batch).
- No golden/test was weakened to go green. If a parity gap exists, it must be **quarantined with proof** + logged to the phase backlog — NOT papered over. (CLAUDE.md parity rule 5.)
- You are on the **phase branch**, never committing straight to `main`.

If any fails, STOP and report — do not "fix" by editing a golden or relaxing an assertion.

## Ordered steps

### 1. Full gate suite — BLOCK on any red
Run every backend module from the **repo root** with Java 21, via `ctx_execute(language:'shell')` (the host routes plain `./gradlew` through a context-mode wrapper):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --rerun-tasks \
  :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test 2>&1 | tail -60
```

**Verify by TEST XML, not exit code** (`task-notification` exit 0 is unreliable). Confirm `BUILD SUCCESSFUL` in the tail AND inspect `*/build/test-results/test/*.xml` for `failures="0" errors="0"`. Pay special attention to the **`*GoldenTest` / `*ReplayGateTest`** classes — those are the draw-for-draw parity gates; a single one red = NOT shippable. Testcontainers ITs that **skip** for Docker-unavailable are fine; **failed** ITs are not. Any red ⇒ STOP, hand the failure back, do not continue.

### 2. Adversarial parity review on the diff
Spawn the **parity-reviewer** agent (or invoke `/review`) against `git diff <parent>...HEAD`. It must clear, with focus on:
- RNG draw order/count/args (one `RandUtil(warSeed)` threaded, never re-seeded),
- `PhpRound` half-away (no `Math.round` / `kotlin.math.round`),
- Korean log byte-parity + log-order == execution-order,
- ChangeRecorder delta only (no inline DB writes; daemon never touches JPA for writes),
- LinkedHashMap insertion-order preservation.

Unresolved HIGH findings ⇒ STOP.

### 3. Commit logical units
One logical commit per task, Korean code comments (identifiers + parity-string literals stay English). **Every** commit message ends with the trailer — verbatim:

```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

### 4. Push branch + open PR
Push the phase branch and open a PR via `gh`. Base = the **parent phase branch** for a stacked diff, or `main` if this phase lands directly. PR body ends with the Generated-with-Claude-Code line. **Opening a PR does NOT deploy** — only a push/merge to `main` does.

### 5. SAFETY GATE — explicit human go (one-way door)
`main` is wired to auto-deploy. Before any merge/push to `main`, surface this to the human and WAIT for an explicit "yes, deploy":

> Merging to `main` fires `.github/workflows/deploy.yml`, which builds + pushes GHCR images and SSH-deploys to **live EC2 (3.37.232.176 / sam.peppone.dev)**. Confirm to proceed.

No implicit consent. No "I'll just merge it." If unsure, stay on the PR.

### 6. Merge → CI deploys (or deployer agent manual deploy)
On explicit go, **merge the PR to `main`**. CI (`deploy.yml`, on `push: branches:[main]`) then:
- `build-jvm` (JDK21 `./gradlew build`) → pushes `ghcr.io/<owner>/opensamguk:{gateway-api,game-api,game-engine}-{<sha>|latest}`,
- `build-web` matrix → `web-{gateway,game}-…` images,
- `deploy` job → `appleboy/ssh-action` to EC2 (`secrets.EC2_HOST/EC2_USER/EC2_SSH_KEY`): GHCR login → `docker compose -f docker-compose.production.yml pull` → `up -d --no-deps gateway-api game-api gateway-frontend game-frontend nginx` → `sleep 5` → `up -d --no-deps game-engine` (**engine LAST — it owns in-memory turn state**) → `docker image prune` → health gate (`/health`, `/api/game/actuator/health` UP, `/api/gateway/actuator/health` UP).

If CI is unavailable or a hotfix is needed, fall back to the **deployer** agent / `scripts/deploy.sh 3.37.232.176 ubuntu` (rsyncs `docker-compose.production.yml` + `infra/nginx/nginx.conf`, sources `~/opensamguk/.env`, SAME restart order, then health loops incl. `docker exec opensamguk-game-engine` actuator + `/api/game/health` lowercase `"up"`). SSH key `~/.ssh/id_ed25519`.

### 7. Post-deploy VERIFY — the real ship gate
A green `/actuator/health` is **NOT** "shipped". The deployer must additionally confirm both ops rules:

- **Rule A (nginx LAST):** nginx resolves upstream container names ONCE at start (no `resolver` directive in `infra/nginx/nginx.conf`). If an upstream restarted into a new docker IP while nginx cached the old one → **502 on every route**. nginx must be (re)started/reloaded AFTER all upstreams are up and health-checked. On a stale-DNS 502 recover with:
  ```bash
  docker restart opensamguk-nginx   # or: docker exec opensamguk-nginx nginx -s reload
  ```
- **Rule B (turn-advance):** the engine turn-daemon can silently freeze (Redis `XREAD BLOCK 0` infinite block → Lettuce 60s timeout → `runTick` aborts before advancing). Health can be green while the world clock is frozen. So **query the DB and confirm `world_state.current_year` / `current_month` ADVANCES over time** before declaring the deploy healthy:
  ```bash
  ssh -i ~/.ssh/id_ed25519 ubuntu@3.37.232.176 \
    "docker exec opensamguk-db psql -U postgres -d opensamguk -tAc \
     'select current_year, current_month from world_state;'"
  # sample, wait a turn interval, sample again — values MUST change.
  ```

Only when prod health passes AND the clock has visibly advanced (and no 502) is the batch **shipped**. Report final state: merged SHA, deployed images, health results, before→after world-clock readings.

## Safety gates (summary)
- Red gate suite or red `*GoldenTest`/`*ReplayGateTest` ⇒ never ship.
- Weakened golden/test or fabricated value ⇒ never ship (quarantine-with-proof only).
- Merge/push to `main` ⇒ only on explicit human go (auto-deploys live).
- nginx restarted LAST; on 502 → `docker restart opensamguk-nginx`.
- Not "shipped" until prod health AND `world_state` clock-advance are verified.
