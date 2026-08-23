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
The issue authority is https://github.com/peppone-choi/opensamguk/issues/491;
this review does not depend on an absent local master-plan document.

## Findings resolved

1. Unsupported scenario lifecycle derivation was removed. The validator rejects
   all five per-node runtime lifecycle fields, pins exactly 15 active Han resource
   hashes, requires `NOT_CLAIMED_BY_W0_DATA_CONTRACT`, and enforces exact rewrite
   policies from an independently hash-pinned validation contract.
2. A name-wide ban incorrectly conflated the legitimate HHS county `哀牢`
   (`hhs:113:永昌郡:007`) with an external polity place. The legitimate county is
   allowed, while `external:v1:X060`, polity-presence, and forbidden node classes
   are rejected by typed policy in both materializer and validator.
3. The eight location-only claims are required exactly once each and are typed as
   `W0_ROUTE_NODE_PLACE_IDENTITY_ONLY`. They contain no historical/runtime period.
   Both stages verify repository-contained evidence, source witness identity, and
   exact authority path/hash/record/Wikidata/physical-place/subject/name binding.
   An unused ninth claim is rejected.
4. Save surfaces are split into mutable live state, immutable audit/history, and
   derived/reseed outputs. Terminal result and outbox payloads are `NO_REWRITE`;
   scenario and generated map constants require reseed or regeneration and remain
   blocked on unknown bindings.

The 105-group/1,180-identity completeness invariant applies to the source
catalog, not to group or ordinal-1 coverage in the reviewed 780-node playable
subset. The selection preserves and reviews the 780 legacy physical slots;
neither the ticket acceptance nor the pinned policy requires every catalog
group or every commandery seat to be selected. The audit's requirement not to
take an arbitrary first 780 rows is satisfied by the binding and adjudication
artifacts, without inventing a separate seat-coverage invariant.

## Final evidence

- Candidate generator check: no drift; 1,960 candidates, all `PENDING`.
- Materializer check: no drift.
- Validator: approved 780, active scenario resources 15.
- Focused suite: 186/186 tests passed.
- Materializer rejects any extra or missing Han scenario resource relative to
  the pinned 15-file set, matching validator behavior.
- A temporary real corpus fixture exercises source snapshot hash, line-range,
  and verbatim validation without relying on ignored local inputs.
- Numeric city IDs are immutable (`newCityId == oldCityId`) under the embedded
  `numericCityIdChangeAllowed=false` policy. Every HHS-bound node's class,
  names, parent identity, and seat role exact-match its catalog row, and rejected
  false-homonym adjudications require rationale and non-empty evidence.
- Coherent rehash mutations rejected: injected runtime lifecycle fields, false
  runtime enforcement, live rewrite, polity node class,
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

- administrative catalog: `668165bce575a618be5f30738221fe657b30710d0c92f7e984a018711313b19f`
- reviewed candidate manifest: `1ce715b3e757b6bbb2a59115946bd0c71bbe063e0b5b68b8d2ac930d1c0a0f01`
- legacy Han map: `a61cbd8aa6fd0dd2f7f794df6d0ebdc026c0b6c351568c60efb8d115f54b3670`
- legacy tile map: `1979c193de6774af7c3cf5a9ddfd1c81bf94ead5b8c5b46dafd06bed03c6888d`
- key registry: `7f462487d593940e2bbfe51edceea76c74a1dc589d8731e3ab0c7d6b9a267284`
- location adjudications: `f1c6c39607bbb8e48db3cf8a885a09594fbf01d983451005b74915e6d406af1a`
- source claims: `bdc84f732351962632b5e14306b60821d2436526f35ebc6c9fc6c46ca829266e`
- source witness: `7fe27b667b4066200882f9e1815e07a6adb24d826f09e0605145041897f76ee4`
- review policy: `b4a8755853f17180f3a4bc99200bfc758942199c8ead41a186f114c2568beb07`
- selection: `144318023bbc3d77827a5048f0848ad400affc7e09aeecb802e4fd10d6ea290b`
- migration: `014cba2324c7482a34e4dd1b165f06089396ae5753807ace09333d1347a04c3d`
- independent validation contract: `83c11fc237a6f03f8699a97f56326a9a6dc65990c6460482b024e7a0ee3bef66`

## Intermediate failure and residual boundary

Removing surplus EOF blank lines from the key registry, location adjudications,
and source claims changed their byte hashes. The materializer correctly rejected
each intermediate state until the policy was repinned and selection and migration
were regenerated. The latest hashes above then passed materialization, drift
checking, validation, and all 186 focused tests.

Residual risks are upstream source freshness and the absence of a live-save
cutover rehearsal. Neither changes the W0-C data-contract verdict; runtime
activation and cutover remain explicitly unclaimed and blocked.
