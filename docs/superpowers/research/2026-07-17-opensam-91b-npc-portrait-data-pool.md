# OPENSAM-91b NPC portrait/data pool source and allowlist contract

- **Status:** `DONE_WITH_CONCERNS`
- **Contract state:** A1-approved policy made auditable here; A2 source/security review remains pending
- **Scope:** source inventory, catalog schema, eligibility rules, and a build-time allowlist interface only
- **Not authorized:** source-table copying, portrait download/bundling, gameplay roster activation, seed changes, Jira/GitHub writes, commit/push/PR/deploy
- **Evidence date:** 2026-07-17

## 1. Outcome

The user-provided WIKIWIKI source is useful as a bounded **discovery lead**, but it is not currently an ingestible dataset or portrait source.

- `[사실]` The inspected WIKIWIKI terms do not provide an observed public redistribution grant to opensamguk or general downstream users.
- `[사실]` No portrait binary was downloaded, hashed, copied, embedded, or hotlinked during the source reconnaissance or this contract pass.
- `[사실]` The approved OPENSAM-91 A1 policy admits only provenance-cleared data/assets to a runtime allowlist or repository bundle.
- `[판정]` Therefore the currently eligible WIKIWIKI-derived data-row count is **0**, and the currently eligible WIKIWIKI-derived portrait-asset count is **0**. This is a present clearance result, not a permanent target count.
- `[판정]` The future coverage target is **all provenance-cleared rows**, reconciled into unique canonical identities. It is not `1,000`, `1,127`, `1,177`, `3,000`, or any other invented fixed count.
- `[판정]` Research metadata may record page-level evidence and hashes. Raw tables, prose, source portraits, and derived portraits remain unbundled.

The result is `DONE_WITH_CONCERNS`, rather than unqualified `DONE`, because rights clearance blocks ingestion. The catalog/allowlist contract itself is complete and can be consumed by OPENSAM-91a once a cleared input set and cleared fallback exist.

## 2. Evidence and method

### 2.1 User source and source-brief integrity

- User-provided source: <https://wikiwiki.jp/sangokushi14/>
- Bounded source brief: `/tmp/opensam-wiki-source-brief.md`
- Required and observed SHA-256: `5330f2d494d39c112405797f6e980c2801d9644bba29a06e41bef87480d83f08`
- Brief access window: `2026-07-17T02:42:10Z` through `2026-07-17T02:48:11Z`
- Brief posture: read-only representative-page reconnaissance; no sitemap crawl, full officer crawl, asset fetch, or external write

Integrity command:

```bash
shasum -a 256 /tmp/opensam-wiki-source-brief.md
```

Observed output:

```text
5330f2d494d39c112405797f6e980c2801d9644bba29a06e41bef87480d83f08  /tmp/opensam-wiki-source-brief.md
```

No additional internet request was necessary for this pass. The provided brief already contained the decision-changing terms, rules, count observations, and direct canonical page URLs. Avoiding another request also respects the observed Cloudflare `403`, the site's load rules, and the bounded-reconnaissance instruction.

### 2.2 Direct source inventory and count semantics

The following is an inventory of page-level observations only. It does not copy source rows.

