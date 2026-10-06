#!/usr/bin/env bash
# Candidate-only JVM issuer. No deployment, latest promotion or operating writes.
# Source fingerprint = SHA256 of `git ls-tree -rz --full-tree --abbrev=40 HEAD` bytes.
# Mode fingerprint is local/read-only; admit/issue require pinned Actions identity.
# RTK14 enrichment below is copied from deploy.yml; all child diagnostics are sealed.
set -euo pipefail
set +x
if [[ "${1:-}" == "enrich-internal" ]]; then
set -euo pipefail
umask 077

readonly SCENARIO_DIR="$GITHUB_WORKSPACE/infra/src/main/resources/scenario"
source_json="$(mktemp)"
diagnostics="$(mktemp)"
staging_dir="$(mktemp -d)"
cleanup() {
  rm -f -- "$source_json" "$diagnostics"
  rm -rf -- "$staging_dir"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

if [[ -z "${RTK14_STATS_JSON_B64:-}" ]]; then
  echo "ERROR: RTK14_STATS_JSON_B64 is required for main production image builds." >&2
  exit 1
fi

shopt -s nullglob
candidate_scenario_files=("$SCENARIO_DIR"/scenario_*.json)
scenario_files=()
for candidate in "${candidate_scenario_files[@]}"; do
  scenario_schema="$(
    python3 - "$candidate" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    scenario = json.load(handle)
if not isinstance(scenario, dict):
    raise ValueError("scenario JSON root must be an object")

runtime_sections = ("general", "general_ex", "general_neutral")
archive_sections = ("generals", "generalsEx")
if not any(section in scenario for section in runtime_sections) and any(section in scenario for section in archive_sections):
    print("normalized_archive")
else:
    print("runtime_or_settings")
PY
  )"
  case "$scenario_schema" in
    normalized_archive)
      ;;
    runtime_or_settings)
      scenario_files+=("$candidate")
      ;;
    *)
      echo "ERROR: unable to classify a scenario JSON file for RTK14 enrichment." >&2
      exit 1
      ;;
  esac
done
scenario_count="${#scenario_files[@]}"
if (( scenario_count == 0 )); then
  echo "ERROR: no runtime scenario JSON files were found for RTK14 enrichment." >&2
  exit 1
fi

if ! (printf '%s' "$RTK14_STATS_JSON_B64" | base64 -d | gzip -dc) > "$source_json" 2>"$diagnostics"; then
  echo "ERROR: RTK14_STATS_JSON_B64 could not be decoded." >&2
  exit 1
fi

if ! python3 tools/rtk14/build_rtk14_stats.py \
  --rtk-source-json "$source_json" \
  --scenario-dir "$SCENARIO_DIR" \
  --out-dir "$staging_dir" \
  > "$diagnostics" 2>&1; then
  echo "ERROR: RTK14 scenario enrichment failed." >&2
  # The builder's fail-closed errors identify only contract fields
  # (officer number/name/stable ID), never the secret source payload.
  sed -n '1,120p' "$diagnostics" >&2
  exit 1
fi

