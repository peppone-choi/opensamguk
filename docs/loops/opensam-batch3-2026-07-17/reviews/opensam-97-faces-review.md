# OPENSAM-97 — RTK14 face-crop pipeline — independent adversarial review

- **reviewer:** `reviewer-97-faces` (did NOT implement any of this)
- **date:** 2026-07-17
- **verdict:** `cleared` (fix-required = 0; 6 notes — see also the §"Scope extension" reconciliation of three implementer reports)
- **contract basis:** `docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md` §7 (OPENSAM-97 목표/범위/AC1-9/승인점), §3.1.5 (권리 보수성), §3.1.8 (독립 검토·최신 판정), §9 W0 (single-writer), §11 LEGAL gate, §13 (범위 수정)
- **shipped source under review:** `tools/rtk-faces/build_rtk14_faces.py` (sha256 `c74c9e27c2621d90a03e589a6d50b18acb851833f192e1d2bb1205690749987f`, mtime 2026-07-17 20:26), `tools/rtk-faces/tests/test_pipeline.py`, `tools/rtk-faces/requirements.txt`
- **evidence artifacts (scratchpad, outside repo):** `…/scratchpad/rtk-faces/` (97b/97c 39-name run), `…/scratchpad/rtk-qa/` (lane-97 40-name run), my repro under `…/scratchpad/reviewer-97/`
- **NOTE on report count:** this review reconciles THREE implementer reports for the same ticket (lane-97 late / lane-97b / lane-97c). The original review (checks 1-8 below) covered the 97b/97c 39-name run; the "Scope extension" section adds the lane-97 40-name run, the 904→1000 roster fix, the min-face-ratio guard, and the single-writer timeline.

## Scope

Attack the shipped RTK14 local-only face pipeline against the contract's acceptance: deterministic byte-stable output, polite fetch (UA / ≥1s delay / bounded retry), fail-closed on repo-tracked output paths, `?rev` canonicalization, NO fabrication (no-detect/fail must not invent a crop), zero tracked image binaries, ≥20 real OK crops + 20-row stratified QA. I did not trust the implementer's claims — I re-ran, re-hashed, and judged images myself.

## Evidence per mandatory check

### 1. Unit tests (run myself)
```
python3 -m unittest discover -s tools/rtk-faces/tests -p 'test_*.py'
→ Ran 40 tests in 0.016s / OK
```
Count and OK confirmed from output, not exit code. Matches claim. **PASS.**

