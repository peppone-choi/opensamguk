#!/usr/bin/env python3
"""Manage only the loopback QA160 stack; never load an existing env file."""
import argparse
import base64
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import re
import shutil
import socket
import signal
import time
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def meta_root(root):
    root = Path(root).resolve()
    if root.parent.name == "projects":
        candidate = root.parent.parent
    elif root.parent.parent.name == "worktrees":
        candidate = root.parent.parent.parent
    else:
        return None
    if (all((candidate / "bin" / name).is_file() for name in ("start-task", "finish-task"))
            and all((candidate / name).is_dir() for name in ("projects", "worktrees"))):
        return candidate
    return None


META = meta_root(ROOT)
PROJECT = "opensamguk-qa160"
SERVICES = ("gateway-api", "board-api", "game-api", "game-engine", "web-gateway", "web-game")
GIB = 1024 ** 3


def run(argv, **kwargs):
    return subprocess.run(argv, check=True, **kwargs)


def resource_gate():
    free = shutil.disk_usage(ROOT).free
    load5 = os.getloadavg()[1]
    if free < 3 * GIB or load5 > 400:
        raise RuntimeError("QA resource gate: free disk <3 GiB or load5 >400")
    return {"freeGiB": round(free / GIB, 2), "load5": round(load5, 2)}


@contextmanager
def heavy():
    backend = os.environ.get("QA_EXECUTION_BACKEND", "managed")
    if backend == "github-hosted":
        owner = hosted_owner()
        lock = Path(owner["directory"]) / "operation"
        lock.mkdir(mode=0o700)
        try:
            resource_gate()
            yield
        finally:
            lock.rmdir()
        return
    if backend != "managed":
        raise ValueError("Unknown QA execution backend")
    if META is None:
        raise RuntimeError("QA operations require a managed metarepository checkout or worktree")
    lock = META / ".locks/heavy-run"
    lock.parent.mkdir(exist_ok=True)
    nonce = secrets.token_hex(16)
    lock.mkdir()  # Atomic acquisition. A different owner's lock is never removed.
    owner = lock / "owner"
    try:
        owner.write_text(f"QA {nonce} driver-pid={os.getpid()} local-stack\n")
        resource_gate()
        yield
    finally:
        if owner.exists() and nonce in owner.read_text():
            owner.unlink()
            lock.rmdir()


def private_json(path, value):
    with open(path, "x", opener=lambda p, flags: os.open(p, flags, 0o600)) as handle:
        json.dump(value, handle, ensure_ascii=False)
        handle.write("\n")


def custody(path):
    supplied = Path(path)
    directory = supplied.resolve(strict=True)
    if supplied.is_symlink() or directory.stat().st_mode & 0o077:
        raise ValueError("QA custody directory must be private")
    public = json.loads((directory / "stack.json").read_text())
    if public.get("project") != PROJECT or public.get("worldId") != 160:
        raise ValueError("Not a QA160 custody directory")
    env = directory / "qa.env"
    if env.is_symlink() or not env.is_file() or env.stat().st_mode & 0o077:
        raise ValueError("QA env must be private and regular")
    return directory


def compose(directory, *args, **kwargs):
    directory = custody(directory)
    command = ["docker", "compose", "--project-name", PROJECT, "--env-file",
               str(directory / "qa.env"), "-f", str(ROOT / "docker-compose.qa.yml")]
    if os.environ.get("QA_EXECUTION_BACKEND") == "github-hosted":
        owner = hosted_owner()
        if directory != Path(owner["directory"]) / "custody":
            raise ValueError("Foreign hosted custody")
        hosted_inventory(owner)
        command += ["-f", str(Path(owner["directory"]) / "ownership.json")]
    return run([*command, *args], **kwargs)