staged_files=("$staging_dir"/scenario_*.json)
if (( ${#staged_files[@]} != scenario_count )); then
  echo "ERROR: RTK14 scenario enrichment did not produce every runtime scenario file." >&2
  exit 1
fi
for scenario_file in "${scenario_files[@]}"; do
  staged_file="$staging_dir/$(basename "$scenario_file")"
  if [[ ! -f "$staged_file" ]]; then
    echo "ERROR: RTK14 scenario enrichment missed a runtime scenario file." >&2
    exit 1
  fi
  cp "$staged_file" "$scenario_file"
done

echo "RTK14 scenario enrichment completed for ${scenario_count} scenario file(s)."
  exit 0
fi
export JVM_ISSUER_SCRIPT="$(cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")"
python3 - "$@" <<'JVM_PY'
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import textwrap
import shlex
import urllib.parse
import urllib.request

REPO = "peppone-choi/opensamguk"
URL = "https://github.com/" + REPO
REGISTRY = "ghcr.io/" + REPO
APPS = ("game-api", "game-engine")
PLATFORM = "linux/amd64"
WORKFLOW = ".github/workflows/build-game-jvm-images.yml"
TOOL = "tools/ci/build_game_jvm_images.sh"
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
SEED_TEST = "opensamguk.infra.seed.ScenarioJsonTest.product runtime scenario declares the HWIHA new world and satisfies its seed contract"
METADATA_KEY = "https://mobyproject.org/buildkit@v1#metadata"


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def command(argv, cwd=None, env=None, timeout=60):
    result = subprocess.run(argv, cwd=cwd, env=env, timeout=timeout, capture_output=True)
    require(result.returncode == 0, "candidate command failed: " + argv[0])
    return result.stdout


def git(root, *args):
    return command(["git", "-C", str(root), *args])


def tree_fingerprint(root):
    return digest(git(root, "ls-tree", "-rz", "--full-tree", "--abbrev=40", "HEAD"))


def save(path, data):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(data, stream, sort_keys=True, indent=2)
        stream.write("\n")


def current_main():
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    token = os.environ.get("GH_TOKEN", "")
    require(bool(token), "readonly source observation token absent")
    request = urllib.request.Request("https://api.github.com/repos/" + REPO + "/git/ref/heads/main",
        headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "opensamguk-jvm-candidate"})
    with urllib.request.build_opener(NoRedirect).open(request, timeout=20) as response:
        raw = response.read(65537)
    require(len(raw) <= 65536, "source observation response too large")
    value = json.loads(raw)
    require(value.get("ref") == "refs/heads/main" and value.get("object", {}).get("type") == "commit",
            "source observation is not main commit")
    result = value["object"]["sha"]
    require(bool(SHA.fullmatch(result)), "invalid main SHA")
    return result


def source_files(root):
    # Source bytes bind the transformation without publishing any scenario content.
    result = {}
    for raw in git(root, "ls-files", "-z").split(b"\0"):
        if not raw:
            continue
        name = os.fsdecode(raw)
        path = root / name
        require(path.is_file() or path.is_symlink(), "tracked source absent")
        data = os.readlink(path).encode() if path.is_symlink() else path.read_bytes()
        result[name] = digest(data)
    return result



def enrichment_contract(source, issuer):
    lines = (source / ".github/workflows/deploy.yml").read_text().splitlines()
    start = lines.index("      - name: Materialize RTK14 scenario stats for image build")
    start = lines.index("        run: |", start) + 1
    end = lines.index("      - name: Validate materialized scenario seed contracts", start)
    actual = textwrap.dedent("\n".join(lines[start:end])).rstrip() + "\n"
    script = (issuer / TOOL).read_text()
    copied = script.split('then\n', 1)[1].split('  exit 0\nfi\nexport JVM_ISSUER_SCRIPT=', 1)[0]
    require(actual == copied, "existing RTK14 enrichment contract drifted; review source before issuance")
    run_start = lines.index("        run: >-", end) + 1
    run_end = run_start
    while run_end < len(lines) and (not lines[run_end].strip() or lines[run_end].startswith("          ")):
        run_end += 1
    validation = shlex.split(" ".join(x.strip() for x in lines[run_start:run_end]))
    require(validation == ["./gradlew", ":infra:test", "--no-daemon", "--tests", SEED_TEST],
            "existing scenario validation contract drifted")
    return {"materialization_sha256": digest(actual.encode()), "validation_argv": validation}


def validate(args):
    require(bool(SHA.fullmatch(args.source_sha)) and bool(SHA.fullmatch(args.issuer_sha)),
            "source and issuer must be lowercase full40")
    require(bool(re.fullmatch(r"[0-9a-f]{64}", args.fingerprint)), "source fingerprint must be lowercase full64")
    require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch" and
            os.environ.get("GITHUB_REPOSITORY") == REPO, "manual issuer repository mismatch")
    require(os.environ.get("ACTUAL_WORKFLOW_SHA") == args.issuer_sha, "actual workflow issuer differs")
    require(os.environ.get("GITHUB_RUN_ATTEMPT") == "1", "rerun is not a new authorized issuance")
    require(bool(re.fullmatch(r"[1-9][0-9]*", os.environ.get("GITHUB_RUN_ID", ""))), "invalid run ID")
    issuer = args.issuer.resolve(strict=True)
    source = args.source.resolve(strict=True)
    require(git(issuer, "rev-parse", "HEAD").decode().strip() == args.issuer_sha, "issuer checkout mismatch")
    require(git(source, "rev-parse", "HEAD").decode().strip() == args.source_sha, "source checkout mismatch")
    for name in (WORKFLOW, TOOL):
        require((issuer / name).read_bytes() == git(issuer, "show", args.issuer_sha + ":" + name),
                "issuer file differs from pinned revision")
    require(Path(os.environ["JVM_ISSUER_SCRIPT"]).resolve() == issuer / TOOL, "issuer script path mismatch")
    require(not git(source, "status", "--porcelain", "--untracked-files=all"), "source checkout is not clean")
    require(tree_fingerprint(source) == args.fingerprint, "reviewed source fingerprint mismatch")
    enrichment_contract(source, issuer)
    require(current_main() == args.source_sha, "execution-time main differs from approved source")
    return source, issuer


