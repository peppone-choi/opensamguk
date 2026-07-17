# OPENSAM-97 full-frame portrait loop ledger

## Baseline

- Generator selected a detected face, expanded it to a square, and encoded only that slice.
- Browser portrait surfaces used `object-fit: cover`, creating a second crop.
- Existing QA recorded a visible false-positive crop and clipped small-face cases.
- The 743-image crop/composite batch was produced under that obsolete contract and is not an activation candidate.

## Single hypothesis

Removing face-derived geometry and fitting the complete decoded frame inside fixed bounds, then rendering it with `contain`, will eliminate clipping without identity heuristics.

## Adopted change

- `tools/rtk-faces/build_rtk14_faces.py`: decode → aspect-preserving downscale → PNG encode; no detector/crop path.
- `tools/rtk-faces/tests/`: full-frame, small-input, custom-bound, source/path, report, and live OpenCV corner-preservation regressions.
- General portrait styles in `web/game` and `web/gateway`: `cover` → `contain`.
- Jira OPENSAM-96/97/100 and GitHub #239/#240/#243 now carry the same full-frame contract.

## Review disposition

- Removed obsolete `detected_faces`, `chosen_box`, `crop`, and `NO_DETECT` report/CLI state.
- Replaced raw JSON return annotations with explicit `TypedDict` report shapes.
- Split source/path tests out of the pipeline test module; both test modules are below 250 pure LOC.
- Retained the generator as one local-only CLI under a named `SIZE_OK` exemption: its source, fetch, path-safety, transform, and report stages form one audited rights boundary and are not a reusable application library.
- Removed jsdom assertions that only mirrored an implementation token. The rendered contract is evidenced by the browser-computed style, painted dimensions, and captures in `GOLDENSET.md`.

## Remeasurement

| Gate | Observation | Verdict |
|---|---|---|
| Python unit tests with OpenCV requirements | 34 passed, including four-corner content preservation | PASS |
| Python unit tests without optional OpenCV requirements | 33 passed, 1 dependency-gated skip | PASS |
| Python compile | exit 0 | PASS |
| OpenCV live resize | 633×900 → 148×210 PNG; four distinct corner markers retained | PASS |
| game portrait tests | 23 passed | PASS |
| gateway tests | 53 passed | PASS |
| game TypeScript | `tsc --noEmit` exit 0 | PASS |
| gateway TypeScript | `tsc --noEmit` exit 0 | PASS |
| Forbidden path scan | no detector, box, crop encoder, min-face-ratio, or portrait `cover` reference | PASS |
| Python structural rules | no violations in generator and two test modules | PASS |
| Browser geometry | 633×900 source in 64×64 element; 45.013333×64 painted; `contain` | PASS |
| Browser visual | TOP and BOTTOM reference bands both visible | PASS |

## Decision

ADOPT. The full-frame contract replaces the face-crop contract. OPENSAM-97 remains in progress until original-cache regeneration and real-officer visual sampling are complete; no CDN activation or issue completion is claimed here.
