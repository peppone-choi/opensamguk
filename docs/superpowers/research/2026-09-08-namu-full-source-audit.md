# Supplied Namu source audit — 2026-09-08

This is a complete accounting of the supplied page text and existing catalog rows, **not certification of ancient locations**. All location-verification flags remain false. The 14 unique pages include Yangzhou (Gangdong); the two Xuzhou attachments are byte-identical. Original supplied text has 12,295 lines. Source SHA-256, title, supplied last-modified timestamp, URL and line references are retained in the factual extraction.

## Period and scope

The user identifies the linked [Google My Maps reference](https://www.google.com/maps/d/u/0/viewer?mid=1Aj4nj1c9djirgLAca5eBuUjjn1F91GOM) as approximately 260 CE. Tool access to that map was blocked, so no map layers or coordinates were read from it. The supplied Namu pages contain multiple historical periods. The canonical catalog is based on the Later Han administrative record around 140 CE. A matching historical name or group alias does not establish that a seat remained at the same coordinates.

No active map geometry, runtime city, scenario or existing coordinate row is modified by this audit.

## Complete accounting

The extractor separates 1,971 county headings from non-county entries and preserves all explicit coordinate mentions, including unassigned prose mentions. It does not borrow a fort, mountain, crossing or separately named location's coordinates for its preceding county. Three nonstandard coordinate notations are diagnosed: Yongzhou line 760, Jiaozhou line 248, Yangzhou (Gangdong) line 100. Their source references remain visible.

All 227 existing Namu coordinate rows have exactly one audit result:

| Text comparison result | Rows |
| --- | ---: |
| Name/group evidence and exact source coordinate agree | 193 |
| Coordinates agree when rounded to five decimals | 1 |
| County name found, group relation remains unresolved | 21 |
| Multiple source coordinates require review | 4 |
| No normalized name match; manually located below | 7 |
| Direct county coordinate attribution unavailable | 1 |
| Total | 227 |

The rounding case is 汝南郡 灈陽: longitude 114.10267 versus source 114.102668. Multiple-coordinate cases are 西河郡 中陽, 鴈門郡 平城, 北海國 劇 and 漢陽郡 西. The unavailable direct attribution is 玄菟郡 高句驪. Agreement statuses retain UNCERTAIN/DISPUTED and per-location qualifiers; agreement does not make those uncertainties disappear.

All 1,180 canonical county identities also receive exactly one result: 604 have group-name evidence and coordinates, 11 have group-name evidence without coordinates, 411 have a name match requiring group review, and 154 have no normalized name match. **154 does not mean 154 names are absent from the source**: spelling, aliases, country suffixes and incomplete parsing of narrative identities require further review.

## Manual review of the seven unmatched existing names

Each source passage was opened and its coordinate compared with the existing row. All seven coordinate pairs agree. These observations are retained separately in `namu-source-audit-manual-notes-v1.json`; they do not add broad spelling rules or silently remove 國/道 suffixes.

| Existing name | Supplied form | Page / heading line | Review boundary |
| --- | --- | --- | --- |
| 慎陽 | 愼陽縣 | Yuzhou / 126 | Explicit orthographic mapping remains separate |
| 思善 | 思善國 | Yuzhou / 160 | County/kingdom identity needs explicit treatment |
| 安昌 | 安昌國 | Yuzhou / 164 | County/kingdom identity needs explicit treatment |
| 博陽 | 博陽國 | Yuzhou / 166 | County/kingdom identity needs explicit treatment |
| 皋狼 | 皐狼縣 | Bingzhou / 621 | Explicit orthographic mapping remains separate |
| 盧鄉 | 盧鄕縣 | Qingzhou / 506 | Explicit orthographic mapping remains separate |
| 俞元 | 兪元縣 | Yizhou / 825 | Source coordinate is explicitly uncertain |

## Famous places and non-county names

Named sites are preserved separately from the county comparison. 赤壁 is an explicit Jingzhou entry at line 1003 (29.88533, 113.61877); 官渡, 長坂 and 函谷關 are also retained. 白帝城 occurs as a county alias and 虎牢關 occurs in prose, so geographical-name mentions are retained as well. A prose/alias mention has no inherited county coordinate and must not be interpreted as a confirmed map point. Non-county entries include unreviewed labels; their count is not a count of distinct famous places.

## Reproduction

- `python3 tools/map/parse_namu_source_pages.py --manifest <local-attachment-manifest.json> --output <source-records.json>` validates each raw source SHA and deduplicates identical attachments. The local manifest provides `files` with `path`, `title` and `sha256`. Host-specific attachment paths and full source prose are not committed.
- `python3 tools/map/audit_namu_sources.py --out data/curated/han/namu-source-audit-v1.json` audits the committed factual extraction against the canonical and existing Namu ledgers.
- `python3 -m unittest tools.map.tests.test_parse_namu_source_pages tools.map.tests.test_audit_namu_sources` checks attribution, temporal versus named labels, uncertainty, source-URL isolation, numeric validation, exact row accounting and deterministic results.

Remaining work is explicit historical/group reconciliation, uncertain or multiple coordinates, and landmark placement review. This report does not mark all counties historically verified.

## Validation result

34 focused and full-input accounting tests pass. Regenerating the extracted source artifact from all supplied raw attachments produces byte-identical output. All 227 existing rows and all 1,180 canonical identities are accounted exactly once, with deterministic comparison output. Independent review passed after correcting other-site attribution, strict temporal labels and cited-page isolation. The final source inventory contains 687 non-county entries (550 with coordinates) and 239 separate geographical-name mentions; these counts include unreviewed categories and are not unique-place counts.
