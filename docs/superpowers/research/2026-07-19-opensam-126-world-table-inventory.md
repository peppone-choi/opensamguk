# OPENSAM-126 world-table ownership inventory and scoped-schema completion

Status: V31 first slice landed; V32 completes the current physical ownership partition for S2-T1.
Date: 2026-07-19
Updated: 2026-07-20
Scope owner: op126-complete-schema

## Boundary and consumed contract

This document inventories every relation created by current Flyway V1--V30
CREATE TABLE statements and records the final V32 ownership decision. The
original C1/C2/C3 tables below preserve the evidence state that preceded that
decision; the `V32 final ownership partition` section is authoritative where a
provisional classification differs.

- C1 was the initial canonical world-owned set.
- C2 was the initial account/cross-season/global candidate set.
- C3 was the initial classification-blocked set.

V32 resolves every provisional row: `general_owner`, `inheritance_result`, and
`select_npc_token` are world-owned; `game_kv` is row-family mixed; and the exact
global allowlist is `inheritance_point`, `inheritance_log`,
`inheritance_user_state`, `users`, `system_flag`, `banned_member`, and
`error_log`.

OPENSAM-148 is consumed as a hard predecessor:

1. Canonical identity is only positive SQL INTEGER world_state.id, represented
   by WorldId (canonical-world-identity-contract.md:8-25).
2. profile, server_id, and ng_games.id are operational/legacy IDs, never
   aliases/defaults/fallbacks for world_state.id
   (canonical-world-identity-contract.md:27-32).
3. World-owned keys are composite: (world_id, local_id) and
   (world_id, request_id); a local ID never repairs a missing world ID
   (canonical-world-identity-contract.md:34-56).
4. A migration locks its canonical source and backfills only from exactly one
   positive world_state id. A zero-world database may expand only when every
   declared world-owned legacy relation is empty; otherwise zero,
   multiple/non-positive, orphan, and unresolvable cases fail closed. No
   profile/server_id/ng_games fallback is legal
   (canonical-world-identity-contract.md:58-76).

The OPENSAM-126 plan requires mutable and append-only/cold/satellite log,
history, rank, turn, KV, message, auction, and archive-style world data to be
scoped, and requires a recorded global allowlist for every unscoped relation
(CQRS plan:145-154). Therefore server_id, profile_name, and all local IDs below
are unscoped legacy coordinates, not canonical world_id aliases.

## Raw CREATE TABLE audit

Source command:

~~~bash
rg -n '^CREATE TABLE' --glob 'V*.sql' infra/src/main/resources/db/migration
~~~

Result: 44 raw CREATE TABLE entries. V7 drops V1 auction and auction_bid
(V7__p6_messaging_economy.sql:71-72), so the final V1--V30 physical schema has
42 current tables. The two retired relations remain below so the raw CREATE
inventory and this document have identical name sets.

There is intentionally no V26 migration file. An initial read-only zsh glob
V{1..30}__*.sql therefore raised NOMATCH. It made no file/database change and
was isolated by the file-presence command above; it is not a schema finding.

Current explicit FKs are limited to board_comment.post_id, vote.vote_id, and
vote_comment.vote_id (plus retired auction_bid.auction_id). Local-looking
columns elsewhere are not FKs unless listed below.

## C1 canonical world-owned (33 raw CREATE entries)

