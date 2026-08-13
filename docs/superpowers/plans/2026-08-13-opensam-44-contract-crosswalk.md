# OPENSAM-44 v2 persistence contract crosswalk

Date: 2026-08-13
Scope: contract reconciliation only; no code, schema, migration, mapper, flush, read API, or runtime change
Status: approved for merge; effective when this PR is merged

## Approval and supersession record

The user explicitly authorized merging the reviewed open-PR queue, including this OPENSAM-44
reconciliation, on 2026-08-13 (`"병합, 배포하고. 남은거 처리하자."`). That approval makes the
contract change below an accepted supersession rather than an unapproved proposal. This task-local
plan is the durable OPENSAM-44 execution contract for the PR. The shared `.ai/task.md` continues to
describe the separate OPENSAM-43 lane and is not co-written from this parallel worktree; its later
fan-in synchronization must preserve this approved decision rather than restore the broad T1 batch.

## Outcome

OPENSAM-44 is a **contract and ownership decomposition gate**, not the implementation of every
`04-systems-micro.md` `[아키]` row. Its old literal "T1 전체 영속화 일괄" reading is superseded.

- OPENSAM-43 already closed the V2-0B runtime boundary: sibling Flyway location, V900+ convention,
  test-only `V900__v2_sandbox_probe.sql`, gated catalog/runtime adapter, and v1 non-application checks.
- OPENSAM-44 adds no production SQL and claims no persisted v2 product model.
- OPENSAM-150 remains the first product persistence leaf and owns
  `infra/src/main/resources/db/migration_v2/V901__v2_city_ledger.sql`.
- OPENSAM-150 also owns the v2 scenario-seeding **mechanism seam**: it must prove migration-before-
  seed ordering and that the configured v2 scenario source can be loaded into the v2 database.
  OPENSAM-151 owns the first v2 scenario **content** that consumes that seam: the tracked scenario
  JSON, `ignoreDefaultEvents: true`, its complete scenario-authored event set, action registration,
  and seed/reseed acceptance evidence.
- Every remaining T1 `[아키]` item is implemented just in time by the product slice that owns its
  prerequisite model and observable behavior. It must be split into schema, daemon write, and read
  deliverables where those concerns do not land atomically.

This interpretation follows ADR-LITE-019 (G0 and its 1,180-place work moved post-open),
ADR-LITE-029 (the first real v2 leaf belongs to OPENSAM-150), and the completed OPENSAM-43 contract
(`docs/superpowers/plans/2026-08-09-opensam-43-v2-0b-runtime-contract-plan.md`).

## Migration numbering boundary

| Version | Owner | Meaning |
|---|---|---|
| `V900` | OPENSAM-43 tests only | Isolation probe under `app/game-engine/src/test/resources/db/migration_v2/`; never product content and never copied to the production migration location. |
| `V901` | OPENSAM-150 | First production v2 migration: `v2_city_ledger`. It must satisfy the OP43 world-scope, forward-only, sibling-location, and v1 non-application contract. |
| `V902+` | Future owning product slices | Allocated only when a prerequisite model and a concrete write/read consumer are ready; no number is pre-consumed by OPENSAM-44. |

Production `infra/src/main/resources/db/migration_v2/` therefore remains SQL-empty after OPENSAM-44.
The test-only V900 probe does not consume the first product migration number.

## V2 scenario-seeding ownership

The earlier R1-R6 design said the seed mechanism already belonged to OPENSAM-43/44. That statement
is superseded together with the broad OP44 implementation contract. Ownership is now explicit:

| Owner | Required seam/deliverable |
|---|---|
| OPENSAM-150 (R1) | Establish and test the product seed integration seam: Flyway `V901` completes before seed consumption; the configured v2 `SCENARIO_CODE`/`SCENARIO_DIR` source is resolved and its event rows can be persisted in the isolated v2 database. It does not author the R2 event payload. |
| OPENSAM-151 (R2) | Author the v2 scenario JSON with `ignoreDefaultEvents: true`, the complete scenario-derived event rows, `V2ProcessCityIncome` registration, and seed/reseed assertions including no `ProcessIncome` row. |
| OPENSAM-152 (R3) | Consume the R2-owned scenario file only after R2 lands; it does not open a second seeding mechanism or edit that shared file in parallel. |

Thus the removal of product SQL from OPENSAM-44 leaves no ownerless path between the scenario input,
database `event` rows, and the R2/R3 runtime consumers.

## Shared flush handoff supersession

The active shared-flush handoff, CQRS failure contract, and memory-consistency hardening plan formerly
named OPENSAM-44 as the implementation consumer. This crosswalk retargets that routing without
changing the foundation contract: OPENSAM-150 is the first consumer, and each later mapper/flush
extension belongs to the just-in-time product ticket that owns the accepted model and observable
mutation. OPENSAM-44 remains documentation-only and cannot receive shared-flush implementation work.

## Checklist crosswalk

