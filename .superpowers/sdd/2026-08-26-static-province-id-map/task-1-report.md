# Task 1 — Deterministic Province ID Asset Generator

## Result

Implemented the standard-library-only province identity asset generator.  It expands
the `owner` and `seatOwner` RLE grids, validates their shared coverage, encodes the
documented 12-bit province / 8-bit commandery identity into a lossless RGB PNG, and
writes deterministic metadata.  The CLI supports both generation and no-rewrite
drift checking.

## Files changed

- `tools/map/build_province_map.py` — generator API, deterministic PNG writer,
  metadata writer, checker, and CLI.
- `tools/map/tests/test_build_province_map.py` — literal 3×2 codec, round-trip,
  determinism, validation, tamper, and map-code-safety coverage.

## Checkpoint commit

`feat: generate static province identity map` with the required
`Co-Authored-By: Codex <codex@openai.com>` trailer.  This report is included in
that Task 1 checkpoint.

## RED evidence

1. Before the generator existed:

   ```text
   python3 -m unittest tools.map.tests.test_build_province_map -v
   ModuleNotFoundError: No module named 'tools.map.build_province_map'
   ```

2. During self-review, the new decoder-boundary test failed as intended:

   ```text
   AssertionError: ValueError not raised
   ```

   The test supplied the valid-RGB but invalid hierarchy code `(0x10, 0x00, 0x01)`,
   which decodes to commandery index 255 and must be rejected.

## GREEN evidence

```text
python3 -m unittest tools.map.tests.test_build_province_map -v
Ran 5 tests in 0.012s
OK
```

The suite verifies the literal bit layout, exact dual-grid round trip, byte-for-byte
repeatability, truncated RLE, coverage disagreement, both index limits, tamper
detection in check mode, and safe map-code validation.  `python3 -m py_compile` on
the two changed Python files and `git diff --check` also succeeded.

## Real `han` dataset

```text
python3 tools/map/build_province_map.py --input data/map/han-tiles.json --output-dir build/generated-map --map-code han
generated build/generated-map/han-provinces.png: 768x669, 1138 province identities, 170 commandery identities

python3 tools/map/build_province_map.py --input data/map/han-tiles.json --output-dir build/generated-map --map-code han --check
province map check passed: han
```

- PNG: 768×669, 8-bit RGB, non-interlaced.
- PNG SHA-256: `9d87ceb0976f8ca71acc16e4f95335a35f3b75d275435e5e140dff7a6c7f4bc5`.
- Metadata SHA-256: `0ec6dc858fb6d6aa0e11e55f2751c351942b804c2dee93a5f2d0bdb7dd574972`.
- Metadata reports 332,914 covered cells, 2 water/political-covered mismatches, and
  5,883 land/political-uncovered mismatches; these are recorded only and never alter
  identities.
- `file` reported the expected PNG format.  `git status --short build/generated-map
  data/map` was empty and `git check-ignore` confirmed both generated outputs are
  ignored by the `build/` rule; no generated asset was promoted into `data/map`.

## Self-review and risks

- PNG has exactly `IHDR`, one `IDAT`, and `IEND` chunks; every scanline uses filter
  byte 0 and the compressed stream is RFC 1950 stored-DEFLATE, eliminating library
  version and compression-level variability.
- Metadata is sorted, compact JSON with one trailing newline; it contains neither
  timestamps nor absolute paths.
- `decode_identity` rejects malformed byte triplets, zero hierarchy fields, and
  commandery values outside the documented 0–254 range.
- No later task files were changed.  The remaining integration risk is intentionally
  deferred: Tasks 2–6 must package, serve, and consume this ignored build output.