| Dataset ID | Direct URL | Brief observation | Evidence class | OPENSAM-91b treatment |
|---|---|---|---|---|
| `wikiwiki-frontpage` | <https://wikiwiki.jp/sangokushi14/> | Community-edited index and independent-verification caveat | observed | Discovery only; not an official roster oracle |
| `rtk14-cities` | <https://wikiwiki.jp/sangokushi14/%E9%83%BD%E5%B8%82> | **46 cities, 338 regions** | observed | Geography coverage control; not an NPC row count |
| `rtk14-geography` | <https://wikiwiki.jp/sangokushi14/%E5%9C%B0%E7%90%86> | 46 base-city placements and 5 added ethnic strongholds described; no numeric coordinate table observed | observed | Excluded from 91b; route to map/city contracts |
| `rtk14-region-income` | <https://wikiwiki.jp/sangokushi14/%E5%9C%B0%E5%9F%9F%E5%8F%8E%E5%85%A5> | City totals and region rows | observed | Excluded from 91b; no raw rows copied |
| `rtk14-historical-base` | <https://wikiwiki.jp/sangokushi14/%E5%8F%B2%E5%AE%9F%E6%AD%A6%E5%B0%86> | **1,000 rows** in the observed base sequence | observed | Candidate historical source version, rights-blocked |
| `rtk14-historical-pk` | <https://wikiwiki.jp/sangokushi14/%E5%8F%B2%E5%AE%9F%E6%AD%A6%E5%B0%86%28PK%29> | **1,000 rows**, same apparent identity span with PK variants | observed | A version of base identities, not another 1,000 identities |
| `rtk14-historical-sortable` | <https://wikiwiki.jp/sangokushi14/%E5%8F%B2%E5%AE%9F%E6%AD%A6%E5%B0%86%28%E3%82%BD%E3%83%BC%E3%83%88%E5%8F%AF%29> | **1,000 rows**, explicitly derived and older | observed | Parsing/reconciliation candidate, not additive identities |
| `rtk14-officer-detail-sample` | <https://wikiwiki.jp/sangokushi14/%E5%91%82%E5%B8%83> | One detail page with a portrait and enrichment fields | observed sample | Does not prove 1,000-page or portrait coverage |
| `rtk14-bonus` | <https://wikiwiki.jp/sangokushi14/%E3%81%8A%E3%81%BE%E3%81%91%E6%AD%A6%E5%B0%86> | **127 rows** across ancient, external-work, collaboration, PK, and reward groups | observed | Separate optional class; third-party IP and repeated-name risk |
| `rtk14-ethnic` | <https://wikiwiki.jp/sangokushi14/%E7%95%B0%E6%B0%91%E6%97%8F> | **5 factions** | observed | Separate faction/NPC class |
| `rtk14-ethnic-joinable` | <https://wikiwiki.jp/sangokushi14/%E7%95%B0%E6%B0%91%E6%97%8F> | **50 joinable rows = 5 factions × 10** | arithmetic inference | Never present 50 as a completed row audit |
| `rtk14-ethnic-xianbei-sample` | <https://wikiwiki.jp/sangokushi14/%E7%95%B0%E6%B0%91%E6%97%8F%E3%83%BB%E9%AE%AE%E5%8D%91> | 10 garrison-template rows, 10 dispatchable rows, and 3 portrait archetypes on one sampled faction page | observed sample | Schema/share-pattern evidence only; four factions not crawled |

Count interpretation is load-bearing:

1. `46 cities` and `338 regions` are observed geography counts, not NPC identities.
2. The base, PK, and sortable tables each showed 1,000 rows, but their apparent identity span overlaps. They are three source versions of a candidate identity set, not 3,000 approved identities.
3. The 127 bonus rows remain a separate source class. A repeated name or historical reference does not make a row an automatic duplicate or an automatic new identity.
4. Five ethnic factions are observed. Fifty joinable NPC rows are an inference from `5 × 10`, not a completed observation of all five detail pages.
5. No unique portrait count follows from any row count. The sampled ethnic page demonstrates that multiple NPCs may share a portrait archetype.

### 2.3 User-provided source addendum and lane mapping

The user additionally fixed the following exact sources and roles. This is a source-routing contract, not permission to crawl, copy tables, fetch an attachment, or ingest coordinates.

| User-provided source | Exact URL | Required role | 91b boundary |
|---|---|---|---|
| 都市 | <https://wikiwiki.jp/sangokushi14/%E9%83%BD%E5%B8%82> | City master/count cross-reference | Use page-level provenance and later approved external IDs only; do not copy city rows into the NPC catalog |
| 地理 | <https://wikiwiki.jp/sangokushi14/%E5%9C%B0%E7%90%86> | Its city-detail links identify strongpoint name, strongpoint type, and parent city | Treat those fields as OPENSAM-102/105 source leads; 91b may later reference a cleared ledger, not parse or duplicate the page corpus |
| PK coordinate image | `https://cdn.wikiwiki.jp/to/w/sangokushi14/地理/::attach/PK.png?rev=ab2f8e0ceae2aeb16f3291ffa88907d0&t=20201213195804` | Coordinate-only source | Evidence locator only: do not fetch, embed, hotlink, trace, OCR, crop, bundle, or derive coordinates in 91b |
| 異民族 | <https://wikiwiki.jp/sangokushi14/%E7%95%B0%E6%B0%91%E6%97%8F> | Required foreign-tribe NPC/catalog cross-reference | Reconcile faction/NPC source aliases against a later cleared strongpoint ledger; do not promote inferred rows to audited identities |

PK locator provenance must preserve the acquired resource separately from the renderer locator:

