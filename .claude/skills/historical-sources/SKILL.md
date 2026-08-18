---
name: historical-sources
description: Use when a claim about Han/Three-Kingdoms history needs a primary source — unit types, offices, place names, people, events, distances, populations. Queries a local FTS index over 719 volumes of public-domain Chinese histories (三國志·後漢書 including 續漢書 志·華陽國志·晉書·資治通鑑·元和郡縣圖志·宋書·世說新語·三國演義) and returns book, volume, and the verbatim passage. Use it INSTEAD of guessing, and use it to prove a term is absent before calling it fabricated.
---

# Historical Sources — 사료 질의

Grounds historical claims in text you can quote. **The point of this skill is to make
"I don't know" measurable**: a query that returns nothing is evidence of absence, and
absence must be reported as UNKNOWN — never filled in from memory.

## Setup (once)

```bash
python3 tools/corpus/fetch_sources.py --jobs 4   # ~15 min, writes data/corpus/*.txt (gitignored)
python3 tools/corpus/index_sources.py            # builds data/corpus/index.db (~45MB, gitignored)
```

Raise `--jobs` above 4 and wikisource silently returns empty bodies. Re-running
`fetch_sources.py` is safe and resumes — it skips files already on disk.

## Query

```bash
python3 tools/corpus/index_sources.py 白馬義從
python3 tools/corpus/index_sources.py 連弩 --book 華陽國志 --limit 5
```

Output is `[사서 권] …passage…` with the hit wrapped in 《》. Works at any query length,
including 2-character terms. Book names: 三國志 · 後漢書 · 華陽國志 · 晉書 · 資治通鑑 ·
元和郡縣圖志 · 宋書 · 世說新語 · 三國演義.

後漢書 卷109–113 are labeled 郡國志 and 卷114–118 百官志 — the 續漢書 志 that carry the
administrative geography and the office/military-rank tables.

## Rules

1. **Query before asserting.** Naming a unit, office, county, or polity from memory is
   fabrication until the index confirms it. Two claims this index has already overturned:
   「象兵」's only 後漢書 hit is 「執銅鏡以象兵」 — a verb, not a corps; 「白毦兵」 appears in
   no volume at all.
2. **Search 繁體.** The corpus is traditional. 簡體 queries miss (雒阳 finds nothing, 雒陽 works).
3. **Quote, don't paraphrase.** Carry the passage into the artifact so a reader can check it.
4. **Zero hits ⇒ UNKNOWN.** Say which volumes were searched and stop. Do not substitute
   a plausible term, and do not silently downgrade the claim.
5. **Grade the source, don't mix grades.** 正史 and 演義 are separate claims, never one
   averaged assertion — see `logic/src/main/kotlin/opensamguk/logic/v2/evidence/EvidenceContracts.kt`
   (`EvidenceClass` / `SourceProximity`) and `data/v2/unit-types.json` for the applied pattern.
6. **Read the snippet — matches are character-level, so false positives are real.**
   Querying `屯長` (a Han officer rank) also returns 「西**屯長**安」 — "garrisoned Chang'an",
   a word boundary the index cannot see. Never count hits without reading them; a raw
   count is not evidence.
7. **Absence in this corpus is not absence in the record.** 水經注 and 東觀漢記 are not
   indexed. Report the boundary rather than claiming the record is silent.

## Adding a source

Add its title pattern to `titles()` in `tools/corpus/fetch_sources.py` and its filename
prefix to `BOOK` in `tools/corpus/index_sources.py`, then re-fetch and re-index. Confirm
the exact wikisource title first — `後漢書/卷18` resolves, `後漢書/卷十八` does not.
