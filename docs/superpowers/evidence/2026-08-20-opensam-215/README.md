# OPENSAM-215 mock evidence

This directory contains documentation-only visual evidence for the Han map design specification.

## Input and provenance

- Sole map-data input: `data/map/han-tiles.json`
- Input SHA-256: `1979c193de6774af7c3cf5a9ddfd1c81bf94ead5b8c5b46dafd06bed03c6888d`
- No `terrain-grid.json`, `han-places.json`, shapefile, downloaded map, or licensed raw source is read or bundled.
- The generator preserves the current product isometric mapping: `x=(col-row)*scale+ox`, `y=(col+row)*scale/2+oy`.

The mock ownership hues are not historical data. `han-tiles.json` contains geographic Voronoi ownership but no live game-nation ids. The generator groups real Jun cells into three broad column bands only to demonstrate hue/value separation, and every map mock labels this as an encoding specimen.

The `181년 1월 상순` header reproduces the OPENSAM-209 scenario chrome. It does not change or relabel the tile payload's geographic metadata year, which remains 220.

## Regenerate

From the repository root:

```bash
python3 -m py_compile docs/superpowers/evidence/2026-08-20-opensam-215/generate_mock.py
python3 docs/superpowers/evidence/2026-08-20-opensam-215/generate_mock.py
```

Dependency: Python 3 with Pillow. The generator reads no network resources.

## Outputs

| File | Size | SHA-256 |
| --- | --- | --- |
| `han-map-direction-overview.png` | 1440×900 | `c3a5889a9497812ecd35552af1deef84ff83be1e64552cc0d8dd3934aeec9cd5` |
| `han-map-direction-local.png` | 1440×900 | `7299552dc1ef5660dd07152a24119752ded50652ee410d646275c786995a4918` |
| `han-map-token-specimen.png` | 1440×760 | `4c66c303752330a7cc7c9bb9ad8acd5448562493bb47ac66296d0846879e134e` |

## Source-screen evidence

The design diagnosis also inspected OPENSAM-209's read-only real-Han captures in the parent checkout:

- `.playwright-mcp/opensam-209-real-han-before-restart-1280x900.png`, SHA-256 `d686fbddeb5944ba155186402b8f32c361b81544a3be4f3761acfe3e3ff1c7b8`
- `.playwright-mcp/opensam-209-real-han-after-restart-1280x900.png`, SHA-256 `c8dde7b423e8757600de8f2ee41ce0f31bbfb4a1ea956fb032fd3a7214315d63`

They are not copied here. Their 10 changed pixels are confined to `(90,807)-(98,860)`; the visible Han map is stable across restart.