| Table | Migration source | Current PK / unique / FK shape | Required ownership outcome |
|---|---|---|---|
| world_state | V1__baseline.sql:10 | PK(id); no extra unique/FK | Canonical anchor; its positive id is the only backfill source and it receives no self world_id. |
| nation | V1__baseline.sql:21 | PK(id); no FK; capital_city_id is local/unconstrained | Core root; future (world_id,id). |
| city | V1__baseline.sql:34 | PK(id); no FK; nation_id is local/unconstrained | Core root; future (world_id,id). |
| general | V1__baseline.sql:60 | PK(id); no FK; nation_id/city_id/troop_id/user_id unconstrained | Core root; sentinel/local-reference policy must precede composite FKs. |
| troop | V1__baseline.sql:104 | PK(troop_leader); no unique/FK | Game troop state keyed by local general ID. |
| general_turn | V1__baseline.sql:110 | PK(id); UNIQUE(general_id,turn_idx); no FK | Reserved turn; business key must be world-qualified. |
| nation_turn | V1__baseline.sql:120 | PK(id); UNIQUE(nation_id,officer_level,turn_idx); no FK | Reserved turn; business key must be world-qualified. |
| diplomacy | V1__baseline.sql:131 | PK(id); UNIQUE(src_nation_id,dest_nation_id); no FK | Nation-pair state with local nation IDs. |
| diplomacy_letter | V1__baseline.sql:144 | PK(id); indexes only; no FK | World diplomatic history. |
| rank_data | V1__baseline.sql:162 | PK(id); UNIQUE(general_id,type); no FK | Per-world rank state. |
| hall | V1__baseline.sql:173 | PK(id); UNIQUE(server_id,type,general_no), UNIQUE(owner,server_id,type); no FK | Game-derived archive/leaderboard. server_id is noncanonical; a separate global projection needs separate approval. |
| ng_games | V1__baseline.sql:189 | PK(id); UNIQUE(server_id); no FK | Current game/scenario satellite state; neither id nor server_id can stand in for world_id. |
| ng_old_nations | V1__baseline.sql:203 | PK(id); UNIQUE(server_id,nation); no FK | Deleted-nation archive with local nation identity. |
| ng_old_generals | V1__baseline.sql:212 | PK(id); UNIQUE(server_id,general_no); no FK | Deleted-general archive with local general identity. |
| yearbook_history | V1__baseline.sql:227; V28__yearbook_server_id_insert_contract.sql:1-45 | PK(id); V1 UNIQUE(profile_name,year,month) is dropped by V28; current non-unique index(server_id,year,month,id); no FK | Monthly world history. V28 server/profile reconciliation is not canonical identity. |
| event | V1__baseline.sql:239 | PK(id); no unique/FK | Dynamic world events. |
| log_entry | V1__baseline.sql:249; V29__log_entry_year_month_index.sql:15-16 | PK(id); indexes including (year,month,id); no FK | Append-only world log with local general/nation IDs. |
| auction (retired) | V1__baseline.sql:320; dropped V7:71-72 | Historical PK(id); no unique/FK | Raw audit only; former world auction table, not a V31 physical target. |
| auction_bid (retired) | V1__baseline.sql:338; dropped V7:71-72 | Historical PK(id); FK(auction_id)->auction(id) cascade | Raw audit only; replaced by ng_auction_bid. |
| board_post | V1__baseline.sql:352 | PK(id); no unique/FK | World board state. |
| board_comment | V1__baseline.sql:365 | PK(id); FK(post_id)->board_post(id) cascade; no unique | World child; later scoped FK must preserve parent-world equality. |
| vote_poll | V1__baseline.sql:377 | PK(id); no unique/FK | World vote state. |
| vote | V1__baseline.sql:393 | PK(id); FK(vote_id)->vote_poll(id) cascade; UNIQUE(vote_id,general_id) | World child; poll/general local IDs must share a world. |
| vote_comment | V1__baseline.sql:404 | PK(id); FK(vote_id)->vote_poll(id) cascade; no unique | World child; later FK must include matching world. |
| nation_env | V3__p2_nation_env.sql:16 | PK(id); UNIQUE(namespace,key); no FK | Nation-ID KV namespace is local world state. |
| message | V7__p6_messaging_economy.sql:24 | PK(id); indexes only; no FK | World mailbox/public/national/diplomacy data. |
| ng_betting | V7__p6_messaging_economy.sql:40 | PK(id); UNIQUE(general_id,betting_id,betting_type) and equivalent-order UNIQUE(betting_id,betting_type,general_id); no FK | World betting rows. |
| ng_auction | V7__p6_messaging_economy.sql:79 | PK(id); indexes only; no FK | World auction state. |
| ng_auction_bid | V7__p6_messaging_economy.sql:93 | PK(no); UNIQUE(general_id,auction_id,amount), UNIQUE(auction_id,amount); no FK | World auction child; missing auction FK is later design work, not permission for unscoped data. |
| statistic | V13__statistic_table.sql:13 | PK(id); no unique/FK | Monthly/annual world statistics. |
| select_pool | V23__select_pool.sql:1 | PK(id); UNIQUE(unique_name), UNIQUE(general_id); no FK | Game selection pool; general_id is local. |
| general_access_log | V25__general_access_log.sql:1 | PK(id); UNIQUE(general_id); no FK | Per-general game refresh/access state. |
| emperior | V27__unification_emperior.sql:1 | PK(id); non-unique index(server_id,id); no FK | World unification/dynasty archive; server_id remains noncanonical. |

