# Vendored anti-slop subset

Source: https://github.com/dmmulroy/anti-slop
Revision: `13909c9e07590f5a85d93c62712259fe14a883f7` (MIT, Dillon Mulroy).

The six files below and LICENSE are byte-for-byte upstream copies. `index.ts`
is a local entry point registering only the four approved rules. No upstream
rule/helper has been modified; no other upstream embedded vendor code is included.
Keep LICENSE and embedded notices when updating. Updates require a separate review.

| Upstream path (under src/, except LICENSE) | SHA256 |
| --- | --- |
| `rules/no-chained-type-assertions.ts` | `72f8e981520f94adac0ffbdab8f8e84e5f2a4595f14e4a6471be917a85a20a15` |
| `rules/no-widen-then-assert.ts` | `63f2c07cc42b8ad60a7e868efbd7b29e462b9ba960fbd07bbc8f590ef9e3f879` |
| `rules/no-reflect-apply.ts` | `f7920a332106252d6a24cdc917a2c1a433eb438792996ccceb5a25cd6e591dbb` |
| `rules/no-reflect-get.ts` | `95c5aa0f8e23b10d9345b1776082e08704ec6590298176a8ede8dfdb6e7cb56f` |
| `shared/scope.ts` | `e38d41eeada854dbd02285e84e065d10e69460695e06e53fbe8662c963ae7eb4` |
| `shared/reflect-method.ts` | `d1b7bd13ca714bf20bec15e835ed0526316b7274e06bd9a582cf623c09ac1d40` |
| `LICENSE` | `10ed33bf340d6d63dc0633dfc917a346b369b6aa41fe20734aefc6a3fb75ba17` |

## Run

CI uses Node 22 (>=22.18 for native TypeScript stripping), with oxlint and
@oxlint/plugins pinned to 1.78.0. From the repository root:

```sh
npm --prefix tools/oxlint ci --ignore-scripts
python3 -m unittest discover -s tools/ci -p 'test_anti_slop_lint.py'
python3 tools/ci/anti_slop_lint.py --base-ref origin/main
```

Only tracked/unignored TypeScript files in web/game, web/gateway and web/shared
are scanned (tests included). Categories and unrelated rules are disabled.
Nested config and inline suppression cannot hide new debt. Failures, parser
errors, unknown diagnostics and incomplete scan summaries fail closed.

The initial 79 findings are pinned by path, rule, exact source text and SHA256
in tools/ci/anti_slop_lint_baseline.json, verified against the fixed sourceCommit.
Offsets/line numbers are not identities: adding lines before old debt is allowed.
Counts and source fingerprints may only decrease against both the snapshot and
PR base. Identical additional copies also fail. New files at RATCHET_HEAD allow
zero debt. There is deliberately no --write-baseline or automatic refresh mode;
reviewed reductions may edit the snapshot, but additions/refreshes fail against
the fixed sourceCommit. Introducing new exemptions requires a separate policy PR.

This CI/tooling PR does not fix product source (PR-A2), run agentic e2e/gdp-ts,
change existing QA requirements, or authorize merge/deployment/game resets.