- User-requested locator: `https://cdn.wikiwiki.jp/to/w/sangokushi14/地理/::attach/PK.png?rev=ab2f8e0ceae2aeb16f3291ffa88907d0&t=20201213195804`
- Acquired locator recorded by the OPENSAM-102 report/CSV: the same exact `::attach/PK.png` URL above.
- Acquired media and SHA-256: PNG bytes, `dfe5049245e730ff1f81325157dedfcf1079786e33c6ad8b3970140954910a89`. This hash belongs only to those actually acquired PNG bytes.
- Separate discovery/renderer locator: `https://cdn.wikiwiki.jp/to/w/sangokushi14/%E5%9C%B0%E7%90%86/%3A%3Aref/PK.png.webp?rev=ab2f8e0ceae2aeb16f3291ffa88907d0&t=20201213195804`. It is not the acquired locator, and this contract makes no byte-equivalence or hash attribution claim for it.

The OPENSAM-102 coordinate ledger is the only coordinate/topology interface that 91b may consume later. Consumption remains blocked until that ledger records source fingerprints, observation versus inference, stable strongpoint IDs, and rights/redistribution clearance. The image URL itself is never a runtime field or a substitute for that ledger.

All four sources remain under the same rights gate as the rest of this contract. No raw image, full table, full row set, or extracted coordinate set is copied here. Public visibility and a direct URL do not establish redistribution or derivative-work permission.

### 2.4 Rights sources

- WIKIWIKI terms: <https://wikiwiki.jp/pp/policies>
- WIKIWIKI service rules: <https://wikiwiki.jp/pp/rules>
- Robots policy observed by the source brief: <https://wikiwiki.jp/robots.txt>

`[사실]` The brief reports that the terms assign service IP to WIKIWIKI or licensors and grant poster rights to WIKIWIKI and parties it permits. It did not observe a grant to opensamguk or the public. The rules also warn about third-party infringement, excessive/repeated requests, and direct-URL display/hotlinking.

`[판정]` Robots permission, page visibility, factual subject matter, or a `rev=` attachment fingerprint is not a copyright, database-right, derivative-work, or redistribution license. A source URL and displayed modification string establish provenance; they do not establish reuse permission.

## 3. Scope boundary and dependent work

This wave creates only a catalog/source contract and the shape of a generated shared-icon allowlist.

```text
OPENSAM-91b now
  source inventory -> clearance decisions -> canonical catalog -> 91a shared-icon allowlist

gameplay activation later
  OPENSAM-96 source/IP clearance
    + OPENSAM-103 roster/spec
    + OPENSAM-98 stable keying
    + OPENSAM-105 city contract
      -> OPENSAM-104/#247 first-roster builder
        -> explicit license + seed + parity approval
```

- OPENSAM-96/#239 is a portrait/stat sourcing input. The local execution contract mentions approximately 1,039 RTK14 and approximately 900 RTK8R candidates, but those figures are not 91b expected counts and OPENSAM-96 completion is not assumed.
- OPENSAM-104/#247 is the downstream builder for a planned first 152-member scenario roster. `152` is a later builder scope, not a catalog target and not authorized for activation here.
- OPENSAM-95/#237 concerns a single existing icon/individual portrait item. It does not duplicate broad source-catalog expansion.
- OPENSAM-100/#243 concerns CDN/serving. It does not duplicate subject identity, data provenance, or roster work.
- No OPENSAM-91b-specific duplicate ticket exists; 91b remains under the OPENSAM-91 umbrella.
- ADR-LITE-010's approved product direction to replace devsam content does not itself grant third-party redistribution rights. The later A1-6 and A1-8 clearance gates still control this artifact.

No generated output from 91b may be imported by a scenario seed, `ScenarioImporter`, game-engine world loader, AI selection, command resolver, or PHP parity fixture in this wave.

## 4. Coverage and eligibility policy

### 4.1 Required sets

The generator and coverage report must keep these sets distinct:

- `observed_source_rows`: count observations tied to one source snapshot; may be rights-unknown.
- `cleared_source_rows`: source rows with complete provenance and an explicit decision permitting the intended data redistribution.
- `canonical_identities`: cleared rows reconciled by the identity-binding rules below.
- `cleared_portrait_assets`: decoded, hashed assets whose binary redistribution and any required derivative use are explicitly allowed.
- `runtime_allowlist_identities`: every eligible canonical identity exactly once, with a cleared portrait or the cleared fallback.

