# OPENSAM-82 follow-up backlog: board media and search

## Tracker state and planning rule

This is the executable local backlog for the proposed OPENSAM-82 decision. A
read-only Atlassian search probe on 2026-08-13 returned HTTP 403, “app is not
installed on this instance.” No Jira issue, comment, state transition, or estimate
was created. Each identifier below is a **local draft label, not a Jira key**; Jira
creation is **채점대기** until a permitted authenticated tracker surface exists.

All implementation tickets require separate approval. They must use a new forward
Flyway migration after a fresh migration-version inventory, never modify
V40__gateway_board.sql, and must preserve the gateway-account/global-board boundary.
The production target is GCP Compute Engine e2-standard-2 through the sibling
opensamguk-docker control repository, not EC2.

## Dependency map

~~~text
82-A schema + storage contract + text backfill
 ├── 82-B media upload/lifecycle/read API
 │    └── 82-C gateway UI attachment flow
 ├── 82-D literal ILIKE API/UI search
 │    └── 82-E measured pg_trgm performance decision
 └── 82-F GCP Persistent Disk + backup/restore control-plane gate

82-B and 82-C cannot claim production durability until 82-F passes.
82-D can proceed after 82-A, independently of 82-B/82-F.
~~~

## 82-A — board media persistence foundation and searchable plaintext

**Purpose.** Establish the database and application seams shared by every later
media/search task. This is the required foundation-first ticket.

**Scope.**

- Reserve a new Flyway version only after inventorying all current SQL and Kotlin
  migrations at implementation time.
- Add an account-global gateway_board_attachment table and classify it exactly once
  in the V32 world-scope inventory as a post-V32 global table. It must not gain
  world_id, general_id, nation_id, or game-engine ownership.
- Add gateway_board_post.content_text, write it from the same normalized plaintext
  input that creates the current safe content_html, and keep content_html the
  server-owned render field.
- Backfill existing content_html only through the known
  GatewayBoardContentSanitizer output form. The migration/test fixture must reject or
  explicitly report unexpected markup instead of silently stripping it.
- Add attachment metadata needed by the proposed contract: opaque ID, post ID,
  uploader account ID, opaque storage key, internal media type, encoded bytes,
  dimensions, SHA-256, status, and lifecycle timestamps.
- Define a board-media storage interface and operation-journal/reconciliation
  boundary. It may extract a generic primitive from profile storage only with
  equivalent secure-root/symlink/atomic-operation tests; it must not widen or
  repurpose the profile-icon business API/root.

**Out of scope.** Multipart HTTP, file content validation, actual file writes,
serving routes, UI, Persistent Disk provisioning, GCS, tsvector, pg_trgm.

**Acceptance criteria.**

1. Given a migrated PostgreSQL database, when the schema inventory runs, then
   gateway_board_attachment is explicitly global and no new board table has a
   world/game identity column.
2. Given plaintext with Korean, line breaks, and HTML-sensitive characters, when a
   post is created, then content_text retains the normalized plaintext and
   content_html remains exactly the sanitizer-derived safe render representation.
3. Given every supported historical safe-HTML fixture, when backfill runs, then the
   plaintext round-trip is deterministic; given unexpected markup, it fails safely
   with a migration/operator-visible diagnostic rather than changing content.
4. Given an attachment record, then its storage key and ID are opaque, its original
   filename is absent, and status/lifecycle constraints disallow an ACTIVE record
   with incomplete required metadata.
5. Focused PostgreSQL Testcontainers tests, V32 inventory regression, and migration
   idempotence tests pass with fresh XML.

**Likely owned surfaces.** infra migration and V32 inventory test; gateway-board
entities/repositories/storage boundary and tests. Re-inventory before assignment so
the exact files do not collide with concurrent work.

## 82-B — authenticated media ingestion, lifecycle, and controlled read API

**Purpose.** Implement safe, application-controlled image storage without a static
path bypass.

**Dependencies.** 82-A complete. 82-F is required before a production durability
claim, but not necessarily before local/Testcontainers implementation.

**Scope.**

- Keep existing JSON POST /board/posts working unchanged for no-file clients; add a
  multipart variant for a post with up to five images.
- Extend the existing same-origin /api/board Next route handler, rather than routing
  browser upload/read traffic directly through /api/gateway/. Allowlist only
  /api/board/posts and /api/board/attachments/{opaqueAttachmentId}; forward the
  fixed multipart fields and image files without request.text() conversion, and
  stream attachment bytes without upstream.text() conversion.
- Forward Content-Type, Content-Disposition, X-Content-Type-Options, Cache-Control,
  and Vary on attachment reads. Preserve the httpOnly cookie-to-Bearer bridge; do
  not turn the route into a generic proxy.
- Add a scoped nginx board-post multipart limit and bounded Next proxy handling:
  2 MiB per file plus a request ceiling greater than 10 MiB by explicitly tested
  multipart overhead. If Spring's servlet-wide multipart ceiling must rise, add a
  route-aware pre-resolution body limiter/wrapper that preserves the current
  50 KB/64 KB envelope for non-board multipart paths even when Content-Length is
  missing. Do not silently broaden profile/general gateway admission.
