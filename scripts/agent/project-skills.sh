#!/usr/bin/env bash
# Restore, discover, and install skills.sh packages into this repository.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

usage() {
  cat <<'EOF'
Usage:
  scripts/agent/project-skills.sh restore [--soft]
  scripts/agent/project-skills.sh status
  scripts/agent/project-skills.sh find <query> [--owner <owner>]
  scripts/agent/project-skills.sh inspect <owner/repository>
  scripts/agent/project-skills.sh add <owner/repository> <skill-name>
  scripts/agent/project-skills.sh list
  scripts/agent/project-skills.sh update

Commands never install globally. Review search results and source reputation before add.
EOF
}

missing_locked_skills() {
  skill_integrity missing
}

write_integrity_stamp() {
  skill_integrity write
}

skill_integrity() {
  node - "${1:-missing}" <<'NODE'
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const mode = process.argv[2];
const root = process.cwd();
const lock = JSON.parse(fs.readFileSync(path.join(root, "skills-lock.json"), "utf8"));
const stampPath = path.join(root, ".agents", ".skills-integrity.json");
let stamp = { version: 1, skills: {} };
try { stamp = JSON.parse(fs.readFileSync(stampPath, "utf8")); } catch {}

function folderHash(dir) {
  const files = [];
  function collect(current) {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      if (entry.isDirectory() && (entry.name === ".git" || entry.name === "node_modules")) continue;
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) collect(full);
      else if (entry.isFile()) files.push({
        relativePath: path.relative(dir, full).split(path.sep).join("/"),
        content: fs.readFileSync(full),
      });
    }
  }
  collect(dir);
  files.sort((a, b) => a.relativePath.localeCompare(b.relativePath));
  const hash = crypto.createHash("sha256");
  for (const file of files) {
    hash.update(file.relativePath);
    hash.update(file.content);
  }
  return hash.digest("hex");
}

const next = { version: 1, skills: {} };
for (const name of Object.keys(lock.skills || {}).sort()) {
  const dir = path.join(root, ".agents", "skills", name);
  const skillFile = path.join(dir, "SKILL.md");
  if (!fs.existsSync(skillFile)) {
    if (mode === "missing") console.log(name);
    else process.exitCode = 1;
    continue;
  }
  const installedHash = folderHash(dir);
  const lockHash = lock.skills[name].computedHash;
  next.skills[name] = { lockHash, installedHash };
  const recorded = stamp.skills && stamp.skills[name];
  if (mode === "missing" && (!recorded || recorded.lockHash !== lockHash || recorded.installedHash !== installedHash)) {
    console.log(name);
  }
}
if (mode === "write" && !process.exitCode) {
  fs.mkdirSync(path.dirname(stampPath), { recursive: true });
  fs.writeFileSync(stampPath, JSON.stringify(next, null, 2) + "\n");
}
NODE
}

run_skills() {
  command -v npx >/dev/null 2>&1 || {
    echo "npx is required to run skills.sh" >&2
    return 1
  }
  env DISABLE_TELEMETRY=1 npx --yes skills "$@"
}

restore_skills() {
  local soft=0
  [ "${1:-}" = "--soft" ] && soft=1

  local missing
  missing="$(missing_locked_skills)"
  if [ -z "$missing" ]; then
    echo "Project skills ready: every skills-lock.json entry is installed."
    return 0
  fi

  echo "Restoring missing project skills:"
  printf '  - %s\n' $missing
  if ! run_skills experimental_install; then
    if [ "$soft" -eq 1 ]; then
      echo "Project skill restore pending; run scripts/agent/project-skills.sh restore when network access is available."
      return 0
    fi
    return 1
  fi

  write_integrity_stamp
  missing="$(missing_locked_skills)"
  if [ -n "$missing" ]; then
    echo "skills.sh finished but locked skills are still missing:" >&2
    printf '  - %s\n' $missing >&2
    [ "$soft" -eq 1 ] && return 0
    return 1
  fi
  echo "Project skill restore complete."
}

add_skill() {
  local source="${1:-}"
  local skill="${2:-}"
  [[ "$source" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || {
    echo "Source must be owner/repository; global flags and arbitrary URLs are rejected." >&2
    return 2
  }
  [[ "$skill" =~ ^[a-z0-9][a-z0-9-]*$ ]] || {
    echo "Skill name must use lowercase letters, digits, and hyphens." >&2
    return 2
  }

  run_skills add "$source" --skill "$skill" -y
  [ -f ".agents/skills/$skill/SKILL.md" ] || {
    echo "skills.sh did not create .agents/skills/$skill/SKILL.md" >&2
    return 1
  }
  write_integrity_stamp
  echo "Installed project skill: $skill"
  echo "Review skills-lock.json and document its routing/risk in docs/superpowers/WORKING_SYSTEM.md."
}

inspect_source() {
  local source="${1:-}"
  [[ "$source" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || {
    echo "Source must be owner/repository; global flags and arbitrary URLs are rejected." >&2
    return 2
  }
  run_skills add "$source" --list
}

case "${1:---help}" in
  restore)
    shift
    restore_skills "$@"
    ;;
  status)
    missing="$(missing_locked_skills)"
    if [ -n "$missing" ]; then
      echo "Missing locked project skills:"
      printf '  - %s\n' $missing
      exit 1
    fi
    echo "Project skills ready."
    ;;
  find)
    shift
    [ $# -ge 1 ] || {
      usage >&2
      exit 2
    }
    run_skills find "$@"
    ;;
  inspect)
    shift
    [ $# -eq 1 ] || {
      usage >&2
      exit 2
    }
    inspect_source "$1"
    ;;
  add)
    shift
    [ $# -eq 2 ] || {
      usage >&2
      exit 2
    }
    add_skill "$1" "$2"
    ;;
  list)
    run_skills list --json
    ;;
  update)
    run_skills update --project -y
    write_integrity_stamp
    ;;
  --help|-h|help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
