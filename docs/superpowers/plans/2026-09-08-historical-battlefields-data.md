# Historical battlefield source data

Create a separate factual ledger for the approved first five battlefield identities. The root task owns independent movement, encounters, combat integration and rendering; this task changes source data and its validator only.

1. Add a focused validator regression first for the five stable identities, roles, four APPROXIMATE positions and one WITHHELD null position. Reject invalid coordinates, untraceable evidence, duplicate identities and invented CONFIDENT positions.
2. Add `data/curated/han/historical-battlefields-v1.json`. Keep primary identity/event evidence and modern point provenance separate. Preserve exact source SHA-256, book/volume/line/quote, use `eventYear` rather than a foundation date, and use repository-independent source paths. Historical primary evidence does not establish decimal coordinates.
3. Verify the committed evidence against the supplied-source extraction and the locally available primary corpus using an explicit validator argument. Keep the corpus external and do not embed machine-specific paths.

Approved entries: chibi NAVAL (208), guandu FIELD (200), changban FIELD (208), baidicheng FORTRESS (222), hulaoguan PASS (event year unknown). The first four use APPROXIMATE points; hulaoguan has WITHHELD position and null coordinates. No combat bonuses, city IDs, routes, map mutations, grid projection or historical boundary reconstruction are part of this ledger.

Validation and independent review precede any commit. All collaborators retain ownership of their other files.

## Validation evidence

- Seven focused Python regressions pass: first-batch identities/roles/statuses, changed source point, explicit alias requirement, withheld coordinate prohibition, invalid numeric values/duplicate IDs, source hash/quote mismatch, and machine-path/missing-record rejection.
- `python3 tools/map/validate_historical_battlefields.py --primary-corpus <shiliao/corpus> --attachment-manifest <manifest.json>` passed against actual local inputs: five entries, four approximate positions, one withheld; primary and attachment files both checked.
- Modern quotations are checked against raw attachments only when the optional manifest is supplied. Primary quotations are checked against source bytes only when the optional corpus directory is supplied. Default validation checks the committed extraction and schema; its result explicitly reports whether raw files were checked.
- No source binaries or host-specific paths are committed. No implementation commit yet; independent review pending.