def registry():
    return [{"id": "qa160", "name": "QA 전용", "generation": 0,
             "gameUrl": "http://127.0.0.1:18300/game/qa160",
             "gameApiUrl": "http://sqa160-game-api:8081",
             "gameEngineUrl": "http://sqa160-game-engine:8082",
             "deployProject": "opensamguk-sqa160", "scenarioCode": "scenario_3190"}]


def nginx():
    return """events { worker_connections 256; }
http {
 include /etc/nginx/mime.types;
 resolver 127.0.0.11 ipv6=off;
 proxy_http_version 1.1;
 proxy_set_header Host $host;
 proxy_set_header X-Forwarded-Proto $scheme;
 proxy_read_timeout 3600s;
 server {
  listen 80;
  location /health { return 200 'QA160'; }
  location /api/gateway/ { proxy_pass http://gateway-api:8080/; }
  location /api/ { proxy_pass http://web-gateway:3000; }
  location /game/_next/ { rewrite ^/game/(.*)$ /$1 break; proxy_pass http://web-game:3001; }
  location /game/ { proxy_pass http://web-game:3001; }
  location /d_pic/ { alias /var/lib/opensamguk/profile-icons/; }
  location / { proxy_pass http://web-gateway:3000; }
 }
}
"""


def image_refs(source_sha, run_id, channel="main", pr=None):
    if channel not in ("main", "pr-candidate"):
        raise ValueError("Unknown QA channel")
    if channel == "pr-candidate":
        if not re.fullmatch(r"[1-9][0-9]*", str(pr)):
            raise ValueError("Candidate requires a positive PR number")
        suffix = f"qa160cand-pr{pr}"
    else:
        if pr is not None:
            raise ValueError("Main channel cannot carry a PR")
        suffix = "qa160"
    return {name: f"ghcr.io/peppone-choi/opensamguk:{name}-{suffix}-{source_sha}-{run_id}"
            for name in SERVICES}


def check_image(record, name, labels, digests):
    channel = record.get("channel", "main")
    expected = image_refs(record["sourceSha"], record["runId"], channel, record.get("pr"))
    if record["images"][name] != expected[name]:
        raise ValueError("QA image tag/channel mismatch")
    if labels.get("org.opencontainers.image.revision") != record["sourceSha"]:
        raise ValueError("QA image revision differs from shared source")
    if channel == "pr-candidate":
        if (labels.get("org.opensamguk.qa.channel") != channel
                or labels.get("org.opensamguk.qa.pr") != str(record["pr"])
                or labels.get("org.opensamguk.qa.base") != record["baseSha"]):
            raise ValueError("Candidate image custody labels mismatch")
        ref = record["images"][name].split(":")[0] + "@" + record["digests"][name]
        if ref not in digests:
            raise ValueError("Candidate receipt digest mismatch")
    elif labels.get("org.opensamguk.qa.channel") not in (None, "main"):
        raise ValueError("Candidate cannot be promoted to main QA")


def candidate_receipt(path, source_sha, run_id, pr):
    row = json.loads(Path(path).read_text())
    if (row.get("channel") != "pr-candidate" or row.get("sourceSha") != source_sha
            or str(row.get("runId")) != run_id or row.get("pr") != int(pr)
            or not re.fullmatch(r"[a-f0-9]{40}", row.get("baseSha", ""))
            or set(row.get("digests", {})) != set(SERVICES)
            or any(not re.fullmatch(r"sha256:[a-f0-9]{64}", d) for d in row["digests"].values())):
        raise ValueError("Candidate receipt identity/digests mismatch")
    return row


