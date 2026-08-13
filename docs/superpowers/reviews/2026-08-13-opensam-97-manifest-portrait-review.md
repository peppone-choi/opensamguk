# OPENSAM-97 manifest-only portrait pipeline review

Scope: `tools/` OPENSAM-97 manifest-only, cache-only full-frame portrait pipeline and unchanged frontend portrait contain surfaces.

- `tools/rtk-faces/build_rtk14_faces.py`
- `tools/rtk-faces/requirements.txt`
- `tools/rtk-faces/tests/test_cli.py`
- `tools/rtk-faces/tests/test_cache_reader.py`
- `tools/rtk-faces/tests/test_pipeline.py`
- `tools/rtk-faces/tests/test_sources_and_paths.py`
- portrait rendering sources under `web/game` and `web/gateway` (read-only)
- `docs/loops/opensam-97-full-frame-resize-2026-07-17/LEDGER.md`

No portrait dataset, CDN configuration, runtime portrait row, deployment, or
external tracker is in scope.

## Independent review rounds

Three read-only reviewers independently attacked provenance, security, and the
visual/frontend contract. Their initial verdicts were `FIX_REQUIRED`:

- Provenance: the first draft incorrectly applied a robots rule across
  authorities, described an unverified cache as lawful, retained stale
  page-parser prose, and recorded a stale test count.
- Security: a predictable output symlink could overwrite its target, cache
  reads and decoded pixels were unbounded, and fixed-pass URL decoding allowed
  deeper encoded separators.
- Visual: the synthetic Pillow geometry was correct and portrait sources used
  `contain`, but the Python/security failures and stale ledger prevented
  clearance. Historical captures are synthetic and do not replace lawful live
  officer QA.

## Remediation

- Removed the roster/page/network path. `--manifest` is required and cache
  misses remain `FAIL/cache_miss`.
- Described cache bytes as operator-supplied and provenance-unverified; rights
  remain `BLOCKED`.
- Repeated URL decoding until stable before namespace validation.
- Deduplicated officer pages by their fully decoded identity so percent-encoding
  aliases cannot create duplicate or misattributed records.
- Bounded cache bytes and decoded pixels. Cache opens are non-blocking and
  descriptor metadata rejects directories, FIFOs, and every other non-regular
  entry as `FAIL/cache_unsafe` before a read. Platforms without both required
  safe-open flags fail closed before opening a cache entry.
- Kept descriptor ownership explicit until `fdopen` succeeds, and translated
  descriptor conversion, metadata, close, and read failures into per-entry
  `FAIL/cache_unsafe` results.
- Replaced direct output/report writes with same-directory atomic replacement,
  preventing output symlinks from redirecting writes.
- Added real CLI and Pillow regressions for deterministic bytes, 633×900 →
  148×210 four-corner preservation, no upscale, cache miss, nested encoding,
  resource limits, and symlink safety.

## Evidence

- Python unit suite: 45 tests, 0 failures.
- Python compile: exit 0.
- Game portrait Vitest: 1 file, 22 tests, 0 failures.
- Gateway portrait Vitest: 1 file, 24 tests, 0 failures.
- Game and gateway TypeScript checks: exit 0.
- Portrait source scan: relevant game/gateway surfaces use `object-fit: contain`;
  no portrait `cover` remains.
- `git diff --check`: exit 0.
- Live famous/minor officer visual QA: `PENDING / SOURCE BLOCKED`; no lawful
  cache or reuse clearance was available.
- Generic Fablize tool-failure notifications repeated while direct commands
  completed successfully. They are isolated as wrapper noise; the explicit
  command outputs above are the acceptance evidence.
- Terminal provenance review: `CLEARED` after the machine report began carrying
  explicit `provenance: unverified`; no crawler/downloader/network path or
  tracked portrait/deploy change remains.
- Terminal security review: `CLEARED`; exact 45/45, nested encoding, cache and
  output symlinks, decompression bomb, byte/pixel bounds, and no-network surface
  were independently rechecked.
- Terminal visual/frontend review: `CLEARED`; full-frame geometry and unchanged
  `contain` surfaces are correct. Lawful live-officer QA remains source-blocked.

## Verdict

Verdict: cleared

No merge or deployment is authorized by this review.