The acceptance formula is:

```text
eligible identity =
  identity binding is resolved
  AND every required runtime data field has cleared provenance
  AND redistribution_status == "allowed"
  AND no unresolved identity/source conflict exists
  AND (cleared portrait exists OR cleared fallback exists)
```

The target is **all provenance-cleared rows**. The audit catalog retains each cleared source row/version; the runtime allowlist projects one entry per eligible canonical identity. An exclusion is valid only when the coverage report carries a deterministic reason code and source fingerprint.

### 4.2 Expected count

- Do not set the expected runtime count from any discovery count in section 2.
- After clearance and identity reconciliation, a reviewer approves `expected_eligible_identity_count` in the versioned clearance snapshot.
- The generator recomputes the eligible identity count and fails on drift from that approved value.
- A count change requires a new source snapshot, clearance evidence, dedupe report, and human-reviewed expected-count update.
- Current WIKIWIKI-derived `expected_eligible_identity_count` is not frozen for production. Current eligibility is zero because all inspected reuse rights are unresolved.

## 5. Catalog model

The auditable model separates identity, source observation, ruleset version, portrait binary, and clearance decision. Flattening them into one row would make dedupe and rights review ambiguous.

### 5.1 Canonical subject record

Required fields:

| Field | Rule |
|---|---|
| `schema_version` | Exact generator/schema contract version |
| `canonical_id` | Stable opaque ID from section 6; never a display name or row ordinal |
| `subject_class` | `historical`, `bonus_or_collaboration`, or `ethnic_npc`; no cross-class auto-merge |
| `identity_binding_key` | Immutable approved source key or frozen registry key |
| `primary_name` | Exact source script preserved; no silent translation |
| `name_aliases` | Typed, sourced aliases; never identity keys by themselves |
| `source_aliases` | Source dataset/version/key bindings |
| `versions` | Base/PK/sortable/other source-version references |
| `portrait_candidates` | Zero or more `portrait_asset_id` references |
| `clearance_decision_id` | Versioned decision that makes runtime projection auditable |

### 5.2 Source observation record

Required provenance fields:

| Field | Rule |
|---|---|
| `source_dataset_id` | Stable local label such as those in section 2 |
| `source_class` / `source_variant` | Example: `historical` + `base|pk|sortable`; values are controlled enums |
| `canonical_page_url` | Direct page URL, not a search-result URL |
| `source_record_key` | Upstream immutable key if verified; otherwise `[UNKNOWN]` until binding review |
| `site_modified_display` | Exact site-displayed string; timezone remains unknown unless proven |
| `observed_at_utc` | Extraction timestamp |
| `parser_name` / `parser_version` | Exact collector identity |
| `source_kind` | `table`, `prose`, `inference`, or `detail_sample` |
| `raw_row_sha256` | Hash of the authorized raw row bytes in restricted staging; never a portrait hash |
| `normalized_record_sha256` | Hash of canonical serialized parsed fields |
| `brief_sha256` | Source-brief digest above for this reconnaissance lineage |

Candidate data field names discovered by the brief may be modeled without copying their values: exact name, reading, sex, birth/appearance/death years, compatibility, leadership, strength, intelligence, politics, charm, doctrine, policy and level, traits, formations, faction, and NPC role. Each populated value needs its own observation/version provenance. Conflicting base/PK values remain versioned; later values do not silently overwrite earlier values.

### 5.3 Clearance record

Required fields:

| Field | Rule |
|---|---|
| `rights_status` | `allowed`, `not_allowed`, or `unknown`; only `allowed` is eligible |
| `rights_holder` | Identified rights holder or `[UNKNOWN]` |
| `license_expression` | SPDX expression or reviewed `LicenseRef-*`; never infer from visibility |
| `license_evidence_url` | Direct permission/license URL |
| `license_evidence_sha256` | Hash of retained permission evidence when policy allows retention |
| `permission_scope` | Separate flags for data redistribution, binary redistribution, derivatives, repository bundling, and deployment |
| `redistribution_status` | Explicit allow/deny/unknown for the exact intended use |
| `third_party_ip` | Collaboration or other embedded-rights declarations |
| `reviewed_at` / `reviewed_by` / `review_ref` | Auditable human/legal decision reference |
| `expires_at` | Optional permission expiry; expired permission becomes ineligible |