## C2 account/cross-season/global candidates (8 raw CREATE entries)

| Table | Migration source | Current PK / unique / FK shape | Candidate boundary; not yet global allowlist |
|---|---|---|---|
| inheritance_point | V1__baseline.sql:280 | PK(id); UNIQUE(user_id,key); no FK | V1 explicitly groups it as cross-season; account balance policy still needs access evidence. |
| inheritance_log | V1__baseline.sql:291 | PK(id); no unique/FK | Cross-season account history candidate; user_id is unconstrained. |
| inheritance_result | V1__baseline.sql:301 | PK(id); index(server_id,owner); no unique/FK | Cross-season result candidate, but local general_id/server_id require a source-world provenance decision. |
| inheritance_user_state | V1__baseline.sql:313 | PK(user_id); no FK | Account-level continuation candidate. |
| users | V9__users_table.sql:1 | PK(id); UNIQUE(username), optional UNIQUE(email); no FK | Gateway account owner, not game-world state. |
| general_owner | V10__general_owner.sql:19 | PK(general_id); UNIQUE(user_id); deliberately no FK | Account-side possession mapping, but general_id is game-local; a future association may need canonical world_id. |
| system_flag | V11__admin_member_fields.sql:26 | PK(id); no unique/FK; migration seeds id=1 | Gateway/global join/login-policy candidate. |
| banned_member | V11__admin_member_fields.sql:41 | PK(id); UNIQUE(hashed_email); no FK | Gateway/global account-ban candidate. |

## C3 classification-blocked (3 raw CREATE entries)

| Table | Migration source | Current PK / unique / FK shape | Missing evidence; required decision |
|---|---|---|---|
| error_log | V1__baseline.sql:268 | PK(id); category index; no unique/FK | Opaque context cannot distinguish engine/world errors from gateway/global operations. Set retention, reader, and write-owner policy first. |
| game_kv | V7__p6_messaging_economy.sql:58 | PK(id); UNIQUE(table,namespace,key); no FK | Intentionally mixes world families (game_env, betting) and cross-season inheritance. Define row-family partition, provenance, and flush/query scope before adding world_id. |
| select_npc_token | V12__select_npc_token.sql:1 | PK(id); owner/expiry indexes; no unique/FK | Account owner plus opaque pick_result may contain local game choices. Current access is owner/time only; prove its world relationship before C1/C2. |

## V31 first TDD slice: exact and intentionally narrow

### Cohort

V31 is limited to five physical C1 tables:

1. nation: root local nation identity;
2. city: root local city identity;
3. general: root local general identity;
4. general_turn: representative child with a local business unique;
5. nation_turn: counterpart child with a local business unique.

world_state is the unchanged canonical anchor, not a sixth modified table. This is
the smallest cohort that exercises root rows plus both reserved-turn uniqueness
contracts. troop, diplomacy, logs, history, KV, messages, auctions, archives,
board/vote, and all C2/C3 relations are deferred. V31 does not claim the
full-table completion required by OPENSAM-126.

ScenarioImporter is the explicit bootstrap seeding path for this cohort. It
captures the positive id returned by its just-inserted world_state row and
explicitly writes that id into nation, city, general, general_turn, and
nation_turn; it does not assume that the inserted row has id 1. Runtime
loader/read/precheck/intake/flush writers remain outside this slice and require
a separate compatibility audit before V31 is deployed.

### Migration contract for V31__world_scope_expand.sql

1. At the beginning of the transactional migration, acquire
   `SHARE ROW EXCLUSIVE` on world_state so writer transactions cannot change
   canonical-world cardinality or identity between preflight and backfill.
2. With exactly one positive world_state id, use that locked id as the only
   backfill source. With zero worlds, scan every current physical C1 relation
   other than world_state and retired auction tables: an entirely empty set is
   a pristine schema-only expansion; any legacy row (including `ng_games`) is a
   deterministic error and rollback. More than one world, a non-positive id,
   or an orphaned/unresolvable cohort row also fails and rolls back. Never use
   MIN/MAX, ORDER BY ... LIMIT 1, profile, server_id, ng_games.id, or config.
