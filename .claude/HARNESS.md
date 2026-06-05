# HARNESS.md — opensamguk parity + deploy harness

> Index / navigable map for the agent team that keeps **opensamguk** byte-faithful to the
> PHP grand-truth **`legacy/devsam-core`**, then ships it. Read this first; it points at every
> agent, skill, workflow, and discipline gate. Authoritative rules live in
> [`/CLAUDE.md`](../CLAUDE.md) — this file never overrides them, it operationalizes them.

---

## 1. Purpose

opensamguk is a faithful migration of the PHP game **devsam/core** → Kotlin/Spring + Next.js +
PostgreSQL + Redis + nginx on a memory-centric CQRS spine. The work is repetitive in shape but
unforgiving in detail: for each of ~133 reservable mutations you must (a) capture a real PHP
golden, (b) port the Kotlin logic draw-for-draw, (c) gate it against the golden, (d) wire the
intake seam end-to-end, (e) surface a frontend page, (f) review, (g) commit — then eventually
push and deploy.

This harness **codifies that manual flow** so a single command closes one parity gap and a
single command ships a batch, while the parity discipline (RNG / rounding / log byte-parity /
flush-delta / faithful-never-fabricate / insertion-order) and the one-daemon-write-rule are
enforced by the workers, not left to memory.

**Grand-truth order (never invert):** `legacy/devsam-core` (PHP) **wins every divergence** →
`legacy/devsam-core2026` (TS) is a *structural* oracle only → opensamguk Kotlin is the port.
`legacy/` is git-ignored. On a mismatch you fix the Kotlin impl, never the golden.

---

## 2. Architecture — generate-verify + pipeline + fan-out hybrid

Three composed patterns:

- **Generate-verify** — `parity-porter` generates the Kotlin port; `golden-capturer` (oracle)
  and `parity-gate-runner` (judge) verify it draw-for-draw against a real PHP capture. The port
  is never "done" until the gate is green or the gap is quarantined-with-proof.
- **Pipeline** — within one gap the stages are strictly ordered: golden → port → gate → intake →
  FE → review → commit. A later stage never runs until the earlier one is green.
- **Fan-out / fan-in** — `parity-wave.js` shards the pipeline across N independent gaps in
  **parallel git worktrees** (disjoint files — never co-widen one file), then fans results back in.

Skills are the entry points; agents are the workers; the workflow is the fan-out engine.

```
                              ENTRY SKILLS (human-invoked)
        ┌────────────────────────────┐        ┌──────────────────────────────┐
        │  /parity-close <command>   │        │  /parity-ship [--commands …] │
        │  close ONE parity gap      │        │  gate-all → push → deploy     │
        └────────────┬───────────────┘        └───────────────┬──────────────┘
                     │                                         │
   ┌─────────────────▼──────────────── PIPELINE ──────────────▼─────────────────┐
   │                                                                            │
   │  golden ──▶ port ──▶ gate ──▶ intake ──▶ FE ──▶ review ──▶ commit          │
   │   │          │        │         │          │       │          │            │
   │  golden-   parity-  parity-      intake- fe-submit- parity-  (per-task      │
   │  capturer  porter   gate-runner  wirer   wirer      reviewer commit,        │
   │  (oracle)  (gen)    (verify)                                 trailer)       │
   │                     (verify)                                               │
   │                                                                            │
   │  ===  /parity-ship adds, AFTER all gates green:  ===                       │
   │  gate-all ──▶ commit ──▶ push(main) ──▶ PR ──▶ deployer ──▶ verify     │
   │                                              (deploy.yml / scripts/deploy.sh)│
   └────────────────────────────────────────────────────────────────────────────┘

   FAN-OUT:  .claude/workflows/parity-wave.js
     ┌──────────────────────────────────────────────────────────────┐
     │  pick N gaps (gap denominator §7)                             │
     │     ├─ worktree A ─▶ /parity-close cmdA  (disjoint files)     │
     │     ├─ worktree B ─▶ /parity-close cmdB                       │
     │     └─ worktree C ─▶ /parity-close cmdC                       │
     │  fan-in ─▶ collect green gates ─▶ hand to /parity-ship        │
     └──────────────────────────────────────────────────────────────┘
```

Files (siblings of this index):
- Agents → [`.claude/agents/`](agents/)
- Skills → [`.claude/skills/`](skills/) (`parity-close/SKILL.md`, `parity-ship/SKILL.md`)
- Fan-out workflow → [`.claude/workflows/parity-wave.js`](workflows/parity-wave.js)

