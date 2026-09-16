# Han world v3 release: 1098 identities

This immutable release snapshot contains nine strategic loader inputs and five ownership/supply companions. The catalog records each original path, raw SHA-256 and byte length, and deterministic gzip blob hash (level 9, mtime 0). `sourceBaseCommit` records the reviewed source checkout.

Do not regenerate this directory in place when future cities, names or boundaries change. Register a new release identity instead. The 832/835, 846 and 848 catalogs remain unchanged.

This release was prepared as `han-world-v3-1097` and never shipped under that identity; it was rebased onto the 848 repairs of 2026-09-16 and re-frozen as 1098.

- 849–1024: 176 cities for han-tiles county jurisdictions that had no route node (`w2-cityless-jurisdiction-route-claim`, REVIEWED_SOURCE_CLAIM bound to CHGIS county points or seat recoveries).
- 1025–1097: 73 non-county strongholds (`w3-strategic-site-route-claim`: 37 ferries 수, 28 forts 진, 8 passes 관) whose provinces were carved from their county provinces and appended at the end of `provinceRecords` (`strategic-site-province-carves-v1`).
- 1098: 河南尹 平陰 (`w4-vacated-county-location`, HHS `hhs:109:河南尹:011`, CHGIS 82879). City 56 五原郡 河陰 had held the same-coordinate CHGIS record of 平陰's 220 rename (82880); moving 56 to its source-attested place left that footprint to 平陰.
- 五原郡 moves home: 680 九原 (commandery seat) and 56 河陰 now stand at Baotou on land carved from the 南匈奴 direct province (`county-misbinding-rebindings-v1`, `RELOCATE_TO_SOURCE_ATTESTED_EXTERNAL_LAND`). On the 220 raster 五原郡 had only the 建安 20 僑置 enclave at 忻州, where 1007 九原(新興郡) now stands alone.

Provinces: 1,520 → 1,594 (平陰 1 + strongholds 73). Existing province indices below 1,520 and the 848 city identities are unchanged; 56 and 680 keep their ids, names and route keys while their coordinates and provinces move.

The terrain rows are byte-identical to the 848 release; only the owner raster, province/jurisdiction/city records and adjacency changed.

Runtime loading verifies the pinned catalog hash, complete expected path set and every raw/compressed hash and length before the strategic loader validates cross-file identity and topology. World selection must use the complete city roster plus all persisted spatial pins, never only the city count.
