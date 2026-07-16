---
name: os-verify
description: Use when verifying the current opensamguk diff, deciding completion readiness, or reporting which required checks ran, failed, skipped, or remain unexecuted.
---

# OS Verify

Announce this workflow, then read `.claude/commands/os-verify.md` and `docs/agent/verification.md` completely. Treat any scope or base named by the caller as workflow input; `$ARGUMENTS` in the Claude command is not a literal Codex variable.

Run `scripts/agent/verify-changes.sh` to classify the diff and add `--run` when execution is requested or required for completion. Its completion path runs the strict checker against `origin/main` (or `AGENT_BASE`) for code, tool, and Agent OS changes, so an unresolved or missing independent review must fail. Judge Gradle by `BUILD SUCCESSFUL` plus fresh XML with zero failures and errors. Report three groups: executed checks with evidence, unexecuted checks, and failures. Never call an unexecuted, skipped, stale, or unavailable check a pass.
