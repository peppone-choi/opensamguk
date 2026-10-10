"""Local, bounded tool/repository inventory. Never install, copy auth or log in."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from work_units.schema import read_record, safe_path

VERSION_ARGS = {"python3": ["--version"], "git": ["--version"], "node": ["--version"],
                "pnpm": ["--version"], "corepack": ["--version"], "java": ["-version"], "docker": ["--version"],
                "gh": ["--version"], "claude": ["--version"], "codex": ["--version"]}
AUTH_PATHS = {"claude": ".claude/.credentials.json", "github-cli": ".config/gh/hosts.yml",
              "codex-cli": ".codex/auth.json"}


def version_probe(name):
    if name not in VERSION_ARGS:
        return {"tool": name, "status": "UNSUPPORTED_PROBE"}
    executable = shutil.which(name)
    if executable is None:
        return {"tool": name, "status": "NOT_INSTALLED", "nextAction": "Use the reviewed environment setup recipe"}
    try:
        result = subprocess.run([executable, *VERSION_ARGS[name]], capture_output=True, text=True,
                                timeout=5, env=dict(os.environ, COREPACK_ENABLE_NETWORK="0"))
        text = (result.stdout + result.stderr).strip()
        # Do not include arbitrary diagnostic output in the exported environment.
        match = re.search(r"\b(v?\d+\.\d+(?:\.\d+)?)\b", text)
        return {"tool": name, "status": "AVAILABLE" if result.returncode == 0 and match else "VERSION_UNVERIFIED",
                "version": match[1] if match else None}
    except (OSError, subprocess.TimeoutExpired):
        return {"tool": name, "status": "VERSION_UNVERIFIED", "version": None}


def repository_name(url):
    # Secret-bearing URLs are rejected; never export the raw remote value.
    match = re.fullmatch(r"(?:git@github\.com:|https://github\.com/)([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?", url)
    return match[1] if match else None


def repo_read(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True, timeout=5)
    if result.returncode:
        raise ValueError("LOCAL_REPOSITORY_UNVERIFIED")
    return result.stdout.strip()


def public_source(path):
    parts = Path(path).parts
    return not any(p in {".aws", ".codex", ".ssh", "sessions", "credentials"} or
                   (p.startswith(".env") and p != ".env.example") or
                   p.startswith("settings") or "credentials" in p or
                   p.lower().endswith((".pem", ".key", ".env")) for p in parts)


def project_probe(project, workspace, overrides):
    row = {"project": project["id"], "status": "CONFIGURATION_UNKNOWN", "configuration": [],
           "skills": [], "commands": project.get("commands", []), "runtime": project.get("runtime", []),
           "commandTargets": {}}
    checkout = overrides.get(project["id"], project.get("checkout"))
    if not checkout or not project.get("repository"):
        row["nextAction"] = "Supply allowed repository, source revision, AGENTS/setup/tool requirements and skill paths"
        return row
    root = Path(checkout)
    root = root if root.is_absolute() else Path(workspace) / root
    try:
        actual = repository_name(repo_read(root, "remote", "get-url", "origin"))
        if actual != project["repository"]:
            raise ValueError("ALLOWED_REPOSITORY_MISMATCH")
        row.update(repository=actual, checkoutHead=repo_read(root, "rev-parse", "HEAD"), status="LOCAL_SOURCE_OBSERVED")
        for name in project.get("configuration", []):
            # Only tracked, declared public source files are hashed; no auth settings.
            if not public_source(name):
                row["configuration"].append({"path": name, "status": "EXCLUDED_PRIVATE_CONFIGURATION"})
                continue
            safe_path(name) if name != ".env.example" else None
            path = root / name
            if path.is_symlink() or not path.is_file():
                row["configuration"].append({"path": name, "status": "MISSING_OR_SYMLINK"})
                continue
            repo_read(root, "ls-files", "--error-unmatch", "--", name)
            if path.stat().st_size > 2 * 1024 * 1024:
                raise ValueError("CONFIGURATION_TOO_LARGE")
            row["configuration"].append({"path": name, "status": "TRACKED_PUBLIC_SOURCE",
                                         "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        for directory in project.get("skillDirectories", []):
            safe_path(directory)
            if not public_source(directory):
                continue
            # Git's tracked inventory does not traverse arbitrary host directories.
            tracked = repo_read(root, "ls-files", "--", directory).splitlines()
            row["skills"].extend(path for path in tracked if path.endswith("/SKILL.md") and public_source(path))
        for cmd in row["commands"]:
            targets = cmd.get("requiredPaths", [])
            for name in targets:
                safe_path(name)
            row["commandTargets"][cmd["purpose"]] = (
                "PRESENT" if targets and all((root / name).exists() for name in targets) else
                "MISSING" if targets else "NOT_DECLARED")
    except (OSError, ValueError, subprocess.TimeoutExpired) as exc:
        row.update(status="CONFIGURATION_UNKNOWN", reason=str(exc), nextAction="Repair the declared isolated checkout; do not overwrite another worktree")
    return row


def preflight(manifest, workspace, *, overrides=None, home=None):
    if manifest.get("schema") != "development-bootstrap/1":
        raise ValueError("BOOTSTRAP_MANIFEST_SCHEMA")
    names = sorted(set(manifest.get("tools", [])))
    with ThreadPoolExecutor(max_workers=4) as pool:
        tools = list(pool.map(version_probe, names))
    projects = [project_probe(p, workspace, overrides or {}) for p in manifest.get("projects", [])]
    by_tool = {tool["tool"]: tool for tool in tools}
    for project in projects:
        project["runtimeChecks"] = []
        for required in project["runtime"]:
            observed = by_tool.get(required["tool"], {})
            version = observed.get("version")
            expected = required.get("version")
            match = bool(version and observed.get("status") == "AVAILABLE" and
                         (expected is None or version.lstrip("v") == expected) and
                         (required.get("major") is None or int(version.lstrip("v").split(".")[0]) == required["major"]))
            project["runtimeChecks"].append({"tool": required["tool"], "required": required,
                                             "observedVersion": version, "status": "MATCH" if match else "UNVERIFIED_OR_MISMATCH"})
        project["commandAvailability"] = [{"purpose": cmd["purpose"], "program": cmd["argv"][0],
                                            "programStatus": by_tool.get(cmd["argv"][0], {}).get("status", "UNVERIFIED"),
                                            "targets": project["commandTargets"].get(cmd["purpose"], "UNVERIFIED"),
                                            "execution": "NOT_RUN"} for cmd in project["commands"]]
    home = Path(home or Path.home())
    authentication = []
    for name, path in AUTH_PATHS.items():
        try:
            (home / path).stat()
            presence = "PRESENT_UNVERIFIED"
        except FileNotFoundError:
            presence = "NOT_FOUND"
        except OSError:
            presence = "UNKNOWN"
        authentication.append({"tool": name, "presence": presence, "usableAuthentication": "UNKNOWN",
                               "copied": False, "valuesRead": False})
    partial = any(p["status"] == "CONFIGURATION_UNKNOWN" or
                  any(check["status"] != "MATCH" for check in p["runtimeChecks"]) for p in projects)
    return {"schema": "development-preflight/1", "mode": "OBSERVE", "executionAllowed": False,
            "status": "PARTIAL" if partial else "CONFIGURATION_OBSERVED",
            "tools": tools, "projects": projects, "authentication": authentication,
            "savedCloudConfigurationChanged": False, "networkPolicyChanged": False,
            "setupExecuted": False, "setupRecipe": manifest.get("setupRecipe", [])}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Observe the combined public development bootstrap")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--workspace", type=Path, default=Path.cwd())
    parser.add_argument("--checkout", action="append", default=[], help="PROJECT=isolated-checkout-path")
    args = parser.parse_args(argv)
    try:
        overrides = dict(value.split("=", 1) for value in args.checkout)
        result = preflight(read_record(args.manifest), args.workspace, overrides=overrides)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except (ValueError, OSError, KeyError, TypeError) as exc:
        print(json.dumps({"status": "UNKNOWN", "reason": str(exc), "executionAllowed": False}))
        return 2
