# PR #528 (`work/opensamguk/han-unitset-2000-crewtype`) — Independent Cross-Agent Critique

Scope: data/unitset, tools/unitset, logic/ tests, common/ tests, app/ game-engine IT — 6 non-review files (`tools/assets` sprite tooling removed from this PR in `fa3e571a`), `b9cec50c..fa3e571a`
Verdict: cleared

Reviewer: independent `code-reviewer` lane. Base `origin/main` = `b9cec50c` (verified merge-base).
Diff read directly from git; every number below was produced by a command run in this review, not copied
from the PR body or a commit message. Findings from any prior review of `work/opensamguk/han-map-wave` /
PR #508 were deliberately not reused.

## Raw verification evidence

Worktree `/private/tmp/han-map-wave-unitset` at `a4235574`.

```
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test --no-daemon --rerun-tasks
  -> exit 0.  test-results XML aggregated (not the BUILD SUCCESSFUL text):
     common: files=45  tests=255  failures=0  errors=0  skipped=0
     logic:  files=296 tests=3320 failures=0 errors=0 skipped=0
     UnitCatalogTest      tests=8 failures=0 errors=0 skipped=0
     HanGateRegionsTest   tests=5 failures=0 errors=0 skipped=0
     UnitSetTableHanTest  tests=5 failures=0 errors=0 skipped=0

python3 -m unittest discover -s tools/map/tests        -> OK, Ran 28,  skipped=10, exit 0
python3 -m unittest discover -s tools/scenario/tests   -> OK, Ran 252, skipped=1,  exit 0
python3 -m unittest discover -s tools/agent-system/tests -> OK, Ran 9, exit 0
python3 tools/scenario/han_route_node_candidates.py --check   -> exit 0
python3 tools/scenario/han_route_node_selection.py --check    -> exit 0
python3 tools/scenario/validate_han_route_node_selection.py   -> exit 0
   "approved production manifest ... approved=780 scenarios=15"
python3 tools/unitset/build_unitset.py --check -> exit 0 ("data/unitset/units.json — 최신")
```

Generated-artifact/builder sync confirmed: `--check` reproduces the committed `units.json` byte-identically.

## Verified claims (these hold up)

1. **The B1 regression test is real and red-able, not vacuous.** I patched
   `tools/unitset/build_unitset.py` back to the old single-bucket behaviour
   (`bucket = "tribe" if k == "tribe" else "_other"` -> `bucket = "_other"`), regenerated
   `units.json`, and re-ran `:common:test --tests '*UnitCatalogTest*'`:
   ```
   FAILED: 郡과 부족을 동시에 요구하는 병종은 ReqRegions 가 뭉치지 않는다(B1)
   AssertionFailedError: 永昌+夷 게이트가 ReqRegions 1개로 뭉쳤다 ... expected: <2> but was: <1>
   ```
   Builder and data were restored afterwards (`build_unitset.py --check` re-verified clean).
2. **The B1 fix itself is correct.** `RecruitAlgorithm.canRecruit` iterates `unit.reqConstraints`
   requiring every entry (AND) while `ReqRegions` matches with `.any(...)` (OR) — so promoting
   `tribe` into its own `ReqRegions` entry does restore the intended AND. Applied consistently:
   26 han units changed from one merged `ReqRegions` to two, with no unit losing a gate value.
   `adjacentTribe` is correctly left OR-merged (2100 유주돌기 unchanged).
3. **The `34177c3f` exclusion is honestly disclosed and factually correct.**
   `shasum -a 256 infra/src/main/resources/map/han.json` at HEAD =
   `a61cbd8a…4b3670`, exactly `PINNED_LEGACY_HAN_MAP_SHA256` in
   `tools/scenario/validate_han_route_node_selection.py:105`; `34177c3f`'s version hashes
   `2a2cd0c5…4d3a58`. Including it would break the pin, as claimed.
4. **The 永昌 test really was moved, not dropped.** `HanGateRegionsTest.kt:98-102` leaves an
   in-code explanation, and `git show f2d986d8` carries the rationale. The move is technically
   forced: HEAD's `HanGateIndex.kt` contains zero occurrences of `永昌` (34177c3f's contains 2),
   so `cityWithKey("永昌")` would throw.
