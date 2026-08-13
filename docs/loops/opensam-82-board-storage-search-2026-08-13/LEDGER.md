# OPENSAM-82 board storage and search loop ledger

## Task contract

- Isolated worktree: /private/tmp/opensam-op82-board-storage-search.
- Branch/base: codex/opensam82-board-storage-search from origin/main
  fe7127fdd240632b448409b7f7c04e3ef7c1e966.
- Ownership: new OPENSAM-82 research, decision, planning, loop, review, and tracker
  evidence documents only. No production code, shared .ai files, legacy source,
  deployment, tracker mutation, GCP provisioning, secret access, or merge.
- User authorization permits documentation commit, push, and ready-PR handoff only
  after the applicable documentation gates. It does not permit implementation,
  infrastructure changes, deployment, or external tracker writes.

## Baseline

| Check | Observation | Result |
| --- | --- | --- |
| Runtime authority | Current operations docs name GCP Compute Engine e2-standard-2, gcp-prod, and the sibling opensamguk-docker control repository. | GCP is the only current deployment target used by this spike. |
| Stale wording | A historical planning artifact calls docker-compose.production.yml an EC2 surface. The current compatibility compose itself says GCP. | Explicit correction required in every new OPENSAM-82 artifact; old history is not edited by this worktree. |
| Board data/API | V40 has only gateway_board_post/comment; the list API has category/page/size and the form has no file input. No attachment/search contract exists. | A new forward migration and additive API/UI work are required; no capability is claimed as present. |
| Existing media pattern | Profile images use a hardened local root and direct nginx static route with gateway-api as sole writer. | Reuse only security lessons; a board path needs DB-state-aware reads and a separate root. |
| Search | No plaintext post field or full-text/trigram index exists. | Search semantics must be chosen before schema/index work. |
| Tracker | Atlassian read-only probe returned 403 because the app is not installed. | No external Jira write; local child-ticket draft is the source of truth and Jira is 채점대기. |

## Hypotheses and measurements

| Round | Hypothesis | Measurement | Result | Decision |
| --- | --- | --- | --- | --- |
| 1 | A current GCP single-VM deployment can use a dedicated Persistent Disk more safely than an unspecified Docker local volume. | Read current deployment docs/compatibility compose plus Google Compute Persistent Disk, auto-delete, data-protection, and snapshot-schedule primary docs. | PD can be detached/preserved and snapshot-scheduled, but it is not automatic backup. Current repo does not prove a live disk/snapshot. | **Adopt proposed PD boundary**, with auto-delete=false and snapshot/restore as a blocking control-plane child ticket. |
| 2 | Existing profile storage can justify direct board nginx serving. | Compare LocalProfileIconStorage, ProfileIconDecoder, compose mount, nginx alias, and board soft deletion behavior. | Profile static alias bypasses a board attachment/post status lookup. | **Reject direct nginx board alias.** gateway-api must validate ACTIVE/non-deleted state before reads. |
| 3 | GCS should replace local storage immediately. | Compare current single-VM scope/project no-external-runtime posture to primary GCS IAM/uniform-access/lifecycle docs. | GCS gives useful lifecycle/IAM but introduces additional object-access, identity, and managed-service decisions not authorized here. | **Defer GCS** behind explicit scale/recovery trigger and human approval. |
| 4 | tsvector/GIN is the default correct first Korean search implementation. | Read PostgreSQL primary text-search index/dictionary docs and inspect current board storage/rendering fields. | GIN indexes tsvector; tokenization is dictionary/config dependent; no Korean semantics fixture exists. | **Reject for first slice.** Add canonical plaintext and literal ILIKE first. |
| 5 | A GIN index cannot serve the literal-search evolution. | Read PostgreSQL pg_trgm docs. | pg_trgm can accelerate ILIKE but short/no-trigram inputs need measured treatment. | **Defer pg_trgm GIN** to a benchmark child ticket; preserve literal ILIKE semantics. |
| 6 | The planned browser path can carry five 2 MiB images and controlled attachment reads as documented. | Independent review traced web/gateway/app/api/board/[...path]/route.ts and application.yml. | Current allowlist, request.text()/upstream.text(), response-header filtering, 50 KB/64 KB Spring caps, and no scoped nginx size route contradict the draft. | **Remediated in proposal:** select an allowlisted same-origin board proxy extension, binary/header preservation, and scoped aligned limits as 82-B/82-C acceptance. |
| 7 | A database backup and PD snapshot in the same named window are recoverable as a pair. | Independent review compared the operation journal lifecycle to the proposed backup wording. | A same-window pair can split around upload/withdrawal unless writes are fenced/checkpointed and restore has deterministic outcomes. | **Remediated in proposal:** 82-F owns a write fence/journal checkpoint, ordered backup, disabled read during restore, deterministic reconciliation, and injected split-point restore drill. |

## Proposed acceptance scorecard