---

## 3. Agents (the 7 workers)

Each lives at `.claude/agents/<name>.md` (YAML frontmatter + system-prompt body). The
orchestrator matches on the `description`. Workers are deliberately narrow so fan-out stays safe.

| Agent | Role |
|-------|------|
| **golden-capturer** | The **oracle**. Drives `tools/php-golden/` Docker (MariaDB 11.4 + `php:8.3-cli`, scenario `1010` = 174 generals) + `RandUtilDrawRecorder.php` to capture a real PHP draw stream / log / numbers for one command, writes a fixture under `logic/src/test/resources/golden/<area>/`. Honors the capture quirks (`_boot.php` binds `DB::db()`, `j_install.php` called twice, install non-idempotent, dumps byte-identical across two runs). **Never fabricates** — if a value can't be captured faithfully it returns a quarantine recommendation + proof, it does not invent. |
| **parity-porter** | The **generator**. Ports the PHP behavior into `logic` (`actions/*`, `war/*`, `ai/*`, `CommandRegistry`) draw-for-draw: same RNG draw order/count/args via `RandUtil(LiteHashDrbg(SeedSerializer…))`, `PhpRound` (half-away), truncating `toInt`/`intdiv`, exact Korean log strings (`Josa`/markup). Writes mutations as `created`/`dirty`/`deleted` delta on `ChangeRecorder` only — never an inline DB write, never a JPA write in the daemon. Korean code comments; identifiers + log-parity strings stay English. |
| **parity-gate-runner** | The **judge**. Runs the gate test (`*GoldenTest` / `*ReplayGateTest`) draw-for-draw with the byte-exact seed via `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --rerun-tasks`. Verifies by **test XML + output-tail grep `BUILD SUCCESSFUL`**, never exit code. On mismatch: bounce back to parity-porter (fix Kotlin, never the golden). Asserts HARD on unfaithful goldens. |
| **intake-wirer** | Wires the end-to-end mutation seam so the ported command is actually reachable: adds the code to `intakeCodes` + `toCommand` in `app/game-api/.../reserve/CommandWireMapper.kt`, the wire variant in `common/wire/TurnDaemonCommand.kt`, the engine `TurnDaemonCommandDispatcher` route / `ReservedTurnHandler` actor+target threading, and the `ChangeRecorder` channel + `JdbcFlushExecutor` flush step. A code absent from `intakeCodes` → precheck AVAILABLE but engine silently denies. Stays JDBC-only; never adds a daemon JPA write. |
| **fe-submit-wirer** | Surfaces the command in `web/game/app/game/<page>/page.tsx`: form → Next route handler → game-api intake. Arg-bearing commands use the shared `web/game` `CommandModal` (no page nav). Read renders go through game-api read controllers; httpOnly-cookie auth pattern is unchanged. |
| **parity-reviewer** | Pre-commit multi-lens review (correctness / maintainability / testing / project-standards, conditional security/perf when touched). Confirms: no inline DB writes, no daemon JPA write, no weakened test/edited golden, RNG order untouched, log byte-parity, insertion-order preserved (`LinkedHashMap`). Uses code-review-graph (`detect_changes` / `get_review_context` / `get_impact_radius`) before Grep. |
| **deployer** | Pushes to `main` (fires `.github/workflows/deploy.yml`) **or** runs `scripts/deploy.sh 3.37.232.176`, then verifies. Enforces the two ops lessons (§6): nginx restarts **LAST** after upstreams are health-checked; a green `/actuator/health` is **not** sufficient — confirms `world_state.current_year/current_month` actually advances before declaring healthy. |

---

## 4. Skills (entry points)

Each lives at `.claude/skills/<name>/SKILL.md`. Invoke via the `Skill` tool or `/<name>`.

| Skill | Purpose | Spawns (in order) |
|-------|---------|-------------------|
| **/parity-close** `<command>` | Close ONE parity gap end-to-end, leaving a single green commit on a phase/feature branch. | golden-capturer → parity-porter → parity-gate-runner (loop until green) → intake-wirer → fe-submit-wirer → parity-reviewer → commit (trailer required). |
| **/parity-ship** `[--commands a,b,…]` | Take a batch of already-closed gaps from green gate → live. | gate-all (re-run full `:common :logic :infra :app:game-engine :app:game-api` test wall) → commit/squash → push `main` → PR (stacked base) → deployer → verify (turn-advance + health). |

