# Han world v3 release: 846 identities

This immutable release snapshot contains nine strategic loader inputs and five ownership/supply companions. The catalog records each original path, raw SHA-256 and byte length, and deterministic gzip blob hash. `sourceBaseCommit` records the reviewed source checkout; the exact release bytes are identified by the file hashes, including regenerated scenario provenance after that commit.

Do not regenerate this directory in place when future cities, names or boundaries change. Register a new release identity instead. The historical `han-world-artifacts-v1` 832/835 catalogs remain unchanged.

The terrain bytes match both historical releases. Their shared source-validated PNG remains applicable; this does not imply that a future terrain revision can reuse that PNG.

Runtime loading verifies the pinned catalog hash, complete expected path set and every raw/compressed hash and length before the existing strategic loader validates cross-file identity and topology. World selection must use the complete city roster plus all persisted spatial pins, never only the city count.
