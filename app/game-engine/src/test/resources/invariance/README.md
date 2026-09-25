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
compares two independent executions before checking its committed hash.

The projection includes calendar, gameplay counters and ownership for generals,
cities, nations, troops, diplomacy, retainers, units, operations, plans, sieges,
positions, and primitive metadata values. It deliberately excludes:

- World/server IDs, writer epoch and world version; generated retainer, unit,
  operation, and battle-plan row IDs; request and order IDs.
- Wall-clock timestamps and scheduled turn times; paths, absolute path strings,
  topology revision/hash pins, config and map names.
- Storage metadata **keys** (only sorted values enter the hash), metadata keys
  ending in `id`, `ids`, `time`, `path`, `hash`, `revision`, or `version`, and metadata
  strings containing the old product name, absolute paths, or ISO timestamps.
- Names, titles, display text, colors, user IDs, raw logs, access logs, and
  unsupported non-primitive metadata object values.

Stable fixture general/city/nation IDs and province node IDs remain because they
identify relationships and capture ownership; changing them may require a
documented projection update. This gate cannot detect changes confined to an
excluded field or tell which field changed from a SHA mismatch alone. The
existing chain assertions and the seed replay assertion remain complementary.