def prepare(source_sha, run_id, channel="main", pr=None, receipt=None, directory=None):
    if len(source_sha) != 40 or any(c not in "0123456789abcdef" for c in source_sha):
        raise ValueError("Use the full merged-main source SHA")
    if not run_id.isdecimal() or int(run_id) < 1:
        raise ValueError("Use the successful QA image workflow run ID")
    images = image_refs(source_sha, run_id, channel, pr)
    metadata = {"channel": channel}
    if channel == "pr-candidate":
        if receipt is None:
            raise ValueError("Candidate requires downloaded workflow receipt")
        metadata.update(candidate_receipt(receipt, source_sha, run_id, pr))
    elif receipt is not None:
        raise ValueError("Main cannot use a candidate receipt")
    resource_gate()
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 18300))
    if directory is None:
        directory = Path(tempfile.mkdtemp(prefix="opensamguk-qa160-"))
    else:
        directory = Path(directory)
        directory.mkdir(mode=0o700)
    directory.chmod(0o700)
    key = directory / "new-key.pem"
    run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
         "-out", str(key)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    key.chmod(0o600)
    private = run(["openssl", "pkcs8", "-topk8", "-nocrypt", "-in", str(key), "-outform", "DER"],
                  capture_output=True).stdout
    public = run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"],
                 capture_output=True).stdout
    key.unlink()
    values = {"QA_RUNTIME": str(directory), "QA_DB_PASSWORD": secrets.token_urlsafe(32),
              "QA_JWT_PRIVATE_KEY": base64.b64encode(private).decode(),
              "QA_JWT_PUBLIC_KEY": base64.b64encode(public).decode(),
              "QA_INTERNAL_TOKEN": secrets.token_urlsafe(32), "QA_DEPLOYER_TOKEN": secrets.token_urlsafe(32),
              "QA_ADMIN_PASSWORD": secrets.token_urlsafe(32),
              "QA_REGISTRY": json.dumps(registry(), ensure_ascii=False, separators=(",", ":"))}
    values.update({"QA_IMAGE_" + name.upper().replace("-", "_"): ref for name, ref in images.items()})
    with open(directory / "qa.env", "x", opener=lambda p, flags: os.open(p, flags, 0o600)) as handle:
        for name, value in values.items():
            handle.write(f"{name}='{value}'\n")
    (directory / "nginx.conf").write_text(nginx())
    private_json(directory / "stack.json", {"project": PROJECT, "worldId": 160,
                 "sourceSha": source_sha, "runId": run_id, "images": images, **metadata})
    print(str(directory))  # No credentials, keys or token values.
    return directory


def validate(directory):
    model = json.loads(compose(directory, "config", "--format", "json", capture_output=True).stdout)
    if model["name"] != PROJECT or "build" in model:
        raise ValueError("QA project mismatch")
    total = 0
    for name, service in model["services"].items():
        if "build" in service or service.get("container_name") != f"{PROJECT}-{name}":
            raise ValueError("QA-only container and image contract violated")
        total += int(service["deploy"]["resources"]["limits"]["memory"])
        for port in service.get("ports", []):
            if name != "nginx" or port.get("host_ip") != "127.0.0.1" or str(port["published"]) != "18300":
                raise ValueError("QA exposes a non-loopback port")
        for mount in service.get("volumes", []):
            if mount["type"] == "bind" and Path(mount["source"]).resolve().parent != custody(directory):
                raise ValueError("QA binds an existing host resource")
    if total > 6 * GIB or any(n.get("external") for n in model["networks"].values()):
        raise ValueError("QA budget or network isolation violated")
    if any(not v["name"].startswith(PROJECT + "-") or v.get("external") for v in model["volumes"].values()):
        raise ValueError("QA volume isolation violated")
    print("QA compose isolation/budget valid; resolved secret values withheld")


def container_guard(name, running=True):
    rows = json.loads(run(["docker", "inspect", "--format",
                          '{{json .Config.Labels}}', f"{PROJECT}-{name}"], capture_output=True).stdout)
    if rows.get("com.docker.compose.project") != PROJECT or rows.get("com.docker.compose.service") != name:
        raise ValueError("QA container identity mismatch")
    if os.environ.get("QA_EXECUTION_BACKEND") == "github-hosted":
        if rows.get(OWNER_LABEL) != hosted_owner()["nonce"]:
            raise ValueError("QA container nonce mismatch")
    if not running:
        state = run(["docker", "inspect", "--format", "{{.State.Running}}",
                     f"{PROJECT}-{name}"], capture_output=True).stdout.strip()
        if state != b"false":
            raise ValueError("QA engine must be stopped for fixture writes")


