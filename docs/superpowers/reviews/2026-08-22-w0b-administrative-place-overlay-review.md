# W0-B Administrative Place Overlay Review

Scope: tools/
Verdict: cleared

- Date: 2026-08-22
- Scope: `tools/map/build_administrative_place_overlay.py`, its focused tests, the
  W0-A 105/1,180 catalog, and the ignored CHGIS 220 overlay.
- Reviewer: independent read-only `fable-deep-reasoner`
- Final verdict: `cleared`

## Acceptance result

- The overlay preserves 1,180/1,180 unique administrative identities. The 104 rows
  omitted by the legacy 1,076-row parser are now explicit identity rows; this does
  not claim that all 1,180 coordinates are resolved.
- A point is selected only when the administrative-unit degree and the CHGIS
  physical-place degree are both exactly one. The 722 selected physical-place IDs
  are 722/722 unique.
- Final statuses are 722 `RESOLVED_POINT`, 55 `AMBIGUOUS_POINT`, 400
  `NO_COORDINATE_CANDIDATE`, and 3 `SOURCE_PLACEHOLDER`. The 55 ambiguous rows have
  zero selected coordinates; eight of them have one candidate shared with another
  administrative identity.
- Nineteen CHGIS physical places participate in candidate-side conflicts. They stay
  explicit and unselected for W0-C review.
- CHGIS input is fixed to year 220 and SHA-pinned in the generated provenance. Active
  duplicate `recordIndex` or `physicalPlaceId(SYS_ID)`, malformed active coordinates,
  malformed catalog identity/citation, and tracked coordinate paths fail closed.
- The coordinate-bearing artifact remains gitignored under ADR-LITE-039.

## Review findings and remediation

The first review found that four CHGIS records were selected by two administrative
identities, creating eight false `RESOLVED_POINT` rows. It also found unguarded
tracked output, permissive catalog validation, silent DBF parsing, and overly broad
administrative suffix removal. The implementation now uses a global bipartite
candidate graph, rejects tracked coordinate paths, validates identity and citation
shape, parses the 220 snapshot strictly, and limits suffix removal to
`縣|县|道|侯國|侯国|邑`.

The first re-review then found that graph identity used `recordIndex` while output
identity used `SYS_ID`. The graph and output now share `physicalPlaceId(SYS_ID)`, and
active duplicate physical identities fail closed. It also aligned the ambiguity
policy text and external-path provenance. The terminal re-review found no remaining
issues.

## Evidence

- `python3 -m unittest discover -s tools/map/tests -p 'test_*.py'`:
  25/25 passed, including 16 W0-B tests.
- `python3 tools/map/build_administrative_place_overlay.py --check`: no drift.
- Overlay SHA-256:
  `bfefb8af7058aa11606cb20575653dcf4dec45b5535cab4a5e2e97fbb1e3ef30`.
- W0-A catalog SHA-256:
  `668165bce575a618be5f30738221fe657b30710d0c92f7e984a018711313b19f`.
- Independent terminal verdict: `cleared`; unresolved findings: 0.

The 55 ambiguous, 400 no-candidate, and three source-placeholder rows are explicit
inputs to the W0-C reviewed 780-node selection. They are not silently promoted here.
