#!/usr/bin/env python3
"""Issue one pinned web-game candidate; never deploy it."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


SOURCE = "cf7a1968993e41a98940031c60683129e9ae919a"
REPOSITORY = "ghcr.io/peppone-choi/opensamguk"
SOURCE_URL = "https://github.com/peppone-choi/opensamguk"
PLATFORM = "linux/amd64"
DOCKERFILE = "docker/web-game.Dockerfile"
PROVENANCE_DOCKERFILE = "web-game.Dockerfile"
DOCKERFILE_SHA256 = "e122a4e0f079a97ba470c088a8569e29a7ab9a2c67bb941200b5fb17245103bd"
BUILD_ARGS = {
    "ASSET_PREFIX": "/game",
    "GATEWAY_WEB_URL": "http://web-gateway:3000",
    "NEXT_PUBLIC_GATEWAY_URL": "",
}
FLAGS = {
    "NEXT_PUBLIC_MAP_RENDERER": "unset",
    "NEXT_PUBLIC_TOPDOWN_SCREENS": "unset",
    "NEXT_PUBLIC_BATTLE": "unset (no dedicated reader in this source)",
}
SHA40 = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
METADATA_KEY = "https://mobyproject.org/buildkit@v1#metadata"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def command(argv, cwd=None, env=None):
    # Captured failures must not dump Docker auth or arbitrary child output.
    result = subprocess.run(argv, cwd=cwd, env=env, text=True, capture_output=True)
    require(result.returncode == 0, f"command failed: {argv[0]} {argv[1]} (exit {result.returncode})")
    return result.stdout


def check_source_sha(value):
    require(bool(SHA40.fullmatch(value)), "source_sha must be lowercase full40")
    require(value == SOURCE, "source_sha is outside the reviewed cf7 candidate")


def hash_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_pins(root, source_sha, run=command):
    check_source_sha(source_sha)
    require(run(["git", "rev-parse", "HEAD"], cwd=root).strip() == source_sha, "checkout source mismatch")
    require(not run(["git", "status", "--porcelain", "--untracked-files=all", "--ignored"], cwd=root),
            "source context must contain only clean tracked files")
    require(hash_file(root / DOCKERFILE) == DOCKERFILE_SHA256, "Dockerfile pin mismatch")
    names = run(["git", "ls-files", "-z"], cwd=root).split("\0")
    for name in filter(None, names):
        path = root / name
        require(not path.is_symlink(), "symlink in source context")
        require(not path.name.startswith(".env") or path.name == ".env.example",
                "non-template env file in source context")
    inputs = [DOCKERFILE, ".dockerignore", "web/package.json", "web/pnpm-lock.yaml",
              "web/pnpm-workspace.yaml", "web/game/package.json", "web/gateway/package.json",
              "web/shared/package.json", "web/game/next.config.mjs"]
    return {name: hash_file(root / name) for name in inputs}


def make_plan(root, source_sha, issuer_sha, run_id, attempt, run=command):
    require(bool(SHA40.fullmatch(issuer_sha)), "issuer SHA must be full40")
    require(bool(re.fullmatch(r"[1-9][0-9]*", run_id)), "invalid Actions run ID")
    require(bool(re.fullmatch(r"[1-9][0-9]*", attempt)), "invalid Actions attempt")
    return {
        "schema": "web-game-image-candidate/v1",
        "source_sha": source_sha,
        "issuer_sha": issuer_sha,
        "run_id": run_id,
        "run_attempt": attempt,
        "repository": REPOSITORY,
        "tag": f"{REPOSITORY}:web-game-candidate-{source_sha}-{run_id}-{attempt}",
        "platform": PLATFORM,
        "dockerfile": DOCKERFILE,
        "source_pins_sha256": source_pins(root, source_sha, run),
        "build_args": BUILD_ARGS.copy(),
        "flags": FLAGS.copy(),
        "deployment_approved": False,
    }


def inspect_json(reference, field, run):
    return json.loads(run(["docker", "buildx", "imagetools", "inspect", reference,
                           "--format", "{{json ." + field + "}}" ]))


def verify_provenance(provenance, plan):
    slsa = provenance.get("SLSA", {})
    require(slsa.get("buildType") == "https://mobyproject.org/buildkit@v1", "missing BuildKit SLSA")
    invocation = slsa.get("invocation", {})
    require(invocation.get("configSource", {}).get("entryPoint") == PROVENANCE_DOCKERFILE,
            "provenance Dockerfile entry point mismatch")
    args = invocation.get("parameters", {}).get("args", {})
    actual = {key.removeprefix("build-arg:"): value for key, value in args.items()
              if key.startswith("build-arg:")}
    require(actual == BUILD_ARGS, "provenance build args mismatch")
    require(invocation.get("environment", {}).get("platform") == PLATFORM,
            "provenance platform mismatch")
    metadata = slsa.get("metadata", {}).get(METADATA_KEY, {})
    vcs = metadata.get("vcs", {})
    require(vcs.get("revision") == plan["source_sha"], "provenance revision mismatch or dirty")
    require(vcs.get("localdir:context") == "." and vcs.get("localdir:dockerfile") == "docker",
            "provenance source context or Dockerfile directory mismatch")
    require(vcs.get("source") in (SOURCE_URL, SOURCE_URL + ".git",
                                 "git@github.com:peppone-choi/opensamguk.git"),
            "provenance source repository mismatch")
    sources = metadata.get("source", {}).get("infos", [])
    dockerfiles = [item for item in sources if item.get("filename") == PROVENANCE_DOCKERFILE]
    require(len(dockerfiles) == 1, "missing or ambiguous max provenance Dockerfile")
    content = base64.b64decode(dockerfiles[0].get("data", ""), validate=True)
    require(hashlib.sha256(content).hexdigest() == DOCKERFILE_SHA256,
            "provenance Dockerfile bytes mismatch")
    require(bool(slsa.get("materials")), "provenance materials absent")


def verify_image(plan, build_metadata, run=command):
    index_digest = build_metadata.get("containerimage.digest", "")
    config_digest = build_metadata.get("containerimage.config.digest", "")
    require(bool(DIGEST.fullmatch(index_digest)), "missing OCI index digest")
    require(bool(DIGEST.fullmatch(config_digest)), "missing image config digest")
    index_ref = f"{REPOSITORY}@{index_digest}"
    index = inspect_json(index_ref, "Manifest", run)
    require(index.get("mediaType") == "application/vnd.oci.image.index.v1+json",
            "expected OCI index with provenance attestation")
    require(index.get("digest") == index_digest, "registry index digest mismatch")
    manifests = index.get("manifests", [])
    images = [m for m in manifests if m.get("platform") == {"architecture": "amd64", "os": "linux"}]
    require(len(images) == 1, "exactly one linux/amd64 image required")
    platform_digest = images[0].get("digest", "")
    require(bool(DIGEST.fullmatch(platform_digest)), "invalid platform digest")
    require(images[0].get("mediaType") == "application/vnd.oci.image.manifest.v1+json",
            "platform descriptor must be an OCI manifest")
    require(len({index_digest, platform_digest, config_digest}) == 3, "digest kinds confused")
    attestations = [m for m in manifests if m is not images[0]]
    require(len(attestations) == 1, "exactly one provenance attestation required")
    attestation = attestations[0]
    require(attestation.get("platform") == {"architecture": "unknown", "os": "unknown"}
            and attestation.get("annotations", {}).get("vnd.docker.reference.type") == "attestation-manifest"
            and attestation.get("annotations", {}).get("vnd.docker.reference.digest") == platform_digest
            and bool(DIGEST.fullmatch(attestation.get("digest", ""))), "unbound attestation")
    platform_ref = f"{REPOSITORY}@{platform_digest}"
    raw_manifest = run(["docker", "buildx", "imagetools", "inspect", platform_ref, "--raw"])
    require("sha256:" + hashlib.sha256(raw_manifest.encode()).hexdigest() == platform_digest,
            "platform manifest digest mismatch")
    manifest = json.loads(raw_manifest)
    require(manifest.get("config", {}).get("digest") == config_digest, "image config digest mismatch")
    image = inspect_json(platform_ref, "Image", run)
    require(image.get("architecture") == "amd64" and image.get("os") == "linux", "image platform mismatch")
    config = image.get("config", {})
    labels = config.get("Labels", {})
    require(labels.get("org.opencontainers.image.revision") == plan["source_sha"]
            and labels.get("org.opencontainers.image.source") == SOURCE_URL, "OCI source labels mismatch")
    env = dict(item.split("=", 1) for item in config.get("Env", []) if "=" in item)
    require(env.get("NODE_ENV") == "production", "runtime must be production")
    require(all(not value for key, value in env.items()
                if key.startswith("NEXT_PUBLIC_") and
                (key in FLAGS or "TOPDOWN" in key or "BATTLE" in key)), "runtime feature flag enabled")
    provenance = inspect_json(index_ref, "Provenance", run)
    verify_provenance(provenance, plan)
    return {
        **plan, "status": "VERIFIED_CANDIDATE", "index_digest": index_digest,
        "platform_manifest_digest": platform_digest, "config_digest": config_digest,
        "attestation_manifest_digest": attestation["digest"], "index_reference": index_ref,
        "platform_reference": platform_ref, "provenance": provenance,
        "runtime_check": {"NODE_ENV": "production", "feature_flags": "absent or empty"},
    }


def issue(root, plan, output, run=command):
    expected = make_plan(root, plan["source_sha"], plan["issuer_sha"],
                         plan["run_id"], plan["run_attempt"], run)
    require(plan == expected, "plan differs from pinned issuer contract")
    require(not output.exists(), "output directory must be fresh")
    output.mkdir(parents=True)
    metadata_file = output / "build-metadata.json"
    argv = ["docker", "buildx", "build", "--platform", PLATFORM, "--file", DOCKERFILE,
            "--provenance=mode=max,version=v0.2", "--sbom=false", "--output=type=image,oci-mediatypes=true",
            "--metadata-file", str(metadata_file),
            "--tag", plan["tag"], "--label", f"org.opencontainers.image.revision={plan['source_sha']}",
            "--label", f"org.opencontainers.image.source={SOURCE_URL}"]
    for key, value in BUILD_ARGS.items():
        argv.extend(["--build-arg", f"{key}={value}"])
    argv.extend(["--push", "."])
    env = {**os.environ, "BUILDX_METADATA_PROVENANCE": "max", "BUILDX_GIT_INFO": "true",
           "BUILDX_GIT_CHECK_DIRTY": "true"}
    run(argv, cwd=root, env=env)
    metadata = json.loads(metadata_file.read_text())
    evidence = verify_image(plan, metadata, run)
    (output / "candidate.json").write_text(json.dumps(evidence, indent=2) + "\n")
    # The success file is written only after all registry/provenance guards pass.
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    validate = sub.add_parser("validate-input")
    validate.add_argument("--source-sha", required=True)
    plan_parser = sub.add_parser("plan")
    plan_parser.add_argument("--source", type=Path, required=True)
    plan_parser.add_argument("--source-sha", required=True)
    plan_parser.add_argument("--issuer-sha", required=True)
    plan_parser.add_argument("--run-id", required=True)
    plan_parser.add_argument("--attempt", required=True)
    plan_parser.add_argument("--output", type=Path, required=True)
    publish = sub.add_parser("issue")
    publish.add_argument("--source", type=Path, required=True)
    publish.add_argument("--plan", type=Path, required=True)
    publish.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.mode == "validate-input":
            check_source_sha(args.source_sha)
        elif args.mode == "plan":
            plan = make_plan(args.source.resolve(), args.source_sha, args.issuer_sha,
                             args.run_id, args.attempt)
            args.output.write_text(json.dumps(plan, indent=2) + "\n")
        else:
            evidence = issue(args.source.resolve(), json.loads(args.plan.read_text()), args.output.resolve())
            summary = os.environ.get("GITHUB_STEP_SUMMARY")
            if summary:
                with open(summary, "a") as stream:
                    stream.write("## Verified web-game candidate (deployment approval pending)\n\n")
                    for key in ("source_sha", "issuer_sha", "tag", "index_reference", "platform_reference", "config_digest"):
                        stream.write(f"- {key}: {evidence[key]}\n")
    except (ValueError, KeyError, OSError, TypeError) as error:
        parser.exit(1, f"web-game image candidate rejected: {error}\n")


if __name__ == "__main__":
    main()
