# Supplied Namu county source audit

Goal: account for every supplied unique page, all 227 existing Namu coordinate rows and all 1,180 canonical counties, with source lines and explicit unresolved statuses. This is a secondary-source text audit; it does not certify ancient coordinates or automatically change the map.

1. Parse all 14 unique supplied province pages (15 attachments, one byte-identical Xuzhou duplicate). Preserve source SHA/title/line references; extract county headings, aliases, affiliation timelines and direct location statements. Keep subordinate named sites separate. Diagnose unparsed structures.
2. Compare every existing coordinate row and canonical county against the extracted records. Preserve commandery identity and 國/道 name suffixes. Distinguish direct/alias name matches, group evidence, coordinate agreement, disagreement, unavailable coordinates and ambiguous candidates. Never equate a later administrative alias with proven year-140 continuity.
3. Validate representative parser edge cases and comparator failures, then run the full supplied corpus and assert complete row accounting and deterministic output. Independently review. Write a report with counts and remaining uncertainties, commit and merge only after gates pass.

Ownership: parser worker owns parse_namu_source_pages.py, its tests and factual source record artifact. Root owns comparator, comparator tests and documentation/report. Existing map relocation work is separate. Active map geometry and coordinate ledger remain unchanged by this audit.

Additional user-supplied reference: https://www.google.com/maps/d/u/0/viewer?mid=1Aj4nj1c9djirgLAca5eBuUjjn1F91GOM . User dates this map to approximately 260 CE. Direct tool access was blocked; its layers/coordinates have not been inspected or imported. The temporal attribution is user-provided context, not a verified geometry source. Supplied page text remains the auditable input for this task.

User clarification: preserve famous non-county landmarks such as 赤壁, including names occurring only in prose or county aliases (e.g. 虎牢關 and 白帝城). Keep such mentions attributed and separate; do not inherit nearby county coordinates. The audit reports source agreement, not a completed landmark placement design.
