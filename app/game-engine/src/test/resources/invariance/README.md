# Final world behavior baseline

Run with JDK 21 and Docker:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
  --tests 'opensamguk.engine.invariance.YuzhouCampaignInvarianceTest' \
  --tests 'opensamguk.engine.boot.PassChainInvarianceIT' --rerun-tasks
```

`world-state-sha256.txt` pins SHA-256 over UTF-8 rows written by `WorldStateBaseline`.
Rows have an explicit field order. All normalized rows are sorted by their full
content, so generated row ID order does not affect the hash. Metadata map values
are sorted recursively.
The two 豫州 runs take 36 phases with fixed seeds `00` and `01`. The S3 chain takes
48 phases through the imported, database-backed world. The seed `01` test also
compares two independent executions before checking its committed hash. The two
seeds currently reach the same final state: this replay check alone does not
prove sensitivity to RNG seed changes. S3 does: changing only the final hex
digit of its imported runtime `hiddenSeed` made its final hash change from
`7d7bb2bbf88065514a920655fb460a79b92d10ec8c5b91d3e9dffd73370ba861` to
`d2c068b146d4435bccec624d7a83b85424ee494d457882a25071f4a913e1112f`
while the chain assertions still passed. This checks one seed change, not all
possible command-key or seed-reason renames.

The projection includes calendar, gameplay counters and ownership for generals,
cities, nations, troops, diplomacy, retainers, units, operations, plans, sieges,
positions, and primitive metadata values. It deliberately excludes:

- World/server IDs, writer epoch and world version; generated retainer, unit,
  operation, and battle-plan row IDs; request and order IDs.
- Wall-clock timestamps and scheduled turn times; paths, absolute path strings,
  topology revision/hash pins, config and map names.
- Storage metadata **keys** (only sorted values enter the hash), metadata keys
  with an exact `id`, camel-case `Id`/`Ids`, snake-case `_id`/`_ids`, or
  camel/snake suffix `Time`/`_time`, `Path`/`_path`, `Hash`/`_hash`,
  `Revision`/`_revision`, `Version`/`_version`, and metadata
  strings containing the old product name, absolute paths, or ISO timestamps.
- Names, titles, display text, colors, user IDs, raw logs, access logs, and
  unsupported non-primitive metadata object values.

Stable fixture general/city/nation IDs and province node IDs remain because they
identify relationships and capture ownership; changing them may require a
documented projection update. This gate cannot detect changes confined to an
excluded field or tell which field changed from a SHA mismatch alone. The
existing chain assertions and the seed replay assertion remain complementary.
Bugok commander assignment is also outside this projection: its retainer row ID
is generated, and this baseline does not resolve it to a stable person key.
