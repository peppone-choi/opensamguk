# OPENSAM-124 GA-079 PHP oracle capture independent review

Scope: Current working-tree changes across `.codex/`, `app/`, and `tools/`; `.codex/config.toml` and the `app/game-engine` loader-baseline diff were checked as concurrent non-GA-079 work, while the GA-079 implementation/evidence review covers `tools/php-golden/` and its linked `docs/` records.
Verdict: cleared

## Review evidence

- The canonical artifact [`ga079-nation-bulk-php.json`](../../loops/cqrs-runtime-safety-2026-07-18/evidence/ga079-nation-bulk-php.json) is valid JSON, exactly **8731 bytes**, and SHA-256 `a8918979ab2d532d85a4b4604c55944d76d5a70b4ee9bb726ba8161e3ff22418`.
- Its schema is `ga079-nation-bulk-php-v1`; it has ten cases; every case has an assertion object and every encoded assertion is `true`. The metadata records `@@autocommit=1`, Aria for `general` and `nation_turn`, PHP 8.3.32, MariaDB 11.4.12, and legacy revision `4de7ebec17a722d516608dbb987467f1a451dada`.
- I recomputed all eight source-evidence SHA-256 values against the local PHP oracle tree. Each matches the artifact and capture-script pin. In particular, `legacy/devsam-core/vendor/sergeytsalkov/meekrodb/db.class.php:919-961` is the cited `run_success`/`run_failed` branch, and its SHA-256 is `0a393a368d5b0d49bd6cc68474582b029675ff0265878420567e241999f31685`.
- The ticket’s source table and the capture constant both cite that exact `919-961` range. The ticket correctly retains the one-daemon-write approval boundary: the observed PHP partial-durability behavior does not authorize a Kotlin API `general` write or a ring-only parity claim.

## Publish and containment review

- `stage_cleanup_and_publish` in [`run_ga079_nation_bulk.sh`](../../../tools/php-golden/run_ga079_nation_bulk.sh) stages and byte-compares the artifact in the target directory, calls `cleanup_owned_resources`, discards the stage if cleanup fails, and only then performs the same-directory `mv` atomic publish (`281-328`). The exit trap is idempotent and converts a post-success cleanup failure into a nonzero wrapper result (`330-353`).
- The ownership fence requires both `opensamguk.capture=ga079` and the exact run label before removal, then re-inspects for absence (`117-218`). Cleanup aggregates every recorded container and network failure rather than stopping at the first one (`220-255`).
- I ran `tools/php-golden/run_ga079_nation_bulk.sh --self-test-cleanup`. It intentionally failed cleanup for two synthetic containers and one synthetic network, exited `1` as required, and printed confirmation that no final artifact was published. Immediate Docker queries for `opensamguk.capture=ga079` returned no containers and no networks.
- `run_once` copies `legacy/` and `tools/php-golden/` into its per-run `work` directory before mounting `/work:rw`; only that disposable copy is writable, while the shared workspace/legacy tree is not mounted into the capture container (`436-490`).

## Output-path and syntax checks

- `ga079OutPath` requires `/out` to resolve exactly to `/out`, permits only a direct safe basename, rejects traversal and nesting, rejects symlinks, and rejects an existing non-regular or escaped path (`capture_ga079_nation_bulk.php:134-155`). Its atomic writer and reader both route through that fence (`157-190`).
- I ran the focused `--self-test-out-paths` in the PHP 8.3 golden image with `/work` mounted read-only and an ephemeral writable `/out` tmpfs. It passed the valid-basename, traversal, nested-path, and symlink-escape checks and left `/out` empty. `bash -n` for the runner, PHP lint for the capture script, and a targeted `git diff --check` all passed.

## Review-tooling note

- Two broad discovery bundles reached the tool-output cap; I recovered by rerunning the relevant files and validations as bounded, file-scoped commands. A first host-temporary variant of the path test was rejected before execution by the safety hook because its cleanup trap contained `rm -rf`; the container-only tmpfs validation above replaced it. These were review-tooling constraints, not product-test failures.

## Approval boundary

The evidence and containment remediation meet this review’s acceptance conditions. GA-079 implementation remains blocked until a human approves a daemon-owned lifecycle that represents the observed post-ring, pre-`killturn` durability boundary and focused lifecycle tests prove it.
