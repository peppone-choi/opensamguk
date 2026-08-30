# Scenario Province Affiliations Design

> Superseded on 2026-08-31 for political paint: runtime ownership is now direct
> county-province ownership only. Unowned and unspecified county provinces remain
> transparent instead of inheriting a commandery or administrative affiliation.

## Goal

Every directly owned county province must display its gameplay owner without inventing ownership, while ownership and city visuals must never leak outside the county boundary.

## Decisions

- A province controlled by a live nation keeps that nation's exact political color and tooltip identity.
- A province without a directly placed live owner remains transparent in the political layer and exposes no inferred nation identity.
- Neither the commandery owner nor the nearest same-commandery city may fill an unowned county province.
- Each affiliation has one deterministic flat color. Terrain shading must not alter the political color.
- A city coordinate can directly claim a province only when the city's declared commandery matches that province's declared parent. Otherwise ownership is resolved from the correct commandery pool.
- The documented 225 and 228 New City boundary exception is corrected at the scenario source: `상용` and `방릉` belong to Wei even though the runtime city metadata still places Shangyong in Hanzhong.

## Data Flow

`buildProvinceAdministrativeIndex` carries province-to-commandery identity into `bindCompleteProvinceOwnership`. The binding returns paint colors only for directly owned county provinces. `HanMapCanvas` uses the same direct binding for paint and hover output, keeping color and tooltip semantics consistent.

Legacy map data without province records remains supported, but it does not receive a fabricated local-administration affiliation.

## Acceptance Criteria

- Only directly owned county provinces have an opaque political color; all other county provinces are transparent.
- No cross-parent direct city sample can recolor a province.
- Unowned Han and external provinces expose no inferred nation name or color.
- Owned provinces retain their actual nation id, name, and exact RGB color.
- Scenario 1100 and 1110 assign both Shangyong and Fangling to Wei through explicit city overrides.
- Focused frontend tests, scenario rewrite checks, type checking, and the full relevant test suites pass.