def sql(directory, database, statement):
    custody(directory)
    if database not in ("game", "gateway"):
        raise ValueError("QA database mismatch")
    container_guard(database + "-postgres")
    return run(["docker", "exec", "-i", f"{PROJECT}-{database}-postgres", "psql", "-X", "-qAt",
                "-v", "ON_ERROR_STOP=1", "-U", "qa160", "-d", f"qa160_{database}"],
               input=statement.encode(), capture_output=True).stdout.decode().strip()


def refresh_routes(directory):
    """After install/rollback health wait, re-resolve upstreams under the caller's heavy lock."""
    directory = custody(directory)
    container_guard("nginx")
    compose(directory, "exec", "-T", "nginx", "nginx", "-t")
    compose(directory, "exec", "-T", "nginx", "nginx", "-s", "reload")


def start(directory):
    validate(directory)
    with heavy():
        initial_free = shutil.disk_usage(ROOT).free
        for name in SERVICES:
            compose(directory, "pull", name)
            resource_gate()
            if initial_free - shutil.disk_usage(ROOT).free > 6 * GIB:
                raise RuntimeError("QA image/storage growth exceeded 6 GiB")
        record = json.loads((custody(directory) / "stack.json").read_text())
        for name, ref in record["images"].items():
            inspected = json.loads(run(["docker", "image", "inspect", ref], capture_output=True).stdout)[0]
            check_image(record, name, inspected["Config"].get("Labels") or {}, inspected.get("RepoDigests") or [])
        if record.get("channel") == "pr-candidate":
            # Bind compose to immutable verified digests, not a subsequently moved tag.
            env_path = custody(directory) / "qa.env"
            content = env_path.read_text()
            for name, ref in record["images"].items():
                content = content.replace(ref, ref.split(":")[0] + "@" + record["digests"][name])
            env_path.write_text(content)
        compose(directory, "up", "-d", "--no-build", "--wait", "--wait-timeout", "300")
        refresh_routes(directory)
        if os.environ.get("QA_EXECUTION_BACKEND") == "github-hosted":
            resource_gate()
            if initial_free - shutil.disk_usage(ROOT).free > 6 * GIB:
                raise RuntimeError("QA image/storage growth exceeded 6 GiB including dependencies")


OWNER_LABEL = "org.opensamguk.qa.owner"


def hosted_environment():
    required = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
                "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64",
                "GITHUB_REPOSITORY": "peppone-choi/opensamguk", "GITHUB_REF": "refs/heads/main"}
    if any(os.environ.get(k) != v for k, v in required.items()):
        raise RuntimeError("Explicit trusted GitHub-hosted main runner required")
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        if not re.fullmatch(r"[1-9][0-9]*", os.environ.get(key, "")):
            raise ValueError("Hosted run identity missing")
    temp = Path(os.environ["RUNNER_TEMP"])
    if temp.is_symlink() or not temp.is_absolute() or temp.stat().st_uid != os.getuid():
        raise ValueError("Runner temporary directory owner mismatch")
    return temp.resolve(strict=True)


def hosted_owner():
    temp = hosted_environment()
    index = Path(os.environ["QA_OWNER_INDEX"])
    if index != temp / "qa-owner-index.json" or index.is_symlink():
        raise ValueError("Hosted owner index location mismatch")
    row = json.loads(index.read_text())
    directory = temp / "opensamguk-qa160-owner"
    if (index.stat().st_mode & 0o077 or row.get("directory") != str(directory)
            or row.get("runId") != os.environ["GITHUB_RUN_ID"]
            or row.get("attempt") != os.environ["GITHUB_RUN_ATTEMPT"]
            or not re.fullmatch(r"[a-f0-9]{64}", row.get("nonce", ""))
            or type(row.get("processUid")) != int or row["processUid"] != os.geteuid()
            or type(row.get("processFloorTick")) != int or row["processFloorTick"] <= 0
            or directory.is_symlink() or directory.stat().st_mode & 0o077
            or directory.stat().st_uid != os.getuid()
            or (directory / "nonce").read_text() != row["nonce"]):
        raise ValueError("Hosted custody ownership mismatch")
    return row