- Authenticate uploads and require owner-or-ADMIN authorization for withdrawal.
- Accept raster images only after actual decode/container checks. Reject file
  extension/MIME trust, SVG, HTML, arbitrary binaries, and multi-frame/animated
  images. Enforce the proposed 2 MiB per image, 10 MiB request, 4,096-pixel edge,
  and 16 MP pixel count bounds.
- Use a CSPRNG opaque key and an operation journal/staging root. No file becomes
  visible until database metadata and the managed file are consistent.
- Implement PENDING, ACTIVE, WITHDRAWN, and PURGED transitions; reclaim stale
  pending state after 24 hours and purge withdrawn media after 30 days through an
  idempotent reconciler.
- Add GET /board/attachments/{opaqueAttachmentId} through gateway-api. It must
  validate attachment and parent-post state before opening a file, then return
  internally determined content type, inline disposition, nosniff, and a
  deletion-safe cache policy.
- Do not mount the root into nginx or add a direct nginx alias. Do not add direct
  object storage, public filenames, or a standalone pre-upload endpoint.

**Acceptance criteria.**

1. Given an unauthenticated request or a non-image/oversize/animated/image-bomb
   fixture, when upload is attempted, then no ACTIVE row or retrievable file exists.
2. Given five valid single-frame fixtures in one valid multipart request, when post
   creation succeeds, then the response exposes only opaque attachment IDs and
   same-origin URLs; storage keys/paths/original names do not occur in JSON.
3. Given a process interruption at each staging, database, and promotion boundary,
   when the reconciler runs, then it either safely completes the intended ACTIVE
   record or reclaims it; it never exposes an attachment without an active,
   non-deleted post.
4. Given owner deletion or ADMIN withdrawal, when the image route is fetched before
   physical purge, then it returns indistinguishable 404 and never opens bytes.
5. Given a valid active image, when the route is fetched, then its type is
   internally determined and nosniff/inline/cache headers meet the decision record.
6. Given five binary fixtures through the real browser-facing /api/board/posts
   multipart route, when the post is created and each
   /api/board/attachments/{id} URL is fetched, then byte hashes match the
   stored/read fixture contract. The proxy never text-converts the request or
   response.
7. Given one file over 2 MiB or a multipart request over the documented aggregate
   ceiling, when it is sent through the browser-facing route, then exactly the
   intended 413 path occurs without a PENDING/ACTIVE leak.
8. Given an over-limit profile-icon request or a non-board multipart request without
   Content-Length, when its existing limit applies, then it remains rejected; the
   board endpoint is the only one admitted by the wider multipart ceiling.
9. Unit/security tests, proxy route tests, filesystem fault-injection tests, and a
   manual local browser request pass; the latter verifies accepted image render plus
   deleted-image denial.

**Likely owned surfaces.** gateway-api board controller/service/contracts, a
dedicated board-media storage package, security/HTTP tests,
web/gateway/app/api/board/[...path]/route.ts and its proxy tests, plus a narrowly
scoped approved nginx/Spring multipart configuration. No game-api/engine path is in
scope.

## 82-C — gateway board attachment UI flow

**Purpose.** Wire the live gateway UI to the 82-B API instead of exposing a dead
file control.

**Dependencies.** 82-B complete and its route contract frozen.

**Scope.**

- Add file selection, client-side count/size guidance, upload progress/failure state,
  and image rendering from the API-provided same-origin
  /api/board/attachments/{opaqueAttachmentId} URL.
- Preserve plain JSON posting where no files are selected and preserve all existing
  board category/authorization UI behavior.
- Render server error messages safely as text. Never construct a file path, media
  URL, or MIME claim from a client filename.

**Acceptance criteria.**

1. Given a user selects zero, one, or five valid images, when a post is submitted,
   then the UI invokes the live intended JSON/multipart route and renders the
   returned attachments after refresh.
2. Given client-side validation is bypassed, then the browser-facing proxy preserves
   server 413/validation status and the UI renders it as a recoverable text error;
   no optimistic fake image remains.
3. Given a post is deleted or an attachment is withdrawn, then the detail UI no
   longer emits a visible image request that can succeed.
4. Next lint/typecheck and an authenticated browser/manual scenario pass against a
   running local stack.

**Likely owned surfaces.** web/gateway board components, board client contract,
and focused UI tests. It must not edit the control-plane compose or nginx media path.

## 82-D — literal board search API and UI

**Purpose.** Add correct, explainable Korean-friendly substring search before
optimizing its database plan.

**Dependencies.** 82-A complete.

**Scope.**

- Add optional q to GET /board/posts and the matching gateway UI control.
- Search title plus content_text using bound, literal ILIKE contains semantics.
  Escape %, _, and the selected escape character before adding wildcards.
- With q absent, preserve the exact current category/page/size and pinned-first
  behavior. With q present, apply category if supplied, exclude soft-deleted rows,
  and retain the explicit pinned-first/pinnedAt/createdAt/id ordering.
- Do not search comments, attachment labels/metadata, image bytes, OCR, raw
  content_html, game-world board data, or use tsvector/GIN.

**Acceptance criteria.**

