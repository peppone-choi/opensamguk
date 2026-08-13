# OPENSAM-82 board attachment storage and search spike

## Status, scope, and authority

**Status: PROPOSED — no production storage, bucket, database migration, API, or
deployment change is authorized by this document.** It is a bounded architecture
spike for the gateway-account community board. The resulting implementation work
must be separately approved and follow the child-work backlog in
docs/superpowers/plans/2026-08-13-opensam-82-board-storage-search-follow-up.md.

- Investigated at origin/main commit
  fe7127fdd240632b448409b7f7c04e3ef7c1e966 in isolated worktree
  /private/tmp/opensam-op82-board-storage-search, branch
  codex/opensam82-board-storage-search.
- This worktree owns only new OPENSAM-82 documentation. Shared .ai state is owned
  by another workstream and remains untouched.
- Current production authority is **GCP Compute Engine e2-standard-2**, with the
  gcp-prod self-hosted runner synchronizing the sibling opensamguk-docker control
  repository. docker-compose.production.yml is a compatibility surface, not the
  current production control plane. See docs/agent/lifecycle-ops.md:5 and
  docs/agent/claude-user-manual.md:150.
- Correction: an old EC2 parenthetical in
  docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md:1081 is
  historical/stale wording, not an operation target. This spike must not be read
  as approval to use EC2.
- Jira was not changed. A read-only tracker probe returned HTTP 403 because the
  Atlassian app is not installed in this session; local Markdown is therefore the
  executable backlog source and Jira follow-up creation is **채점대기**.

## What exists today

| Surface | Observed fact | Consequence for this decision |
| --- | --- | --- |
| Board schema | V40 creates only gateway_board_post and gateway_board_comment. Posts have content_html and feed/category indexes; there is no attachment table, plaintext body column, or search index. | A future change needs a forward migration, an explicit global-table inventory entry, and a canonical searchable text column. It must not edit V40. |
| Board API | GatewayBoardService.list accepts category/page/size and uses a pinned-first sort. CreateGatewayBoardPostRequest accepts category/title/content only; creation turns plaintext into safe HTML. | Existing JSON post creation and no-query list behavior must remain compatible. Search and multipart media are additive contracts. |
| Board UI | BoardPostForm has category, title, and a textarea, but no file input. web/gateway/lib/board has no attachment or search shape. | UI is a dependent child task, not evidence that upload is already implemented. |
| Browser ingress | The same-origin board catch-all proxy admits only current posts/comments/pin paths, consumes every write body with request.text(), converts upstream reads to text, and reconstitutes only Content-Type/Vary/Cache-Control. gateway-api currently caps multipart at 50 KB/file and 64 KB/request; nginx has no scoped board-upload body limit. | A board-media plan must explicitly own a binary-preserving proxy route, image-header forwarding, and aligned scoped proxy/Spring limits. A backend-only multipart endpoint would be unreachable or corrupt on the real browser path. |
| Profile media | gateway-api has a hardened LocalProfileIconStorage and ProfileIconDecoder; production compatibility compose mounts profile-icons writable only to gateway-api and read-only to nginx. nginx serves a tightly matched static /d_pic/ path. | The security properties are reusable design evidence, but profile roots, names, limits, and direct nginx static serving are not a board-media contract. |
| Runtime storage | The compose compatibility surface declares a Docker local profile-icons volume only. It declares no board-media volume or GCE Persistent Disk mount. | A Docker local volume on an unspecified host path is insufficient evidence of recoverable board attachment storage. |