Fan-out (not a skill — a workflow): `node .claude/workflows/parity-wave.js cmdA cmdB cmdC`
shards `/parity-close` across N gaps in parallel worktrees, then hands the green set to
`/parity-ship`.

---

## 5. The discipline it enforces (condensed — full text in `/CLAUDE.md` §Parity)

1. **RNG draw-for-draw.** All randomness = `RandUtil(LiteHashDrbg(SeedSerializer…))`. Draw
   **order + count + method args** are parity targets. Battle runs the WHOLE fight on **ONE**
   `RandUtil(warSeed)` built once in `processWar()`, threaded by reference, never re-seeded.
2. **Rounding.** `PhpRound` = half-away-from-zero (`phpRound(v,-2)`, NEVER `phpRound(v/100)*100`).
   NEVER `Math.round` / `kotlin.math.round`. `toInt`/`intdiv` = truncate-toward-zero. Damage
   clamp = `ceil()`.
3. **Korean log byte-parity.** `Josa`, color/tag markup, prefixes, `<Y1>【name】</> <C>HP (-dead)</>`,
   진격·퇴각·패퇴·전멸·분쟁·정복. **Log order = execution order** → execution drift breaks the gate.
4. **Flush delta, not inline writes.** Mutations = `created`/`dirty`/`deleted` on `ChangeRecorder`,
   bulk-flushed by `JdbcFlushExecutor`. Resolvers write delta only.
5. **Faithful, never fabricate.** Goldens come only from a real PHP capture. Can't capture
   faithfully ⇒ quarantine WITH PROOF (sibling-code-path byte-match) + log to the phase backlog.
   Never invent, never weaken a test or edit a golden. On mismatch: fix Kotlin.
6. **Insertion order.** jsonb / conflict-map / trigger keys preserve insertion order
   (`LinkedHashMap`). PHP 8.0+ sorts are stable — no non-stable secondary comparator.

**ONE-DAEMON-WRITE-RULE (architecture-test-enforced).** The game-engine daemon NEVER uses a JPA
`EntityManager` for writes. JPA = read/precheck only (game-api). Daemon writes go only through
`ChangeRecorder` → `JdbcFlushExecutor` JDBC batch. `CommandReserveService.reserve` (general_turn
JDBC + Redis poke) and the `engine.boot` seed path are **sanctioned** intake/boot writes, not
forbidden ones.

---

## 6. Deploy facts + the two ops lessons

**Push to `main` → `.github/workflows/deploy.yml` auto-fires:** build-jvm (JDK21
`./gradlew build` → push `ghcr.io/peppone-choi/opensamguk:{gateway-api,game-api,game-engine}`) +
build-web (matrix gateway/game → 2 images) → deploy job (appleboy/ssh-action → EC2,
secrets `EC2_HOST`/`EC2_USER`/`EC2_SSH_KEY`): `docker compose -f docker-compose.production.yml pull`,
then `up -d --no-deps gateway-api game-api web-gateway web-game nginx`, `sleep 5`, then
`up -d --no-deps game-engine` **LAST** (it owns the in-memory turn state), prune, health gate.
Manual path: `scripts/deploy.sh 3.37.232.176 ubuntu` (rsync compose + `infra/nginx/nginx.conf`,
same restart order, key `~/.ssh/id_ed25519`).

**Live:** EC2 `3.37.232.176` (user `ubuntu`), domain `sam.peppone.dev`, containers
`opensamguk-{db,redis,game-engine,game-api,gateway-api,game-frontend,gateway-frontend,nginx}`.
Flyway `V1..V10` auto-run on boot; `ScenarioSeedRunner` (`SCENARIO_SEED_ENABLED` default true,
`SCENARIO_CODE=scenario_1010`) idempotent on empty `world_state`; `AdminSeeder`
(`ADMIN_USERNAME`/`ADMIN_PASSWORD`).

> **OPS LESSON A — STALE-DNS 502 → restart nginx LAST.** `infra/nginx/nginx.conf` has STATIC
> upstreams (docker embedded resolver `127.0.0.11`, NO explicit `resolver` directive); nginx
> resolves upstream container names ONCE at start. If an upstream restarts and gets a new docker
> IP while nginx holds the old one → 502 on every route. **deployer ALWAYS restarts/reloads
> nginx LAST, after all upstreams are up AND health-checked.** Recovery:
> `docker restart opensamguk-nginx` (or `docker exec opensamguk-nginx nginx -s reload`).