`Source row` is preserved for traceability. `Disposition` replaces the old assumption that all rows
must be implemented together in V2-0B.

| Source row(s) | Old literal reading | Disposition after reconciliation |
|---|---|---|
| T1-A02/A03 EvidenceRef | migrate and flush in OP44 | Defer until the first content/product slice that persists evidence after its model contract is accepted. Split migration from daemon mutation; a read-only catalog may need neither. |
| T1-A07/A08 HistoricalClaim | migrate and flush in OP44 | Defer with EvidenceRef. Historical catalog ingestion is not automatically a daemon-mutable write channel. |
| T1-A14 CatalogBudget/Slot | migrate in OP44 | Defer to V2-C0/C-track ownership after budget/slot transaction semantics exist. Do not pre-create empty product tables. |
| T1-B02/B03/B04 TemporalAdministrativeUnit | migration, mapper/flush, and read API in OP44 | Defer to post-open G0-A. Split schema, mutation path, and read surface; only add a ChangeRecorder channel if runtime behavior actually mutates the model. |
| T1-B09 PhysicalPlace | three persistence surfaces in OP44 | Defer to post-open G0-A/G0-C and its first consuming slice. Preserve identity and uncertainty contracts before persistence. |
| T1-B14 SeatAssignment | migrate and flush in OP44 | Defer to post-open G0-A after the overlap validator and co-location contract are accepted. |
| T1-B16 PlaceControl | migrate, flush, and recorder channel in OP44 | Defer to the first ownership/occupation product slice after G0-A. The consuming command must prove the recorder-to-flush path. |
| T1-C05 Facility/ResourceNode/ResourceSite | three migrations in OP44 | Split by entity and defer to the product slices that introduce inventory, income, replenishment, or transport. OP150's city ledger does not silently absorb these models. |
| T1-C06 ResourceNode | mapper, flush, and recorder channel in OP44 | Defer to the first concrete resource mutation slice. Require ledger invariants and same-transaction flush evidence there. |
| T1-C08 Formation | migrate and flush in OP44 | Defer to OPENSAM-48/V2-2 or its explicitly split successor after the Formation contract is fixed. |
| T1-D02 FormationTemplate | migrate and flush in OP44 | Defer to V2-C0/C-track. A template catalog is not presumed daemon-mutable. |
| T1-F09/F10 polity models | migrate eight models and flush two in OP44 | Defer to post-open G0-B, split per accepted model and consumer. No empty eight-table batch. |
| T1-G04 RouteCorridor/TerrainRegion | migrate in OP44 | Defer to post-open G0-C or the first route/terrain product consumer. |
| T1-I08 sandbox load/runtime repeat | persist the full 1,180-place G0 model in OP44 | Split. OP43 already owns and completed the 94/24 pinned-city typed adapter/repeat-diff contract. The 1,180-place `CountyParticipationFixture` and any matching persistence remain post-open G0 under ADR-LITE-019. |

T2 imperial/court persistence remains outside OPENSAM-44 and is still assigned only when the V2-7
product slices define its lifecycle and consumers.

## Just-in-time persistence admission contract

A future owner may promote one deferred row only when its ticket states all of the following:

1. Accepted prerequisite model/validator and concrete product behavior.
2. Exact production migration filename/version (`V901` for OP150, then the next unused `V902+`).
3. `world_id NOT NULL`, world-scoped primary/unique keys, and the OP43 foreign-key convention.
4. Forward-only change with a new compensating migration for rollback.
5. Daemon mutation through `ChangeRecorder -> JdbcFlushExecutor` in the existing transaction; no
   JPA daemon write and no second dirty truth.
6. Separate read contract and explicit world/profile scope when a read surface exists.
7. v1 default/production applies zero v2 migration, bean, and content; v2 sandbox applies only the
   explicitly enabled sibling location.
8. Focused tests, relevant full gate, and independent review evidence. Empty tables or interfaces are
   not product leaves and do not satisfy the ticket.

## OPENSAM-44 completion contract

OPENSAM-44 is complete when:

- this crosswalk and the backlog supersession pointers are merged;
- GitHub issue #186 uses the reconciled wording and links this document;
- the production v2 migration directory contains no SQL;
- `V900` exists only as the OP43 test probe and OPENSAM-150 is named as the first product migration
  owner at `V901`;
- documentation verification and an independent architecture review are `cleared`.

No implementation gate, product leaf, deploy, cutover, or database mutation is implied by this
documentation-only completion.

## GitHub issue #186 replacement wording

Title:

```text
[OPENSAM-44] [아키] v2 영속화 계약 분해 — broad T1 일괄 구현 supersede
```

Body:

```md
목표: 과거의 "T1 전체 영속화 일괄" 계약을 실제 제품 소비자 기준의 just-in-time 영속화 계약으로 재분해한다. 이 티켓은 문서/소유권 정정만 수행하며 코드·스키마·마이그레이션·mapper·flush·read API를 구현하지 않는다.

정본 crosswalk: `docs/superpowers/plans/2026-08-13-opensam-44-contract-crosswalk.md`

확정 경계:
- OPENSAM-43은 V2-0B runtime/isolation 계약을 test-only `V900__v2_sandbox_probe.sql`로 닫았다. V900은 제품 migration이 아니다.
- OPENSAM-44 이후에도 production `infra/src/main/resources/db/migration_v2/`에는 SQL이 0개다.
- OPENSAM-150이 첫 제품 leaf `v2_city_ledger`와 첫 production migration `V901__v2_city_ledger.sql`을 소유한다.
- OPENSAM-150은 migration-before-seed 순서와 configured v2 scenario source→v2 DB `event` 적재 seam도 개설·실증한다.
- OPENSAM-151은 그 seam을 소비하는 v2 시나리오 JSON, `ignoreDefaultEvents: true`, 시나리오 유래 event 전량, action 등록, seed/reseed 판정을 소유한다.
- 기존 OPENSAM-128 shared-flush handoff의 첫 구현 소비자는 OPENSAM-150이며, 이후 mapper/flush 확장은 실제 mutation을 소유한 just-in-time product ticket이 소비한다. OPENSAM-44는 shared-flush 구현을 소유하지 않는다.
- 이후 migration은 선행 모델/validator와 실제 write/read 소비자가 준비된 제품 티켓이 `V902+`를 순서대로 소유한다.
- daemon mutation은 계속 `ChangeRecorder -> JdbcFlushExecutor` 단일 경로이며, read-only catalog에 불필요한 write channel을 선설치하지 않는다.
- v1 default/production에는 v2 migration/bean/content가 0개여야 한다.

기존 체크리스트 disposition:
- EvidenceRef, HistoricalClaim, CatalogBudget/Slot: 첫 실제 content consumer 또는 V2-C0/C-track으로 이관.
- TemporalAdministrativeUnit, PhysicalPlace, SeatAssignment, PlaceControl: post-open G0-A/G0-C와 첫 점령 consumer로 이관.
- Facility, ResourceNode, ResourceSite, Formation: inventory/income/replenishment/transport 및 OPENSAM-48 계열 consumer로 분리 이관.
- FormationTemplate: V2-C0/C-track으로 이관.
- polity 8모델, TerritorialPresence/SeasonalRange: post-open G0-B로 이관.
- RouteCorridor/TerrainRegion: post-open G0-C 또는 첫 route/terrain consumer로 이관.
- T1-I08: OPENSAM-43의 94/24 pinned-city repeat-diff 계약과 post-open G0의 1,180-place CountyParticipationFixture를 분리한다.
- T2 황실·관직 영속화는 V2-7 consumer가 lifecycle을 확정할 때 별도 소유한다.

근거:
- ADR-LITE-019: G0·1,180·C-track은 post-open으로 이동.
- ADR-LITE-029: 실제 첫 v2 leaf는 OPENSAM-150에서 증명.
- OPENSAM-43: sibling Flyway location, world scope, forward-only, v1 non-application 계약 완료.

DoD:
- [ ] crosswalk와 stale backlog pointer가 merged
- [ ] production v2 migration SQL 0개
- [ ] OPENSAM-150 = `V901` 첫 제품 migration owner 명시
- [ ] docs verify/strict/diff green
- [ ] independent architecture review `cleared`

비범위: 코드/스키마/migration 구현, product leaf, deploy/cutover, DB mutation, shared `.ai` 변경.

---
- Jira: [OPENSAM-44](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-44)
- 에픽: #160 ([OPENSAM-18](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-18))

[OPENSAM-44]: https://pepponechoi-jira.atlassian.net/browse/OPENSAM-44
[OPENSAM-18]: https://pepponechoi-jira.atlassian.net/browse/OPENSAM-18
```

Comment after the issue edit:

```md
2026-08-13 계약 정정: 기존 broad T1 persistence 일괄 구현 문구는 supersede했습니다. OPENSAM-44는 문서/소유권 crosswalk만 닫고 제품 SQL을 추가하지 않습니다. OPENSAM-43의 V900은 test-only isolation probe로 유지하며, OPENSAM-150이 `V901__v2_city_ledger.sql`과 첫 실제 v2 leaf, migration-before-seed/source→DB 적재 seam, OPENSAM-128 shared-flush handoff의 첫 구현 소비를 소유합니다. OPENSAM-151은 seed seam을 소비하는 v2 scenario event 저작·등록·재시드 판정을 소유합니다. 이후 shared-flush mapper/step은 실제 mutation을 소유한 just-in-time product ticket만 소비합니다. 나머지 T1 `[아키]` 항목은 선행 모델과 실제 소비자가 준비된 제품 티켓으로 이관했습니다.

정본: `docs/superpowers/plans/2026-08-13-opensam-44-contract-crosswalk.md`
```