Data and portrait clearance are independent. Cleared facts do not clear a portrait, and a cleared portrait does not clear copied table values.

## 6. Stable ID and alias contract

### 6.1 Canonical ID

The canonical ID is opaque and independent of name, table order, filename, portrait, ruleset version, and selection seed.

1. Resolve an `identity_binding_key` as either:
   - `<approved-source-namespace>:<verified-immutable-upstream-key>`, or
   - `registry:<uuid>` assigned once after manual reconciliation when no immutable upstream key exists.
2. Freeze that binding in a versioned binding registry. The generator never allocates a missing binding implicitly.
3. Compute:

```text
canonical_id = "npc_" + lower_base32(
  first_20_bytes(sha256("opensamguk:npc-id:v1\0" + identity_binding_key))
)
```

This yields `npc_` plus 32 lowercase Base32 characters. A truncated-hash collision is a hard failure; do not append a sort-order suffix. The full SHA-256 remains in the binding audit record.

Names, readings, sex, and date tuples are **candidate-match evidence only**. They must not be the sole key because names can repeat, corrections can change fields, and the source classes include variants and collaborations. Row number and current sort position are always forbidden as identity keys.

### 6.2 Alias and version rules

- `name_aliases` entries contain `value_exact`, `reading_exact`, `language`, `script`, `alias_kind`, and `source_observation_id`.
- Preserve original Japanese strings. Unicode NFC may be used only to produce a candidate-match index; never discard the original bytes or automatically fold traditional/simplified forms, punctuation, spacing, or translated names.
- A rename adds an alias and retains the canonical ID.
- `source_aliases` bind base, PK, sortable, detail, bonus, or ethnic records to the canonical identity.
- Base/PK/sortable differences are `versions`, not automatic replacement and not additive identity counts.
- Alias collisions produce a review item. An alias may point to more than one identity only when explicitly marked ambiguous; it cannot be used as a unique lookup key.

## 7. Dedupe contract

Dedupe occurs independently at four layers:

1. **Source-row idempotency**
   - Same dataset, variant, immutable record key, and `raw_row_sha256` is the same observation.
   - Same key with a different hash is a new source version and must produce a change report.
2. **Canonical identity reconciliation**
   - Verified immutable keys win.
   - Without one, exact name/reading/sex/date evidence may nominate a match, but a reviewer must bind it.
   - Base, PK, and sortable rows bound to the same identity produce one canonical identity with multiple versions.
3. **Class boundary**
   - Never auto-merge `historical`, `bonus_or_collaboration`, and `ethnic_npc` across classes.
   - A same-name bonus/collaboration row may be a costume/version, a third-party character, or another person; preserve it until evidence resolves it.
   - Ethnic NPC identity includes verified faction/role/source identity. Do not collapse same role names across factions.
4. **Portrait binary reuse**
   - Full decoded-file SHA-256 is the binary dedupe key after lawful acquisition.
   - Equal binary hashes may be shared by many subjects and do not merge those subjects.
   - Different hashes are not automatically distinct art; perceptual similarity may raise a review candidate but cannot merge assets automatically.

Every run reports exact duplicate observations, version updates, identity merges, unresolved collisions, cross-class candidates, and shared-portrait groups. Silent last-write-wins behavior is forbidden.

## 8. Portrait metadata and file contract

No portrait from the inspected source is presently eligible. Once an asset is lawfully acquired, the following metadata is mandatory before runtime use:

| Field | Rule |
|---|---|
| `portrait_asset_id` | `pa_` plus the full lowercase binary SHA-256 |
| `canonical_filename` | `<canonical_id>--<variant_slot>.<canonical_ext>`; no person name or source filename |
| `variant_slot` | Controlled value such as `primary` or reviewed `altNN`; not source order |
| `canonical_format` / `media_type` | Determined by a real decoder, not extension or request header |
| `canonical_ext` | Decoder-approved extension mapped from the canonical format |
| `width_px` / `height_px` | Decoded output dimensions |
| `byte_length` | Stored canonical output size |
| `frame_count` | Recorded for GIF/animated inputs; animation acceptance remains a policy gate |
| `sha256` | Full binary hash; mandatory and verified against the file |
| `source_locator` | Restricted provenance only; never emitted in the runtime allowlist |
| `source_fingerprint` | Source revision/ETag when available; never substituted for SHA-256 |
| `derivation` | Crop/resize/format transform plus tool/version and parent asset hash |
| `clearance_decision_id` | Must explicitly cover binary distribution and any derivative transformation |

