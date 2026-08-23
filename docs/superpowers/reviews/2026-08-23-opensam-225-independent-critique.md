# OPENSAM-225 independent critique

PR: https://github.com/peppone-choi/opensamguk/pull/501

Scope: .github/workflows/ app/ common/ infra/ logic/ data/ tools/ docs/

Verdict: cleared

The final change is confined to `.github/workflows/ci.yml`, `data/curated/han/`,
`tools/map/`, `tools/scenario/`, and supporting review documentation. The earlier `app/`,
`common/`, `infra/`, `logic/`, unit-set, sprite-tool, runtime-map, gate-index,
and retired-scenario changes were restored to the base, so runtime engine and
infra-suite mappings are not applicable to this identity-only W0 manifest.
In particular, `HanCityConst` and `HanGateIndex` are unchanged; their mappings
to `CityConstRegistry`, the route validator, and engine/infra suites therefore
do not serve as evidence for this PR.

The independent review mapped `tools/map/tests/test_junguozhi_contract.py` and
`test_administrative_place_overlay.py` to the 1,180-row catalog identity,
provenance, ambiguity, redaction, and deterministic binding contracts.
`test_han_route_node_selection.py` covers matching/classification, coordinate
exclusion, and two-resource catalog ordering and metadata behavior.
`test_han_route_node_materializer.py` covers deterministic materialization,
policy/input hashes, stable registry use, authority/source failures, and drift
checking. `test_han_route_node_review_fixes.py` independently pins the 15
operating scenario codes and hashes, restricted fixture copying, correction-ID
failures, and the CLI drift exit contract. `test_han_route_node_validator.py`
covers the exact 780-node and
unique-key contracts, migration/adjudication, rejection of all five runtime
lifecycle fields, source witness integrity, production-versus-synthetic mode,
authority containment, and exact authority matching for
path, hash, record ID, Wikidata ID, subject key, canonical name, and physical
place ID. It also rejects source-root `data/` and witness-ledger symlink escapes,
keeps symlink aliases out of production approval mode, and verifies every
declared selection provenance dependency. The CI workflow now runs both Python
discoveries, candidate and materializer drift checks, and the production
validator in the required `agent-system` job; source-refresh-only map tests keep
their explicit clean-checkout skips.

The accepted design commits a coordinate-free reviewed binding ledger and a
minimal source witness. Tracking the ignored CHGIS overlay or full corpora was
rejected because it violates the repository's source and redistribution
boundaries. Residual risk is limited to upstream corpus freshness: clean CI
validates the curated witness, while a source-refresh audit must separately
re-fetch and compare the ignored source snapshots.
