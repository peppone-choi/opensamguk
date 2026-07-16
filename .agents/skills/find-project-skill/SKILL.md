---
name: find-project-skill
description: Use when an opensamguk task needs expertise that is not covered by the installed project skills, or when the user asks to find or install a skill from skills.sh.
---

# Find Project Skill

Install only project-scoped, reviewed skills.

1. Convert the task into a narrow skills.sh query and run `scripts/agent/project-skills.sh find <query>`.
2. Run `scripts/agent/project-skills.sh inspect <owner/repository>` to list the exact candidate skills, then inspect the repository. Verify maintainer reputation, repository activity, adoption, and the exact skill content. A search hit alone is not approval.
3. Reject untrusted, unrelated, abandoned, or high-risk skills. Never use `-g` or `--global`.
4. Install one exact candidate with `scripts/agent/project-skills.sh add <owner/repository> <skill-name>`.
5. Read the installed `.agents/skills/<skill-name>/SKILL.md` completely before using it.
6. Review the resulting `skills-lock.json`, document routing and risk in `docs/superpowers/WORKING_SYSTEM.md`, then run `python3 tools/agent-system/check.py`.

Use the existing `java-testing` package as reference-only because its recorded audit is High Risk. Ask before installing when trust or scope remains uncertain.