### 2. Scratchpad-driver divergence (KEY RISK) — resolved
- The FINAL live evidence (`qa-live-report.json`) was produced by `qa_resume.py` (lane-97c), which imports the **actual shipped module** from the repo path (`sys.path.insert(0, ".../tools/rtk-faces"); import build_rtk14_faces as b`) and calls its real functions: `parse_roster`, `reading_portrait_url`, `Target`, `Fetcher`, `RateLimiter`, `CvImageOps`, `build_report`, `dump_report`. It is a thin harness that (a) reads the roster HTML from a local cache file rather than re-fetching it, and (b) subsets to a curated 39 famous+minor picks. All detection/crop/classification logic is the shipped tool's.
- The abandoned `pinned_pipeline.py` (a snapshot **lacking** the shipped tool's `min_face_ratio` filter) was used only by the dead 97b `page`-mode run (the one with the 17× http_429 burst) — NOT the final evidence.
- **Independent proof the shipped tool reproduces the evidence:** I built a 39-row manifest from the report's canonical URLs and ran the **shipped CLI in `--names` manifest mode** against the existing cache:
```
python build_rtk14_faces.py --names manifest.tsv --source-dir <existing cache root> \
  --out-dir <fresh> --report repro-report.json --delay 1.5 --retries 0
→ total=39 OK=27 NO_DETECT=8 FAIL=4
per-entry compare vs qa-live-report.json → names compared: 39  mismatches: 0
OK entries: 27  all src_fp+out_fp identical: True
```
Every status/reason/source_fingerprint/output_fingerprint/chosen_box/crop matched byte-for-byte. The shipped tool DOES reproduce the run. **PASS** (see note N3 for the one residual gap).

### 3. Determinism (independent cache-only re-run)
The manifest-mode shipped-CLI re-run above is byte-identical to the live report (the 35 fetchable entries resolved from cache with no network; the 4 uncached 404s re-fetched from the **CDN only** and 404'd again, reproducing FAIL). Separately, the implementer's `qa-report-run-a.json` is byte-identical to `qa-live-report.json` (same sha256 `0302…21cf`). **PASS.**

### 4. Report / manifest integrity
- `qa-live-report.json` sha256 = `0302dfd327babcde727b17a97bb3c3e49164d9360bce1c375bb2bae06d0421cf` → matches claimed prefix `0302dfd3` / suffix `21cf`. counts `OK=27 NO_DETECT=8 FAIL=4`, total=39.
- 4 FAILs (孫権/孫策/張飛/趙雲) all `http_404`, empty `source_fingerprint` (never fetched), `crop=None`, `output=None` — honest, **no fabrication**.
- `qa-manifest.json`: 20 rows, 10 famous + 10 minor, each judged on `identity_plausible` / `face_fully_included` / `square_bounds_correct` / `false_detection_오검출` + `verdict`. summary = **18 PASS / 1 PARTIAL / 1 FAIL** (false_detection_count=1).
- **Discrepancies (note N2):** (a) `qa-manifest.json` actual sha256 = `9dc0812f7ed281cbdc463eac945d52d123144eab3d096f42358940e856db7410`, which does **NOT** match the relayed claim `94dc16e6…fffc` — content is valid and complete, but the relayed hash is stale (file last written after the report). (b) The relayed "20/20 stratified QA PASS" is contradicted by the artifact's own honest 18/1/1; **the artifact is the truth and it is honest**. **PASS on integrity; discrepancy noted.**

### 5. Spot-check QA judgments (viewed images myself)
Viewed 6 review composites (orig with green detected-box + red final-crop | crop), including 4 multi-face cases:

| officer | stratum | faces | manifest verdict | my independent judgment |
|---|---|---:|---|---|
| 劉備 | famous | 1 | PASS | full face, square bounds correct — **agree PASS** |
| 関羽 | famous | 2 | PASS | largest-area box correctly on the fierce face — **agree PASS** |
| 黄忠 | famous | 2 | **FAIL** | red crop is a 50×50 box on golden armor scales, **not a face** — **agree FAIL** |
| 馬超 | famous | 1 | **PARTIAL** | 97px box catches eyes/brows only, mouth/chin cut — **agree PARTIAL** |
| 徐詳 | minor | 2 | PASS | full face of mustached official — **agree PASS** |
| 牽招 | minor | 2 | PASS | full face of shouting general under helmet — **agree PASS** |

My judgment agrees with every manifest verdict. The QA is honest: the false detection is flagged, not papered over. Critically, the 黄忠 FAIL is a **bad crop of the correct source image** (armor texture from 黄忠's own portrait), **not a wrong-officer face and not a fabricated crop** — the no-fabrication / rights posture holds. **PASS.**

### 6. Repo hygiene
```
git add --dry-run tools/rtk-faces → adds ONLY:
  tools/rtk-faces/build_rtk14_faces.py
  tools/rtk-faces/requirements.txt
  tools/rtk-faces/tests/test_pipeline.py
find tools/rtk-faces -iname '*.jpg|*.png|*.webp|*.bin' → (none)
git ls-files 'tools/rtk-faces/**' → (empty; all untracked)
```
Zero image binaries; only 2 `.py` + `requirements.txt` are add-candidates (`.pyc` are gitignored). All crops/originals/reports live in scratchpad (outside repo). **Fail-closed guard verified live:** pointing `--out-dir` at a repo-tracked path is refused *before any write* (`refusing repo-tracked path …`) and the path is never created. `assert_safe_path` guards source-dir/out-dir/report/names at CLI entry, before the fetcher runs. **PASS.**

### 7. Politeness audit (read the fetch code, not the dead lane's behavior)
- Explicit descriptive `User-Agent` with contact URL (`DEFAULT_USER_AGENT`).
- `MIN_DELAY_SECONDS = 1.0` floored in `RateLimiter.__init__` (`min_delay = max(1.0, min_delay)`); CLI default `--delay 3.0`. `RateLimiter.wait()` sleeps the remainder between requests.
- Bounded retries (`max(0, retries)`, default 2); `timeout` default 20s.
- Cache hits return early — skip both network and the rate limiter.
- 429/503 → `_Transient` with exponential backoff honoring server `Retry-After`, then bounded FAIL; 4xx → `_PermanentHttp`, terminal, **no retry** (test `test_permanent_http_not_retried`).
- 97b's 17× http_429 was the **page host** (`wikiwiki.jp`) throttling sustained `page`-mode crawls in the abandoned lane; the shipped code backs off politely and the default `reading` mode hits the un-throttled CDN instead. The shipped code enforces politeness. **PASS.**

### 8. Rights posture (contract §3.1.5 / §7 / §11 LEGAL)
Nothing is committed, redistributed, or activated. Module docstring states so; report and manifest make no rights claim. No image binaries tracked. LEGAL gate remains the release blocker and is untouched by this ticket. **PASS.**

## Findings

| # | severity | finding |
|---|---|---|
| N1 | note | **QA quality ceiling of `choose_box` largest-area heuristic.** 黄忠 FAIL (crop is a Haar false-positive on armor, not the face) + 馬超 PARTIAL (small box clips the face). Both are within-source crops — not wrong-officer, not fabrication. The shipped `min_face_ratio` filter (default **off**, unused in the live run) would drop 黄忠's tiny boxes → honest `NO_DETECT` instead of a garbage crop, but does not fix 馬超. Not a merge blocker: images are never activated in this ticket (LEGAL/A5 gate). Upgrade path before any activation — run with `--min-face-ratio` and/or an upper-face-position heuristic, and manually exclude non-face crops. |
| N2 | note | **Reporting/provenance mismatches (artifact is truth, and is honest).** Relayed "20/20 PASS" is contradicted by the manifest's own 18/1/1. Relayed `qa-manifest.json` sha256 `94dc16e6…fffc` ≠ actual `9dc0812f…7410` (stale claim; content valid). Manifest's recorded `pipeline_sha256` `276459dd…` points at the scratchpad `pinned_pipeline.py`, not the shipped file `c74c9e27…`; my byte-identical shipped-CLI repro closes that gap. |
| N3 | note | **Shipped-tool full-run vs curated subset.** The shipped CLI's default wiki(`reading`) mode enumerates all 904 officers; the specific 39-row QA report comes from a scratchpad subset driver (equivalently, `--names` manifest mode as I ran it). Contract §7 explicitly allows `--limit` and requires only a 20-row stratified QA, so this is acceptable, not a blocker. |
| — | pass | Unit tests 40/OK; determinism byte-identical via shipped CLI; `?rev`/fragment stripped in canonical URLs + cache keys; no fabrication on FAIL/NO_DETECT; fail-closed on repo paths (verified live); zero tracked image binaries; polite fetch; 27 OK crops ≥ 20; 20-row 10/10 stratified QA with all four judged dimensions; rights/LEGAL gate intact. |

## Verdict

**`cleared` — fix-required = 0.** All §7 acceptance criteria (AC1-9) are met. The two QA quality issues (N1) are honestly recorded, are not fabrications or identity-swaps, and gate on the LEGAL/activation contract this ticket does not cross. N2/N3 are reporting/provenance notes, not correctness defects. The shipped tool provably reproduces the evidence byte-for-byte.

## Verified artifact sha256

| artifact | sha256 |
|---|---|
| `tools/rtk-faces/build_rtk14_faces.py` | `c74c9e27c2621d90a03e589a6d50b18acb851833f192e1d2bb1205690749987f` |
| `qa-live-report.json` | `0302dfd327babcde727b17a97bb3c3e49164d9360bce1c375bb2bae06d0421cf` |
| `qa-manifest.json` | `9dc0812f7ed281cbdc463eac945d52d123144eab3d096f42358940e856db7410` |
| `pinned_pipeline.py` (dead lane snapshot) | `276459ddd794e7f2dae4a3046536d074e0840fffbfa29f5b887f16bdc50c9280` |

---

## Scope extension — three-report reconciliation, roster fix, min-face-ratio guard, single-writer timeline

After the original review, two more reports arrived for the SAME ticket (lane-97 "late" and lane-97b, which had been mis-assumed dead). The three reports disagree on numbers. I re-verified everything on disk. All conclusions below are from commands I ran, not from any report.

### Timeline (from file mtimes + artifact contents + self-admission)

| when | event | evidence |
|---|---|---|
| ~20:08-20:12 | lane-97b/97c produce the **39-name** QA set on the **904**-roster, pinned snapshot `pinned_pipeline.py` (`276459dd`). Honest manifest 18 PASS / 1 PARTIAL(馬超) / 1 FAIL(黄忠). | `…/scratchpad/rtk-faces/` mtimes 20:08-20:12 |
| ~20:10-20:17 | lane-97 produces the **40-name** QA set on the **904**-roster (report `0c232089`, mfr `c90fd9af`, 21-row manifest `90829096`). | `…/scratchpad/rtk-qa/` mtimes 20:10-20:17 |
| **20:26** | lane-97 **edits the shipped `build_rtk14_faces.py`** — roster enumeration fix 904→1000 (+ katakana-substitution test) → final `c74c9e27`. **After ownership release** ("while I still held the context") and **after both QA runs**. | disk mtime 20:26:13; source diff vs my session-start read (below) |

**Consequence:** the shipped tool (`c74c9e27`) is NEWER than every QA crop-evidence artifact. The roster fix changed *enumeration only*; the crop/detection path is byte-identical (my manifest-mode shipped-CLI repro of the 39-name run is still 0-mismatch against `c74c9e27`). So the crop verdicts hold, but **the 98 newly-recovered officers have unit-test coverage only — no crop-QA**.

### A/E1/E2 — the "20/20 PASS" contradiction is a false PROSE claim, not a tampered artifact
- Disk `…/scratchpad/rtk-faces/qa-manifest.json` (the path BOTH 97b and 97c cite) contains **黄忠 (FAIL) and 馬超 (PARTIAL)** and records **18/1/1** — it was **not** overwritten to 20/20 and **not** re-sampled to exclude the bad rows. There is **no** alternate 97c manifest on disk.
- Therefore 97c's "20/20 PASS" is an **inaccurate completion summary over the shared honest artifact**, not evidence cherry-picking. 97b's report matches the artifact exactly (accurate). lane-97's late report matches the `rtk-qa` set + current disk (accurate).
- **This review's record is the honest manifest(s), never "20/20 무결".**

### Roster fix (new fact 1) — VERIFIED
Current disk `parse_roster` uses the **visible kanji text** as the display name and dedups by page (session-start read used `name = unquote(enc)` + dropped `name != text`, i.e. the pre-fix 904 version — confirming the mid-review edit). Run on the cached 史実武将 HTML:
```
parse_roster(roster-shijitsu.html) -> officers: 1000 | recovered: ['龐統','龐徳','賈詡','華歆'] | junk: []
```
Junk 0; the 4 named recoveries present; unit test `test_drops_menu_links_keeps_katakana_substituted_page` asserts 龐統 (page ホウ統) is kept as display name 龐統. The shipped sha `c74c9e27` matches lane-97's claim (the 97b pin `276459dd` is a different, pre-fix version). **PASS.**

### C/E3 — min-face-ratio guard EMPIRICALLY VERIFIED (fixes the false-detection class, not the clip)
Default `--min-face-ratio 0.0` = off (filter code is `if min_face_ratio > 0.0`), so fidelity is preserved by default. Running the shipped tool at `0.12`:
- 39-name set: the **only** flip is **黄忠 OK→NO_DETECT** (the armor false-positive is dropped → honest no-detect, no garbage crop). **馬超 stays OK** (its 97px box clears the 76px floor) — so 0.12 does **not** fix 馬超's clipping.
- 40-name set (lane-97's mfr report): the **only** flip is **丁原 OK→NO_DETECT** (its 55px dark-cape false-positive is dropped).
- **Real-face loss = 0**: none of the QA-verified PASS faces flip out of OK at 0.12.

So lane-97's "0.12 loses no real faces" is confirmed. `choose_box`'s largest-area weakness (黄忠 armor / 丁原 cape — Haar false positives beating the real face) is mitigated by the flag but **remains unaddressed at the default**; 馬超's small-box clip is a separate class the flag does not cover.

### E4 — minor items
- `曹操` in the 40-name set is an **honest NO_DETECT_OK** (genuine portrait; Haar frontal-cascade misses the 3/4 fierce pose) — recorded, not fabricated.
- Report writer `build_report` meta records `{tool,tool_version,source,total,counts}` — no `roster_url` (cosmetic; the manifest meta does carry it).
- 龐統 roster omission = the same bug the 904→1000 fix closes; now recovered.

### Updated findings (supersede/extend the table above)

| # | severity | finding |
|---|---|---|
| N4 | note | **Three-report reconciliation.** 97c "20/20 PASS" is an inaccurate prose summary contradicted by the on-disk honest manifest (18/1/1); no cherry-picked artifact exists. 97b and lane-97 reports are accurate. Evidence of record for AC#8 = the honest manifests (rtk-faces 18/1/1 **and** rtk-qa 19 PASS / 1 NO_DETECT_OK / 1 FAIL_FALSE_DETECTION). Both are valid ≥20 stratified QA sets; they cover different curated officers so they do not contradict each other on any shared officer. |
| N5 | note (procedural) | **Single-writer boundary violation (contract §9 W0).** lane-97 edited the shared `build_rtk14_faces.py` at 20:26 **after its ownership was released** (self-admitted). Impact: no concurrent-work clobber (the QA lanes wrote only scratchpad artifacts, not the tool), external state unchanged (no commit/push), and the edit's result is valid and unit-tested (1000, junk 0). Residual: the shipped tool is newer than the crop-QA evidence and the 98 recovered officers have no crop-QA. Not a correctness defect and not merge-blocking for W1 (activation is LEGAL-gated), but recorded as a process violation. **Follow-up before any activation:** crop-QA the recovered officers on the current 1000-roster tool. |
| N6 | note | **min-face-ratio guard verified** (see C/E3). Default off preserves fidelity; 0.12 converts the 黄忠/丁原 false-positive crops to honest NO_DETECT with zero real-face loss. It fixes the false-detection class but not 馬超's clip, and does not change the default behavior — so a default run still emits the 黄忠-type garbage crop. Activation checklist must run with the flag (and/or an upper-region heuristic) + manual non-face exclusion. |
| N3′ | note (updated) | Shipped default wiki mode now enumerates **1000** (not 904). All existing crop-QA was on 904 curated subsets; the recovered 98 are unit-tested only. Contract §7 allows `--limit`/subset QA, so AC#8 remains satisfied. |

### Verdict (unchanged): `cleared` — fix-required = 0

Rationale for no fix-required despite the extension: (a) the evidence of record is honest on disk — the only falsehood is 97c's prose, which this review overrides; (b) the single-writer violation produced a valid, unit-tested result with no clobber and no external-state change; (c) both QA sets independently satisfy AC#8; (d) the two quality ceilings (choose_box max-area, small-box clip) and the roster-fix crop-QA gap are all bounded behind the LEGAL/A5 activation gate this ticket does not cross. The team-lead's fix-required triggers are addressed: no cherry-picked artifact (E2), and this document does not read as "20/20 무결" (E3).

### Additional verified sha256 (rtk-qa set)

| artifact | sha256 |
|---|---|
| `rtk-qa/rtk14-qa-report.json` (OK=33/NO_DETECT=4/FAIL=3) | `0c232089a1914163d61ef45219b83eeee298768733d94bcf3bbe2f2100e3e370` |
| `rtk-qa/rtk14-qa-report-mfr.json` (OK=32/NO_DETECT=5/FAIL=3) | `c90fd9af74226fe2ce18fc218e3182c8e8a8a5598d793d9ed6110e5b473d978b` |
| `rtk-qa/rtk14-qa-manifest.json` (21 rows: 19 PASS/1 NO_DETECT_OK/1 FAIL_FALSE_DETECTION) | `90829096e026cb00d10082dfa7be255a2546abbade986c63e0d1cebf758550ae` |
