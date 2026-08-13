# Independent remediation review — OPENSAM-82 board storage and search

## Scope

Fresh review of the remediated, documentation-only OPENSAM-82 set:

- `docs/superpowers/research/2026-08-13-opensam-82-board-storage-search-spike.md`
- `docs/superpowers/specs/2026-08-13-opensam-82-board-storage-search-decision.md`
- `docs/superpowers/plans/2026-08-13-opensam-82-board-storage-search-follow-up.md`
- `docs/loops/opensam-82-board-storage-search-2026-08-13/LEDGER.md`

This clears the proposal's remediation only. It does not claim an attachment API,
search implementation, GCP resource, snapshot, restore drill, tracker mutation, or
deployment exists.

## Evidence

- The current board proxy allows only the existing post/comment/pin paths and turns
  both writes and reads into text (`web/gateway/app/api/board/[...path]/route.ts:14-41,69-83`).
  Spring still caps multipart uploads at 50 KB/file and 64 KB/request
  (`app/gateway-api/src/main/resources/application.yml:26-30`), and nginx has no
  board-specific body-size location (`infra/nginx/nginx.conf:99-171`). Profile media
  remains a distinct, 50 KB-bounded path with nginx read-only static serving
  (`web/gateway/app/api/account/profile-icon/route.ts:29-47`,
  `docker-compose.production.yml:160-175,238-266`).
- H1 is now an executable future contract. The decision selects the same-origin
  `/api/board/posts` and `/api/board/attachments/{attachmentId}` bridge, fixes the
  allowlist, requires reconstructed fixed FormData or equivalent byte preservation,
  streams attachment responses, and forwards `Content-Type`, `Content-Disposition`,
  `X-Content-Type-Options`, `Cache-Control`, and `Vary`
  (`decision.md:102-128`). It couples the 2 MiB x 5 policy to a request ceiling above
  10 MiB plus tested multipart overhead at nginx, Next, and Spring, while preserving
  50 KB/64 KB rejection for every non-board multipart path even without
  `Content-Length` (`decision.md:119-128`).
- 82-B makes those statements testable: binary fixture hashes through the real
  browser route, read-header checks, per-file/aggregate 413 checks, no state leak,
  and profile/non-board regression cases (`follow-up.md:91-152`). 82-C adds the live
  UI/browser evidence rather than a dead file selector (`follow-up.md:160-191`).
- M1 is now a recovery contract: media writes are fenced and drained to a checkpoint
  recorded in attachment metadata and the journal; database backup and disk snapshot
  occur in the recorded order while the fence remains active; restore starts with
  media reads disabled; verified pairs alone become readable; missing/unverified
  ACTIVE files become WITHDRAWN; staged/orphan files are removed or quarantined; and
  mid-upload plus mid-withdrawal restore split points are mandatory
  (`decision.md:164-188`, `follow-up.md:273-304`).
- The operating target is consistently GCP Compute Engine `e2-standard-2` via the
  `gcp-prod` runner and sibling control repository, not EC2
  (`docs/agent/lifecycle-ops.md:5,23-28`, `.github/workflows/deploy.yml:207-240`,
  `decision.md:5-13`). The PD decision is a future, operator-owned control-plane
  task; GCS is expressly deferred and neither is provisioned here
  (`decision.md:28-57`, `follow-up.md:264-308`).
- Search is correctly split: 82-D specifies bound, escaped literal `ILIKE` over
  title plus canonical plaintext, while tsvector/GIN remains unselected and 82-E may
  introduce `pg_trgm` only after Korean fixtures and measured plans preserve the same
  contract (`decision.md:190-224`, `follow-up.md:193-262`). The dependency graph keeps
  schema foundation, media/UI, search/performance, and control-plane recovery in
  separate tickets (`follow-up.md:17-29`).

Tooling note: CodeGraph is absent from this isolated worktree. Generic Fablize
failure notices accompanied successful read-only terminal output; the same known
harness baseline is documented in `LEDGER.md:69-91` and is not treated as product
validation.

## Findings

### CRITICAL

None.

### HIGH

None. H1's ingress, binary forwarding, header preservation, scoped limits,
non-board regression, and browser-visible acceptance are all specified.

### MEDIUM

None. M1 now fences/checkpoints writes, requires an ordered fenced backup pair,
defines disabled-read reconciliation for missing/orphan state, and injects both
required recovery split points.

### LOW

None.

Verdict: cleared
