# OPENSAM-82 decision record: board media and search

## Decision status

**PROPOSED.** This is an ADR-style decision record for a future gateway-board
implementation. It does not modify .ai/decisions.md because that shared state is
owned by another active workstream. It creates no GCP resource, bucket, database
object, upload, deploy, or tracker issue. Human approval of the implementation and
operations child work remains required.

The deployment authority is GCP Compute Engine e2-standard-2 through the gcp-prod
runner and sibling opensamguk-docker control repository. Any historical EC2 wording
is stale and must not guide this work.

## Context

The current gateway community board has posts and comments only. It stores the
server-rendered safe HTML body in gateway_board_post.content_html, exposes plain JSON
post creation, and has no attachment metadata, file root, plaintext body column, or
search endpoint/index. It has deliberate soft-deletion masks and a deterministic
pinned-first feed. The profile-icon implementation proves a local root can be
hardened, but its direct nginx read path cannot enforce board-post deletion or
moderation state.

The desired first scope is image attachment plus Korean-friendly user search, not a
generic document service and not a PHP game-world port.

## Decision 1: initial media persistence

**Choose a dedicated board-media root on a separately managed nonboot GCP Persistent
Disk, mounted only into gateway-api.**

The future production-control task must:

1. create or attach the disk through the authorized opensamguk-docker/GCP operation
   path, not by editing this repository's compatibility compose alone;
2. set the disk's instance auto-delete behavior to false;
3. mount it at a stable host path and bind/mount that path into gateway-api as the
   dedicated board-media root;
4. give gateway-api read/write access and give nginx no board-media mount;
5. configure and prove the snapshot/restore policy in Decision 4.

The existing profile-icons root and named volume remain separate. Reusing its generic
secure filesystem primitives may be appropriate after a focused code extraction
decision; reusing its business contract, root, file naming, decoder bounds, or nginx
alias is prohibited.

### Rejected for this phase

- A plain Docker local volume on an unspecified VM/root filesystem cannot claim
  protection from VM replacement or accidental lifecycle changes.
- GCS object storage is deferred. It becomes a reconsideration candidate only when
  multi-VM serving, a measured capacity/recovery need, or asynchronous media
  processing outweighs the additional managed-service, IAM, service-identity, and
  controlled-object-read design work. If approved later, it must use uniform
  bucket-level access and IAM, never object ACLs/public buckets, and receive its own
  architecture approval and threat model.

## Decision 2: admitted media, storage model, and limits

The initial product accepts **raster images only**. It rejects arbitrary attachments,
SVG, HTML, PDF, office documents, archives, remote URLs, and animated/multi-frame
images. A client Content-Type or filename extension is not trusted.

The proposed admission limits are:

| Bound | Proposed value | Reason |
| --- | ---: | --- |
| Images per post | 5 | Keeps one post bounded and makes partial-failure handling tractable. |
| Encoded image bytes | 2 MiB each | Prevents unbounded request/storage admission. |
| Total image bytes per post request | 10 MiB | Equals the five-image upper bound. |
| Width or height | 4,096 px maximum | Bounds decoder/rendering work. |
| Pixel count | 16 megapixels maximum | Prevents dimension-only image bombs. |
| Pending upload retention | 24 hours | Reclaims failed/incomplete staging data. |
| Soft-deleted media retention | 30 days | Supports moderation/recovery before physical purge. |

These are proposed safety limits, not observed capacity figures. The control-plane
ticket must record disk capacity and alert thresholds before deployment; it should
alert at 80% usage and reject new media at 90% only after those thresholds are
validated against the provisioned disk and operational capacity plan.

Each attachment needs a database row containing, at minimum:

- an opaque server-generated identifier;
- post ID and uploader account ID;
- opaque storage key, never a client filename or filesystem path;
- internally determined media type, encoded size, width, height, and SHA-256;
- status and lifecycle timestamps;
- creation, attachment, withdrawal, purge-eligibility, and purge-completion times.

The status model is PENDING, ACTIVE, WITHDRAWN, and PURGED. Only ACTIVE records whose
parent post is not soft-deleted are visible. PENDING is internal/staged only; it must
not be renderable or retrievable.

