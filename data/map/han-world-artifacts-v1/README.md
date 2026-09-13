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