def qa_model():
    return json.loads((ROOT / "docker-compose.qa.yml").read_text())


def resource_names():
    model = qa_model()
    return {"container": [s["container_name"] for s in model["services"].values()],
            "network": [v["name"] for v in model["networks"].values()],
            "volume": [v["name"] for v in model["volumes"].values()]}


def hosted_inventory(owner=None):
    """Never touch even a same-named resource without both compose and nonce labels."""
    expected = resource_names()
    found = {kind: [] for kind in expected}
    for kind, names in expected.items():
        command = ["docker", "ps", "-a", "--format", "{{.Names}}"] if kind == "container" else ["docker", kind, "ls", "--format", "{{.Name}}"]
        existing = set(run(command, capture_output=True).stdout.decode().splitlines())
        # Also catch unknown orphans carrying this fixed project label.
        command = (["docker", "ps", "-aq"] if kind == "container" else ["docker", kind, "ls", "-q"])
        project_ids = run([*command, "--filter", "label=com.docker.compose.project=" + PROJECT], capture_output=True).stdout.decode().split()
        for identifier in sorted((existing & set(names)) | set(project_ids)):
            data = json.loads(run(["docker", kind, "inspect", identifier], capture_output=True).stdout)[0]
            labels = data.get("Config", {}).get("Labels", {}) if kind == "container" else data.get("Labels", {})
            name = data["Name"].lstrip("/")
            if (owner is None or name not in names or labels.get(OWNER_LABEL) != owner["nonce"]
                    or labels.get("com.docker.compose.project") != PROJECT):
                raise ValueError("Foreign or unexpected QA resource; cleanup refused")
            if name not in found[kind]:
                found[kind].append(name)
    return found


def hosted_preflight():
    hosted_environment()
    metrics = resource_gate()
    available = next(int(line.split()[1]) * 1024 for line in Path("/proc/meminfo").read_text().splitlines()
                     if line.startswith("MemAvailable:"))
    metrics.update(availableGiB=round(available/GIB, 2))
    if available < 8 * GIB or shutil.disk_usage(ROOT).free < 12 * GIB:
        exc = RuntimeError("Hosted preflight requires available RAM >=8GiB and disk >=12GiB")
        exc.metrics = metrics
        raise exc
    run(["docker", "info"], capture_output=True)
    run(["docker", "compose", "version"], capture_output=True)
    hosted_inventory()  # Any previous project/name is a refusal, not an adoption.
    return metrics


def hosted_claim():
    temp = hosted_environment()
    if Path(os.environ["QA_OWNER_INDEX"]) != temp / "qa-owner-index.json":
        raise ValueError("Wrong owner index path")
    metrics = hosted_preflight()
    directory = temp / "opensamguk-qa160-owner"
    directory.mkdir(mode=0o700)  # No reuse, no recursive delete on collision.
    nonce = secrets.token_hex(32)
    row = {"directory": str(directory), "nonce": nonce,
           "runId": os.environ["GITHUB_RUN_ID"], "attempt": os.environ["GITHUB_RUN_ATTEMPT"],
           "processUid": os.geteuid(),
           # A nonce child cannot predate the process that minted its nonce. Use
           # the kernel start tick (not wall time/rounded uptime) as a safe floor.
           "processFloorTick": int(Path("/proc/self/stat").read_text().rsplit(")", 1)[1].split()[19])}
    (directory / "nonce").write_text(nonce)
    (directory / "nonce").chmod(0o600)
    private_json(temp / "qa-owner-index.json", row)  # Before key/DB preparation.
    model = qa_model()
    override = {kind: {name: {"labels": {OWNER_LABEL: nonce}} for name in model[kind]}
                for kind in ("services", "volumes", "networks")}
    override["services"]["web-gateway"]["environment"] = {"COOKIE_SECURE": "false"}
    private_json(directory / "ownership.json", override)
    return row, metrics