The runtime derivative must satisfy the approved 91a shared-icon envelope: a decoder-supported AVIF, WebP, JPEG, PNG, or GIF; square dimensions with width and height each in `64..128`; and canonical stored output no larger than `51,200` bytes. Extension/MIME spoofing, unsupported formats, hash mismatch, missing files, and dimensions outside the envelope are hard failures.

Source originals may have other dimensions, but remain restricted metadata/binaries outside the runtime bundle. Cropping, resizing, tracing, or format conversion is a derivative action and requires permission. The source brief produced no portrait byte hashes or verified dimensions; attachment `rev=` values are only source fingerprints.

## 9. Fallback/default contract

- Reserve the logical fallback ID `npc-fallback-v1`. It is not an NPC identity and is excluded from NPC expected counts.
- The concrete fallback asset must be project-owned or separately licensed, pass the same decoder/format/dimension/size/hash checks, and have its own clearance decision.
- The current fallback binary, hash, format, and dimensions are `[UNKNOWN]`; this task does not create one.
- A cleared data identity with no cleared individual portrait may resolve to the cleared fallback with `fallback_reason=portrait_not_cleared`.
- Unknown data rights cannot be cured by a fallback portrait; the identity remains excluded.
- Missing, ineligible, or hash-invalid fallback is a hard generator failure, even for an otherwise empty runtime set.
- No external URL, CDN lookup, WIKIWIKI attachment, or runtime API may serve as an implicit fallback.

## 10. Offline allowlist-generation interface for OPENSAM-91a

The exact implementation module/class/path remains `[UNKNOWN]` until the shared foundation owner is assigned. The interface and behavior are fixed here so 91a can consume it without choosing source or rights policy itself.

### 10.1 Logical interface

```text
compileNpcSharedIconAllowlist(
  schemaVersion,
  catalogSeedHex,
  sourceSnapshot,
  identityBindings,
  clearanceDecisions,
  portraitAssetManifest,
  fallbackManifest,
  expectedEligibleIdentityCount
) -> {
  auditCatalog,
  sharedIconAllowlist,
  coverageReport,
  rejectionReport,
  outputDigests
}
```

All inputs are immutable, versioned, and hashed. Missing inputs are errors. The compilation happens at build/approval time and performs no network request.

### 10.2 Outputs

- `auditCatalog`: one canonical identity plus all cleared source versions and provenance references. It contains no unlicensed raw table or portrait binary.
- `sharedIconAllowlist`: minimal runtime projection sorted by `canonical_id`, containing only `icon_id`, `canonical_id`, `canonical_filename`, `portrait_asset_id`, `sha256`, decoded format/dimensions, and fallback flag.
- `coverageReport`: source and eligibility counts, dedupe outcomes, exclusions by reason, fallback count, and input/output hashes.
- `rejectionReport`: source observation IDs/hashes and deterministic reason codes only; it must not reproduce blocked row values or prose.
- `outputDigests`: SHA-256 for every canonical JSON output.

91a consumes the generated manifest as a closed set through equivalent operations:

```text
isAllowedSharedIconId(iconId) -> Boolean
resolveSharedIcon(iconId) -> { canonicalFilename, sha256, mediaType, width, height, fallback }
```

It must never accept an arbitrary path, client filename, source URL, source attachment revision, or unlisted catalog field. Provenance/permission records are audit inputs and are not exposed as runtime user-profile fields.

### 10.3 Fatal and exclusion reason codes

Fatal examples: `SCHEMA_INVALID`, `ID_BINDING_MISSING`, `ID_COLLISION`, `ALIAS_COLLISION_UNRESOLVED`, `EXPECTED_COUNT_DRIFT`, `ASSET_MISSING`, `ASSET_HASH_MISMATCH`, `ASSET_FORMAT_UNSUPPORTED`, `ASSET_DIMENSION_INVALID`, `FALLBACK_MISSING`, `OUTPUT_NONDETERMINISTIC`.

Exclusion examples: `RIGHTS_UNKNOWN`, `REDISTRIBUTION_DENIED`, `IDENTITY_UNRESOLVED`, `SOURCE_CONFLICT`, `PORTRAIT_UNCLEARED`. An excluded item appearing in `sharedIconAllowlist` is always fatal.

## 11. Deterministic seed and coverage report

