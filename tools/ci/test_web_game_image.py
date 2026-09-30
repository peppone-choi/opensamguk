import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
import sys
from unittest import mock

import yaml

import web_game_image as issuer


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/build-web-game-image.yml"

# Exact cf7 bytes. Never read the moving checkout Dockerfile or patch its expected hash.
CF7_DOCKERFILE = """# web/game (:3001) — Next.js standalone (game client). NEXT_PUBLIC_* are inlined at BUILD time.
FROM node:22-alpine AS build
WORKDIR /src
RUN npm install -g pnpm@10.33.0
# pnpm-workspace.yaml MUST be copied with the manifest+lock so the install honors
# `onlyBuiltDependencies` (sharp/unrs-resolver) — pnpm 10 blocks dep build scripts by default and
# would otherwise leave the natives unbuilt (next build then fails).
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml web/
COPY web/gateway/package.json web/gateway/
COPY web/game/package.json web/game/
COPY web/shared/package.json web/shared/
WORKDIR /src/web
RUN pnpm install --frozen-lockfile --filter @opensamguk/web-game...
COPY web/shared/ /src/web/shared/
COPY web/game/ /src/web/game/
RUN pnpm --dir shared verify:topology game
WORKDIR /src/web/game
# Next.js rewrites are captured during build, including server-only proxy destinations.
ARG GATEWAY_WEB_URL=http://web-gateway:3000
ENV GATEWAY_WEB_URL=$GATEWAY_WEB_URL
ARG NEXT_PUBLIC_GATEWAY_URL=
ENV NEXT_PUBLIC_GATEWAY_URL=$NEXT_PUBLIC_GATEWAY_URL
# 공유 도메인 에셋 충돌 방지 — prod는 ASSET_PREFIX=/game(next.config assetPrefix가 빌드타임에 읽음).
# 미설정(로컬) 시 기본 /_next. assetPrefix는 에셋 URL만 바꿈(라우트/ api 경로 불변).
ARG ASSET_PREFIX=
ENV ASSET_PREFIX=$ASSET_PREFIX
RUN pnpm build

FROM node:22-alpine AS run
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3001
COPY --from=build /src/web/game/.next/standalone ./
COPY --from=build /src/web/game/.next/static ./game/.next/static
COPY --from=build /src/web/game/public ./game/public
EXPOSE 3001
CMD ["sh", "-c", "HOSTNAME=0.0.0.0 exec node game/server.js"]
""".encode("utf-8")

SOURCE_INPUTS = (issuer.DOCKERFILE, ".dockerignore", "web/package.json", "web/pnpm-lock.yaml",
                 "web/pnpm-workspace.yaml", "web/game/package.json", "web/gateway/package.json",
                 "web/shared/package.json", "web/game/next.config.mjs")


def write_source_fixture(root):
    for name in SOURCE_INPUTS:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(CF7_DOCKERFILE if name == issuer.DOCKERFILE else (ROOT / name).read_bytes())