The decoder must parse the actual image, enforce the byte/dimension/pixel/single-frame
limits before publication, and derive the canonical media type internally. The
implementation must prove one of two safe byte policies with fixtures: (a) re-encode
to a fixed canonical format, or (b) retain only successfully decoded and
container-bounded JPEG/PNG/WebP/AVIF bytes and serve the internal type. It must not
serve an unvalidated original MIME type.

## Decision 3: lifecycle and read access

Initial post creation may support both the current JSON body (no files) and a
multipart body (image files); it must not break existing JSON clients. Do not add a
standalone public pre-upload API in the first slice.

The browser-visible route is deliberately selected now: extend the existing
same-origin httpOnly-cookie bridge from /api/board/posts to
/api/board/attachments/{attachmentId}. It remains an allowlisted gateway proxy, not
a browser-direct /api/gateway/ route. For multipart post creation it forwards
reconstructed allowlisted FormData (category/title/content plus image files), or an
equivalent binary-preserving body; it must never call request.text() for multipart.
For attachment reads it streams bytes rather than reading upstream.text(), and
forwards upstream Content-Type, Content-Disposition, X-Content-Type-Options,
Cache-Control, and Vary. This retains the current same-origin cookie-to-Bearer
boundary without making the handler a generic binary relay.

The current gateway 50 KB per-file / 64 KB request limits and absence of a
board-scoped nginx body limit are incompatible with the proposed 2 MiB by five image
admission policy. The implementation must add a **scoped** board-post limit at nginx
and bounded proxy multipart handling. If Spring's servlet-wide multipart ceiling must
rise to admit that route, a route-aware pre-resolution body limiter/wrapper must
retain the existing 50 KB/64 KB envelope for every non-board multipart path,
including requests without a trustworthy Content-Length. The request limit must
exceed 10 MiB by documented multipart overhead. Tests must prove per-file and
aggregate 413 behavior through the browser-visible route and prove the profile-icon
path still rejects its current over-limit request.

The write sequence is:

1. authenticate the gateway principal and validate the post fields and every image;
2. write accepted bytes only to an opaque server-side staging operation;
3. create the post plus PENDING attachment records in one database transaction;
4. promote the staged bytes and transition attachment records to ACTIVE through an
   operation journal/reconciler that is safe across process interruption;
5. publish only after both the database state and managed file are present.

If the database transaction fails, staging bytes are removed or become reclaimable.
If promotion is interrupted, the reconciler must either complete the active operation
or withdraw/reclaim it without exposing a partial attachment. The result is
application-level consistency; a filesystem move and a PostgreSQL transaction are not
assumed to be one atomic transaction.

Public board reads currently exist, so an active image is read through a same-origin
gateway API route such as GET /board/attachments/{attachmentId}. That route must:

- look up the attachment by opaque ID;
- require ACTIVE status and a non-deleted parent post before opening the managed file;
- return 404 for absent, pending, withdrawn, purged, deleted-parent, or unauthorized
  state without revealing which condition occurred;
- set the internally determined image Content-Type, Content-Disposition: inline, and
  X-Content-Type-Options: nosniff;
- use no public filesystem path, storage key, nginx alias, or permanent shared-cache
  response. Cache policy stays no-store/private until a deletion-aware cache contract
  is designed.

Post soft deletion immediately changes associated ACTIVE media to WITHDRAWN for reads
and starts the proposed 30-day purge window. An administrator withdrawal uses the
same state transition. A scheduled reconciler may physically purge only after that
window; it must be idempotent and leave an auditable PURGED metadata record. PENDING
operations older than 24 hours are reclaimed by the same reconciler.

## Decision 4: backup and recovery

Persistent Disk is storage, not backup. The proposed minimum production policy is:

- the dedicated nonboot disk has instance auto-delete disabled;
- standard Persistent Disk snapshots run daily and retain 30 days;
- the application enters a documented media write fence, drains every operation up
  to a checkpoint that is durably recorded in both attachment metadata and the
  operation journal, and records the fence/checkpoint identifier;
- the database backup and media snapshot are taken in documented order while the
  fence remains active, with the actual RPO recorded by the operator;
- restores begin with media reads disabled and run a deterministic checkpoint
  reconciler: an ACTIVE row with a missing/unverified file becomes WITHDRAWN and is
  never served, while a staged/orphan file is removed or quarantined;