def hosted_process_cleanup(owner, process=None):
    receipt = Path(owner["directory"]) / "process.json"
    if process is None and receipt.exists():
        if receipt.is_symlink() or receipt.stat().st_mode & 0o077:
            raise ValueError("Private process receipt required")
        process = json.loads(receipt.read_text())
    uid, floor = owner.get("processUid"), owner.get("processFloorTick")
    if (type(uid) != int or uid != os.geteuid()
            or type(floor) != int or floor <= 0):
        raise ValueError("Owned process UID/start-time custody required")
    pid = None
    if process is not None:
        if (process.get("nonce") != owner["nonce"]
                or type(process.get("pid")) != int or process["pid"] <= 1):
            raise ValueError("Owned process receipt mismatch")
        pid = process["pid"]
        try:
            created = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[19]
        except FileNotFoundError:
            pass  # An exited runner can still have detached browser descendants.
        else:
            if created != process["createdTick"]:
                raise ValueError("PID was reused; process cleanup refused")

    nonce = ("QA_PROCESS_NONCE=" + owner["nonce"]).encode()

    def identity(path):
        fields = path.read_text().rsplit(")", 1)[1].split()
        return fields[0], int(fields[2]), fields[19]

    def credentials(path):
        # /proc/<pid> directory ownership is NOT process credential evidence:
        # Linux may expose it as root for a non-dumpable same-UID process.
        lines = (path.parent / "status").read_text().splitlines()
        values = next(line.split()[1:] for line in lines if line.startswith("Uid:"))
        if len(values) != 4:
            raise ValueError("Incomplete process UID evidence")
        return tuple(int(value) for value in values)

    def members():
        active = []
        for path in Path("/proc").glob("[0-9]*/stat"):
            tick = None
            try:
                uids = credentials(path)
                if uid not in uids:
                    # Proven other credentials cannot be this unprivileged job's
                    # nonce descendants. Some hosted system processes also deny
                    # stat access; that denial is not missing QA ownership proof.
                    try:
                        if identity(path)[1] == pid:
                            raise ValueError("Foreign process in owned process group; cleanup refused")
                    except PermissionError:
                        pass
                    continue
                state, group, tick = identity(path)
                if state == "Z":
                    continue
                if int(tick) < floor:
                    if group == pid:
                        raise ValueError("Foreign process in owned process group; cleanup refused")
                    continue  # Cannot inherit a nonce minted after its creation.
                if uids != (uid,) * 4:
                    raise RuntimeError("CLEANUP_FAILED: ambiguous process UID ownership"
                                       f" (pid={path.parent.name}, uids={uids})")
                environment = (path.parent / "environ").read_bytes().split(b"\0")
                if identity(path)[1:] != (group, tick) or credentials(path) != uids:
                    raise RuntimeError("CLEANUP_FAILED: process identity changed during scan")
                if nonce in environment:
                    active.append((int(path.parent.name), tick))
                elif group == pid:
                    raise ValueError("Foreign process in owned process group; cleanup refused")
            except (FileNotFoundError, ProcessLookupError):
                continue
            except (PermissionError, OSError, ValueError, StopIteration) as exc:
                if isinstance(exc, ValueError) and str(exc).startswith("Foreign process"):
                    raise
                # Linux exit can deny environ before stat reports Z. Allow that
                # same-tick transition up to five 10ms waits, never an active or
                # reused PID exemption. Unknown identity and lasting denial fail.
                exited = False
                for attempt in range(6):
                    try:
                        state, _, current_tick = identity(path)
                        if state == "Z" and tick is not None and current_tick == tick:
                            exited = True
                            break
                        if (not isinstance(exc, PermissionError) or tick is None
                                or current_tick != tick or attempt == 5):
                            break
                    except (FileNotFoundError, ProcessLookupError):
                        exited = True
                        break
                    except (OSError, ValueError, IndexError):
                        break
                    time.sleep(.01)
                if exited:
                    continue
                raise RuntimeError("CLEANUP_FAILED: process ownership cannot be verified"
                                   f" (pid={path.parent.name}, {type(exc).__name__})") from None
        return active

    def terminate(targets, sig):
        for target, tick in targets:
            try:
                # pidfd pins the kernel process identity, including across PID reuse.
                fd = os.pidfd_open(target)
            except ProcessLookupError:
                continue
            try:
                path = Path(f"/proc/{target}/stat")
                state, _, current_tick = identity(path)
                if current_tick != tick:
                    raise RuntimeError("CLEANUP_FAILED: PID reused before signal")
                if state == "Z":
                    continue
                if (credentials(path) != (uid,) * 4 or int(current_tick) < floor
                        or nonce not in (path.parent / "environ").read_bytes().split(b"\0")):
                    raise RuntimeError("CLEANUP_FAILED: process ownership changed before signal")
                if identity(path)[2] != tick or credentials(path) != (uid,) * 4:
                    raise RuntimeError("CLEANUP_FAILED: process identity changed before signal")
                signal.pidfd_send_signal(fd, sig)
            except (FileNotFoundError, ProcessLookupError):
                pass
            finally:
                os.close(fd)

    for sig in (signal.SIGTERM, signal.SIGKILL):
        targets = members()
        if not targets:
            break
        terminate(targets, sig)
        deadline = time.monotonic() + 10
        while members() and time.monotonic() < deadline:
            time.sleep(.2)
    if members():
        raise RuntimeError("CLEANUP_FAILED: owned processes remain")
    if receipt.exists():
        receipt.unlink()