5. **The float-tolerance change does not weaken a real assertion.** Values compared are ~6-15;
   `1e-9` is ~6 orders of magnitude tighter than any rounding-rule regression could be, and both
   sides still compute independently.
6. **The IT change is a root-cause fix**, replacing a hardcoded `crewType:1100` with the world's
   own `unitSet` metadata, so it will not re-break on the next default swap.

## Findings

### [HIGH] Confidence HIGH — the PR body does not disclose commit `a9e9d901`, which is the majority of the diff
`tools/unitset/build_unitset.py`, `data/unitset/units.json`, `tools/assets/build_unit_prompts.py`,
`tools/assets/check_sprite_chroma.py`

`git log --stat b9cec50c..HEAD` shows 7 commits, of which `a9e9d901 feat(unit): 한 병종 데이터와 생성 규칙을
보강한다` alone is **1,496 insertions / 126 deletions** — 2 brand-new Python scripts (499 lines), 7 new
han units, and a redesign of the builder's tech axis:

- `reqTech` stops being read from authored `reqTech`/`reqConstraints` and becomes
  `max(techFloor, techByMaterial)` (`build_unitset.py:203`);
- new `tables.techByMaterial` and `tables.materialCeiling` blocks (absent from `origin/main`'s
  `units.json`), with `sys.exit()` hard-fail rules;
- new authored key `techFloor` added to `KEYS`;
- `trait_of` gains an `"event"` key.

Real data consequences I measured: 2121 연노사 `reqTech` 2000 -> 3000, 2186 변진 철갑병 0 -> 2000,
2191 부여 기병 0 -> 1000, and 2127 청주병 stats change (def 130->109, atk 110->125, avoid 15->10).

The PR body's "변경 내용" section enumerates only the `defaultCrewTypeId` flip, the IT, B1, and the
float tolerance. Its verification section states the verified tree was `origin/main` plus five named
cherry-picks (`753b8d8d 8ced5e14 62ca5c82 fea53d71 aaad9f16`) plus one commit — none of those SHAs
exist on this branch, and that composition does not match the 7 commits actually here.

This matters in this repo specifically: the same PR body spends a paragraph justifying, correctly,
why a *disclosed* out-of-scope commit must not be silently absorbed. Undisclosed *in*-scope changes
of this size are the same failure in the other direction.

**Fix:** either split `a9e9d901` into its own PR, or rewrite the PR body to describe the tech-axis
redesign, the 7 new units, the 4 measured data deltas above, and the two new `tools/assets` scripts;
and restate the verification section against the commits that are actually on the branch.

### [HIGH] Confidence HIGH — 6 of the 7 new han units are permanently unrecruitable dead data at this HEAD
`data/unitset/units.json` (ids 2196-2201)

Every `ReqRegions` gate value in the shipped han catalog was resolved against the shipped
`common/.../HanGateIndex.kt` and `HanCityConst.kt`. Six values resolve to nothing:
`武陵`, `牂牁`, `越巂`, `永昌`, `鬱林`, `夷`. Because `reqConstraints` entries are ANDed, a unit whose
commandery entry is unresolvable can never be recruited anywhere:

```
2196 무릉만 노수  ReqRegions[武陵]  (+ 蠻)
2197 오계만 도병  ReqRegions[武陵]  (+ 蠻)
2198 장가이병    ReqRegions[牂牁]  (+ 夷)
2199 월수 수병    ReqRegions[越巂]  (+ 叟)
2200 애뢰 노수    ReqRegions[永昌]  (+ 夷)
2201 오호만병    ReqRegions[鬱林]  (+ 蠻)
```

The same scan against `origin/main`'s `units.json` returns **0** such units. This is a regression
introduced by this branch. The PR body discloses only the `永昌` case, and only as the rationale for
moving a test — never as a data defect, and never for the other five. Nothing in the test suite
guards gate-key resolvability, which is why 255+3320 green tests do not catch it.

Note the interaction with the B1 fix: the AND restoration this PR is titled around currently protects
only units that no player can reach until PR B lands.

