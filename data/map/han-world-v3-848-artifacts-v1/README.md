# Han world v3 release: 848 identities

This immutable release snapshot contains nine strategic loader inputs and five ownership/supply companions. The catalog records each original path, raw SHA-256 and byte length, and deterministic gzip blob hash. `sourceBaseCommit` records the reviewed source checkout; the exact release bytes are identified by the file hashes, including regenerated scenario provenance after that commit.

Do not regenerate this directory in place when future cities, names or boundaries change. Register a new release identity instead. The historical `han-world-artifacts-v1` 832/835 catalogs and the `han-world-v3-846-artifacts-v1` catalog remain unchanged.

The terrain bytes match all historical releases. Their shared source-validated PNG remains applicable; this does not imply that a future terrain revision can reuse that PNG.

This release adds 847 吳縣 (hhs:112:吳郡:001, chgis 40404, 吳郡 seat) and 848 毘陵縣 (hhs:112:吳郡:005, chgis 40451, non-seat) via reviewed variant-character folding. Source spellings (呉/毘陵) are preserved; the 220 affiliation reconstruction is not claimed. Previously unattested city 516 烏程縣 is a different county and is unchanged.

## 2026-09-16 topology repair re-pin

`han-world-v3.json`, `han-world-v3-manifest-v1.json` and `HanWorldV3848CityConst.kt` were re-pinned in
place. The 848 identities, names, levels, coordinates and boundaries are byte-for-byte unchanged; the only
delta is four route edges — 305 徐縣↔299 下邳 and 548 鄮縣↔536 山陰. Both counties are water-locked in the
raster (徐縣's nine cells sit inside a lake, 鄮縣's thirty-seven inside the sea), so the projected county
adjacency gave them no edges at all and they were unreachable from every other city. That stalled
production for fourteen hours on 2026-09-16. The repair rule lives in the generator
(`tools/scenario/build_han_world.py`, `--target han-world-v3`), which now refuses to emit a disconnected
graph at all.

This is a defect repair on a fixed identity, not a new release: no city was added, removed, renamed or
moved. The release `contentHash` does change, because the world JSON bytes are part of it — worlds already
pinned to the previous 848 bytes must be reset or re-pinned before they will load.

## 2026-09-16 misbound county re-pin (second, same day)

Re-pinned in place again. Two counties that had carried the coordinates of a same-named county elsewhere
now stand where the sources place them: 651 南安 (犍為郡) moves from 豫章郡 南安縣 in Jiangxi to the 樂山
anchor (CHGIS 96062 龍遊縣; 讀史方輿紀要 卷72 「龍遊廢縣，今州治。漢南安縣地，屬犍爲郡」), and 773 定陽
(上郡) moves from the 孫吳 定陽縣 in Zhejiang to the 洛川縣 seat (CHGIS 70227; 讀史方輿紀要 卷57
「定陽城，在縣北。漢爲定陽縣，屬上郡」). The rule and quotes live in
`data/curated/han/county-misbinding-rebindings-v1.json`. The 848 identities and names are unchanged;
the two cities' coordinates, provinces and route edges change, and so do `han-tiles.json`, the water
overlay base, the strategic topology manifest and scenario province ownership. The release
`contentHash` changes again, so worlds pinned to the earlier bytes must be reset or re-pinned.

Runtime loading verifies the pinned catalog hash, complete expected path set and every raw/compressed hash and length before the existing strategic loader validates cross-file identity and topology. World selection must use the complete city roster plus all persisted spatial pins, never only the city count.