def hosted_cleanup():
    owner = hosted_owner()
    directory = Path(owner["directory"])
    if (directory / "operation").exists():
        raise RuntimeError("CLEANUP_FAILED: owned operation still active")
    pending = directory / "process-pending.json"
    if pending.exists() and not (directory / "process.json").exists():
        raise RuntimeError("CLEANUP_FAILED: incomplete process custody")
    hosted_process_cleanup(owner)
    if pending.exists():
        pending.unlink()
    found = hosted_inventory(owner)
    if any(found.values()):
        # Custody may be incomplete on prepare failure, but no Docker resources should
        # exist until compose first succeeds. Missing env with resources fails closed.
        compose(directory / "custody", "down", "--volumes", "--remove-orphans", "--timeout", "20",
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if any(hosted_inventory(owner).values()):
        raise RuntimeError("CLEANUP_FAILED: owned resources remain")
    shutil.rmtree(directory)
    Path(os.environ["QA_OWNER_INDEX"]).unlink()
    if directory.exists():
        raise RuntimeError("CLEANUP_FAILED: private custody remains")
    return {"status": "PASS", "containers": 0, "networks": 0, "volumes": 0, "privateCustodyRemoved": True, "ownedProcessesAbsent": True}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "validate", "up", "status"))
    parser.add_argument("--custody")
    parser.add_argument("--source-sha")
    parser.add_argument("--run-id")
    parser.add_argument("--channel", choices=("main", "pr-candidate"), default="main")
    parser.add_argument("--pr")
    parser.add_argument("--receipt")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.source_sha or "", args.run_id or "", args.channel, args.pr, args.receipt)
    elif args.action == "validate":
        validate(args.custody)
    elif args.action == "up":
        start(args.custody)
    else:
        compose(args.custody, "ps")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as exc:
        # Subprocess stdout/stderr can contain a resolved config; never dump those.
        raise SystemExit(f"QA setup stopped: {type(exc).__name__}") from None