| Dimension | Evidence | State |
| --- | --- | --- |
| Storage choice | Current GCP topology + official PD lifecycle/backup docs + separate control-plane ticket. | Proposed; no disk exists by this evidence. |
| Security/lifecycle | Separate root, opaque ID/key, decoded raster-only admission, statuses, state-checked reads, staged/reconciled operations, 24-hour pending and 30-day withdrawn retention. | Proposed implementation contract. |
| Backup/recovery | Auto-delete=false, daily 30-day standard snapshot baseline, paired DB/media restore drill and SHA-256 reconciliation. | Proposed; operator proof required. |
| Size bounds | Five images/post; 2 MiB/image; 10 MiB/post; 4,096 edge; 16 MP. | Proposed safety bounds; capacity validation required. |
| Search | Canonical plaintext, bound/escaped literal ILIKE, no-query compatibility, deleted-row exclusion for q, pg_trgm benchmark later. | Proposed implementation contract. |
| Jira follow-up | Six local draft items with dependency and Given/When/Then acceptance criteria. | Complete locally; external issue creation is 채점대기. |
| Independent critique | First reviewer found H1 browser-ingress and M1 cross-resource recovery gaps; the remediated proposal was re-reviewed independently. | Cleared: docs/superpowers/reviews/2026-08-13-opensam-82-board-storage-search-remediation-review.md has Verdict: cleared. |
| Documentation gates | scripts/agent/verify-changes.sh --run, python3 tools/agent-system/check.py --strict --base origin/main, git diff --check, and untracked-document whitespace checks ran after the cleared review. | PASS: six OPENSAM-82 docs, 0 checker errors/warnings, no whitespace findings. |

## Evidence sources

- Repository: infra/src/main/resources/db/migration/V40__gateway_board.sql;
  app/gateway-api/src/main/kotlin/opensamguk/gateway/board/;
  app/gateway-api/src/main/kotlin/opensamguk/gateway/profile/;
  docker-compose.production.yml; infra/nginx/nginx.conf;
  docs/agent/lifecycle-ops.md; docs/agent/claude-user-manual.md.
- External primary documents:
  [Persistent Disk](https://cloud.google.com/compute/docs/disks/persistent-disks),
  [persistent-disk auto-delete](https://cloud.google.com/compute/docs/disks/modify-persistent-disk),
  [data protection](https://cloud.google.com/compute/docs/disks/data-protection),
  [snapshot schedules](https://cloud.google.com/compute/docs/disks/about-snapshot-schedules),
  [uniform bucket-level access](https://cloud.google.com/storage/docs/uniform-bucket-level-access),
  [Cloud Storage lifecycle](https://cloud.google.com/storage/docs/lifecycle),
  [PostgreSQL text-search indexes](https://www.postgresql.org/docs/17/textsearch-indexes.html),
  [PostgreSQL dictionaries](https://www.postgresql.org/docs/current/textsearch-dictionaries.html),
  and [pg_trgm](https://www.postgresql.org/docs/17/pgtrgm.html).

## Environment and tool anomalies

- The isolated worktree has no .codegraph directory. The CodeGraph CLI explicitly
  reported no index, so direct rg/file inspection was used. This is a discovery
  fallback, not a missing product dependency.
- The Fablize wrapper emitted generic “tool failure” notices around successful
  read-only commands. The corresponding terminal commands completed with repository
  output and no product test was run or inferred from the warning. Treat the notices
  as a known harness baseline, not a passing/failing implementation signal.
- One web-wrapper result-shape call failed before consuming the response. The
  read-only primary-document fetch was reissued successfully; no external state was
  changed.
- The Atlassian app probe failed with HTTP 403. This is an external capability
  limitation, not a reason to invent ticket IDs or claim Jira updates.
- The first independent review recorded HIGH H1 (browser ingress/proxy and scoped
  size-limit gap) and MEDIUM M1 (cross-resource backup consistency gap) in
  docs/superpowers/reviews/2026-08-13-opensam-82-board-storage-search-review.md.
  Both are remediated in the proposed decision/backlog above. The fresh independent
  re-review is docs/superpowers/reviews/2026-08-13-opensam-82-board-storage-search-remediation-review.md
  with Verdict: cleared; it confirms the binary ingress/header/limit contract and
  fenced restore/reconciliation contract without claiming implementation exists.
- A bulk remediation patch initially failed because one expected plan-context block
  had changed; it made no repository edit. The changes were then applied as four
  targeted patches and are subject to the same re-review. This is an edit-tool
  failure, not product/test evidence.
- A final ledger patch initially hit a local JavaScript quoting syntax error before
  calling the patch tool; it made no repository edit and was immediately retried
  successfully. This is likewise a tool-wrapper failure, not product/test evidence.

## Next loop

1. The independent review/remediation/re-review and scoped docs/diff/strict gates
   are complete. Preserve both review artifacts: the first fix-required finding is
   evidence of the corrected boundary, and the remediation review is the clearance.
2. Make the authorized single documentation commit, push the branch, and open a
   ready PR. Do not provision, deploy, merge, or mutate Jira.
