# Web CI shards — D49 (2026-10-03)

`web execution` uses four separate hosted runners for game and one for gateway.
The required checks remain `web (game)` and `web (gateway)`. The required
`contracts` check also retains its name; its map unit suite runs in the separate
`han-map-tests` child. Required slow-map suites and JVM/IT skip rejection remain.

Each browser runner collects the complete unsharded inventory with `--list`
and runs its assigned shard for both smoke and the topdown switch build.
Playwright configuration, worker count, retries, test selectors and job timeout
are unchanged. File-level sharding can be uneven; measurements from an actual
successful run are required before claiming a speed improvement.

The aggregator requires all selected child jobs to succeed and validates each
phase's complete shard set, run/attempt/head/workflow identity, completion,
source/project identity and complete executed union. Missing, duplicate,
skipped, cancelled, failed or unexecuted tests fail. An empty individual shard
is allowed only when the complete union covers the nonempty collected suite.
The existing explicit no-topdown-spec case remains; empty smoke is rejected.

Native JSON spec ids vary when a shard contains only one project: Playwright
retains the first project's id when merging project reports. Cross-shard
identity therefore uses source file/line/column/title and project name. Native
execution objects and suite hierarchy are preserved.

Artifacts `web-<app>-<phase>-e2e-shard-<n>-of-<count>-attempt-<attempt>` retain
each original phase receipt, inventory, native JSON, log and failure traces.
`web-<app>-aggregate-attempt-<attempt>` contains the merged execution objects
and `web-e2e-shard-aggregate-v1` receipts holding each original receipt and its
SHA-256. This is explicitly a new schema; it must not be treated as a single
native browser invocation or accepted by an O3 consumer that only supports
`web-e2e-phase-v1`. Selected O3 UI proofs retain their separate retry0 and
producer/source/start-final requirements. C1 owns that consumer follow-up.

JVM core XML is uploaded on success as well as failure for stable individual
suite receipts; existing required JVM checks and their skip gates are unchanged.

Rollback is a normally reviewed revert of this PR. No protection setting,
production deployment, operational data, frontend product source or test
coverage exemption is introduced.

References: [Playwright sharding](https://playwright.dev/docs/test-sharding),
[JSON reporter](https://playwright.dev/docs/test-reporters#json-reporter),
[v1.52 JSON reporter source](https://github.com/microsoft/playwright/blob/v1.52.0/packages/playwright/src/reporters/json.ts).
