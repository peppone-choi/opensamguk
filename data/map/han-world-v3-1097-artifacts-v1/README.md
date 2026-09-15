# Han world v3 release: 1097 identities

This immutable release snapshot contains nine strategic loader inputs and five ownership/supply companions. The catalog records each original path, raw SHA-256 and byte length, and deterministic gzip blob hash (level 9, mtime 0). `sourceBaseCommit` records the reviewed source checkout.

Do not regenerate this directory in place when future cities, names or boundaries change. Register a new release identity instead. The 832/835, 846 and 848 catalogs remain unchanged.

This release adds 176 cities for han-tiles county jurisdictions that had no route node (849–1024, `w2-cityless-jurisdiction-route-claim`, REVIEWED_SOURCE_CLAIM bound to CHGIS county points or seat recoveries) and 73 non-county strongholds (1025–1097, `w3-strategic-site-route-claim`: 37 ferries 수, 28 forts 진, 8 passes 관) whose provinces were carved from their county provinces and appended after the 1,520 existing provinces (1,593 total; `strategic-site-province-carves-v1`). Existing province indices and the 848 city identities are unchanged.

The terrain rows are byte-identical to the 848 release; only the owner raster, province/jurisdiction/city records and adjacency changed.

Runtime loading verifies the pinned catalog hash, complete expected path set and every raw/compressed hash and length before the strategic loader validates cross-file identity and topology. World selection must use the complete city roster plus all persisted spatial pins, never only the city count.
