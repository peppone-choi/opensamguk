# W0-C reviewed route-node selection independent review

Scope: tools/
Verdict: cleared

## Scope and decision

An independent read-only `fable-deep-reasoner` reviewed the candidate generator,
reviewed 780-node materialization, migration inventory, validator, mutation tests,
and the generated artifacts. The first review returned `fix-required`; the final
review cleared the remediated snapshot below.

W0-C is a data-contract approval only. It does not claim runtime scenario
activation or live-save cutover. Scenario resources remain blocked until their
numeric and name-based references are replaced by reviewed typed route-node
bindings. Existing saves remain `NEW_WORLD_ONLY` and are never silently rewritten.

## Findings resolved

1. Scenario lifecycle and runtime claims previously failed open. The validator now
   recomputes exact active scenario IDs, checks all 31 scenario states including
   year and state, requires `NOT_CLAIMED_BY_W0_DATA_CONTRACT`, and enforces exact
   rewrite policies.
2. A name-wide ban incorrectly conflated the legitimate HHS county `哀牢`
   (`hhs:113:永昌郡:007`) with an external polity place. The legitimate county is
   allowed, while `external:v1:X060`, polity-presence, and forbidden node classes
   are rejected by typed policy in both materializer and validator.
3. The eight location-only claims are required exactly once each. Both stages
   verify a repository-relative `data/corpus` path, the actual file SHA-256, line
   bounds, and exact verbatim containment. An unused ninth claim is rejected.
4. Save surfaces are split into mutable live state, immutable audit/history, and
   derived/reseed outputs. Terminal result and outbox payloads are `NO_REWRITE`;
   scenario and generated map constants require reseed or regeneration and remain
   blocked on unknown bindings.

## Final evidence

- Candidate generator check: no drift; 1,960 candidates, all `PENDING`.
- Materializer check: no drift.
- Validator: approved 780, scenarios 31.
- Focused suite: 110/110 tests passed.
- Coherent rehash mutations rejected: active lifecycle omission, scenario-state
  contradiction, false runtime enforcement, live rewrite, polity node class,
  forbidden X060, unused ninth claim, forged source SHA/line/verbatim, and mutable
  reclassification of terminal or derived surfaces.
- Unique sets: 780 UUIDv4 route keys, 780 physical places, 780 HHS bindings.
- Review batches: 722 unique overlay rows, 50 reviewed ambiguities, 8 reviewed
  external location claims.
- Migration counts: numeric ID changes 0, unrelated replacements 101, historical
  binding corrections 25, physical-place corrections 1.
- City 704 remains `hhs:113:上郡:009` / `external:v1:X026` with disposition
  `CORRECTED_BINDING_SAME_NODE`.
- Tracked coordinate fields: 0.

Final SHA-256 values:

- key registry: `7f462487d593940e2bbfe51edceea76c74a1dc589d8731e3ab0c7d6b9a267284`
- location adjudications: `a181aa916ad0269247e2cb18d76ed1b4011b047b370e0485acf35c8ea6f6a881`
- source claims: `5e093077911a8c3d0a55d53853802d80ba72b616f0ee0dd656ee6c8f83bf5186`
- review policy: `54691d3445179eb70d098192987789beaec04c3b18f4d987845f0d1552cd0c48`
- selection: `e2f2f1aec914071fbf8658ceacb099cbd9948f91766139eaa1316a87017f8c4a`
- migration: `b30f6dbaaa86cc305f7060bdff04f2294209b14b9c2bce28748688f899ab7353`

## Intermediate failure and residual boundary

Removing surplus EOF blank lines from the key registry, location adjudications,
and source claims changed their byte hashes. The materializer correctly rejected
each intermediate state until the policy was repinned and selection and migration
were regenerated. The latest hashes above then passed materialization, drift
checking, validation, and all 110 tests.

Residual risks are the validator importing materializer constants and the absence
of a live-save cutover rehearsal. Neither changes the W0-C data-contract verdict;
runtime activation and cutover remain explicitly unclaimed and blocked.