def plan(args, source, issuer):
    run_id = os.environ["GITHUB_RUN_ID"]
    attempt = os.environ["GITHUB_RUN_ATTEMPT"]
    return {"schema": "game-jvm-candidates/v1", "source_sha": args.source_sha,
            "issuer_sha": args.issuer_sha, "source_inputs_sha256": args.fingerprint,
            "fingerprint_algorithm": "sha256(git ls-tree -rz --full-tree --abbrev=40 HEAD raw bytes)",
            "run_id": run_id, "run_attempt": attempt, "platform": PLATFORM,
            "tags": {app: f"{REGISTRY}:{app}-candidate-{args.source_sha}-{run_id}-{attempt}" for app in APPS},
            "build_args": {"IMAGE_TAG": args.source_sha}, "deployment_approved": False,
            "issuer_files": {name: digest((issuer / name).read_bytes()) for name in (WORKFLOW, TOOL)},
            "enrichment_contract": enrichment_contract(source, issuer),
            "dockerfiles": {app: digest((source / f"docker/{app}.Dockerfile").read_bytes()) for app in APPS}}


def inspect(reference, field, evidence, name):
    raw = command(["docker", "buildx", "imagetools", "inspect", reference,
                   "--format", "{{json ." + field + "}}"], timeout=120)
    (evidence / name).write_bytes(raw)
    return json.loads(raw)


def read_registry_config_blob(config_digest, size):
    """Read one immutable GHCR config blob; never pull layers or log auth."""
    require(isinstance(config_digest, str) and bool(DIGEST.fullmatch(config_digest)) and
            type(size) is int and 0 < size <= 16 * 1024 * 1024, "config blob request invalid")
    registry = REGISTRY
    require(registry.startswith("ghcr.io/") and
            bool(re.fullmatch(r"[a-z0-9._-]+/[a-z0-9._-]+", registry[8:])),
            "config registry repository invalid")
    repository = registry[8:]
    actor, credential = os.environ.get("GITHUB_ACTOR", ""), os.environ.get("GH_TOKEN", "")
    require(bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]*", actor)) and bool(credential) and
            "\r" not in credential and "\n" not in credential, "registry read credential absent")

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError("registry token redirect rejected")

    class BlobRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            parsed = urllib.parse.urlsplit(newurl)
            host = parsed.hostname or ""
            hops = getattr(req, "config_redirect_hops", 0) + 1
            require(parsed.scheme == "https" and not parsed.username and not parsed.password and
                    parsed.port in (None, 443) and hops <= 3 and
                    (host == "ghcr.io" or host.endswith(".githubusercontent.com") or
                     host.endswith(".blob.core.windows.net")), "config redirect rejected")
            forwarded = {key: value for key, value in req.header_items()
                         if key.lower() not in ("authorization", "host")}
            if host == "ghcr.io" and urllib.parse.urlsplit(req.full_url).hostname == host:
                authorization = req.get_header("Authorization")
                if authorization:
                    forwarded["Authorization"] = authorization
            redirected = urllib.request.Request(newurl, headers=forwarded, method="GET")
            redirected.config_redirect_hops = hops
            return redirected

    try:
        query = urllib.parse.urlencode({"service": "ghcr.io", "scope": "repository:" + repository + ":pull"})
        basic = base64.b64encode((actor + ":" + credential).encode()).decode()
        request = urllib.request.Request("https://ghcr.io/token?" + query, headers={
            "Authorization": "Basic " + basic, "Accept-Encoding": "identity",
            "User-Agent": "pinned-candidate-config-read"})
        with urllib.request.build_opener(NoRedirect).open(request, timeout=20) as response:
            require(response.status == 200, "registry token response rejected")
            wire = response.read(65537)
        require(len(wire) <= 65536, "registry token response oversized")
        value = json.loads(wire)
        require(isinstance(value, dict), "registry token response malformed")
        token = value.get("token") or value.get("access_token")
        require(isinstance(token, str) and bool(token) and "\r" not in token and "\n" not in token,
                "registry scoped token absent")
        request = urllib.request.Request("https://ghcr.io/v2/" + repository + "/blobs/" + config_digest,
            headers={"Authorization": "Bearer " + token, "Accept": "application/octet-stream",
                     "Accept-Encoding": "identity", "User-Agent": "pinned-candidate-config-read"})
        with urllib.request.build_opener(BlobRedirect).open(request, timeout=120) as response:
            require(response.status == 200, "registry config response rejected")
            return response.read(size + 1)
    except Exception:
        # Do not disclose token endpoint responses, auth headers or signed URLs.
        raise ValueError("bounded registry config read failed") from None