**Fix:** either hold ids 2196-2201 for PR B (which supplies the gate keys), or add a catalog test
asserting every han `ReqRegions` value resolves to a `HanGateIndex` key or a `CityConst` region name.
The latter is the durable fix and would have caught this class before review.

### [MEDIUM] Confidence HIGH — the transferred-test comment points at a file that contains no such coverage
`logic/src/test/kotlin/opensamguk/logic/actions/military/HanGateRegionsTest.kt:98-102`

The replacement comment states the AND restoration "그 아래 UnitSetTableHanTest 등 다른 회귀로 이미
커버된다". `UnitSetTableHanTest` is a different file (not "그 아래"), and its five tests cover
set support, per-set listing, per-set default ids, cross-set id rejection, and the cost curve —
zero gate assertions. The actual replacement coverage is
`common/src/test/kotlin/opensamguk/common/constants/UnitCatalogTest.kt`. A PR-B author following this
pointer looks in the wrong place, and the comment overstates what is covered.

**Fix:** name `common/.../UnitCatalogTest.kt` `郡과 부족을 동시에 요구하는 병종은 ReqRegions 가 뭉치지 않는다(B1)`
explicitly in that comment.

### [MEDIUM] Confidence HIGH — `check_sprite_chroma.py` is a check that can never fail
`tools/assets/check_sprite_chroma.py:28-40`

`main()` counts failures into `fails`, prints them, then `return 0` unconditionally. Wired into CI as
written, it green-lights a directory of entirely non-magenta raws. Two smaller defects in the same
function: `sys.argv[1]` is unguarded (`IndexError` with no argument, no usage message), and the
summary `len(list(raw.glob('*.png')))//2` under-reports whenever an image lacks a `.raw.png` twin.
Both new `tools/assets` scripts are referenced by nothing in the repo — no workflow, no doc, no test.

**Fix:** `return 1 if fails else 0`; `argparse` for the path; count the files actually inspected.

### [MEDIUM] Confidence MEDIUM — only `tribe` was split out; two region-type keys still silently OR
`tools/unitset/build_unitset.py:176-181`

`bucket = "tribe" if k == "tribe" else "_other"` puts every non-tribe gate key
(`province`/`commandery`/`region`/`city`/`external`) plus `adjacentTribe` into one OR group. A unit
authored as `{province: 涼州, commandery: 隴西}` would be recruitable from either — exactly the defect
class B1 fixes. I scanned the catalog: no unit currently declares two non-tribe gate keys, so there is
no live bug. But nothing prevents one, and the builder fails silently rather than loudly.

**Fix:** bucket per key by default (`bucket = "_or" if k == "adjacentTribe" else k`), or `sys.exit()`
on an unmodelled multi-region-key combination the way `materialCeiling` already does.

### [LOW] Confidence HIGH — the second assertion in the new default-crew-type test cannot fail independently
`common/src/test/kotlin/opensamguk/common/constants/UnitCatalogTest.kt:86-89`

`assertTrue(units.values.any { it.reqConstraints.isEmpty() })` is entailed by the assertion three
lines above it (`default.reqConstraints.isEmpty()`, where `default` is drawn from `units`). It can
only fail if the first already failed. The KDoc claims it keeps catching the defect class "if
defaultCrewTypeId is re-pointed at some other unconstrained unit" — but that is precisely the case
the first assertion already covers. Harmless, but it is dead weight sold as coverage.

### [LOW] Confidence HIGH — the PR body's `common:test` count is stale
PR body reports `common:test -> 254 tests`; HEAD measures **255**. The final commit `a4235574` added
the B1 test after the reported run, so the quoted figure predates the tree being reviewed. The
`logic:test` 3320 figure matches exactly.

## Positive observations

- The `--check` mode makes the generated artifact and its builder verifiable in one command, and it
  passes — the review could confirm the data was actually regenerated rather than hand-edited.
- Falsifying the B1 test by regressing the builder took one line, because the assertion targets the
  builder's structural output rather than a snapshot. That is the right shape for a regression test.
- The `34177c3f` exclusion is the strongest part of this PR: refusing to recompute a human-reviewed
  provenance pin, and saying so in the PR body with the reason, is exactly right.
