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

## 2026-08-13 follow-up — provenance RED, cache-only remeasurement

This follow-up changes the local tool boundary only. It does not add, alter, or
activate an officer portrait dataset.

### Provenance and source status

- Direct observation of `https://wikiwiki.jp/robots.txt` on 2026-08-13 returned
  `Disallow: /*?` and `Disallow: /*/::*` (with `Allow: /common/`). That policy is
  scoped to the `wikiwiki.jp` authority and supports the decision not to crawl
  roster/officer pages; it is not asserted as a rule for `cdn.wikiwiki.jp`.
- No explicit image-reuse clearance or legitimate cached officer source was
  supplied or found in this worktree. Source/reuse status is **BLOCKED** pending
  human clearance and a lawful cache supplied outside the tracked tree.
- No roster crawl, officer-page fetch, attachment fetch, CDN upload, deployment,
  or portrait-data edit was performed in this follow-up. Robots and source-policy
  pages were inspected only to establish the boundary; no image was obtained.
- Famous/minor browser visual sampling is **PENDING**. It may be performed only
  after a legitimate cache and source/reuse clearance are available; historical
  visual notes above are not current evidence for that future source set.

### Adopted boundary correction

- Removed roster and officer-page parsing entirely. The CLI now requires an
  exact three-column observed manifest (`name`, officer page URL, attachment
  URL) and an operator-supplied local cache.
- The manifest verifies the RTK14 page namespace and requires its attachment to
  stay in that exact page namespace; it removes `?rev` and all other query or
  fragment values before cache/report use.
- The tool contains no network client, crawler, rate limiter, or downloader. A
  cache miss remains `FAIL/cache_miss`; a cache hit is operator-supplied and
  provenance-unverified, so it does not establish source or reuse clearance.
- Replaced the optional OpenCV runtime with pinned `Pillow==12.2.0`. The current
  transform preserves the complete decoded frame and alpha where present, fits
  inside the bounds, never enlarges, and emits deterministic PNG bytes.
- The existing game and gateway portrait surfaces were source-audited: all
  relevant portrait styles use `object-fit: contain`; no frontend source change
  was necessary for this tool-only correction.

### TDD and remeasurement

| Gate | Observation | Verdict |
|---|---|---|
| RED checkpoint | New tests failed before implementation: missing Pillow operation, manifest accepted two-column/inconsistent URLs, and attachment cache miss reached the opener | EXPECTED RED |
| Python unit suite | `python3 -m unittest discover -s tools/rtk-faces/tests -p 'test_*.py' -v` → 37 tests, 0 failures | PASS |
| Required geometry | Real Pillow fixture: 633×900 → 148×210; all four asymmetric corner markers remain | PASS |
| Never-upscale / deterministic PNG | Real 100×80 fixture remains 100×80; repeated encode bytes are equal | PASS |
| Cache-only CLI | Synthetic external cache and observed TSV ran twice through the real CLI: status OK, 633×900 → 148×210, report bytes equal, PNG bytes equal | PASS |
| Failure classification | Uncached attachment yields report `FAIL/cache_miss`; no network implementation exists | PASS |
| Compile / diff hygiene | `python3 -m py_compile …` and `git diff --check` exit 0 | PASS |
| Frontend focused tests | After frozen-lockfile dependency restore: game portrait 22/22; gateway portrait 24/24; both app typechecks exit 0 | PASS |
| Browser source sampling | No legitimate cached famous/minor inputs | PENDING / SOURCE BLOCKED |

### Tooling notes

- CodeGraph indexing is absent from this isolated worktree, so ordinary file
  inspection was used.
- The local lint/type binaries `ruff` and `basedpyright` are absent. The repository
  test/compile gates above are the observed Python evidence.
- A wrapper emitted generic tool-failure notices even when the command tail
  showed successful tests/compile/diff checks. Those notices were isolated as
  harness noise; completion relies on the concrete command output recorded here.
