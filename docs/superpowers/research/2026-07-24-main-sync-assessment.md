# Safe origin/main sync assessment (opensamguk active worktree)

**Date:** 2026-07-24  
**Role:** research (read-only git evidence)  
**Branch:** `main`  
**Scope constraint:** no pull / push / merge / deploy / `reset --hard` / commit / checkout performed during this assessment.

---

## 1) Exact git evidence

Captured from the active worktree (no network `fetch` during this pass; remote tip is whatever `origin/main` already points to).

| Fact | Value |
|------|--------|
| Branch | `main` |
| Upstream | `origin/main` |
| `HEAD` | `a58b8215c58b5c48f2e852a7a39476ce52ad0fd5` — *Fix Agent OS guard fallback for max_threads config* |
| `origin/main` | `3d432bfca68393e31eb5a414e927bc12f5cac914` — *Merge pull request #313 from peppone-choi/peppone-choi/heartbeat-task-alive* |
| Left-right (`HEAD...origin/main`) | **`0  23`** → ahead **0**, behind **23** |
| Merge-base | `a58b8215…` (= `HEAD`) |
| FF ancestry | `HEAD` **is an ancestor** of `origin/main` → **ff-only is possible once the index/worktree is clean** |
| Ref dates (local) | `main` 2026-07-21 09:16 +0900 · `origin/main` 2026-07-23 22:48 +0900 |

### Status (short / porcelain)

```text
## main...origin/main [behind 23]
D  .codex/config.toml
 M .gitignore
```

Porcelain v2 (abridged):

```text
# branch.oid a58b8215c58b5c48f2e852a7a39476ce52ad0fd5
# branch.head main
# branch.upstream origin/main
# branch.ab +0 -23
1 D. … .codex/config.toml
1 .M … .gitignore
```

### Staged vs unstaged

| Path | Index (staged) | Worktree (unstaged) | Notes |
|------|----------------|---------------------|--------|
| `.codex/config.toml` | **Delete** (`D`, 22 lines removed) | Matches staged delete for index purposes; file **still present on disk** | Disk file is **ignored** by current worktree `.gitignore` (`/.codex/config.toml`) |
| `.gitignore` | clean vs `HEAD` in index | **Modified** (` M`) | +2 / −1 lines (codex tracking rules) |

**Staged diffstat:**

```text
 .codex/config.toml | 22 ----------------------
 1 file changed, 22 deletions(-)
```

**Unstaged diffstat:**

```text
 .gitignore | 3 ++-
 1 file changed, 2 insertions(+), 1 deletion(-)
```

### Object hashes (config.toml)

| Location | SHA-1 (blob) |
|----------|----------------|
| `HEAD:.codex/config.toml` | `db3320e9c9f4ddd13455c44661aeda2494407870` |
| Disk worktree file | `aa28b522f2a71137921815290bcdc7fa4073ea88` (**≠ HEAD**) |
| `origin/main:.codex/config.toml` | `a37dabc9c9a2674f5d18e1eef9a03c2d7afc8c7e` (**≠ HEAD, ≠ disk**) |

Interpretation: index wants to **stop tracking** the file (staged delete). Disk still holds a **local-edited** copy that is currently **check-ignored**. Origin carries a **third, intentionally tracked** revision (model/thread pin updates from OPENSAM-135).

### Incoming remote tip summary (23 commits)

Confirmed subjects on `HEAD..origin/main` include merges/PRs **#302–#313** and tickets **OPENSAM-127…137** themes:

- world_id scope (loader / query / reservation / Redis keys) — OPENSAM-127 (+ residual reads)
- world-scoped flush contract — OPENSAM-128
- two-world isolation — OPENSAM-129
- immutable delta generation — OPENSAM-130
- world_version CAS + writer_epoch fence — OPENSAM-131
- FLUSH_RETRY/RELOAD recovery + clock after FLUSH_RETRY — OPENSAM-132
- durable reserved result outbox — OPENSAM-135
- hot/cold catalog + architecture guard — OPENSAM-137
- heartbeat/task-alive merge #313

**Path scale:** `git diff --name-status HEAD...origin/main` ≈ **122 paths** (engine/api/common/docs heavily touched).

**Overlap with local dirty paths:**

| Path | Touched on origin since HEAD? |
|------|--------------------------------|
| `.codex/config.toml` | **YES** — modified in `73fb13cb` (OPENSAM-135) |
| `.gitignore` | **NO** — no commits on that path in the 23 |

