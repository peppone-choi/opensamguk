# rng-dump — one-shot golden generator

This tool imports the reference TypeScript RNG kernel from
`legacy/devsam-core2026/packages/common` and emits a single JSON fixture
(`common/src/test/resources/rng/rng-fixtures.json`) that every Kotlin RNG
golden test asserts against. It is the **golden generator**: run it ONCE,
commit the produced JSON, and regenerate **only** when the TS kernel changes.
It is never executed in CI.

Floats are emitted as **raw 64-bit hex** (`Buffer.writeDoubleBE`) to remove
printf rounding ambiguity; the Kotlin tests decode the same bits.

## Prerequisite (one-shot, manual host step — never CI)

`legacy/devsam-core2026` must be present, and a one-shot dependency install
must have been run so `@noble/hashes` resolves. The package ships with no
`node_modules`/`dist` today, so from the `legacy/devsam-core2026` root run:

```sh
pnpm install   # pnpm 10
```

## Run

```sh
npx tsx /Users/apple/Desktop/개인프로젝트/opensamguk/tools/rng-dump/dumpRngFixtures.mjs
```

Expected output: `wrote rng-fixtures.json`.

`npx tsx` imports the `.ts` source directly. **Documented fallback only** (if
`tsx` ever fails): build the package and re-point the imports at the bundle —

```sh
pnpm --filter @sammo-ts/common build
# then change the imports in dumpRngFixtures.mjs to dist/index.js
```

## Output

`common/src/test/resources/rng/rng-fixtures.json` — committed. Regenerate it
only when the upstream TS kernel (`LiteHashDRBG`, `RandUtil`, `TournamentRNG`,
seed serializers) changes.
