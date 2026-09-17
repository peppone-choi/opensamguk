# Han world v3 release: 1133 identities

This immutable release snapshot contains nine strategic loader inputs and five ownership/supply companions. The catalog records each original path, raw SHA-256 and byte length, and deterministic gzip blob hash (level 9, mtime 0). `sourceBaseCommit` records the reviewed source checkout.

Do not regenerate this directory in place when future cities, names or boundaries change. Register a new release identity instead. The 832/835, 846, 848 and 1098 catalogs remain unchanged.

What changed from 1098 — no province is left without a city-bearing jurisdiction (user directive 2026-09-17):

- Folds (`cityless-jurisdiction-fold-decisions-v1` → `cityless-jurisdiction-folds-v1`, the last han-tiles stage): 11 city-less jurisdictions hand their provinces to the jurisdiction of the same place's city — 杜→杜陵(5), 鄄良→鄄城(285), 益都→益(382), 㡉縣→㡉侯國(917), 贊→陰(411), 新平郡 漆→漆(80), 毗陵典農校尉→毗陵(848), 汶山郡→蜀郡 廣都(632, the only touching city jurisdiction), 章武郡 東平舒→河閒 東平舒(191), and the two duplicate cities below. Geometry and parent regions are unchanged; the four post-郡國志 commanderies left without jurisdictions keep their rows with an empty `jurisdictionIds` and a null seat.
- Duplicate cities withdrawn: 977 巴郡 漢昌 (CHGIS 44621) duplicated 579, and 989 北地郡 富平 (CHGIS 70523, the 永初 寄治) duplicated 627. Both jurisdictions fold into the kept city's county.
- 977, 989 and 1099–1133: 37 external-settlement cities (`w5-external-settlement-route-claim`, REVIEWED_SOURCE_CLAIM on `data/map/external-places.json` records) for 夫餘·高句麗·沃沮·濊·三韓·伽耶·倭·夷洲·流求·烏桓·鮮卑·南匈奴·西羌·白馬氐·哀牢·山越. The two freed numbers are reused through registry rebinding so ids stay contiguous.
- Province 1304 at the Yalu mouth moves from 卒本 to 遼東 西安平 (`provinceTransfers`): 漢書 地理志 places 西安平 where the 馬訾水 enters the sea.
- Island commanderies are joined by the sourced sea links already used by v2 (`SEA_LINKS`), and water-separated city groups (一大國·伊都國) link to their commandery seat.

Provinces stay 1,594 and province indices are unchanged; jurisdictions 1,144 → 1,133; every province resolves to its own jurisdiction's city (`province-city-attribution-v1`: 1,594 `OWN_COUNTY_SEAT`).

Runtime loading verifies the pinned catalog hash, complete expected path set and every raw/compressed hash and length before the strategic loader validates cross-file identity and topology. World selection must use the complete city roster plus all persisted spatial pins, never only the city count.
