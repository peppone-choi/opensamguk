# Scenario Province Affiliations Design

## Goal

Every land province on every scenario map must display a meaningful affiliation without inventing gameplay ownership, while county ownership must never leak across a commandery boundary because of a misplaced runtime city coordinate.

## Decisions

- A province controlled by a live nation keeps that nation's exact political color and tooltip identity.
- A province whose commandery has no live owner receives a map-only administrative affiliation. This does not add cities to a nation or change gameplay ownership.
- Unowned Han-commandery provinces use one stable `지방관·미확정 지배` affiliation so the map does not imply a fabricated warlord.
- Unowned non-Han provinces use the map's administrative-system name (`고구려`, `선비`, `마한`, `왜` and so on), never Han `군·현` terminology for the affiliation.
- Each affiliation has one deterministic flat color. Terrain shading must not alter the political color.
- A city coordinate can directly claim a province only when the city's declared commandery matches that province's declared parent. Otherwise ownership is resolved from the correct commandery pool.
- The documented 225 and 228 New City boundary exception is corrected at the scenario source: `상용` and `방릉` belong to Wei even though the runtime city metadata still places Shangyong in Hanzhong.

## Data Flow

`buildProvinceAdministrativeIndex` carries each province's administrative system and affiliation label into `bindCompleteProvinceOwnership`. The binding returns both paint colors and affiliation metadata. `HanMapCanvas` uses the same binding for paint and hover output, keeping color and tooltip semantics consistent.

Legacy map data without province records remains supported and falls back to the generic local-administration affiliation.

## Acceptance Criteria

- Every land province has an opaque political color.
- No cross-parent direct city sample can recolor a province.
- Unowned Han and external provinces expose non-empty, system-correct affiliation names.
- Owned provinces retain their actual nation id, name, and exact RGB color.
- Scenario 1100 and 1110 assign both Shangyong and Fangling to Wei through explicit city overrides.
- Focused frontend tests, scenario rewrite checks, type checking, and the full relevant test suites pass.