---

## 2) Conflict risk if `git pull --ff-only` after stash / after commit local WIP

### Preconditions (common)

- Dirty index **blocks** a clean `pull` in many setups; even when allowed, mixing dirty tree with a large ff is unsafe.
- Once clean, **ff-only succeeds** because `HEAD` is a strict ancestor of `origin/main` (no local unique commits).
- **Do not** use plain `git pull` without `--ff-only` if the goal is “no merge commit on main.”

### Path A — stash → `pull --ff-only` → stash pop

| Step | Risk |
|------|------|
| Stash staged delete + unstaged `.gitignore` | Low if both index and worktree are stashed together. |
| `git pull --ff-only` | **Low** once clean — pure fast-forward, 23 commits. |
| `git stash pop` | **HIGH on `.codex/config.toml`** |

**Why stash pop is high risk:**

- After ff, tree has origin’s **modified tracked** `.codex/config.toml` (`a37dabc9…`: model context window, reasoning effort, `max_threads = 16`, concurrent threads 16).
- Stash wants to **delete** the path from the index (and re-apply ignore rule).
- Git will typically raise a **modify/delete conflict** (or refuse clean apply) because the file changed on the branch since the stash base.
- `.gitignore` stash application is **LOW conflict** (origin did not touch it); it may apply cleanly, re-removing `!.codex/config.toml` and adding `/.codex/config.toml`.

**Also:** disk-only personal content (`aa28b522…`) is **not** fully represented by a staged delete of `db3320e9…`. Stashing only the staged deletion does **not** preserve the disk blob unless the file is still tracked or explicitly stashed as an untracked/ignored file (`stash -u` / `stash -a`). Today the path is **ignored**, so default stash **will not** save the disk personalization.

### Path B — commit local WIP → pull

| Step | Risk |
|------|------|
| Commit delete + `.gitignore` on `main` | Creates **local unique commit** → left-right becomes **ahead 1 / behind 23**. |
| `git pull --ff-only` | **FAILS** (non-ff; histories diverge). |
| `git pull --rebase` or merge | **HIGH conflict** on `.codex/config.toml` (local delete vs origin modify in OPENSAM-135). `.gitignore` likely clean. |

This is worse than stash for a pure sync: it forces a non-ff integration **and** still hits the delete/modify fight, while putting WIP on shared `main` history.

### Net conflict forecast

| Scenario | `.codex/config.toml` | `.gitignore` | Overall |
|----------|----------------------|--------------|---------|
| Clean tree, then ff-only | n/a (takes origin) | n/a | **Safe ff** |
| Stash → ff → pop | **High (modify/delete)** | Low | **Unsafe without manual resolve** |
| Commit → rebase/merge | **High (modify/delete)** | Low | **Unsafe + pollutes main** |

---

## 3) What `.codex/config.toml` deletion means vs AGENTS.md tracking rules

### What the staged delete is

- Removes the **tracked** project surface file (22 lines at `HEAD`): features/hooks, multi_agent, agents.max_depth, MCP server entries (headroom, playwright, atlassian, sentry), multi_agent_v2 concurrency.
- Worktree still has a **local file** (mtime Jul 19) whose content **differs** from both `HEAD` and `origin/main`, currently suppressed by ignore rules.

### What AGENTS.md / CLAUDE.md require (repo 정본)

From `AGENTS.md` / `CLAUDE.md` (current tracked docs):

- **Tracked Codex project surface:** `.codex/config.toml`, `.codex/hooks.json`, `.codex/agents/*.toml`.
- Unselected local `.codex/*` may be ignored; **config.toml is explicitly listed as tracked**.
- `HEAD` / `origin/main` `.gitignore` both still have:

```gitignore
.codex/*
!.codex/config.toml
!.codex/hooks.json
!.codex/agents/
.codex/agents/*
!.codex/agents/*.toml
```

### Policy tension

| Source | Stance on `.codex/config.toml` |
|--------|--------------------------------|
| AGENTS.md / CLAUDE.md | **Track** (shared project surface) |
| Local WIP `.gitignore` + staged delete | **Do not track** (“개인 모델 핀 … 로컬 전용”) |
| `origin/main` (OPENSAM-135) | **Track and update** (model_context_window, reasoning effort, max_threads=16, concurrency 16) |

**Assessment:** local WIP is a **deliberate policy divergence**, not a merge accident. Shipping that delete as part of a silent main sync would:

