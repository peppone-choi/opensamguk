# Historical Han strategic artifact sets

This directory freezes the nine inputs read by `HanStrategicTopologyJson` from
the known 832-city CF5 release and the 835-city 91fa release. Original logical
paths, bytes, and internal manifest hashes are retained. Identical files share
one SHA256-named blob. `catalog.json` is pinned in the validator source.

Validate offline with:

```sh
python3 tools/map/han_world_artifact_sets.py
python3 -m unittest discover -s tools/map/tests -p test_han_world_artifact_sets.py
```

Reproduce from the two fixed Git commits with `--freeze`. Missing Git objects
fail; working-tree files are never substituted. Existing files with different
bytes are never overwritten.

This is the provenance foundation for preserving existing worlds while adding
counties. The game does not select these sets yet. CityConst/gate tables,
commandery supply policy, and scenario ownership are outside these nine loader
inputs and still require separate version-aware handling. No new county is
approved by this snapshot and no existing world is migrated or reset.

## Historical ownership companions

`ownership-catalog.json` binds scenario province ownership and the jurisdiction
conflict allowlist to the same source commits as `catalog.json`. Its compressed
blobs preserve the exact original JSON bytes. The runtime verifies the approved
catalog digest, source commit, compressed digest, decompressed length and original
digest before exposing these inputs with the selected map bundle.

The ownership payloads have identical scenario assignments across these two
historical versions, but different source metadata. Both originals are retained;
no semantic rewrite or reference to the current runtime ownership file is used.
The API's administrative projection caches parsed data by the selected immutable
historical variant. This archive does not approve new cities or impose a city
count on a future map release.

The same companion catalog also preserves each release's commandery supply links,
territory-disconnection source adjudications and V3 supply-disconnection policy.
Monthly supply uses these inputs together with the selected tiles, ownership and
runtime city map. It does not combine a historical city roster with current
policy files. Original-byte hashes still identify each archived file.

### Derived province image

The API validates the generated `han-world-v3-provinces.png` against its build metadata before serving it:
`sourceSha256` must equal the selected archived tile bytes, and `pngSha256` must equal the image bytes.
Both archived variants currently share the same tile source, so the Docker-generated image serves both.
A future variant with different tiles needs its own matching derived image; it must not reuse an image
whose source differs. Historical terrain and province responses use private revalidation, including 304 responses.
