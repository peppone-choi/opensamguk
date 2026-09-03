# Han anchor fragment adjudication plan

## Scope

Resolve only the three `ANCHOR_CONTAINING_REVIEW_REQUIRED` entries left by the
province-fragment ledger. Do not rebuild the canonical map, invent historical
counties, or adjudicate the remaining maritime, lacustrine, and multi-neighbor
inventory in this pull request.

## Acceptance criteria

1. Write failing contract tests before changing the materializer.
2. For `40957` and `87273`, prove that the disconnected component contains a
   commandery marker while the canonical jurisdiction seat remains in the main
   component.
3. Relocate each commandery marker to the seat of its declared
   `seatJurisdictionId`; update the aligned `juns` marker and reassign every
   removed component cell by an explicit nearest-target-seat ledger.
4. Preserve `X055` as an anchored maritime multi-component polity only when
   every component satisfies the minimum area and the SEA/anchor evidence is
   exact.
5. Keep `seatOwner`, terrain, province/jurisdiction/commandery identities, and
   scenario political assignments unchanged.
6. Regenerate all downstream snapshots, run map/scenario suites, web/JVM
   verification, create a small PR, merge after green CI, deploy, promote PEP,
   and record operational smoke evidence without reseeding.

## Tasks

- [x] Add RED tests for anchor normalization, target partition drift, canonical
      seat drift, and maritime preservation drift.
- [x] Extend the deterministic patcher and ledger; patch only the reviewed
      cells and marker coordinates.
- [x] Regenerate topology, reconciliation, strategic-site, and scenario map
      snapshots required by the new map hash.
- [x] Run full verification and independent review.
- [ ] Open, review, merge, deploy, promote PEP, smoke-test, and report.
