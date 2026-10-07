#!/usr/bin/env python3
"""Manage only the loopback QA160 stack; never load an existing env file."""
import argparse
import base64
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
META = ROOT.parents[2]
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
    if env.is_symlink() or env.stat().st_mode & 0o077:
        raise ValueError("QA env must be private and regular")
    return directory


def compose(directory, *args, **kwargs):
    directory = custody(directory)
    return run(["docker", "compose", "--project-name", PROJECT, "--env-file",
                str(directory / "qa.env"), "-f", str(ROOT / "docker-compose.qa.yml"), *args], **kwargs)


def registry():
    return [{"id": "qa160", "name": "QA 전용", "generation": 0,
             "gameUrl": "http://127.0.0.1:18300/game/qa160",
             "gameApiUrl": "http://sqa160-game-api:8081",
             "gameEngineUrl": "http://sqa160-game-engine:8082",
             "deployProject": PROJECT, "scenarioCode": "scenario_3190"}]


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


def prepare(source_sha, run_id):
    if len(source_sha) != 40 or any(c not in "0123456789abcdef" for c in source_sha):
        raise ValueError("Use the full merged-main source SHA")
    if not run_id.isdecimal() or int(run_id) < 1:
        raise ValueError("Use the successful QA image workflow run ID")
    resource_gate()
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 18300))
    directory = Path(tempfile.mkdtemp(prefix="opensamguk-qa160-"))
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
    images = {name: f"ghcr.io/peppone-choi/opensamguk:{name}-qa160-{source_sha}-{run_id}"
              for name in SERVICES}
    values.update({"QA_IMAGE_" + name.upper().replace("-", "_"): ref for name, ref in images.items()})
    with open(directory / "qa.env", "x", opener=lambda p, flags: os.open(p, flags, 0o600)) as handle:
        for name, value in values.items():
            handle.write(f"{name}='{value}'\n")
    (directory / "nginx.conf").write_text(nginx())
    private_json(directory / "stack.json", {"project": PROJECT, "worldId": 160,
                 "sourceSha": source_sha, "runId": run_id, "images": images})
    print(str(directory))  # No credentials, keys or token values.


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
        for ref in record["images"].values():
            revision = run(["docker", "image", "inspect", "--format",
                '{{index .Config.Labels "org.opencontainers.image.revision"}}', ref], capture_output=True).stdout.decode().strip()
            if revision != record["sourceSha"]:
                raise ValueError("QA image revision differs from the shared source")
        compose(directory, "up", "-d", "--no-build", "--wait", "--wait-timeout", "300")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "validate", "up", "status"))
    parser.add_argument("--custody")
    parser.add_argument("--source-sha")
    parser.add_argument("--run-id")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.source_sha or "", args.run_id or "")
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
