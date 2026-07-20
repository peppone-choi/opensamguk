# Agent Handoff

- Updated at: 2026-07-20
- From: Codex (`cqrs-hardening-root`)
- Branch: `codex/op-126-scoped-schema`
- State: verified strict V31 stack; commit/push/merge explicitly approved, deploy/tracker mutation not approved

## Goal

Continue OPENSAM-126 from the strict V31 first slice. The current stack also includes only the affected
OPENSAM-127/128 runtime changes required to keep that migration safe and bootable.

## Implemented

- V31 locks `world_state` plus the exact 30 current C1 relations, permits a truly pristine empty schema,
  backfills only one positive canonical world, and fails closed for legacy data without a world,
  multiple/non-positive worlds, or concurrent legacy writers.
- The first scoped cohort is `nation`, `city`, `general`, `general_turn`, and `nation_turn`.
- Scenario seeding is serialized and transactional, inserts the configured `WorldId` explicitly,
  synchronizes the serial sequence only on the success path, and supports same-ID retry after rollback.
- Engine load, reservation, flush, and world snapshots require the configured process world. Cohort
  create/update/root-delete/profile writes are scoped and exact-count checked inside the flush transaction.
- Game-api five-cohort reads use world-bound facades over raw Spring Data repositories; reservation calls
  pass the configured process world. Runtime configuration has no default or profile/server alias.
- Local and production Compose require `OPENSAMGUK_WORLD_ID`; `.env.example` documents `1`.

## Verification evidence

- Fresh Java 21 forced three-module run: `BUILD SUCCESSFUL in 9m 3s`; infra 43 suites / 160 tests,
  game-engine 85 suites / 578 tests / 1 known skip, and game-api 57 suites / 402 tests, all with
  0 failures or errors in current XML.
- Compose config: local and production passed using an empty env-file plus explicit validation values.
- Docker smoke: isolated fresh project applied Flyway V1 through V31, seeded configured world 1, and passed
  gateway-api, game-api, game-engine status, web-gateway, web-game, and nginx gateway HTTP checks. The daemon
  was running/loop-alive with zero failures and a 3,600-second scheduled tick. Initial failures were isolated
  to parallel Docker build memory exhaustion and a pre-existing default-project PostgreSQL volume password;
  serial image builds plus an isolated fresh volume recovered the gate. Temporary containers, volumes, and
  tags were removed; the existing `opensamguk_*` volumes remain.
- `git diff --check` and untracked whitespace scan: clean. Independent final review verdict: `cleared`.
- Remaining project-tool baselines: Agent OS test expects missing tracked `agents.max_threads`; strict checker
  reports only the user-owned personal model pin in `.codex/config.toml`. Neither file was changed for this task.

## Deferred scope

Do not claim full OPENSAM-127/128 or full S2-T2/T3. Deferred work includes other C1 tables,
request/JWT world authorization, Redis key/consumer scoping, log/KV/history/message/auction ownership,
same-local-ID coexistence, second-world admission, and cutover/production activation.

## Safety and ownership

- `.codex/config.toml` contains a pre-existing user change and must remain untouched.
- Commit/push/merge is explicitly approved for this verified stack. Deploy, production migration, Jira update,
  and GitHub issue update remain unapproved.
- Two exact abandoned Testcontainers Postgres containers from local hangs were removed. The default local
  Compose containers were recreated during smoke and removed by cleanup; persistent `opensamguk_*` volumes
  remain. The running MySQL container was not changed.
- Root owns `.ai/*`; implementation lanes are released. Obtain explicit approval before any git/external action.

## Next action

After the approved landing, fix the next bounded OPENSAM-126 cohort and ownership before implementation.
Do not deploy or mutate trackers without explicit approval.
