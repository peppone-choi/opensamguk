# Independent field combat foundation

Scope: pure combat only. Spatial movement, persistence, encounter selection and UI belong to the parent task. No dummy city, terrain bonuses or ownership effects.

- [x] Add regression tests for zero-effect empty encounter, exhausted defenders, retreat and deterministic real combat.
- [x] Extract a shared phase kernel with explicit nullable city mode, retaining the existing city API and exact ordering.
- [x] Add a pure field wrapper accepting real generals, per-combatant pipelines/crew types/tech and returning working states plus encounter outcome.
- [x] Run focused field tests and existing phase-order/plan/golden regressions. Report API for integration, without committing.

Field defender order is caller supplied and stable. Empty encounters do not invoke training, wound or RNG paths. Field exhaustion finishes without a city fallback; city mode keeps all existing siege/conquest behavior. Both modes use the same phase arithmetic and trigger order.

Validation: missing API RED, then one empty-snapshot regression exposed legacy dex normalization. The result now supplies exact attackerAfter/defendersAfter records for persistence, preserving originals for uncontacted units. Final focused phase/order/plan/wrapper/replay/position tests passed (see /tmp/field-combat-final.log). No commit.