def verified_image_config(metadata, manifest, read_blob=None):
    """Bind original config bytes to the already SHA-verified platform manifest."""
    require(isinstance(manifest, dict) and
            manifest.get("mediaType") == "application/vnd.oci.image.manifest.v1+json",
            "config platform manifest malformed")
    descriptor = manifest.get("config")
    require(isinstance(descriptor, dict), "config descriptor absent")
    config_digest, size = descriptor.get("digest"), descriptor.get("size")
    require(isinstance(config_digest, str) and bool(DIGEST.fullmatch(config_digest)) and
            descriptor.get("mediaType") == "application/vnd.oci.image.config.v1+json" and
            type(size) is int and 0 < size <= 16 * 1024 * 1024, "config descriptor invalid")
    require(isinstance(metadata, dict), "build metadata malformed")
    if "containerimage.config.digest" in metadata:
        supplied = metadata["containerimage.config.digest"]
        require(isinstance(supplied, str) and bool(DIGEST.fullmatch(supplied)) and
                supplied == config_digest, "metadata config binding mismatch")
    raw = (read_blob or read_registry_config_blob)(config_digest, size)
    require(isinstance(raw, bytes) and len(raw) == size and
            "sha256:" + hashlib.sha256(raw).hexdigest() == config_digest, "config raw bytes mismatch")
    config = json.loads(raw)
    require(isinstance(config, dict) and isinstance(config.get("config"), dict), "config payload malformed")
    return config_digest, config, raw