1. Given Korean particles, spaces, one/two/three-character substrings, line breaks,
   and HTML-sensitive input, when q is issued, then expected title/plaintext
   substring matches are returned without interpreting the input as a wildcard.
2. Given q contains %, _, or the escape character, then only literal matching occurs
   and SQL is parameter-bound; an injection-shaped q cannot change filters/sort.
3. Given q is absent, then the current feed's response shape, sorting, totals, and
   soft-delete masking behavior are unchanged.
4. Given q is present, then a soft-deleted post does not become a search result or
   leak historical title/body through the result.
5. Focused PostgreSQL integration and gateway/UI tests pass, including pagination
   boundaries and a static query review for bound parameters.

**Likely owned surfaces.** gateway board repository/service/contracts/controller,
web/gateway board list controls, migration tests, and Korean search fixtures.

## 82-E — search performance measurement and pg_trgm decision

**Purpose.** Decide from evidence whether literal ILIKE needs a pg_trgm GIN index
while preserving exact user-visible semantics.

**Dependencies.** 82-D complete with a stable query contract and fixture corpus.

**Scope.**

- Build a reproducible PostgreSQL benchmark dataset at agreed cardinalities, including
  Korean particles, spacing variants, short strings, HTML-escaped historical values,
  title/body combinations, category filters, pins, and deleted rows.
- Capture EXPLAIN (ANALYZE, BUFFERS) and response timings for q lengths that do and
  do not yield trigrams.
- Verify pg_trgm extension availability and migration permissions in the intended
  PostgreSQL environment before proposing CREATE EXTENSION or a GIN index.
- Add pg_trgm GIN only if measured results show a need and the literal ILIKE
  contract remains identical. Do not silently substitute tsvector search.
- Record an explicit short-query behavior/fallback and rollback plan.

**Acceptance criteria.**

1. The benchmark report includes dataset generator/version, PostgreSQL version,
   query corpus, plans/buffers, and raw timings for both unindexed and candidate
   index runs.
2. Every Korean/literal fixture returns identical IDs/order before and after a
   candidate pg_trgm index.
3. The ticket either produces a forward migration plus tested rollback/maintenance
   plan or records evidence that no index is justified. “Probably faster” is not an
   acceptance result.
4. A tsvector/GIN proposal is rejected unless a separate approved Korean
   tokenization/dictionary/ranking contract and fixtures accompany it.

**Likely owned surfaces.** benchmark/research artifact first; only then a reviewed
infra migration/repository query change if evidence supports it.

## 82-F — GCP Persistent Disk, snapshot, and restore control-plane gate

**Purpose.** Make the chosen single-VM media boundary operationally recoverable.

**Dependencies.** Human operations approval; may be prepared in parallel with 82-A,
but must clear before production attachment durability is claimed.

**Scope.**

- In the authorized sibling opensamguk-docker/GCP control surface, attach a dedicated
  nonboot Persistent Disk to the GCP Compute Engine e2-standard-2 target, ensure
  auto-delete is false, mount a stable board-media path, and wire it writable only
  to gateway-api.
- Configure daily standard snapshots retained for 30 days, record capacity and
  alert/rejection thresholds, and document the actual RPO/RTO.
- Establish and record a media write fence plus attachment-operation journal
  checkpoint, durable in attachment metadata and the journal; drain all operations
  up to it, then take the database backup and media snapshot in a documented order
  while the fence is active before release.
- Restore with media reads disabled, run a deterministic checkpoint reconciler, and
  make only verified ACTIVE row/file pairs readable. Missing/unverified ACTIVE files
  become WITHDRAWN; staged/orphan files are removed or quarantined.
- Document and execute an isolated restore drill, including mid-upload and
  mid-withdrawal split points; reconcile ACTIVE attachment metadata and files by
  SHA-256 and record actual RPO/RTO.
- Confirm nginx has no board-media mount/direct alias and no public object/bucket
  path is introduced.

**Acceptance criteria.**

1. Operator evidence identifies the GCP disk, mount ownership, auto-delete=false,
   snapshot schedule, retention, capacity, and responsible recovery runbook without
   publishing credentials or sensitive identifiers.
2. A restore drill in an isolated environment proves an ACTIVE metadata-to-file
   SHA-256 match, identifies missing/orphan files, runs the defined checkpoint
   reconciler for a mid-upload and mid-withdrawal split point, and verifies
   withdrawn/deleted media cannot be served before reconciliation completes.
3. The shared-stack configuration shows gateway-api as sole writer and confirms no
   direct nginx board-media alias.
4. No EC2-specific infrastructure or GCS bucket is introduced by this ticket unless
   separately approved with a revised decision record.

**Likely owned surfaces.** opensamguk-docker/GCP operations repository and runbook,
not this source repository. This task is blocked here by authorization and must not
be attempted from OPENSAM-82 documentation work.

## Completion rule for the parent spike

OPENSAM-82 documentation is ready for review when its decision record, research,
loop ledger, and independent review are present, the six local draft tickets are
internally consistent, and docs/strict/diff checks are recorded. It does **not** mean
attachments, search, snapshots, or Jira child issues already exist.