### 11.1 Seed contract

- `catalog_seed_hex` is an explicit 64-character lowercase hexadecimal value. There is no implicit default.
- Stable IDs, identity membership, rights decisions, and dedupe do **not** depend on the seed.
- If more than one cleared portrait candidate is valid for an identity, rank candidates by the lexicographic value of:

```text
sha256("opensamguk:npc-portrait-select:v1\0" +
       catalog_seed_hex + "\0" +
       canonical_id + "\0" +
       portrait_asset_id)
```

- Select the lowest rank. Input order must not affect the result.
- This is catalog compilation, not gameplay RNG. It must not call `RandUtil`, consume a PHP-parity draw, or alter a scenario seed.
- Canonical JSON serialization uses UTF-8, recursively sorted object keys, stable array ordering defined by the schema, no insignificant whitespace, and one final LF before hashing.

### 11.2 Mandatory coverage report fields

```text
schema_version
source_snapshot_id
catalog_seed_hex
input_digest
observed_rows_by_dataset_and_variant
cleared_rows_by_dataset_and_variant
canonical_identity_count_by_class
eligible_identity_count
expected_eligible_identity_count
cleared_portrait_asset_count
shared_portrait_group_count
fallback_identity_count
excluded_count_by_reason
exact_duplicate_count
source_version_update_count
identity_merge_count
unresolved_collision_count
allowlist_digest
audit_catalog_digest
generator_name_and_version
generated_at_utc
```

`generated_at_utc` is informational and excluded from the deterministic content digest, or supplied as a fixed build input. Two runs with identical semantic inputs and seed must produce byte-identical digest-bearing outputs and identical coverage counts.

## 12. Required future test cases

Use only synthetic or explicitly cleared fixtures; never paste WIKIWIKI rows into tests.

| ID | Test contract |
|---|---|
| `NPC-CAT-001` | All cleared source rows are represented in the audit catalog and all eligible canonical identities appear exactly once in the allowlist |
| `NPC-CAT-002` | `rights_status=unknown|not_allowed` never reaches runtime output and is counted by reason |
| `NPC-CAT-003` | Base/PK/sortable observations bound to one identity yield one canonical ID with three versions |
| `NPC-CAT-004` | Reorder, display-name correction, and version addition do not change a frozen canonical ID |
| `NPC-CAT-005` | Missing binding, duplicate binding, or truncated-ID collision fails without order-based suffixing |
| `NPC-CAT-006` | Same-name rows across historical/bonus/ethnic classes are not auto-merged |
| `NPC-CAT-007` | Same binary hash may serve multiple identities without merging identities; corrupted hash fails |
| `NPC-CAT-008` | Unsupported/forged format, non-square image, dimensions outside `64..128`, or size over `51,200` fails |
| `NPC-CAT-009` | Missing catalog file, missing portrait file, orphan asset, and unsupported extension fail |
| `NPC-CAT-010` | Missing or uncleared fallback fails; cleared portrait absence resolves only to the cleared fallback |
| `NPC-CAT-011` | Same inputs and seed produce byte-identical outputs and digests after input-order shuffle |
| `NPC-CAT-012` | Changing only the seed may change portrait variant selection but never IDs, membership, rights, or expected count |
| `NPC-CAT-013` | Count drift fails and the report distinguishes observed rows, cleared rows, canonical identities, and portraits |
| `NPC-CAT-014` | Runtime manifest contains no `http://`, `https://`, filesystem path, source prose, or unapproved field |
| `NPC-CAT-015` | Existing scenario count, IDs, RNG draw sequence/count/args, AI choices, Korean logs, and flush results remain at the frozen v1 baseline |
| `NPC-CAT-016` | No generated catalog is loaded by scenario/runtime roster paths before OPENSAM-104 approval |

## 13. Exact future validation commands