- a restore drill occurs before launch and at least quarterly afterward in an
  isolated environment;
- the drill verifies ACTIVE attachment rows against restored files by opaque key and
  SHA-256, verifies no missing/orphan published files, and proves a withdrawn/deleted
  attachment is not served. It injects at least one mid-upload and one
  mid-withdrawal split point, records the recovery outcome, and records actual RPO/RTO.

No present repository evidence proves a live disk, snapshot schedule, capacity,
backup, RPO, or restore drill. Therefore board attachment durability is **not
currently established**. The operator/control-plane child ticket is a blocking
acceptance gate, not a documentation nicety.

## Decision 5: search semantics and evolution

**Choose literal, parameterized ILIKE first; do not add PostgreSQL tsvector/GIN in
the initial schema/API slice.**

Introduce a canonical gateway_board_post.content_text column in a new forward
migration. New writes store normalized plaintext there and derive content_html via the
existing server-owned sanitizer. Existing values are backfilled only through a tested
conversion for the exact current safe-HTML form; an unexpected historical shape must
stop the migration/rollout rather than silently strip or reinterpret content.

The initial optional query parameter is q on GET /board/posts:

- q omitted preserves today's category/page/size path and pinned-first ordering;
- q present searches title plus content_text with literal contains behavior;
- q present excludes soft-deleted rows so masked content cannot create a search hit;
- category remains an optional filter; page/size behavior and pinned-first,
  pinnedAt, createdAt, ID tie ordering remain explicit;
- the implementation binds values and escapes %, _, and the escape character before
  surrounding the input with % wildcards;
- comments, attachment filenames/metadata, image bytes, OCR, ranking, highlighting,
  and cross-world/game board search are out of scope.

PostgreSQL GIN is desirable for tsvector when there is an approved tokenization and
ranking contract. It is not selected here because tsvector behavior depends on the
text-search parser/configuration/dictionaries, and this spike has no approved Korean
tokenizer, particle, spacing, stemming, or ranking fixture. Adding an FTS index would
look performant while leaving the user-visible behavior undefined.

The performance successor is a measured pg_trgm GIN option over title/content_text
that preserves the literal ILIKE contract. It is separate because pg_trgm may not
help short/no-trigram queries. It must prove extension availability, write migration
and rollback behavior, Korean fixture correctness, and EXPLAIN (ANALYZE, BUFFERS)
plans before adoption. tsvector/GIN remains deferred until a Korean semantic decision
exists.

## Security and operational invariants

1. Gateway authentication gates uploads; only the owner or an administrator may
   withdraw an attachment. Public visibility follows the existing public-board model
   but only for ACTIVE media on a non-deleted post.
2. gateway-api is the sole file writer. The storage root is canonicalized and
   protected from symlink/path traversal; storage names and operation IDs use a CSPRNG
   and are validated on every managed-file operation.
3. A successful upload response never discloses a local path, disk mount, bucket
   name, original filename, storage key, or unverified Content-Type.
4. Soft deletion, moderation, and physical purge have distinct state/timestamps.
   Database authorization is evaluated before any response body is opened.
5. The production path is GCP, not EC2. This decision does not authorize a cloud
   console change, a disk/snapshot/bucket, or a deployment.
6. The board proxy only forwards the declared post/attachment contract. It preserves
   multipart/image bytes and the API's security/cache headers; it is not a generic
   binary relay.

## Consequences

The initial media implementation is deliberately a gateway-only, single-VM-bound
capability with a clear migration path, not an object-storage abstraction built before
there is a need. It adds database metadata and storage/reconciliation complexity, but
it avoids a deletion bypass and avoids claiming disaster recovery that does not exist.

Search favors correct, explainable Korean substring behavior over premature full-text
indexing. It requires a separate plaintext representation and controlled migration,
but keeps the user-facing contract stable while real query data determines when
pg_trgm is warranted.

The dependency-ordered, executable child backlog and acceptance tests live in
docs/superpowers/plans/2026-08-13-opensam-82-board-storage-search-follow-up.md.
The evidence and alternatives are in
docs/superpowers/research/2026-08-13-opensam-82-board-storage-search-spike.md.
