# Han deployment seed gate repair

Status: cleared (independent review `/root/review_deploy_gate`)

## Scope and cause

Main deploy run [33935085885](https://github.com/peppone-choi/opensamguk/actions/runs/33935085885)
failed before publishing JVM images: its Gradle filter referenced a removed test method.
The Han map work renamed that test to distinguish frozen V2 scenarios from new-world V3.
Update only the workflow selector to the existing test. Do not disable unmatched-test
failure, skip seed validation, or run the whole fixture-sensitive class against enriched data.

## Verification

- Base: `2237df9161dad62ff97bca9e76bd5f13248b098d`.
- RED: original workflow Gradle command failed locally with `No tests found for given includes`.
- GREEN: corrected command completed `BUILD SUCCESSFUL`; XML records one test, zero failures,
  errors or skips. It checks 15 frozen V2 and 16 new V3 scenarios, each with base and extended
  roster validation. Test resources come from the same classpath materialized by deploy.
- `python3 -m unittest tools/ops/test_deploy_service_inventory_contract.py`: two tests passed.
- `git diff --check`: passed.
- Independent reviewer checked workflow ordering, resource loading, scenario coverage and
  the importer's strict roster-count checks; no fix-required findings.

## Release boundary

Local verification uses committed scenarios. Production enrichment is verified only by the
post-merge deployment run; it is not claimed from the local test. Game-server promotion and
DB reset remain separate operations. The recovery runbook gate in `docs/admin/operations-and-recovery.md`
is not removed by this repair. Existing control-plane reset repair resumes a reset, not a
restoration of the previous world from backup.

Docs impact: this review records the changed deployment selector and verification; no player
rule or runbook behavior changed.