1. Fight origin’s intentional config update.
2. Contradict published agent-OS docs until those docs are revised in a separate, reviewed change.
3. Risk losing team-shared Codex defaults (hooks enablement, MCP server wiring) for anyone who relies on the tracked file.

**Recommendation:** treat untracking as a **separate human decision / PR**, not as collateral of catching up 23 commits. For sync-now, prefer restoring the tracked file (or accepting origin’s version after ff) and keep personal model pins in an **untracked overlay** only if the project later adopts that pattern in docs + `.gitignore` together.

---

## 4) What the `.gitignore` change does (diff summary)

Unstaged worktree diff vs `HEAD`:

```diff
 .codex/*
-!.codex/config.toml
 !.codex/hooks.json
 !.codex/agents/
 .codex/agents/*
 !.codex/agents/*.toml
+# 개인 모델 핀을 포함한 Codex 사용자 설정은 로컬 전용으로 두고 추적하지 않는다.
+/.codex/config.toml
```

**Effects:**

1. **Removes** the negation exception `!.codex/config.toml` → the blanket `.codex/*` ignore applies again.
2. **Adds** an explicit root rule `/.codex/config.toml` with a Korean comment stating personal model pins stay local/untracked.
3. Leaves hooks/agents tracking exceptions unchanged.
4. Does **not** appear on origin’s 23-commit range (no remote competition on this file).

**Combined with staged delete:** once both apply, git stops tracking the path and ignores the on-disk file — matching the ignore comment’s intent, but **not** matching AGENTS/CLAUDE tracking rules or origin’s continued maintenance of the file.

---

## 5) Recommended procedure (ranked)

### (A) Stash → ff-only pull → pop — **Rank 3 / not preferred for full WIP restore**

- **Use only if** the goal is temporary parking and the human accepts **manual conflict resolution** on `.codex/config.toml` after pop.
- **Preserve personal disk blob first** if it matters: copy `.codex/config.toml` → e.g. `.codex/config.toml.local.bak` (outside git, or explicitly ignored) **before** stash/reset, because ignored content is easy to lose.
- After ff, **prefer dropping the stash delete** (keep origin file) and re-decide untracking in a dedicated change.
- Pop of `.gitignore` alone is fine; pop of delete is not “safe automatic.”

### (B) Commit WIP then pull — **Rank 4 / avoid on main**

- Breaks ff-only; forces rebase/merge conflict on a policy-sensitive path.
- Parks WIP as a real commit on `main` while 23 remote commits land — messy history for a local preference change.
- Only consider on a **topic branch** after branching from current `HEAD`, then rebase onto updated main later — still not the fastest path to “just sync.”

### (C) Discard local only with **explicit human approval** — **Rank 1 for “sync main cleanly now”**

If the human’s priority is **catch origin/main** and local WIP is “experiments / personal pins,” not shippable product:

```text
# AFTER explicit human approval only — not executed in this research pass
git restore --staged .codex/config.toml
git restore .codex/config.toml .gitignore
# optional: backup personal pins first
cp .codex/config.toml /safe/backup/path/config.toml.local.bak
git pull --ff-only
```

- Yields clean ff of 23 commits.
- Origin’s OPENSAM-135 config wins (tracked, max_threads=16, model pins as committed remotely).
- Re-introduce personal untracking **only** via a reviewed PR that also updates AGENTS.md/CLAUDE.md if policy truly changes.

**Variant (C′):** if personal disk content must survive but stay untracked after sync: backup → discard index/worktree tracking changes → ff-only → restore backup **over** origin file **without** `git add` (and without changing `.gitignore` until policy PR). Note: dirty overwrite of a tracked file will show as ` M .codex/config.toml` again — do not commit casually.

### (D) Abort — **Rank 2 if policy decision is unresolved**

- Do **nothing** until a human chooses: (1) keep tracking + personal overlay, or (2) change repo policy to untrack config.toml with doc updates.
- Correct when the staged delete is intentional product work still under debate.
- Cost: remain 23 commits behind (including world_id / flush CAS / FLUSH_RETRY / durable outbox / hot-cold catalog).

### Ranking summary

| Rank | Option | When |
|------|--------|------|
| **1** | **(C)** discard local WIP **with explicit human approval**, then `pull --ff-only` | Need safe sync now; WIP is personal/unshipped |
| **2** | **(D)** abort | Policy conflict unresolved; no urgent need for 23 commits |
| **3** | **(A)** stash/ff/pop | Temporary; expect manual resolve; backup ignored disk file first |
| **4** | **(B)** commit WIP on main then pull | Avoid — non-ff + conflict + history noise |