Repository evidence is direct: V40 is at
infra/src/main/resources/db/migration/V40__gateway_board.sql:1-46;
the list/create/deletion behavior is at
app/gateway-api/src/main/kotlin/opensamguk/gateway/board/GatewayBoardService.kt:23-110
and :159-203; the request/response shape is at
GatewayBoardContracts.kt:14-69; the form is at
web/gateway/components/board/BoardPostForm.tsx:6-68. The existing profile storage
hardening begins at
app/gateway-api/src/main/kotlin/opensamguk/gateway/profile/LocalProfileIconStorage.kt:64
and decoder bounds/type checks at ProfileIconDecoder.kt:18-94. The compatibility
mount and nginx direct static alias are respectively
docker-compose.production.yml:158-176, :239-266 and infra/nginx/nginx.conf:99-110.

## Primary-source evidence

| Question | Primary evidence | Design implication |
| --- | --- | --- |
| Can a VM-attached disk survive VM replacement? | [Compute Engine Persistent Disk](https://cloud.google.com/compute/docs/disks/persistent-disks) describes durable, detachable block storage; [persistent-disk auto-delete](https://cloud.google.com/compute/docs/disks/modify-persistent-disk) documents preserving a disk when an instance is deleted by disabling auto-delete. | A separately managed, nonboot Persistent Disk is a viable single-VM storage boundary, but only if the operator explicitly controls attachment and auto-delete. |
| Is a Persistent Disk automatically backed up? | [Compute Engine data protection](https://cloud.google.com/compute/docs/disks/data-protection) says snapshots must be configured for durable recovery; [snapshot schedules](https://cloud.google.com/compute/docs/disks/about-snapshot-schedules) describes automated schedules. | PD alone is not a backup decision. A snapshot schedule and restore proof are required before board media is called recoverable. |
| What would GCS provide? | [Uniform bucket-level access](https://cloud.google.com/storage/docs/uniform-bucket-level-access) makes IAM the access model and disables object ACLs; [Cloud Storage lifecycle management](https://cloud.google.com/storage/docs/lifecycle) describes rule-driven retention/deletion. | GCS is a viable later scale-out option, but needs explicit IAM/service-identity, access, lifecycle, and new managed-service approval. It is not silently introduced by this spike. |
| Does PostgreSQL full-text search solve Korean substring search? | [PostgreSQL text-search indexes](https://www.postgresql.org/docs/17/textsearch-indexes.html) explains that GIN indexes tsvector values; [text-search dictionaries](https://www.postgresql.org/docs/current/textsearch-dictionaries.html) explains parser/configuration/dictionary-dependent normalization. | tsvector plus GIN is not an automatically correct Korean search contract. Tokenization, particles, spacing, ranking, and fixtures must be decided first. |
| Can literal ILIKE be indexed without changing its semantics? | [pg_trgm](https://www.postgresql.org/docs/17/pgtrgm.html) documents GIN/GiST support for LIKE and ILIKE, including unanchored patterns, and warns that queries without extractable trigrams can scan broadly. | Start with literal ILIKE for behavior; only add pg_trgm GIN after measured data confirms it. Short search strings need a documented fallback/benchmark. |

## Options considered

| Option | Strength | Rejected or deferred risk |
| --- | --- | --- |
| Docker local volume on the VM boot/root disk | Lowest immediate wiring cost. | It does not establish disk lifecycle, auto-delete protection, snapshot ownership, or restore evidence. Do not use it as a production durability claim. |
| Dedicated GCP Persistent Disk mounted into gateway-api | Fits the current single GCP VM and the existing local-storage implementation pattern. No new managed object API is needed at request time. | Requires a sibling-control-repository/operator task to attach a nonboot disk, set auto-delete false, mount it at a stable path, configure snapshots, and prove restore. |
| GCS object storage now | Strong object lifecycle and IAM primitives; easier future multi-VM read topology. | Adds a managed object-storage integration, bucket/service identity, object access design, and likely a departure from the repository's current no-external-runtime-dependency posture. No such approval or provisioning exists. |
| PostgreSQL tsvector plus GIN now | Good word-oriented full-text index when a correct language configuration is known. | The desired Korean semantics are not specified or validated; indexing the rendered HTML would also create the wrong source field. |
| Literal ILIKE now, later pg_trgm GIN if measured | Preserves transparent substring semantics and uses no extension initially. pg_trgm can later accelerate the same operator. | May be slow at growth and short strings can defeat trigram selectivity, so a benchmark gate is mandatory before calling it sufficient at scale. |

## Findings adopted into the proposed decision

1. Store initial board **images only**, in a dedicated board-media root backed by a
   separately managed GCP Persistent Disk. It is not the existing profile-icons
   root, not a direct nginx alias, and not merely an anonymous Docker local
   volume. GCS is an explicitly deferred alternative, not an implementation
   detail.
2. Upload/read availability is application-controlled. gateway-api is the sole
   writer; it validates post/media state on reads and returns a same-origin route
   rather than a filesystem path or raw storage key. Board media must never be
   exposed through the profile /d_pic/ nginx pattern because board soft deletion
   and moderation state require a database decision before every visible read.
   The browser-visible contract is **/api/board/attachments/{attachmentId}** through
   the existing httpOnly-cookie Next proxy, which must be extended by the media
   ticket. That proxy must preserve multipart bytes (not request.text()), allowlist
   only fixed post/attachment paths, and stream image responses without text
   conversion while forwarding Content-Type, Content-Disposition,
   X-Content-Type-Options, Cache-Control, and Vary.
3. Use a separate canonical content_text column for new writes and a guarded,
   fixture-proven backfill for the existing server-owned content_html shape. Search
   is title plus canonical plaintext only; it does not search media bytes,
   comments, deleted content, or rendered HTML.
4. Start query behavior with parameterized, literal contains ILIKE. Escape user
   %, _, and the chosen escape character before adding the surrounding % wildcards.
   Keep the existing pinned-first ordering and page behavior when q is absent.
   For q present, intentionally exclude soft-deleted rows instead of allowing
   their masked text to influence results.
5. Do not add tsvector/GIN as an assumed Korean solution. A later performance
   ticket may add pg_trgm plus a GIN index only after actual Korean fixture and
   EXPLAIN (ANALYZE, BUFFERS) evidence supports the same literal ILIKE contract.

## Explicit unknowns and gates

- No production board-media capacity, RPO, RTO, account quota, expected post count,
  or Korean search behavior corpus was supplied or observed. The proposed defaults
  in the decision record are safety bounds, not measurements.
- This repository cannot prove the current GCP disk topology or snapshot schedule
  without accessing the control plane, which is outside this authorization. The
  control-plane ticket must supply the proof.
- The Jira app 403 means no real child issue IDs, state changes, or comments were
  created. Draft identifiers in the plan are local labels, not Jira keys.
- CodeGraph is intentionally absent from this isolated worktree, so this spike
  used rg after the CodeGraph CLI reported no local index. That limits only
  navigation convenience, not the direct file evidence cited above.
- The current browser ingress is intentionally insufficient for the proposed
  five-image, 2 MiB-per-image contract: Spring limits are 50 KB/file and
  64 KB/request, while nginx has no scoped board-upload limit. The future ticket
  must set a narrowly scoped /api/board/posts multipart limit that covers the 10 MiB
  payload plus documented multipart overhead at nginx, the Next proxy, and Spring;
  it must not broaden unrelated gateway endpoints. The exact allowance is an
  implementation-time calculation/test, not a claim about current production.

## Reproducibility and method notes

Read-only commands confirmed a clean worktree rooted at the stated origin/main
commit and inspected the board migration, service, contracts, UI, profile storage,
compatibility compose, nginx, and current operations documentation. No secrets,
.env files, cloud console, Docker runtime, database, or external resource were
accessed.

The Fablize harness emitted generic “tool failure” notices around successful
read-only inspection calls. Terminal output and repository state showed no command
failure or product behavior result associated with those notices; they are recorded
as a harness baseline in the companion loop ledger, not treated as validation.
A web-wrapper result-shape error was retried as a read-only primary-document fetch
and produced no external mutation.