3. Add `world_id integer` only to the five cohort tables. For the one-positive-
   world branch, backfill with the cardinality-sensitive scalar world_state.id
   subquery. For a pristine zero-world branch, no row receives an artificial
   identity, default, or backfill value.
4. Verify no cohort world_id is null, set NOT NULL, then add
   FK(world_id)->world_state(id) to each cohort table.
5. Install exactly these logical scoped key/index contracts:

| Table | V31 logical key/index contract |
|---|---|
| nation, city, general | Retain legacy PK(id) during expand; add UNIQUE(world_id,id) as future composite-reference/index surface. |
| general_turn | Replace local UNIQUE(general_id,turn_idx) with UNIQUE(world_id,general_id,turn_idx). |
| nation_turn | Replace local UNIQUE(nation_id,officer_level,turn_idx) with UNIQUE(world_id,nation_id,officer_level,turn_idx). |

6. Do not invent FKs for nation_id, city_id, troop_id, or other local columns:
   current DDL has no such FKs and uses zero/default sentinel semantics. Their
   null/sentinel/composite-FK policy belongs to later cohorts.

Retaining root PK(id) means V31 does not admit two worlds with equal local IDs.
The new unique surfaces are forward-compatible only. No runtime trigger/default,
Redis key change, loader/read/precheck/intake/flush change, or old-binary
compatibility claim is part of this slice.

### TDD evidence written red before the SQL

| Test case | Fixture | Required assertion |
|---|---|---|
| pristine empty schema expands without a placeholder world | No world_state and no C1 rows | V31 applies; all five world_id columns are INTEGER NOT NULL with no default. |
| single world row backfills only canonical id | One world_state(id=701), rows in all five cohort tables | Every row has world_id=701; all five FKs and listed scoped keys exist. |
| zero worlds plus legacy data fail closed without server fallback | No world_state, but legacy ng_games.server_id/profile fixture | V31 fails; no legacy identifier becomes world_id; transactional changes rollback. |
| zero worlds plus an orphaned cohort row fail closed | No world_state, but a nation/city/general/general_turn/nation_turn row | V31 fails and rolls back rather than assigning a synthetic world. |
| multiple world rows fail closed without arbitrary selection | world_state ids 701 and 702 plus cohort data | V31 fails before backfill; it chooses neither row. |
| non-positive canonical source fails closed | Exactly one deliberately non-positive world_state.id fixture | V31 fails under positive-WorldId contract. |
| scoped turn uniqueness is enforced | Successful one-world migration plus duplicate/near-duplicate turn rows | New world-qualified business unique governs; old unqualified turn unique does not. |
| bootstrap importer propagates its inserted canonical id | V31 schema plus ScenarioImporter fixture | Every row in all five cohort tables has the returned world_state id. |

Fresh migration/rehearsal for every remaining relation and two-world
same-local-ID isolation are later S2 work (CQRS plan:156-183), not V31 evidence.

## V32 final ownership partition

V32 resolves all current physical tables. The partition is exhaustive and
disjoint:

| Ownership | Count | Relations |
|---|---:|---|
| Canonical anchor | 1 | `world_state` |
| Strict world-owned | 33 | `nation`, `city`, `general`, `general_turn`, `nation_turn`, `troop`, `diplomacy`, `diplomacy_letter`, `rank_data`, `hall`, `ng_games`, `ng_old_nations`, `ng_old_generals`, `yearbook_history`, `event`, `log_entry`, `board_post`, `board_comment`, `vote_poll`, `vote`, `vote_comment`, `nation_env`, `message`, `ng_betting`, `ng_auction`, `ng_auction_bid`, `statistic`, `select_pool`, `general_access_log`, `emperior`, `general_owner`, `inheritance_result`, `select_npc_token` |
| Mixed row families | 1 | `game_kv` |
| Explicit global allowlist | 7 | `inheritance_point`, `inheritance_log`, `inheritance_user_state`, `users`, `system_flag`, `banned_member`, `error_log` |

That is all 42 current physical tables. The raw CREATE inventory remains 44
because the retired V1 `auction` and `auction_bid` definitions are still
audited even though V7 drops them.