def fixtures():
    config_digest = "sha256:" + "c" * 64
    raw_manifest = json.dumps({"schemaVersion": 2, "mediaType": "application/vnd.oci.image.manifest.v1+json",
                               "config": {"digest": config_digest}, "layers": []})
    platform_digest = "sha256:" + hashlib.sha256(raw_manifest.encode()).hexdigest()
    index_digest = "sha256:" + "a" * 64
    index = {
        "mediaType": "application/vnd.oci.image.index.v1+json", "digest": index_digest,
        "manifests": [
            {"mediaType": "application/vnd.oci.image.manifest.v1+json", "digest": platform_digest,
             "platform": {"architecture": "amd64", "os": "linux"}},
            {"digest": "sha256:" + "d" * 64, "platform": {"architecture": "unknown", "os": "unknown"},
             "annotations": {"vnd.docker.reference.type": "attestation-manifest",
                             "vnd.docker.reference.digest": platform_digest}},
        ],
    }
    provenance = {"SLSA": {
        "buildType": "https://mobyproject.org/buildkit@v1",
        "materials": [{"uri": "pkg:docker/node@22-alpine", "digest": {"sha256": "e" * 64}}],
        "invocation": {"configSource": {"entryPoint": issuer.PROVENANCE_DOCKERFILE},
                       "parameters": {"args": {"build-arg:" + key: value for key, value in issuer.BUILD_ARGS.items()}},
                       "environment": {"platform": issuer.PLATFORM}},
        "metadata": {issuer.METADATA_KEY: {
            "vcs": {"revision": issuer.SOURCE, "source": issuer.SOURCE_URL + ".git",
                    "localdir:context": ".", "localdir:dockerfile": "docker"},
            "source": {"infos": [{"filename": issuer.PROVENANCE_DOCKERFILE,
                                  "data": base64.b64encode(CF7_DOCKERFILE).decode()}]},
        }},
    }}
    image = {"architecture": "amd64", "os": "linux", "config": {
        "Env": ["NODE_ENV=production", "PORT=3001"],
        "Labels": {"org.opencontainers.image.revision": issuer.SOURCE,
                   "org.opencontainers.image.source": issuer.SOURCE_URL},
    }}
    metadata = {"containerimage.digest": index_digest, "containerimage.config.digest": config_digest}
    return metadata, index, raw_manifest, image, provenance


class FakeCommands:
    def __init__(self):
        self.metadata, self.index, self.raw, self.image, self.provenance = fixtures()
        self.calls = []
        self.head = issuer.SOURCE
        self.status = ""
        self.extra_name = ""
        self.build_fails = False

    def __call__(self, argv, cwd=None, env=None):
        self.calls.append((argv, cwd, env))
        if argv[:2] == ["git", "rev-parse"]:
            return self.head + "\n"
        if argv[:2] == ["git", "status"]:
            return self.status
        if argv[:2] == ["git", "ls-files"]:
            return issuer.DOCKERFILE + "\0" + self.extra_name
        if argv[:3] == ["docker", "buildx", "build"]:
            if self.build_fails:
                raise ValueError("synthetic build/publish failure")
            Path(argv[argv.index("--metadata-file") + 1]).write_text(json.dumps(self.metadata))
            return ""
        if argv[:4] == ["docker", "buildx", "imagetools", "inspect"]:
            if argv[-1] == "--raw":
                return self.raw
            return json.dumps({"{{json .Manifest}}": self.index, "{{json .Image}}": self.image,
                               "{{json .Provenance}}": self.provenance}[argv[-1]])
        raise AssertionError(f"unexpected command {argv}")


class ImageCandidateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "source"
        write_source_fixture(self.root)
        self.fake = FakeCommands()
        self.plan = issuer.make_plan(self.root, issuer.SOURCE, "f" * 40, "123", "1", self.fake)
        self.output = Path(self.temp.name) / "evidence"

    def issue(self):
        return issuer.issue(self.root, self.plan, self.output, self.fake)

    def assert_rejected(self, message):
        with self.assertRaisesRegex(ValueError, message):
            self.issue()
        self.assertFalse((self.output / "candidate.json").exists())

    def test_valid_issue_keeps_digest_kinds_and_provenance(self):
        evidence = self.issue()
        self.assertEqual(evidence["status"], "VERIFIED_CANDIDATE")
        self.assertFalse(evidence["deployment_approved"])
        self.assertNotEqual(evidence["source_sha"], evidence["issuer_sha"])
        self.assertEqual(len({evidence[key] for key in ("index_digest", "platform_manifest_digest", "config_digest")}), 3)
        self.assertEqual(json.loads((self.output / "candidate.json").read_text()), evidence)
        builds = [call for call in self.fake.calls if call[0][:3] == ["docker", "buildx", "build"]]
        self.assertEqual(len(builds), 1)
        argv, cwd, env = builds[0]
        self.assertEqual(cwd, self.root)
        self.assertEqual(argv[argv.index("--platform") + 1], "linux/amd64")
        args = [argv[i + 1] for i, item in enumerate(argv) if item == "--build-arg"]
        self.assertEqual(set(args), {"ASSET_PREFIX=/game", "GATEWAY_WEB_URL=http://web-gateway:3000", "NEXT_PUBLIC_GATEWAY_URL="})
        self.assertIn("--provenance=mode=max,version=v0.2", argv)
        self.assertIn("--output=type=image,oci-mediatypes=true", argv)
        self.assertIn("--push", argv)
        self.assertEqual(env["BUILDX_GIT_CHECK_DIRTY"], "true")
        inspections = [call[0][4] for call in self.fake.calls if call[0][:4] == ["docker", "buildx", "imagetools", "inspect"]]
        self.assertTrue(all(ref.startswith(issuer.REPOSITORY + "@sha256:") for ref in inspections))

    def test_fixture_is_pinned_and_independent_of_moving_checkout_dockerfile(self):
        self.assertEqual(hashlib.sha256(CF7_DOCKERFILE).hexdigest(), issuer.DOCKERFILE_SHA256)
        future = Path(self.temp.name) / "future-checkout"
        for name in SOURCE_INPUTS:
            path = future / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((self.root / name).read_bytes())
        (future / issuer.DOCKERFILE).write_bytes(b"FROM a-different-future-base\n")
        with mock.patch.object(sys.modules[__name__], "ROOT", future):
            source = Path(self.temp.name) / "independent-fixture"
            write_source_fixture(source)
            fake = FakeCommands()
            plan = issuer.make_plan(source, issuer.SOURCE, "f" * 40, "123", "1", fake)
            self.assertEqual(set(plan["source_pins_sha256"]), set(SOURCE_INPUTS))
            self.assertEqual(plan["source_pins_sha256"][issuer.DOCKERFILE], issuer.DOCKERFILE_SHA256)
            evidence = issuer.issue(source, plan, self.output, fake)
            self.assertEqual(evidence["status"], "VERIFIED_CANDIDATE")

    def test_source_input_rejects_short_alias_and_other_full40(self):
        for value in ("cf7a196", "main", "a" * 40, issuer.SOURCE.upper(), issuer.SOURCE + ";echo injected"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                issuer.check_source_sha(value)

    def test_forged_contract_is_rejected_before_push(self):
        for key, value in (("repository", "ghcr.io/elsewhere/image"), ("platform", "linux/arm64"),
                           ("tag", issuer.REPOSITORY + ":latest"), ("deployment_approved", True),
                           ("build_args", {**issuer.BUILD_ARGS, "NEXT_PUBLIC_MAP_RENDERER": "topdown"})):
            with self.subTest(key=key):
                changed = copy.deepcopy(self.plan)
                changed[key] = value
                with self.assertRaisesRegex(ValueError, "plan differs"):
                    issuer.issue(self.root, changed, self.output, self.fake)
        self.assertFalse(any(call[0][:3] == ["docker", "buildx", "build"] for call in self.fake.calls))

    def test_checkout_revision_dirty_and_ignored_context_rejected(self):
        self.fake.head = "a" * 40
        self.assert_rejected("checkout source mismatch")
        self.fake.head = issuer.SOURCE
        for status in (" M docker/web-game.Dockerfile\n", "?? web/game/.env.local\n", "!! web/game/.next/\n"):
            self.fake.status = status
            self.assert_rejected("clean tracked files")

    def test_changed_dockerfile_fails_before_build(self):
        with (self.root / issuer.DOCKERFILE).open("a") as f:
            f.write("\nENV NEXT_PUBLIC_MAP_RENDERER=topdown\n")
        self.assert_rejected("Dockerfile pin")

    def test_tracked_env_name_or_symlink_rejected_without_reading_value(self):
        self.fake.extra_name = "web/game/.env.production\0"
        self.assert_rejected("non-template env")
        self.fake.extra_name = "web/game/.env.example\0"
        issuer.source_pins(self.root, issuer.SOURCE, self.fake)
        path = self.root / "web/game/link"
        path.symlink_to("/nonexistent")
        self.fake.extra_name = "web/game/link\0"
        self.assert_rejected("symlink")

    def test_publish_failure_never_writes_success(self):
        self.fake.build_fails = True
        self.assert_rejected("synthetic build/publish failure")

    def test_missing_build_digest_rejected(self):
        self.fake.metadata.pop("containerimage.digest")
        self.assert_rejected("OCI index digest")

    def test_registry_index_digest_mismatch_rejected(self):
        self.fake.index["digest"] = "sha256:" + "b" * 64
        self.assert_rejected("registry index digest")

    def test_wrong_or_extra_platform_rejected(self):
        self.fake.index["manifests"][0]["platform"]["architecture"] = "arm64"
        self.assert_rejected("exactly one linux/amd64")

    def test_missing_or_unbound_attestation_rejected(self):
        self.fake.index["manifests"][1]["annotations"]["vnd.docker.reference.digest"] = "sha256:" + "b" * 64
        self.assert_rejected("unbound attestation")

    def test_no_attestation_rejected(self):
        self.fake.index["manifests"].pop()
        self.assert_rejected("exactly one provenance attestation")

    def test_digest_type_confusion_rejected(self):
        self.fake.metadata["containerimage.config.digest"] = self.fake.metadata["containerimage.digest"]
        self.assert_rejected("digest kinds confused")

    def test_raw_platform_tampering_rejected(self):
        self.fake.raw += " "
        self.assert_rejected("platform manifest digest mismatch")

    def test_config_link_mismatch_rejected(self):
        self.fake.metadata["containerimage.config.digest"] = "sha256:" + "b" * 64
        self.assert_rejected("image config digest mismatch")

    def test_runtime_development_or_flag_rejected(self):
        self.fake.image["config"]["Env"] = ["NODE_ENV=development"]
        self.assert_rejected("runtime must be production")

    def test_runtime_flag_rejected(self):
        self.fake.image["config"]["Env"].append("NEXT_PUBLIC_MAP_RENDERER=topdown")
        self.assert_rejected("runtime feature flag enabled")

    def test_oci_source_label_mismatch_rejected(self):
        self.fake.image["config"]["Labels"]["org.opencontainers.image.revision"] = "b" * 40
        self.assert_rejected("OCI source labels")

    def test_provenance_absent_rejected(self):
        self.fake.provenance.clear()
        self.assert_rejected("missing BuildKit SLSA")

    def test_provenance_source_revision_and_dockerfile_are_checked(self):
        self.fake.provenance["SLSA"]["metadata"][issuer.METADATA_KEY]["vcs"]["revision"] += "-dirty"
        self.assert_rejected("provenance revision mismatch")

    def test_provenance_buildarg_flag_rejected(self):
        self.fake.provenance["SLSA"]["invocation"]["parameters"]["args"]["build-arg:NEXT_PUBLIC_TOPDOWN_SCREENS"] = "true"
        self.assert_rejected("provenance build args")

    def test_provenance_platform_mismatch_rejected(self):
        self.fake.provenance["SLSA"]["invocation"]["environment"]["platform"] = "linux/arm64"
        self.assert_rejected("provenance platform")

    def test_provenance_dockerfile_bytes_mismatch_rejected(self):
        self.fake.provenance["SLSA"]["metadata"][issuer.METADATA_KEY]["source"]["infos"][0]["data"] = base64.b64encode(b"FROM different").decode()
        self.assert_rejected("provenance Dockerfile bytes")

    def test_provenance_dockerfile_directory_mismatch_rejected(self):
        self.fake.provenance["SLSA"]["metadata"][issuer.METADATA_KEY]["vcs"]["localdir:dockerfile"] = "elsewhere"
        self.assert_rejected("provenance source context or Dockerfile directory")

    def test_provenance_dockerfile_entrypoint_mismatch_rejected(self):
        self.fake.provenance["SLSA"]["invocation"]["configSource"]["entryPoint"] = "other.Dockerfile"
        self.assert_rejected("provenance Dockerfile entry point")

    def test_provenance_other_repository_rejected(self):
        self.fake.provenance["SLSA"]["metadata"][issuer.METADATA_KEY]["vcs"]["source"] = "https://github.com/elsewhere/other.git"
        self.assert_rejected("provenance source repository")

    def test_provenance_materials_required(self):
        self.fake.provenance["SLSA"]["materials"] = []
        self.assert_rejected("provenance materials absent")


class WorkflowBoundaryTest(unittest.TestCase):
    def assert_no_failure_masking(self, workflow):
        for name, job in workflow["jobs"].items():
            self.assertNotIn("continue-on-error", job, f"{name} job must propagate failures")
            for index, step in enumerate(job["steps"]):
                self.assertNotIn("continue-on-error", step, f"{name} step {index} must propagate failures")
        self.assertNotIn("if", workflow["jobs"]["admit"]["steps"][0], "guard cannot be skipped")

    def test_admission_and_image_failures_cannot_be_masked(self):
        workflow = yaml.load(WORKFLOW.read_text(), Loader=yaml.BaseLoader)
        self.assert_no_failure_masking(workflow)

    def test_only_manual_github_hosted_image_job_with_pinned_actions(self):
        workflow = yaml.load(WORKFLOW.read_text(), Loader=yaml.BaseLoader)
        self.assert_no_failure_masking(workflow)
        self.assertEqual(set(workflow["on"]), {"workflow_dispatch"})
        inputs = workflow["on"]["workflow_dispatch"]["inputs"]
        self.assertEqual(set(inputs), {"source_sha", "expected_issuer_sha"})
        self.assertEqual(inputs["expected_issuer_sha"]["required"], "true")
        self.assertNotIn("default", inputs["expected_issuer_sha"])
        self.assertEqual(inputs["source_sha"]["default"], issuer.SOURCE)
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        self.assertEqual(set(workflow["jobs"]), {"admit", "image"})
        admission = workflow["jobs"]["admit"]
        self.assertEqual(admission["runs-on"], "ubuntu-24.04")
        self.assertEqual(admission["permissions"], {"contents": "read"})
        self.assertEqual(len(admission["steps"]), 1)
        self.assertNotIn("uses", admission["steps"][0])
        self.assertNotIn("${{", admission["steps"][0]["run"])
        self.assertNotRegex(admission["steps"][0]["run"], r"(?i)\b(docker|checkout|curl|gh|ssh|sudo)\b")
        job = workflow["jobs"]["image"]
        self.assertEqual(job["runs-on"], "ubuntu-24.04")
        self.assertEqual(job["permissions"], {"contents": "read", "packages": "write"})
        self.assertNotIn("environment", job)
        self.assertEqual(job["needs"], "admit")
        self.assertNotIn("if", job)
        self.assertEqual(job["env"], {"SOURCE_SHA": "${{ needs.admit.outputs.source_sha }}",
                                      "ISSUER_SHA": "${{ needs.admit.outputs.issuer_sha }}"})
        allowed = {
            "actions/checkout@11d5960a326750d5838078e36cf38b85af677262",
            "docker/setup-buildx-action@8d2750c68a42422c14e847fe6c8ac0403b4cbd6f",
            "docker/login-action@c94ce9fb468520275223c153574b00df6fe4bcc9",
            "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02",
        }
        steps = job["steps"]
        self.assertEqual(len(steps), 8)
        for step in steps:
            if "uses" in step:
                self.assertIn(step["uses"], allowed)
            else:
                self.assertNotIn("${{", step["run"])
                self.assertNotRegex(step["run"], r"(?i)\b(ssh|gcloud|kubectl|curl|gh|sudo|compose|deploy)\b")
                self.assertIn("python3 issuer/tools/ci/web_game_image.py", step["run"])
        checkouts = [step for step in steps if step.get("uses", "").startswith("actions/checkout@")]
        self.assertEqual([step["with"]["path"] for step in checkouts], ["issuer", "source"])
        self.assertTrue(all(step["with"]["persist-credentials"] == "false" for step in checkouts))
        self.assertEqual(checkouts[0]["with"]["ref"], "${{ needs.admit.outputs.issuer_sha }}")
        self.assertEqual(checkouts[1]["with"]["ref"], "${{ needs.admit.outputs.source_sha }}")
        self.assertEqual(steps[1]["run"].split()[2], "validate-input")
        upload = steps[-1]
        self.assertNotIn("if", upload)
        self.assertEqual(upload["with"]["if-no-files-found"], "error")
        self.assertEqual(set(upload["with"]["path"].split()),
                         {"plan.json", "evidence/candidate.json", "evidence/build-metadata.json"})

    def run_admission(self, expected, actual, source=issuer.SOURCE):
        workflow = yaml.load(WORKFLOW.read_text(), Loader=yaml.BaseLoader)
        self.assert_no_failure_masking(workflow)
        guard = workflow["jobs"]["admit"]["steps"][0]
        self.assertEqual(guard["env"], {
            "EXPECTED_ISSUER_SHA": "${{ inputs.expected_issuer_sha }}",
            "ACTUAL_ISSUER_SHA": "${{ github.workflow_sha }}",
            "SOURCE_SHA": "${{ inputs.source_sha }}",
        })
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "outputs"
            env = {**os.environ, "EXPECTED_ISSUER_SHA": expected, "ACTUAL_ISSUER_SHA": actual,
                   "SOURCE_SHA": source, "GITHUB_OUTPUT": str(output)}
            result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", guard["run"]],
                                    env=env, text=True, capture_output=True)
            outputs = output.read_text() if output.exists() else ""
            # Model the declared successful needs dependency, never invoke real actions/Docker.
            downstream = ["checkout", "login", "build/publish"] if result.returncode == 0 else []
            return result, outputs, downstream

    def test_approved_issuer_admission_outputs_bind_both_checkouts(self):
        result, outputs, downstream = self.run_admission("f" * 40, "f" * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(outputs, "issuer_sha=" + "f" * 40 + "\nsource_sha=" + issuer.SOURCE + "\n")
        self.assertEqual(downstream, ["checkout", "login", "build/publish"])

    def test_wrong_full40_issuer_never_reaches_checkout_login_publish(self):
        result, outputs, downstream = self.run_admission("e" * 40, "f" * 40)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("differs from approved issuer", result.stderr)
        self.assertEqual(outputs, "")
        self.assertEqual(downstream, [])

    def test_missing_short_alias_uppercase_and_injected_issuer_rejected(self):
        for expected in ("", "abc123", "main", "F" * 40, "f" * 40 + ";echo injected"):
            with self.subTest(expected=expected):
                result, outputs, downstream = self.run_admission(expected, "f" * 40)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("lowercase full40", result.stderr)
                self.assertEqual(outputs, "")
                self.assertEqual(downstream, [])

    def test_invalid_actual_workflow_sha_rejected(self):
        result, outputs, downstream = self.run_admission("f" * 40, "main")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(outputs, "")
        self.assertEqual(downstream, [])

    def test_source_admission_rejects_before_package_write_job(self):
        result, outputs, downstream = self.run_admission("f" * 40, "f" * 40, "a" * 40)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("outside the reviewed cf7 candidate", result.stderr)
        self.assertEqual(outputs, "")
        self.assertEqual(downstream, [])


if __name__ == "__main__":
    unittest.main()
