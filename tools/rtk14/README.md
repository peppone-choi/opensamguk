# RTK14 scenario enrichment

`build_rtk14_stats.py` joins the private workbook/source JSON to runtime scenarios.
Generated source data and enriched scenarios stay local and must not be committed.
Run the regression suite with:

```sh
python3 -m unittest discover -s tools/rtk14 -p test_build_rtk14_stats.py
```

## Reviewed legacy portraits

Legacy override rows may receive a missing portrait independently of gameplay stat
assignment. The following exact input identities are reviewed:

| Input name | Leadership / strength / intelligence | Birth / death | Registry identity |
| --- | --- | --- | --- |
| 루반 | 65 / 76 / 39 | 178 / 207 | 누반 / 楼班, 10502 |
| 곽씨 | 42 / 4 / 55 | 184 / 235 | 곽여왕 / 郭女王, 10815 |

The input fingerprints occur in `infra/src/main/resources/scenario/scenario_1021.json`.
Registry Korean names are recorded in `tools/scenario/officer-name-map.tsv`;
Japanese identities are recorded in `tools/scenario/officer-id-registry.tsv`.
Corroborating identity dates: [楼班](https://wikiwiki.jp/sangokushi14/楼班)
and [郭女王](https://wikiwiki.jp/sangokushi14/郭女王).

The builder fills only null or empty pictures and requires one source candidate
with the exact target name, already joined portrait ID, and matching birth/death.
A mismatch leaves the picture unchanged. Existing custom pictures, stat overrides,
source assignment, row order, lifecycle metadata, and added workbook officers keep
their existing behavior. In particular 루반's existing 62/76 politics/charm values
remain unchanged; the former Sun Luban rationale was an identity error.

This applies when scenarios are regenerated with portrait-enriched RTK14 input.
It does not modify checked-in scenarios or update an existing world's database.
Live repair and deployment verification are separate operations.

## wikiwiki cache extraction

`wikiwiki_inventory.py` builds `~/.cache/rtk14-wikiwiki/inventory.json` from
previously cached sitemap and index HTML. `wikiwiki_fetch.py` requests inventory
pages one at a time, respects cached `robots.txt`, waits at least two seconds
between requests, logs each response, and resumes from saved pages. On HTTP 429,
it honors `Retry-After` or waits at least 60 seconds with increasing backoff;
five consecutive other HTTP errors stop the run. Run it only after reviewing
inventory scope and the current robots rules.

Cached page bodies are now stored losslessly as `pages/<kind>/<key>.html.gz`.
`wikiwiki_compact.py` converts older `.html` files and removes each original
only after byte-for-byte decompression checks. The fetcher recognizes both
formats, writes new responses directly to gzip, and waits with a received
response in memory if disk space runs out. It does not request a cached page
again merely because the cache format changed. Below 500 MiB of free disk, it
waits before starting another request or writing a received response.

For a selected map page already in the HTML cache, `wikiwiki_maps.py --page-key
<page>` downloads only its full-size map attachments after the CDN robots check.
Run it while the HTML collector is stopped, so there is still only one request
at a time. It saves images under `~/.cache/rtk14-wikiwiki/images/maps/` and a
separate `image-fetch-log.jsonl`; portraits and unrelated attachments are not
selected. The map images are private source material and must not enter Git.

`wikiwiki_extract.py` reads only the cache and writes private `extracted/` JSON
and compressed JSONL files (`tables.jsonl.gz`, `sections.jsonl.gz`). It needs
`beautifulsoup4` from `tools/rtk14/requirements.txt`.
City pages expose named subordinate regions, ports and gates with their parent
city and every table column. Their neighboring cities are structured as route
candidates. Mentions of roads and waterways are retained as source text, not
promoted to verified graph edges.
It emits the legacy `officer-data.json` input for `refine_officers.py` only when
all 1,000 historical roster pages pass the existing fingerprint parser. The
historical roster includes Romance-derived Three Kingdoms people; crossover
characters are outside that roster. HTML, extracted data, and refined data stay
outside Git.