def verify_image(app, metadata, record, transformed, evidence):
    index_digest = metadata.get("containerimage.digest", "")
    require(bool(DIGEST.fullmatch(index_digest)), "build index digest absent")
    ref = REGISTRY + "@" + index_digest
    index = inspect(ref, "Manifest", evidence, app + "-index.json")
    require(index.get("digest") == index_digest and
            index.get("mediaType") == "application/vnd.oci.image.index.v1+json", "registry index mismatch")
    manifests = index.get("manifests", [])
    images = [x for x in manifests if x.get("platform") == {"os": "linux", "architecture": "amd64"}]
    require(len(images) == 1 and len(manifests) == 2, "expected one platform and one attestation")
    image = images[0]
    platform_digest = image.get("digest", "")
    require(bool(DIGEST.fullmatch(platform_digest)) and
            image.get("mediaType") == "application/vnd.oci.image.manifest.v1+json", "platform descriptor mismatch")
    attestation = next(x for x in manifests if x is not image)
    attestation_digest = attestation.get("digest", "")
    annotations = attestation.get("annotations", {})
    require(bool(DIGEST.fullmatch(attestation_digest)) and
            attestation.get("platform") == {"os": "unknown", "architecture": "unknown"} and
            annotations.get("vnd.docker.reference.type") == "attestation-manifest" and
            annotations.get("vnd.docker.reference.digest") == platform_digest, "attestation is unbound")
    platform_ref = REGISTRY + "@" + platform_digest
    raw = command(["docker", "buildx", "imagetools", "inspect", platform_ref, "--raw"], timeout=120)
    (evidence / (app + "-platform-manifest.raw.json")).write_bytes(raw)
    require("sha256:" + digest(raw) == platform_digest, "platform raw bytes mismatch")
    platform = json.loads(raw)
    config_digest, image_config, raw_config = verified_image_config(metadata, platform)
    require(len({index_digest, platform_digest, config_digest, attestation_digest}) == 4, "digest kinds confused")
    (evidence / (app + "-config.raw.json")).write_bytes(raw_config)
    labels = image_config.get("config", {}).get("Labels", {})
    require(image_config.get("os") == "linux" and image_config.get("architecture") == "amd64" and
            labels.get("org.opencontainers.image.revision") == record["source_sha"] and
            labels.get("org.opencontainers.image.source") == URL, "image platform/source labels mismatch")
    provenance = inspect(ref, "Provenance", evidence, app + "-provenance.json")
    slsa = provenance.get("SLSA", {})
    require(slsa.get("buildType") == "https://mobyproject.org/buildkit@v1" and bool(slsa.get("materials")),
            "BuildKit provenance materials absent")
    invocation = slsa.get("invocation", {})
    require(invocation.get("configSource", {}).get("entryPoint") == app + ".Dockerfile" and
            invocation.get("environment", {}).get("platform") == PLATFORM, "provenance recipe mismatch")
    build_args = {k.removeprefix("build-arg:"): v for k, v in
                  invocation.get("parameters", {}).get("args", {}).items() if k.startswith("build-arg:")}
    require(build_args == record["build_args"], "provenance buildargs mismatch")
    details = slsa.get("metadata", {}).get(METADATA_KEY, {})
    vcs = details.get("vcs", {})
    revision = record["source_sha"] + ("-dirty" if transformed else "")
    require(vcs.get("revision") == revision and vcs.get("localdir:context") == "." and
            vcs.get("localdir:dockerfile") == "docker" and
            vcs.get("source") in (URL, URL + ".git", "git@github.com:" + REPO + ".git"),
            "provenance source/context transformation mismatch")
    files = [x for x in details.get("source", {}).get("infos", []) if x.get("filename") == app + ".Dockerfile"]
    require(len(files) == 1 and digest(base64.b64decode(files[0].get("data", ""), validate=True)) ==
            record["dockerfiles"][app], "provenance Dockerfile bytes mismatch")
    return {"tag": record["tags"][app], "index_digest": index_digest,
            "platform_manifest_digest": platform_digest, "config_digest": config_digest,
            "attestation_manifest_digest": attestation_digest, "platform_reference": platform_ref,
            "provenance_revision": revision}