> **OPS LESSON B — TURN-FREEZE → verify turn advancement, not just health.** The game-engine
> turn-daemon `readCommands` uses `commandBlockMs`, which DEFAULTS to 0; `block(Duration.ofMillis(0))`
> == `XREAD BLOCK 0` == INFINITE block → exceeds Lettuce's 60s timeout →
> `RedisCommandTimeoutException` → `TurnRunService.runTick` aborts BEFORE turn advancement →
> TURNS FROZEN (`world_state.current_year/month` never advances). Wiring gap at
> `app/game-engine/.../config/DaemonLoopConfig.kt` (~line 175 omits `commandBlockMs`) — pass a
> finite value (< 60s). **deployer's verify step queries the DB and confirms
> `world_state.current_year/current_month` ADVANCES over time; a green `/actuator/health` is NOT
> sufficient to call a deploy healthy.**

---

## 7. Gap denominator (what's left to port) + how to pick the next gap

**Total ≈ 133 reservable mutations:**

- **93 turn-reservable Command classes** = 55 General (`hwe/sammo/Command/General/`) +
  38 Nation/Chief (`hwe/sammo/Command/Nation/`). Whitelists are the denominator of record:
  `GameConstBase.php` `$availableGeneralCommand` (line 316) + `$availableChiefCommand` (line 378).
- **~40 non-Command mutations:** Betting(Bet), Auction(open/bid ×6), Vote(NewVote/cast/comment),
  Message(send/decide/delete), Troop(5), InheritAction(7 mutating), Nation Set* settings(8),
  General(Join/BuildNationCandidate/DropItem/InstantRetreat/DieOnPrestart).

**How to pick the next gap (deterministic, so fan-out shards don't collide):**

1. **Source of truth = the intake seam, not the port.** A command can be fully ported in `logic`
   yet still a gap because it's absent from `intakeCodes` in `CommandWireMapper.kt` (precheck
   AVAILABLE → engine silently denies). Diff the PHP whitelist (`$availableGeneralCommand` /
   `$availableChiefCommand`) against `intakeCodes` to get the true open set.
2. **Prefer gaps with an existing sibling golden** (cheaper capture; reuse a proven capture path).
   RNG-heavy gaps (vote tally, betting reward, anything on `warSeed`) go **last** in a wave — they
   need a fresh golden and are the easiest to desync.
3. **Disjoint files per shard.** `parity-wave.js` must never put two gaps that co-widen the same
   file (`CommandWireMapper.kt`, `TurnDaemonCommand.kt`, a shared `ChangeRecorder` channel) in
   parallel worktrees — those are sequential, creator-then-consumer (foundation-first).
4. **Backlog & quarantines are deferrals, not gaps:** long-sim multi-turn (P5 gate dim c),
   G12 nation reserved-fail deny-log, genfound-방랑군 (거병→건국 mini-sim),
   `chooseInstantNationTurn` (zero PHP callers), Q1 `ORDER BY RAND` (do선양/오랑캐임관, unreachable
   in 1010). Don't re-port these blind — pull from the documented backlog with proof.

---

## 8. Usage examples

```bash
# Close one parity gap end-to-end (golden → port → gate → intake → FE → review → commit)
/parity-close 모병            # General command 모병 (recruit)
/parity-close NewVote         # non-Command mutation (vote creation)

# Fan out three DISJOINT gaps across parallel worktrees, then ship the green set
node .claude/workflows/parity-wave.js 모병 단련 정착
/parity-ship --commands 모병,단련,정착

# Ship everything already gate-green to EC2 (push main → deploy.yml → verify turn-advance)
/parity-ship
```

**Day-to-day loop:** pick gap (§7) → `/parity-close <cmd>` → repeat / fan out with
`parity-wave.js` → batch `/parity-ship`. The deploy is only "healthy" once deployer confirms
`world_state.current_year/month` advances (§6 Lesson B) and nginx was restarted last (§6 Lesson A).

---

### Quick map

| You want to… | Go to |
|---|---|
| Understand the whole flow | §2 diagram |
| Know which worker does what | §3 agents table |
| Run it | §4 skills · §8 examples |
| Not break parity | §5 + `/CLAUDE.md` §Parity |
| Deploy safely | §6 + `deployer` |
| Find the next thing to port | §7 |

> The harness exists to make the *next* gap cheaper than the last. Every closed gap should leave
> a reusable golden, a wired seam, and a sharper backlog — compounding, not just done.
