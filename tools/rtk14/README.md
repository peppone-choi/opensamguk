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
