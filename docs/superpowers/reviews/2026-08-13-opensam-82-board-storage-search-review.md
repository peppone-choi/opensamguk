# Independent Review — OPENSAM-82 board storage and search spike

## Scope

Independent review of the exact uncommitted OPENSAM-82 documentation set:

- `docs/superpowers/research/2026-08-13-opensam-82-board-storage-search-spike.md`
- `docs/superpowers/specs/2026-08-13-opensam-82-board-storage-search-decision.md`
- `docs/superpowers/plans/2026-08-13-opensam-82-board-storage-search-follow-up.md`
- `docs/loops/opensam-82-board-storage-search-2026-08-13/LEDGER.md`

This review is documentation-only. It did not provision GCP resources, access the
control plane, alter a tracker, or claim that attachments, search, snapshots, or a
production disk already exist.

## Evidence reviewed

- Current production authority: `docs/agent/lifecycle-ops.md:5,25-28,59-63`,
  `docs/agent/claude-user-manual.md:150,207`, `.github/workflows/deploy.yml:207-244`,
  and `docker-compose.production.yml:1`. These consistently establish GCP Compute
  Engine `e2-standard-2`, the `gcp-prod` runner, and sibling
  `opensamguk-docker` as the active control plane; the draft correctly treats EC2
  wording as stale and the repository compose as compatibility-only.
- Current board and media boundary: `V40__gateway_board.sql:1-46`,
  `GatewayBoardService.kt:23-74,99-110,159-203`,
  `GatewayBoardController.kt:20-87`, `GatewayBoardContentSanitizer.kt:6-16`,
  `V32WorldScopeCompletionMigrationTest.kt:50-135,656-669`,
  `LocalProfileIconStorage.kt:64-291`, `ProfileIconDecoder.kt:18-94`,
  `docker-compose.production.yml:158-176,232-268`, and
  `infra/nginx/nginx.conf:99-124`.
- Actual browser ingress rather than only the gateway controller:
  `web/gateway/lib/board.ts:152-170`,
  `web/gateway/app/api/board/[...path]/route.ts:6-117`, and
  `app/gateway-api/src/main/resources/application.yml:26-30`.
- Primary operational/database evidence: [Compute Engine Persistent Disk](https://cloud.google.com/compute/docs/disks/persistent-disks),
  [disk auto-delete](https://cloud.google.com/compute/docs/disks/modify-persistent-disk),
  [snapshot schedules](https://cloud.google.com/compute/docs/disks/about-snapshot-schedules),
  [uniform bucket-level access](https://cloud.google.com/storage/docs/uniform-bucket-level-access),
  [Cloud Storage lifecycle](https://cloud.google.com/storage/docs/lifecycle),
  [PostgreSQL text-search indexes](https://www.postgresql.org/docs/16/textsearch-indexes.html),
  [text-search dictionaries](https://www.postgresql.org/docs/16/textsearch-dictionaries.html), and
  [pg_trgm](https://www.postgresql.org/docs/16/pgtrgm.html).

The draft's PD-vs-local-volume-vs-GCS decision is evidence-backed and remains
proposed; its ILIKE-first, deferred-tsvector, measured-pg_trgm direction correctly
avoids claiming a Korean tokenizer or unsupported production index. Its GCP target,
global-table classification, and proposed no-direct-nginx media read rule are also
consistent with the inspected source.

Tooling note: generic Fablize tool-failure notices recurred around successful
read-only inspections. The same known harness baseline is already isolated in the
draft ledger at `LEDGER.md:67-80`; this review relies on the successful terminal
source reads and linked primary documents, not on those notices.

## Findings ranked

### CRITICAL

- None.

### HIGH

- **H1 — The planned 2 MiB / 10 MiB multipart feature has no executable
  browser ingress contract.** The decision requires JSON-or-multipart post creation
  and a same-origin attachment route (`decision.md:102-134`); 82-B requires up to
  five 2 MiB images and 82-C requires the live JSON/multipart UI path
  (`follow-up.md:89-130,139-161`). But the current public board client posts through
  `/api/board/posts` (`web/gateway/lib/board.ts:161-170`), while the catch-all proxy
  only admits the existing post/comment/pin paths and converts every write body with
  `request.text()` (`web/gateway/app/api/board/[...path]/route.ts:14-41,58-72,96-117`).
  It would reject `/attachments/{id}`, cannot preserve multipart binary parts, and
  would also convert a future image GET to text while discarding upstream
  `Content-Disposition`, `X-Content-Type-Options`, and cache headers. Directly
  choosing `/api/gateway/` instead is a materially different browser/auth contract;
  the draft never selects it.

  The proposed sizes are independently impossible at the current lower layers:
  gateway-api caps each multipart file at 50 KB and a request at 64 KB
  (`application.yml:26-30`), and the current nginx configuration has no scoped
  `client_max_body_size`; nginx's documented default is 1 MiB. Consequently, a
  backend-only implementation could pass focused tests while every valid five-image
  browser submission remains unreachable or byte-corrupted.

  **Required remediation:** make 82-B/82-C explicitly own one browser-visible route
  design. It must either extend the board Next proxy or deliberately use a controlled
  `/api/gateway/` route, with the same-origin/auth behavior stated. If the proxy is
  selected, it must forward fixed multipart fields and files without `text()`
  conversion, serve attachment bytes without text conversion, and preserve the
  security/cache response headers. Add a narrowly scoped ingress limit aligned with
  the 10 MiB payload plus multipart overhead at nginx and Spring, rather than
  silently broadening all endpoints. Acceptance must exercise five binary files
  through the real browser-facing route, prove their stored/read hashes or bytes,
  test per-file and aggregate 413 boundaries, and prove withdrawn/deleted attachment
  reads remain indistinguishable 404s with the intended headers on active reads.

### MEDIUM

- **M1 — “Same backup window” is not a recovery consistency contract for the
  database plus Persistent Disk.** The decision correctly states that a filesystem
  move and PostgreSQL transaction are not one atomic transaction
  (`decision.md:108-121`), yet its backup policy only says to initiate a database
  backup and media snapshot in the same declared window (`decision.md:142-154`;
  82-F repeats it at `follow-up.md:243-261`). Those two artifacts can represent
  different points around an upload, promotion, withdrawal, or purge. The proposed
  restore drill detects a missing/orphan mismatch but does not define whether restore
  fences writes, drains/checkpoints the operation journal, or deterministically
  reconciles a restored ACTIVE row with a missing file (and the inverse). A failed
  drill would therefore expose the condition without an executable safe recovery
  path.

  **Required remediation:** before 82-F can claim recoverability, select and test a
  cross-resource recovery strategy: a documented write fence/journal checkpoint and
  ordered DB-plus-disk backup, or a restore-time reconciler with defined outcomes for
  every split point. The control-plane acceptance criteria must inject at least one
  mid-upload and one mid-withdrawal backup/restore case and prove that no attachment
  is served until reconciliation reaches a defined state, with orphan handling and
  the actual RPO/RTO recorded.

### LOW

- None.

Verdict: fix-required