def issue(args):
    source, issuer = validate(args)
    output = args.output.resolve()
    require(not output.exists(), "issuance evidence must be new; never replay a request")
    output.mkdir(parents=True)
    record = plan(args, source, issuer)
    save(output / "intent.json", record)
    before = source_files(source)
    # The secret only reaches the existing enrichment algorithm. Its output and
    # Gradle diagnostics are never emitted or uploaded; temporary JSON is removed.
    environment = dict(os.environ, GITHUB_WORKSPACE=str(source))
    try:
        command(["bash", str(issuer / TOOL), "enrich-internal"], cwd=source, env=environment, timeout=300)
        safe_environment = {k: v for k, v in os.environ.items()
                            if k not in ("RTK14_STATS_JSON_B64", "GH_TOKEN", "GITHUB_TOKEN")}
        command(["./gradlew", ":infra:test", "--no-daemon", "--tests", SEED_TEST],
                cwd=source, env=safe_environment, timeout=900)
        after = source_files(source)
        require(before.keys() == after.keys(), "source file set changed during enrichment")
        changed = {p: {"before": before[p], "after": after[p]} for p in before if before[p] != after[p]}
        require(all(re.fullmatch(r"infra/src/main/resources/scenario/scenario_[^/]+\.json", p)
                    for p in changed), "enrichment changed non-scenario source")
        # No untracked context additions; ignored build/cache outputs remain
        # governed by the reviewed .dockerignore from final T.
        require(not git(source, "ls-files", "--others", "--exclude-standard", "-z"), "untracked source after enrichment")
        save(output / "transformation.json", {"source_sha": args.source_sha, "scenario_changes": changed,
             "seed_contract_test": SEED_TEST, "result": "PASS", "secret_contents_recorded": False,
             "before_manifest_sha256": digest(json.dumps(before, sort_keys=True).encode()),
             "after_manifest_sha256": digest(json.dumps(after, sort_keys=True).encode())})
        candidates = {}
        for app in APPS:
            require(current_main() == args.source_sha, "main moved before candidate publication")
            require(source_files(source) == after, "source changed after reviewed transformation")
            metadata_file = output / (app + "-build-metadata.json")
            argv = ["docker", "buildx", "build", "--platform", PLATFORM, "--file", f"docker/{app}.Dockerfile",
                    "--build-arg", "IMAGE_TAG=" + args.source_sha, "--provenance=mode=max,version=v0.2",
                    "--sbom=false", "--output=type=image,oci-mediatypes=true", "--metadata-file", str(metadata_file),
                    "--tag", record["tags"][app], "--label", "org.opencontainers.image.revision=" + args.source_sha,
                    "--label", "org.opencontainers.image.source=" + URL, "--push", "."]
            save(output / (app + "-push-intent.json"), {"app": app, "argv": argv, "source_sha": args.source_sha,
                                                      "run_id": record["run_id"], "attempt": record["run_attempt"]})
            build_env = dict(safe_environment, BUILDX_METADATA_PROVENANCE="max",
                             BUILDX_GIT_INFO="true", BUILDX_GIT_CHECK_DIRTY="true")
            command(argv, cwd=source, env=build_env, timeout=2700)
            metadata = json.loads(metadata_file.read_bytes())
            candidates[app] = verify_image(app, metadata, record, bool(changed), output)
            require(source_files(source) == after, "build changed source context")
            save(output / (app + "-verified.json"), candidates[app])
        require(current_main() == args.source_sha, "main moved after issuance; candidates retained unpromoted")
        save(output / "candidate.json", dict(record, status="VERIFIED_CANDIDATES", images=candidates,
                                             transformation_sha256=digest((output / "transformation.json").read_bytes())))
    except Exception:
        save(output / "failure.json", {"status": "HOLD_PARTIAL_OR_UNVERIFIED", "automatic_retry": False,
             "candidate_tags": record["tags"], "preserve_partial_registry_and_evidence": True,
             "deployment_approved": False})
        raise


def main():
    p = argparse.ArgumentParser(description="Issue only two immutable JVM candidates; never deploy.")
    p.add_argument("mode", choices=("fingerprint", "admit", "issue"))
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--issuer", type=Path)
    p.add_argument("--source-sha")
    p.add_argument("--issuer-sha")
    p.add_argument("--fingerprint")
    p.add_argument("--output", type=Path)
    args = p.parse_args()
    if args.mode == "fingerprint":
        require(not git(args.source, "status", "--porcelain", "--untracked-files=all"), "fingerprint source must be clean")
        print(tree_fingerprint(args.source))
        return
    require(all((args.issuer, args.source_sha, args.issuer_sha, args.fingerprint, args.output)), "pinned inputs absent")
    if args.mode == "admit":
        source, issuer = validate(args)
        save(args.output, plan(args, source, issuer))
    else:
        issue(args)


if __name__ == "__main__":
    try:
        main()
    except (Exception, KeyboardInterrupt):
        # Never dump command output, environment, decoded JSON or credentials.
        print("ERROR: pinned JVM candidate issuance failed; retain intent/evidence and do not retry.", file=sys.stderr)
        raise SystemExit(1)
JVM_PY
