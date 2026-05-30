# compare-command-logs (P2 GATE-STATIC, Task GS1)

PORT of `legacy/devsam-core2026/tools/compare-command-logs.mjs`, re-pointed PHP↔Kotlin.

A **static source-scan** gate that cross-checks the per-command **action-log template
surface** of the PHP grand truth against the Kotlin resolvers — the GATE-STATIC half of the
P2 two-tier log gate (the per-command runtime byte-match lives in
`logic/.../golden/*GoldenTest.kt`, GATE-RUNTIME). No build, no DB, no `typescript` parser
dependency — pure Node ESM.

## What it does

- **PHP extractor** (kept VERBATIM from the legacy tool): scans
  `legacy/devsam-core/hwe/sammo/Command/{General,Nation}/*.php` (PHP_ROOT re-pointed from the
  legacy 2026 fork's `legacy/hwe/sammo/Command` to **`legacy/devsam-core`**, the grand truth),
  extracting `pushGeneralActionLog` / `pushGlobalActionLog` / … call templates with the same
  `normalizeTemplate` (strip `<1>…</>`, strip `<b>`, collapse `$var`/`${…}`→`${}`, collapse
  whitespace) and the same guard/target exclusions. The PHP `extends` chain is threaded so an
  inheriting command file with no own log call (`che_농지개간 extends che_상업투자`,
  `che_모병 extends che_징병`) picks up the parent template.
- **Kotlin extractor** (REPLACES the legacy TS `ts`-parser extractor): a dependency-free
  regex/brace scan of `logic/src/main/kotlin/opensamguk/logic/actions`. Keys are driven by the
  AUTHORITATIVE `CommandRegistry.resolve()` `when` arms (`"<key>" -> <factory|ctor>`), so the
  computed-key resolvers (`CommerceInvestment` via `che_${name}`, `RecruitAlgorithm` via
  `che_$name`) bind to their concrete captured keys. Each resolver's `context.addLog` /
  `addGlobalActionLog` templates — plus its delegated base-class templates (CommerceInvestment,
  JoinCommand) and local `val log = when/if-else "…"` resolutions — feed the same normalizer.
  `addLogTo`/`addPlainLogTo` (dest-general scope) and `addGlobalActionLog` (broadcast scope) are
  dropped by the same target/SYSTEM-scope filters the PHP side uses, so only the acting-general
  action-log surface is gated.

## Matched set + ignore-list

The matched set is **scoped to the 28 committed PHP-captured P2 goldens**
(`logic/src/test/resources/golden/p2/*.json`). The ignore-list
(`compare-command-logs.ignore.json`) has two mechanisms:

1. `IgnoreCommands` — drops WHOLE command keys: the **12 backlogged P2 commands** (documented in
   `tools/php-golden/p2-capture-backlog.md`; scenario_1010 state gaps — opening-restriction /
   age≥60 / multi-general troop / sub-max capital / seed-fragile lottery — NOT failures), plus
   (with `--all-commands`) the ~57-of-93 non-P2 commands.
2. `Global` / per-command `templates`+`regex` — drops individual cross-module lines that survive
   the scope filters but whose PHP grand truth lives outside the `Command/` tree (e.g.
   `che_장비매매`'s `…보충합니다.` line is emitted by `ActionItem/che_보물_도기.php`, which the
   command-file scan cannot reach — an extractor SCOPE artifact, not a parity bug).

## Usage

```sh
node tools/php-golden/compare-command-logs/compare-command-logs.mjs            # report
node tools/php-golden/compare-command-logs/compare-command-logs.mjs --gate     # CI gate (exit 1 on any mismatch)
node tools/php-golden/compare-command-logs/compare-command-logs.mjs --json     # machine report
node tools/php-golden/compare-command-logs/compare-command-logs.mjs --all-commands  # widen beyond the captured set
```

`--gate` exits non-zero unless `mismatches == 0` over the captured set AND every captured
command has a Kotlin resolver log. The matched-count rises monotonically as families land.

## Current matched-count (this commit)

```
Captured-golden set:   28
Compared commands:     28
Matched commands:      28   ← GATE PASS (0 mismatch)
Mismatched commands:    0
Missing in Kotlin:      0
Ignored (documented):   2   (che_장비매매 ActionItem-hook line; Global cross-command lines)
Backlogged (ignored):  12   (p2-capture-backlog.md — state gaps, not failures)
```

The 28 matched: develop {상업투자, 농지개간, 성벽보수, 수비강화, 치안강화, 기술연구, 정착장려,
주민선정, 물자조달, 군량매매}; military {징병, 모병, 훈련, 맹훈련, 사기진작, 소집해제, 이동};
personnel {하야, 등용}; trade {증여, 헌납, 장비매매}; nation {발령, 포상, 천도, 국호변경, 국기변경};
founding {거병}.
