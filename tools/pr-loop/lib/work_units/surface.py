"""Conservative base-owned impact mapping. Head objects are data only."""
import fnmatch
import base64
import hashlib
import re
import subprocess
from pathlib import Path
from .schema import MAX_BLOB, SHA, json_data, safe_path


class GitTree:
    def __init__(self, repo, ref):
        self.repo = Path(repo)
        self.sha = self.git("rev-parse", "--verify", ref + "^{commit}").decode().strip()
        if not SHA.fullmatch(self.sha):
            raise ValueError("TREE_SHA")

    def git(self, *args):
        done = subprocess.run(["git", "-C", str(self.repo), *args], capture_output=True, timeout=60)
        if done.returncode:
            raise ValueError("GIT_OBJECT_UNAVAILABLE")
        return done.stdout

    def entries(self, prefix=""):
        args = ["ls-tree", "-rz", "--full-tree", self.sha]
        if prefix:
            args += ["--", safe_path(prefix)]
        result = {}
        for row in self.git(*args).split(b"\0"):
            if row:
                metadata, path = row.split(b"\t", 1)
                mode, kind, blob = metadata.decode().split()
                result[path.decode("utf-8")] = (mode, kind, blob)
        return result

    def blob(self, path):
        safe_path(path)
        item = self.entries(path).get(path)
        if item is None:
            return None
        mode, kind, blob = item
        if mode not in {"100644", "100755"} or kind != "blob":
            raise ValueError("UNSAFE_HEAD_OBJECT")
        size = int(self.git("cat-file", "-s", blob))
        if size > MAX_BLOB:
            raise ValueError("BLOB_TOO_LARGE")
        return self.git("cat-file", "blob", blob)

    def text(self, path, default=None):
        content = self.blob(path)
        return default if content is None else content.decode("utf-8")

    def json(self, path, default=None):
        content = self.blob(path)
        return default if content is None else json_data(content)

    def blob_sha(self, path):
        item = self.entries(path).get(path)
        if item is None:
            return None
        self.blob(path)  # validates mode and size before exposing an identity
        return item[2]


class MemoryTree:
    def __init__(self, files):
        self.files = files
        self.sha = "a" * 40

    @classmethod
    def default(cls):
        root = Path(__file__).resolve().parents[4]
        return cls({p: (root / p).read_text() for p in (
            "work-units/surface-map.json", "work-units/binding.json",
            "data/commands/input-catalog.json", "data/commands/input-evidence-debt-v1.json",
            "data/commands/command-work-gaps-v1.json",
            "data/commands/input-delivery-baseline-v3.json")})

    def entries(self, prefix=""):
        return {p: ("100644", "blob", self.blob_sha(p)) for p in self.files if p.startswith(prefix)}

    def blob(self, path):
        safe_path(path)
        value = self.files.get(path)
        return value.encode() if isinstance(value, str) else value

    def text(self, path, default=None):
        content = self.blob(path)
        return default if content is None else content.decode()

    def json(self, path, default=None):
        content = self.blob(path)
        return default if content is None else json_data(content)

    def blob_sha(self, path):
        value = self.blob(path)
        return hashlib.sha1(value).hexdigest() if value is not None else None


class RemoteTree:
    """Bounded GitHub object adapter for installed-host verification."""
    def __init__(self, reader, repo, ref):
        if not SHA.fullmatch(ref):
            raise ValueError("TREE_SHA")
        self.sha, self.reader, self.repo = ref, reader, repo
        data = reader.get(f"repos/{repo}/git/trees/{ref}?recursive=1")
        if data.get("truncated") or len(data.get("tree", [])) > 10000:
            raise ValueError("HOST_VERIFY_TOO_WIDE")
        self.index = {item["path"]: (item["mode"], item["type"], item["sha"])
                      for item in data["tree"] if item["type"] != "tree"}
        self.sizes = {item["path"]: item.get("size", 0) for item in data["tree"]}
        self.cache = {}

    def entries(self, prefix=""):
        return {p: row for p, row in self.index.items() if p.startswith(prefix)}

    def blob(self, path):
        safe_path(path)
        item = self.index.get(path)
        if item is None:
            return None
        if item[0] not in {"100644", "100755"} or item[1] != "blob":
            raise ValueError("UNSAFE_HEAD_OBJECT")
        if self.sizes[path] > MAX_BLOB:
            raise ValueError("BLOB_TOO_LARGE")
        if path not in self.cache:
            data = self.reader.get(f"repos/{self.repo}/git/blobs/{item[2]}")
            if data.get("encoding") != "base64" or data.get("size", MAX_BLOB + 1) > MAX_BLOB:
                raise ValueError("BLOB_ENCODING_OR_SIZE")
            content = base64.b64decode(data["content"], validate=False)
            if len(content) > MAX_BLOB:
                raise ValueError("BLOB_TOO_LARGE")
            self.cache[path] = content
        return self.cache[path]

    text = GitTree.text
    json = GitTree.json
    blob_sha = GitTree.blob_sha