`general_owner` is world-owned because `general_id` is a live local general
identity. Its identity is `(world_id,general_id)` and its user exclusivity is
`(world_id,user_id)`. `inheritance_result` records the source world's local
general and server coordinates, so survival across a season does not make it
global. `select_npc_token.pick_result` carries local general choices and the
token therefore belongs to the world that produced the pool.

### Admission and backfill

V32 acquires one `SHARE ROW EXCLUSIVE` lock statement in the documented
relation order: the anchor, all 33 strict relations, then `game_kv`. It then
applies these fail-closed branches:

1. Zero `world_state` rows are accepted only when all 33 strict relations are
   empty and `game_kv` contains only the global `inheritance` family. The seven
   allowlisted global relations may contain rows.
2. Exactly one positive `world_state.id` backfills every strict legacy row and
   every non-`inheritance` `game_kv` row.
3. A strict legacy row with zero worlds, a non-`inheritance` KV row with zero
   worlds, multiple worlds, or a single non-positive world fails the
   transactional migration and rolls back all V32 DDL.

`world_state` gains `CHECK (id > 0)`. Every strict relation has
`world_id INTEGER NOT NULL` with no default and an FK to `world_state(id)`.
`game_kv.world_id` is nullable only for its global family and still has the
same FK when present. No profile, server, `ng_games.id`, MIN/MAX, or configured
fallback participates in resolution.

ADR-LITE-016 selected the strict schema plus the affected writer stack. The
earlier S2-T1 compatibility-trigger alternative is rejected: V32 creates no
world-id default, function, or trigger, and an INSERT that omits `world_id`
fails. Second-world admission therefore cannot depend on an implicit singleton
world and remains behind the loader/query/writer boot fence in OPENSAM-127 and
OPENSAM-128.

### Key, FK, and index matrix

Serial and bigserial sequence/default expressions remain intact. Their
generated numeric values are surrogates, but every strict table's relational
identity is world-qualified.

| Relations | V32 primary/business-key contract |
|---|---|
| `nation`, `city`, `general`, `general_turn`, `nation_turn` | PK `(world_id,id)`; the V31 turn business uniques remain world-leading. |
| `troop` | PK `(world_id,troop_leader)`; index `(world_id,nation,troop_leader)`. |
| `general_owner` | PK `(world_id,general_id)`; unique `(world_id,user_id)`; the redundant unscoped user index is removed. |
| `ng_auction_bid` | PK `(world_id,no)`; both legacy auction uniques gain leading `world_id`. |
| All other strict relations | PK `(world_id,id)`; every existing business unique gains leading `world_id`. `yearbook_history`, `event`, `log_entry`, `board_post`, `board_comment`, `vote_poll`, `vote_comment`, `statistic`, `inheritance_result`, `select_npc_token`, and `emperior` receive no invented business unique. |

All explicit access indexes on strict relations are rebuilt with `world_id` as
their first column. New index surfaces are
`troop(world_id,nation,troop_leader)`,
`diplomacy(world_id,dest_nation_id,src_nation_id)`, and
`statistic(world_id,year,month,id)`. This also supplies a world-leading index
for every strict world FK; PostgreSQL does not create child-FK indexes
automatically.

The only new live-root FKs are:

- `general_turn(world_id,general_id)` and
  `troop(world_id,troop_leader)` to `general(world_id,id)`;
- `nation_turn(world_id,nation_id)`, `troop(world_id,nation)`, and both
  `diplomacy` nation columns to `nation(world_id,id)`;
- nullable `diplomacy_letter(world_id,prev_id)` to its own composite PK.

Those constraints are `DEFERRABLE INITIALLY DEFERRED` so one flush transaction
may preserve its established statement order while commit still rejects an
orphan or cross-world reference. V32 converts the existing board/vote parent
FKs to `(world_id,parent_id)` with their existing cascade semantics and adds
`ng_auction_bid(world_id,auction_id)` to `ng_auction(world_id,id)`. Historical
general/nation coordinates, sentinel-capable columns, polymorphic message/KV
coordinates, `rank_data`, `select_pool`, `general_access_log`,
`general_owner`, `inheritance_result`, and `select_npc_token` deliberately gain
no live-root FK.

### Mixed `game_kv` contract

`game_kv` keeps its global serial surrogate PK because its global rows cannot
participate in a non-null composite PK. Row ownership is enforced by
`game_kv_world_ownership_check`:

```sql
("table" = 'inheritance' AND world_id IS NULL)
OR ("table" <> 'inheritance' AND world_id IS NOT NULL)
```

The former table-wide unique is replaced by two inference-compatible partial
unique indexes:

- `game_kv_inheritance_global_key_uq` on
  `("table",namespace,key)` where
  `"table"='inheritance' AND world_id IS NULL`;
- `game_kv_world_key_uq` on
  `(world_id,"table",namespace,key)` where
  `"table"<>'inheritance' AND world_id IS NOT NULL`.

Runtime `ON CONFLICT` predicates must use those literal predicates. The global
inheritance family cannot be given a world, non-inheritance families cannot
omit one, the global key remains unique, and identical scoped keys may coexist
in different worlds.

### V32 acceptance evidence

`V32WorldScopeCompletionMigrationTest` fixes these GWT boundaries:

- the fresh 42-table partition is exactly 33 strict + one mixed + one anchor +
  seven global, with no unclassified unscoped relation;
- one representative row in each of the 28 newly strict relations plus both
  `game_kv` families is migrated from V31, while the five V31 relations remain
  correctly scoped;
- zero-world global-only plus inheritance-KV succeeds; representative C1,
  reclassified, and non-inheritance-KV rows fail and roll back;
- multiple and non-positive anchors fail and roll back;
- a forced failure after key promotion restores V31 columns, keys, data, and
  Flyway history transactionally;
- equal nation/city/general/turn/troop local identities coexist in two worlds,
  while composite children cannot reference a parent in another world;
- scoped uniques/FKs, the five ownership/uniqueness sides of `game_kv`, fixed
  lock order, retained serial sequence defaults, no `world_id` defaults, and
  no triggers are catalog-verified.

A production-shaped sanitized legacy dump is not present in this repository.
Its rehearsal is `채점대기`; it is not inferred from synthetic fixtures and is
still required before production migration/cutover.

## Explicit out of scope

- second-world admission and production cutover (the schema-level same-local-ID
  contract is covered, but admission remains fenced);
- runtime loader/read/precheck/intake/Redis/JdbcFlushExecutor scoping;
- W3 durable inbox/outbox activation, fenced flush/CAS activation, production
  migration, or deployment;
- changing OPENSAM-43's approved broad V2 scope/status.

This follows the approved sequence: OPENSAM-148 blocks OPENSAM-126 while
OPENSAM-43 stays open, and W3/production cutover remain activation gates
(CQRS plan:359-384).

## Verification

The normalized source/document name sets are checked with:

~~~bash
diff -u \
  <(rg --no-filename -N '^CREATE TABLE(?: IF NOT EXISTS)? [a-z_][a-z0-9_]*' --glob 'V*.sql' infra/src/main/resources/db/migration \
      | sed -E 's/^CREATE TABLE( IF NOT EXISTS)? ([a-z_][a-z0-9_]*).*/\2/' | sort -u) \
  <(rg -N '^\| [a-z_][a-z0-9_]*( \(retired\))? \|' docs/superpowers/research/2026-07-19-opensam-126-world-table-inventory.md \
      | sed -E 's/^\| ([a-z_][a-z0-9_]*).*/\1/' | sort -u)
~~~

Observed result: exit 0; source names 44, documented names 44, source-only 0,
document-only 0. Retired auction and auction_bid are included on both sides.

Owned-file whitespace verification:

~~~bash
git diff --check -- docs/superpowers/research/2026-07-19-opensam-126-world-table-inventory.md
~~~

Observed result: exit 0.

Focused Java 21/Testcontainers verification:

~~~bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon \
  :infra:test \
  --tests opensamguk.infra.persistence.V31WorldScopeExpandMigrationTest \
  --tests opensamguk.infra.persistence.V32WorldScopeCompletionMigrationTest \
  --rerun-tasks --console=plain
~~~

Observed XML results after the V31 helper was pinned to Flyway target 31:

- `V31WorldScopeExpandMigrationTest`: tests 10, failures 0, errors 0,
  skipped 0;
- `V32WorldScopeCompletionMigrationTest`: tests 9, failures 0, errors 0,
  skipped 0.

The initial paired run exposed that the historical V31 test helper had no
target and therefore advanced through V32. Pinning that helper to version 31
fixed the test boundary; the fresh rerun above is authoritative.