- Moving the `永昌` assertion with an in-code note and a rationale-bearing commit message, instead of
  deleting it, is honest handling of a genuinely blocked test.
- `materialCeiling` failing loudly via `sys.exit()` rather than silently clamping is the correct
  choice for a data-generation pipeline.

## Recommendation

REQUEST CHANGES (`fix-required`). Two HIGH findings at HIGH confidence: an undisclosed 1,496-line
commit that is the bulk of the diff, and 6 newly-shipped unrecruitable units that the disclosed
scope-exclusion narrative does not cover. The titular fix (2006 -> 2000) and the B1 AND restoration
are both correct and correctly tested; the problem is what rode along with them unannounced.

## Update 2026-08-24: re-verification after fixes

Branch moved `a4235574` -> `22b55497` -> `cdb07088` (PR body also rewritten). Re-ran everything myself
in the same worktree (`/tmp/han-map-wave-unitset`), did not trust the author's claims, re-fetched and
re-diffed from scratch. New HEAD `cdb07088`, base still `origin/main` = `b9cec50c` (re-confirmed via
`git merge-base`).

### What I re-ran

```
git diff --stat origin/main...HEAD  -> 10 files, 2271(+) 169(-), matches described set
                                        (includes the 2 new review docs + the B2 test commit)

python3 -m unittest discover -s tools/map/tests          -> OK, Ran 28,  skipped=10
python3 -m unittest discover -s tools/scenario/tests      -> OK, Ran 252, skipped=1
python3 -m unittest discover -s tools/agent-system/tests  -> OK, Ran 9,  skipped=0
python3 tools/scenario/han_route_node_candidates.py --check  -> exit 0
python3 tools/scenario/han_route_node_selection.py --check   -> exit 0
python3 tools/scenario/validate_han_route_node_selection.py  -> exit 0
   "approved production manifest ... approved=780 scenarios=15"
python3 tools/unitset/build_unitset.py --check -> exit 0 ("data/unitset/units.json — 최신")

JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test --no-daemon --rerun-tasks
  -> BUILD SUCCESSFUL. XML aggregated directly (glob + ElementTree, not exit code/text):
     common: tests=256 skipped=0 failures=0 errors=0
     logic:  tests=3320 skipped=0 failures=0 errors=0
     UnitCatalogTest      tests=9 skipped=0 failures=0 errors=0
     HanGateRegionsTest   tests=5 skipped=0 failures=0 errors=0
```

All numbers match the PR body's current verification section exactly (256 / 3320 / 9 / 5).

### HIGH #1 (undisclosed `a9e9d901`, wrong SHAs) — RESOLVED

`gh pr view 528 --json body -q .body` now: discloses `a9e9d901` by name with a description matching
its actual diff (`git show --stat a9e9d901`: 1091(+) in `units.json`, new `build_unit_prompts.py`
(455) and `check_sprite_chroma.py` (44), 32-line change to `build_unitset.py` — all confirmed).
Verification section's SHAs — `34177c3f`, `b9cec50c` — both resolve to real commits
(`git cat-file -t`) and `b9cec50c` is confirmed as the actual merge-base with `origin/main`. The
five nonexistent cherry-pick SHAs from the old body are gone.

### HIGH #2 (6 dead units 2196-2201) — RESOLVED (disclosure), not a code fix

PR body now has a dedicated "이 PR로 뽑을 수 없는 것" section listing all 6 units with their gate keys
and the reason they're unreachable, and links tracking issue #529. I independently confirmed:
`gh issue view 529` — open, title "han 신규 郡+부족 유닛 6종(2196–2201)이 郡 게이트 키 부재로 도달 불가",
matching. This doesn't make the units recruitable (that's PR B's job per the body, correctly scoped
out here) — it converts a silent defect into a disclosed, tracked one, which is what the finding
asked for.

### MEDIUM (credit comment) — RESOLVED

`HanGateRegionsTest.kt`'s comment now names `UnitCatalogTest` (the actual B1 coverage) instead of
`UnitSetTableHanTest`. Confirmed via `git show 22b55497 -- logic/.../HanGateRegionsTest.kt`.

### MEDIUM (`check_sprite_chroma.py` vacuous check) — RESOLVED