---

## 6) Post-sync verification checklist

Run only **after** an approved sync procedure (not part of this research run).

1. **Branch geometry**
   - `git status -sb` → `main...origin/main` with no ahead/behind (or behind 0 / ahead 0).
   - `git rev-list --left-right --count HEAD...origin/main` → `0  0`.
   - `git rev-parse HEAD` equals `git rev-parse origin/main` (or newer only if a deliberate local commit was intentionally kept).

2. **Cleanliness**
   - `git status --porcelain` empty, **or** only expected personal noise documented by the human.
   - Confirm `.codex/config.toml` tracking matches chosen policy (`git ls-files .codex/config.toml`).

3. **Config surface**
   - If remaining tracked: file present; spot-check OPENSAM-135 keys (`model_context_window`, `max_threads`, concurrency 16).
   - Hooks still enabled; MCP blocks intact unless intentionally changed.

4. **Ignore rules**
   - `.gitignore` codex block matches either origin (`!.codex/config.toml`) or an approved policy PR — not a half-applied hybrid.

5. **Build/test smoke (Java 21)**
   - Prefer output-tail verification (host gradle wrapper exit codes can lie):
     - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test 2>&1 | tail -40`
     - Or full backend gate: `tools/parity/gate.sh backend`
   - Confirm `BUILD SUCCESSFUL` / XML failures=0 in `**/build/test-results/test/*.xml`.

6. **World-scope / flush surfaces** (sanity that 23 commits are on the tree you think)
   - Spot-present: flush CAS / recovery types, durable outbox relay, StreamKeys world scoping, hot/cold catalog guard test paths as introduced on origin.

7. **No accidental publish**
   - `git status` shows no staged secrets; no push performed unless separately approved.

8. **Agent OS reload**
   - If Codex config changed: reload/restart Codex so hooks/MCP match the file on disk (per AGENTS.md).

---

## 7) Explicit non-actions

This assessment **did not** and the sync procedure **must not** silently do:

| Forbidden without separate human approval | Why |
|------------------------------------------|-----|
| `git pull` / `git pull --ff-only` | Changes HEAD; out of research scope until human picks A–D |
| `git push` / force-push (`--force`, `--force-with-lease`) | Shared main; irreversible for others |
| `git reset --hard` | Destroys WIP without explicit approval (C requires explicit yes) |
| `git merge` / non-ff pull creating merge commits on main | Avoid unless human chooses non-ff recovery |
| Deploy (`docker compose …`, `scripts/deploy.sh`, CI ship) | Unrelated to worktree sync; production risk |
| Commit of local WIP as part of “just sync” | Couples policy divergence to catch-up |
| Editing goldens / weakening tests to “make sync green” | Parity discipline |
| Assuming local delete of `.codex/config.toml` is already repo policy | Contradicts AGENTS.md / CLAUDE.md and origin |

**Research-only writes:** this document under `docs/superpowers/research/`. No production module edits. No git writes that move `HEAD`.

---

## Appendix — evidence commands used (read-only)

```bash
git status --short --branch
git status --porcelain=v1 -b
git status --porcelain=v2 -b
git rev-parse HEAD origin/main
git rev-list --left-right --count HEAD...origin/main
git merge-base HEAD origin/main
git merge-base --is-ancestor HEAD origin/main
git diff --cached --stat --name-status
git diff --stat --name-status
git diff --cached -- .codex/config.toml
git diff -- .gitignore
git log --oneline HEAD..origin/main
git diff --name-status HEAD...origin/main
git diff HEAD origin/main -- .codex/config.toml
git log --oneline HEAD..origin/main -- .gitignore .codex/config.toml .codex/
git check-ignore -v .codex/config.toml
git hash-object .codex/config.toml
git rev-parse HEAD:.codex/config.toml origin/main:.codex/config.toml
```

---

## Bottom line

Local `main` is a clean ancestor of `origin/main` (**0 ahead / 23 behind**) but the worktree is **not** clean: staged **delete** of a file that origin **modified**, plus an unstaged `.gitignore` policy flip that contradicts AGENTS/CLAUDE tracking rules. **Safest sync is (C) after human approval** (backup personal pins → restore tracked paths → `git pull --ff-only`). Stash-pop and commit-then-pull both hit **modify/delete** on `.codex/config.toml`. Untracking config belongs in a **separate policy PR**, not in the catch-up motion.