After the foundation owner implements the interface and the named test contract, run from repository root with JDK 21:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test --tests '*NpcCatalog*' --rerun-tasks
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test --rerun-tasks
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :infra:test :app:game-engine:test --rerun-tasks
tools/parity/gate.sh backend
tools/agent-system/check.py --strict --base origin/main
scripts/agent/verify-changes.sh
```

Confirm `BUILD SUCCESSFUL` in each Gradle output and require this command to find no failing/error XML:

```bash
! rg -n 'failures="[1-9]|errors="[1-9]' app/gateway-api/build/test-results/test -g '*.xml'
```

After the concrete generated-output path is assigned, require its runtime allowlist to contain no URL:

```bash
! rg -n 'https?://' <generated-shared-icon-allowlist.json>
```

The final path is deliberately not fabricated here. The main execution contract records the catalog-owning module, class/package, seed-file path, and migration number as `[UNKNOWN]` until foundation ownership is assigned. A2 evidence must replace the angle-bracket path with the chosen tracked path before claiming the implementation gate complete.

## 14. UNKNOWN ledger and stop conditions

| UNKNOWN | Consequence / release condition |
|---|---|
| Public redistribution permission for WIKIWIKI text/table compilation | All WIKIWIKI-derived data values remain out of runtime/repo bundles until written permission or reviewed legal basis exists |
| Portrait authorship, publisher/contributor rights, and redistribution/derivative permission | No portrait fetch, crop, conversion, hash claim, bundle, or deploy |
| Immutable upstream officer/NPC primary keys | Missing identities require reviewed frozen registry bindings; row ordinals/names are forbidden |
| Full identity reconciliation across base, PK, sortable, bonus, collaboration, and ethnic sets | No fixed eligible count and no automatic cross-class merge |
| Complete audit of the other four ethnic-faction detail pages | The inferred 50 cannot be promoted to observed/audited coverage |
| Unique portrait count and full portrait sharing graph | Officer/NPC row count cannot stand in for portrait count |
| Portrait binary format, dimensions, byte length, and SHA-256 | No asset is eligible; attachment revision is insufficient |
| Concrete cleared fallback asset | Runtime manifest generation remains blocked even if data rights later clear |
| Final catalog-owning module/class/path and foundation owner | 91a interface is logical only; no shared file is claimed by this lane |
| OPENSAM-96 source/IP result | Downstream portrait selection and bundle remain blocked |
| OPENSAM-103/98/105 contracts and OPENSAM-104 builder evidence | Gameplay roster activation remains blocked |
| Independent official/game verification of community data accuracy | Community rows remain discovery candidates, not roster truth |
| GIF animation acceptance policy | Record `frame_count`; reject or quarantine animation until decoder/security policy is approved |

Stop immediately on any of the following: `403`, `429`, challenge page, absent written crawl authorization for a bulk pass, terms/rules conflict, unclear third-party collaboration rights, attempted attachment hotlink, source row copied into a fixture, missing clearance evidence, or a request to activate the roster before the downstream gates.

## 15. Current document validation

Commands and final results are recorded after generation:

```bash
shasum -a 256 /tmp/opensam-wiki-source-brief.md
git diff --check
git diff --check -- docs/superpowers/research/2026-07-17-opensam-91b-npc-portrait-data-pool.md
! rg -n '[[:blank:]]+$' docs/superpowers/research/2026-07-17-opensam-91b-npc-portrait-data-pool.md
perl -0777 -ne 'exit(!/\n\z/)' docs/superpowers/research/2026-07-17-opensam-91b-npc-portrait-data-pool.md
tools/agent-system/check.py
tools/agent-system/check.py --format json
```

- Source brief SHA-256: `PASS` (exact required digest).
- Target direct trailing-whitespace/final-LF checks: `PASS` (no trailing blanks; final LF present). These direct checks are the actual whitespace coverage for this untracked document.
- `git diff --check`: exited `0` globally. The path-scoped invocation also exited `0`, but the target was untracked, so Git did not inspect its content; that result provides no validation coverage for this document.
- Agent-system check: baseline before this document was `FAIL` with exactly one `codex-surface` finding, `Project Codex config must not pin a personal model.` The pre-existing `.codex/config.toml` also differs from `HEAD` by removal of `agents.max_threads = 1000`. The post-document check returned the **same single finding** and no warning, so this document introduced no new agent-system finding. This lane did not modify `.codex/config.toml`.

## 16. Final decision

The source inventory, count semantics, fields, stable-ID rule, aliases, dedupe, portrait metadata, rights fields, all-cleared coverage policy, 91a interface, deterministic seed/report, fallback behavior, downstream activation fence, tests, and stop conditions are now explicit.

The legal/rights concern is decision-changing: WIKIWIKI is a lead, not a redistributable catalog. Until permission is documented, the only compliant runtime result from this source is no ingested rows and no portraits. Proceeding to catalog ingestion or gameplay activation without that evidence would violate the approved A1 contract.