`main()` now `return 1 if fails else 0` (was unconditional `return 0`). Confirmed via
`git show 22b55497 -- tools/assets/check_sprite_chroma.py`. (The unguarded `sys.argv[1]` and the
`//2` under-count I also flagged were not touched — minor, not re-flagging as blocking.)

### MEDIUM (tribe-only OR bucket gap) — RESOLVED (documented, not code-changed, as expected)

`build_unitset.py` now carries a comment above `gate_groups` explaining exactly what I found: only
`tribe` is split out, `province`/`commandery`/`region`/`city`/`external` still bucket into one `_other`
OR group, scoped out because no unit in the repo currently uses that combination. This was always a
"make it loud" ask, not a "fix the code" ask, since there's no live bug — documentation satisfies it.

### LOW (redundant assertion) — RESOLVED

The second `assertTrue(units.values.any { it.reqConstraints.isEmpty() })` is removed from
`UnitCatalogTest.kt`'s default-crew-type test (`git show 22b55497` confirms), leaving only the
assertion that can actually fail independently.

### LOW (stale "254 tests" claim) — RESOLVED

Body's verification section now says `common:test -> 256 tests`, matching my measured 256 exactly (no
staleness — the B2 test commit landed before this figure was quoted, unlike last round).

### B2 (new `generic` regression, disclosed by both reviewers as the other reviewer's finding) — independently mutation-tested, CONFIRMED real

Added by `cdb07088`. I did not trust either reviewer's or the author's claim of having mutation-tested
this — reran it myself from scratch:

1. Backed up `data/unitset/units.json`, edited the live source (not a build artifact — `processResources`
   overwrites build outputs from source, so mutating the copy doesn't survive) to set
   `sets.han.defaultCrewTypeId` from `2000` back to `2167`.
2. `./gradlew :common:test --tests "opensamguk.common.constants.UnitCatalogTest" --rerun-tasks` ->
   **FAILED**: `UnitCatalogTest > han 기본 병종은 generic 이다(F4)() FAILED — org.opentest4j.AssertionFailedError`
   (the older `reqConstraints.isEmpty()`-based test in the same class stayed green, exactly as claimed
   — 2167 has no `reqConstraints` but `generic:false`).
3. Restored the file from backup, confirmed `git status --short` clean and
   `build_unitset.py --check` reports "최신", then reran the full class -> 9/9 green.

This independently reproduces both reviewers' mutation-testing claims. The test reads `generic`
straight from the JSON resource (`GameUnitDetail` doesn't carry that field at runtime) and would
catch a regression back to 2167 that the pre-existing constraint-only test misses.

### B1 (sprite-tooling scope: `build_unit_prompts.py` + `check_sprite_chroma.py`, 499/1768 = 28%) — confirmed NOT addressed, and correctly so

Independently reconfirmed via `git show --stat a9e9d901`: these two files are still bundled.
`build_unit_prompts.py` (+455) and `check_sprite_chroma.py` (+44) total 499 lines of a 1768-line diff
(28%), unrelated in cause-and-effect to the unitset/gate fix this PR is titled around. This is the other independent reviewer's B1, not mine originally, and I
confirm it is real and still open. I was told, and independently confirm from the PR body's own "미해결로
남긴 것" section, that this has been explicitly escalated to a human/team-lead scope decision (split into
a separate PR vs. keep-with-disclosure) rather than silently dropped or auto-resolved. **I am not
re-flagging it as a new HIGH from my own review** — it is a known, already-disclosed, already-tracked
item awaiting a decision above this review's authority, not something either round of review missed.
If a decision is needed from me: I'd lean toward splitting it (it's genuinely causally unrelated to the
titular fix, and the disclosure paragraph, while honest, doesn't change that a reviewer of "the unitset
fix" has to also review unrelated sprite tooling to approve it) — but that's a preference, not a blocker
I'm asserting independently need fixing before merge, given it's already at the right decision level.

### Verdict

**Cleared.** Every finding from my original round (2 HIGH, 3 MEDIUM, 2 LOW) is resolved, each verified
independently against the actual diff/commits/test output rather than trusting the author's summary.
The new B2 test is real, meaningful, and independently mutation-confirmed. The only open item (B1,
sprite-tooling scope) is a different reviewer's finding, already escalated by design, not a gap in this
round's fixes — I am not treating it as blocking this verdict.

## Update 2026-08-24 (2): fa3e571a — sprite-tooling files actually removed; format fix

Branch moved `cdb07088` -> `fa3e571a`. The B1 item I left as "known-escalated, not blocking my
verdict" in the previous update has now been resolved by removal, not just further disclosure.
Re-verified independently, not from the other reviewer's or the author's claims:

- `git show --stat fa3e571a` — pure deletion, `tools/assets/build_unit_prompts.py` (-455) and
  `tools/assets/check_sprite_chroma.py` (-44), 499 lines removed, no other files touched.
- `ls tools/assets/build_unit_prompts.py tools/assets/check_sprite_chroma.py` — both absent.
  `grep -rln "build_unit_prompts\|check_sprite_chroma"` across `*.py/*.yml/*.md/*.kt` in the tree
  finds nothing outside this review file's own prose.
- History preserved on `origin/wip/opensamguk-209/sprite-chroma-tools` (fetched and confirmed both
  files present there). One inaccuracy in the `fa3e571a` commit message: it claims the preserved
  branch predates the vacuous-check fix (`22b55497`) and would need that fix reapplied — I checked
  `git log` on the wip branch and it actually branches from `08cca52b` (after `22b55497`), so
  `check_sprite_chroma.py` on that branch already has `return 1 if fails else 0`. Cosmetic
  (commit-message-only, doesn't affect this PR's content or any test), noting it so whoever picks
  up OPENSAM-230 doesn't duplicate a fix that's already there.
- PR body (`gh pr view 528 --json body -q .body`) no longer describes either file as included; the
  "고도화" section describing the `check_sprite_chroma.py` fix is gone; a new paragraph documents
  the removal, the preservation branch, and tracking ticket OPENSAM-230.
- Full suite re-run on `fa3e571a` after clearing stale Gradle daemons/caches that had produced one
  spurious `NoClassDefFoundError` run and one spurious single-test-failure run (both artifacts of
  daemon/config-cache contention from overlapping local runs, not real regressions — a clean
  `--no-daemon --no-configuration-cache --no-build-cache` run resolved both):
  ```
  tools/map/tests            -> OK, Ran 28,  skipped=10
  tools/scenario/tests       -> OK, Ran 252, skipped=1
  tools/agent-system/tests   -> OK, Ran 9,  skipped=0
  han_route_node_candidates.py --check   -> exit 0
  han_route_node_selection.py --check    -> exit 0
  validate_han_route_node_selection.py   -> exit 0, approved=780 scenarios=15
  build_unitset.py --check   -> exit 0 ("data/unitset/units.json — 최신")

  :common:test :logic:test --rerun-tasks (XML aggregated directly):
     common: files=45  tests=256  skipped=0 failures=0 errors=0
     logic:  files=296 tests=3320 skipped=0 failures=0 errors=0
     UnitCatalogTest    tests=9 skipped=0 failures=0 errors=0
     HanGateRegionsTest tests=5 skipped=0 failures=0 errors=0
  ```
  All numbers unchanged from the pre-removal round, as expected (nothing referenced the removed files).

Separately: a peer flagged that my `Verdict:`/`Scope:` lines didn't match the gate regex
(`^Verdict: (cleared|fix-required|quarantined-with-proof)\s*$`) because I'd appended a parenthetical
after `cleared`, and that `Scope:` still listed `tools/assets` and a stale commit range
(`b9cec50c..cdb07088`) after those files left the PR in `fa3e571a`. Both are fixed at the top of this
file: `Verdict:` is now the bare anchored value, and `Scope:` reflects the current 6-file diff and
`b9cec50c..fa3e571a`. The peer was correct that a review document with a stale declared scope is the
same defect class as this PR's own B1 — fixed here for the same reason it was fixed there.

**Verdict: cleared**, unchanged in substance from the prior update — this round confirms the one
remaining open item from that verdict (B1) is now actually resolved by removal, and fixes this
document's own scope/verdict staleness.