def changes_between(base, head):
    raw = base.git("diff", "--name-status", "-z", "-M", "-C", base.sha, head.sha)
    parts = iter(raw.decode("utf-8").split("\0"))
    changes = []
    for status in parts:
        if not status:
            continue
        first = next(parts)
        if status[0] in "RC":
            changes.append({"status": status[0], "oldPath": first, "path": next(parts)})
        else:
            changes.append({"status": status[0], "path": first})
    return changes


def derive_impact(base_tree, head_tree, changes):
    mapping = base_tree.json("work-units/surface-map.json")
    if not mapping or mapping.get("schemaVersion") != 1 or not mapping.get("rules"):
        raise ValueError("SURFACE_MAP_UNAVAILABLE")
    catalog = head_tree.json("data/commands/input-catalog.json") or {}
    ids = {row["inputId"] for row in catalog.get("inputs", [])}
    ids |= {row["inputId"] for row in (base_tree.json("data/commands/input-catalog.json") or {}).get("inputs", [])}
    direct, scopes, unresolved, surfaces, paths = set(), set(), [], set(), []
    sammo = False
    for change in changes:
        if change["status"] not in {"A", "M", "D", "R", "C"}:
            unresolved.append("UNKNOWN_CHANGE_STATUS:" + change["path"])
        for path in dict.fromkeys([change.get("oldPath"), change["path"]]):
            if path is None:
                continue
            try:
                safe_path(path)
                rule = next((r for r in mapping["rules"] if any(
                    fnmatch.fnmatchcase(path, pattern) for pattern in r["paths"])), None)
                if rule is None:
                    unresolved.append(path)
                    continue
                surface = rule["surface"]
                legacy_surface = mapping["legacySurface"]
                if surface not in {"NON_COMMAND", "GATE_SURFACE", "INPUT_MAPPED",
                                   "SHARED_COMMAND_SURFACE", "PRODUCT_DEFAULT", legacy_surface}:
                    raise ValueError("UNRESOLVED_SURFACE_RULE")
                surfaces.add(surface)
                scopes.update(rule.get("scopes", []))
                sammo |= surface == legacy_surface
                for pattern in rule.get("inputs", []):
                    direct.update(i for i in ids if fnmatch.fnmatchcase(i, pattern))
                for tree in (base_tree, head_tree):
                    # Inspect bounded source blobs only; large non-command data is not materialized.
                    if surface not in {"NON_COMMAND", "GATE_SURFACE"}:
                        text = tree.text(path, "") or ""
                        direct.update(i for i in ids if re.search(
                            r'["\x27]' + re.escape(i) + r'["\x27]', text))
                paths.append({"path": path, "surface": surface,
                              "baseBlob": base_tree.entries(path).get(path, (None, None, None))[2],
                              "headBlob": head_tree.entries(path).get(path, (None, None, None))[2]})
                # Even non-command changes must reject symlink/submodule objects.
                for tree in (base_tree, head_tree):
                    item = tree.entries(path).get(path)
                    if item and item[0] not in {"100644", "100755"}:
                        raise ValueError("UNSAFE_HEAD_OBJECT")
            except (ValueError, UnicodeError) as exc:
                unresolved.append(f"{path}:{exc}")
    broad = bool(scopes)
    result = "UNRESOLVED" if unresolved else "BROAD" if broad else "DIRECT" if direct or sammo else "NONE"
    na = None
    if result == "NONE":
        na = ("GATE_SURFACE_ONLY" if "GATE_SURFACE" in surfaces else
              "DOCS_ONLY" if all(p["path"].startswith(("docs/", ".ai/")) for p in paths) else
              "NON_COMMAND_DATA" if all(p["path"].startswith("data/") for p in paths) else
              "CI_TOOLING_ONLY")
    return {"result": result, "inputIds": sorted(direct), "scopes": sorted(scopes),
            "sammo": sammo, "na": na, "paths": paths, "reasons": unresolved,
            "gateChanged": "GATE_SURFACE" in surfaces}


def expanded_inputs(impact, catalog):
    rows = catalog.get("inputs", [])
    ids = set(impact["inputIds"])
    for scope in impact["scopes"]:
        if scope == "ALL_INPUTS":
            ids.update(r["inputId"] for r in rows)
        elif scope == "ALL_UI_INPUTS":
            ids.update(r["inputId"] for r in rows if r["deliveryState"] in {
                "UI_READY", "AI_READY", "HELP_READY", "TUTORIAL_READY", "REPLAY_READY", "VERIFIED"})
        elif scope.endswith(".*"):
            ids.update(r["inputId"] for r in rows if r["inputId"].startswith(scope[:-1]))
    return sorted(ids)
