# OPENSAM-91 profile icon loop ledger

## Contract

- Baseline snapshot: `b450dee7a5e785b9faa9b1b9d016714008c601fd`; unrelated shared-workspace changes are preserved.
- One hypothesis: the OPENSAM-91 gap is caused by a metadata-only JSON setter with no decoded-file boundary, durable storage contract, or dedicated daily-limit truth; replacing that boundary with the approved A1 storage/service/catalog design will make the frozen dimensions measurable and green.
- Grader: the named tests and seven compose assertions in `GOLDENSET.md`, followed by the unchanged gateway/backend gates.
- Adopt: retain a candidate only when its deterministic score rises, no scoped requirement regresses, and executed verification is reported separately from pending verification.
- Revert: if a deterministic score ties, falls, or becomes unmeasurable, restore that candidate's pre-loop snapshot without weakening tests.
- Approval waiting: the legal-review release gate, A4 commit/push/PR, and A5 deploy. Independent A2 security/backend re-review is complete and green.

## Environment baselines

- CodeGraph found the current profile implementation, but its first broad result was output-limited before the configuration body. A narrowed `ProfileIconConfiguration` query recovered the exact current property defaults without product changes.
- One broad read-only `rg` over `/tmp` produced truncated noise. Subsequent evidence searches were restricted to the OPENSAM-91 spike paths.
- The first compose patch used a malformed absolute Korean path and was rejected before applying. The repository-relative retry is the candidate under test.
- YAML LSP is not installed because installation was previously declined. `docker compose config --format json` is the syntax and interpolation authority for this lane.
- The first sandboxed Docker daemon probe returned `permission denied`. The approved escalated probe then ran successfully; this is not recorded as Docker-unavailable.
- Compose validation explicitly used `--env-file /dev/null` plus non-secret dummy values for required interpolation. No `.env*` file was read or changed.
- Creating the required `/tmp` review package triggered a post-edit LSP-hook error because that path is outside the repository working directory. The patch itself was present and complete; explicit file, whitespace, final-newline, and SHA-256 checks are the package authority.
- A read-only `ps` attempt to observe the implementer's long-running Gradle rerun was denied by the sandbox. No evidence or conclusion relies on process inspection; completion is taken only from the implementer's captured test output.

## Measurements

| 바퀴 | 가설 | 점수 전->후 | 채점자 | 판정 | 원인 한 줄 |
|---:|---|---|---|---|---|
| 0-pre | 기존 gateway 계약이 OPENSAM-91 전용 동작을 증명하는가 | 전용 증거 0/10 | `git show`/`git ls-tree` baseline inventory | 기준선 | baseline은 client-provided JSON `picture`/`imgsvr` metadata만 갱신했고 dedicated profile package/tests가 없었다. |
| 0-compose | local/prod가 승인된 durable storage root를 보존하는가 | 1/7 -> 7/7 | rendered local/prod compose JSON + fixed `jq` assertions | 채택 | baseline에는 env/mount/volume이 없고 nginx 미노출만 충족했으며, candidate는 양 환경에 동일한 writable named volume만 추가했다. |
| 1-backend | 승인된 storage/service/catalog 경계가 10개 frozen dimension을 닫는가 | 0/10 -> 9/10 verified; D10 `environment-failed/inconclusive` | dedicated gateway-api tests + PostgreSQL IT + broad gates | 조건부 채택 | final module suites are green, but backend parity exited 1 when `GameApiApplicationTests` hit a pre-assertion blank Docker `Status 500`; no retry was authorized, so v1 parity is neither pass nor code-fail. |

## Compose evidence

Baseline render for both files returned:

```text
["MISSING",0,"MISSING",0]
```

The fields are storage-root env, target-mount count, named-volume declaration, and nginx target-mount count. Candidate renders for both local and production returned:

```json
{"env":"/var/lib/opensamguk/profile-icons","mount":{"type":"volume","source":"profile-icons","target":"/var/lib/opensamguk/profile-icons","volume":{}},"volume":{"name":"opensamguk_profile-icons","driver":"local"},"nginxMounts":0}
```

No `read_only` flag is present, so the mount is writable. This is static topology proof only; the full stack smoke test remains pending.
Because crash-recovery markers live beneath this same root, a future OPENSAM-93 serving design must expose only validated managed image names and explicitly exclude the journal namespace. The current nginx service has no root mount, so this candidate does not expose either files or markers.

## Codec artifact and runtime evidence

### Resolved direct artifacts

| Purpose | Maven coordinate | Resolved direct-JAR SHA-256 | Declared license evidence |
|---|---|---|---|
| WebP reader | `com.twelvemonkeys.imageio:imageio-webp:3.13.1` | `2976141af57a2782fb7e1d18b896ed37d3f5e2319342fdb8bdcc7312e43b04c4` | parent POM calls it BSD; upstream tagged tree identifies BSD-3-Clause |
| AVIF native reader | `io.github.nemanjastokuca:avif-imageio-native-reader:0.1.0` | `6176517062a54b584b03d14ce37d7e324a0aa208e3c82693a429b3a1c87657c1` | artifact POM and embedded `META-INF/LICENSE` identify LGPL-3.0 |

Primary upstream references:

- TwelveMonkeys 3.13.1 tagged source/license: `https://github.com/haraldk/TwelveMonkeys/tree/twelvemonkeys-3.13.1`
- TwelveMonkeys Maven artifact: `https://central.sonatype.com/artifact/com.twelvemonkeys.imageio/imageio-webp/3.13.1`
- AVIF reader source/license and usage: `https://github.com/nemanjastokuca/imageio-avif`
- AVIF Maven artifact: `https://central.sonatype.com/artifact/io.github.nemanjastokuca/avif-imageio-native-reader/0.1.0`

The AVIF JAR contains native libraries for `amd64` and `arm64` on Linux, macOS, and Windows. It also embeds `META-INF/LICENSE.libavif` and `META-INF/LICENSE.libdav1d`, each carrying its upstream two-clause BSD notice. The direct-JAR table is not a complete transitive SBOM.
The repository currently has no Gradle dependency-verification metadata, so the SHA-256 values above are observed audit evidence, not build-enforced checksum pins.

### Synthetic fixture and exact runtime probe

- AVIF fixture: 398 bytes, SHA-256 `e43a7ad83e39caeeb12218ee11b284ac50e2a11088c7f0b61e5ff0f06e04df39`.
- WebP fixture: 70 bytes, SHA-256 `b709d23cf44509282ccd675aa4f2cdb78462e93e98945fcafebfe42420e02e38`.
- Implementer provenance: generated from a synthetic 80x80 raster with `lscr.io/linuxserver/ffmpeg:7.1.1` at digest `sha256:aea59a11c54291ac456bb2d67000445e5a8994f70bc3d96cdc29f022fbbf89fb`. This generator digest was relayed by the implementer; this lane independently re-hashed the resulting fixtures.
- Host probe fully called `reader.read(0)` for both files and produced the same dimensions/frame count/center pixel as the container probe below.
- Production runtime-base probe: `eclipse-temurin:21-jre`, Linux `amd64`, digest `sha256:273396ed5998598ed1091e8d72711c2d36980a0e65103859c55a4e977a41ffd3`.

```text
synthetic.avif reader=com.github.ustc_zzzz.imageio.avif.AVIFImageReader format=avif dimensions=80x80 frames=1 center=ff787952
synthetic.webp reader=com.twelvemonkeys.imageio.plugins.webp.WebPImageReader format=webp dimensions=80x80 frames=1 center=ff787850
```

This proves the two pinned readers can load and decode the two pinned fixtures on the current Dockerfile runtime base ABI. It does not prove the final GHCR `gateway-api` image, future mutable base tags, hostile-input safety, decoder DoS resistance, or general codec correctness.

## Shared-catalog provenance evidence

- The production manifest separates `existing_shared_cdn` from `bundled_cleared`; the observed candidate has two existing external entries and zero bundled/redistributed entries. It declares exactly one gateway operational fallback and zero cleared 91b/NPC fallbacks.
- Existing ID `1001` pins `peppone-choi/opensamguk-images`, immutable revision `1b6624d886c1b326a2feeda449288b41231df5ef`, source path `icons/1001.jpg`, and the corresponding revision-pinned jsDelivr URL. **Historical note (2026-08-17):** `opensamguk-images` had its history rewritten to drop 2,335 optional-IP-portrait files. Tag `v2026.05.21` now points at `05842c61132fd5a71268fd9babd80ba74e27be62`, which carries the same file bytes at the same paths, and the live pin was updated separately in `shared-manifest.json`. This entry keeps the old revision string because it records what this ledger's evidence gathering actually observed at the time. **The old revision is unreferenced, NOT gone:** `raw.githubusercontent.com/.../1b6624d886c1b326a2feeda449288b41231df5ef/...` still returns `200` for the removed files (measured 2026-08-17), because GitHub retains unreachable objects until it garbage-collects them. Force-push alone did not close the IP exposure — only a GitHub Support unreachable-object purge or a delete-and-recreate of the repository does. Do not record the removal as complete until that step is verified.
- An implementer-relayed re-fetch of that pinned URL produced a 64x64 JPEG with SHA-256 `4d27da9a19571236183fd9ec40f5cd9550432ef574000ab78519692c1176d3b5`, matching the manifest. This lane independently confirmed that the current manifest and tests encode that revision, path, digest, and dimension contract; it did not perform another network fetch.
- Operational fallback ID `default` pins the same immutable repository revision at `icons/default.jpg`. Implementer/collaborator-relayed re-fetch evidence records a 64x64 JPEG with SHA-256 `f53c76d05281db09a9d859e14c6bf3f6ecbc8001b70330a62d6041d4e168141b`, matching the manifest.
- Catalog construction validates ID, filename/extension, media type, dimensions, digest shape/portrait-ID relationship, repository slug, full 40-hex revision, source path, and exact revision-pinned delivery URL. Bundled entries additionally require classpath presence, byte-for-byte SHA-256 equality, real decode, canonical extension/media type, and dimensions; negative fixtures cover missing and wrong-hash bundled assets.
- Both external entries explicitly carry `license_status=unknown` and `redistribution_status=unknown`; neither is a cleared 91b/NPC asset. The pinned `default.jpg` is only the existing gateway operational fallback. External entries are not downloaded at application startup. Their byte/digest match is captured release evidence, not an online runtime dependency or a proof of redistribution rights.

## Legal-review release gate

Status: `BLOCKS A4/A5`; no legal-clearance claim is made.

The current external `1001` and operational `default` entries both remain `license_status=unknown` and `redistribution_status=unknown`. The code-level catalog gate is green, but this does not satisfy the cleared 91b/NPC fallback requirement and does not authorize roster activation or release.

Before commit/release approval, a human legal/release owner must decide and record all of the following:

1. how the complete TwelveMonkeys BSD-3 copyright/license notice is reproduced with the binary distribution;
2. how LGPL-3.0 notice, license-copy, source/relinking, reverse-engineering, and installation-information obligations apply to the Spring Boot nested-JAR plus bundled JNI delivery model;
3. how the bundled libavif and libdav1d BSD notices are shipped;
4. a complete runtime transitive dependency/SBOM inventory rather than only the two direct-JAR hashes above;
5. a final built `gateway-api` image probe and recorded immutable image/base digests at A5, because `eclipse-temurin:21-jre` is a mutable tag;
6. ownership for native-library CVE monitoring and the rollback or accepted-format reduction path.

No repository-level third-party notice was created in this lane because the required LGPL distribution model and complete transitive inventory have not been legally selected. Creating a partial notice would overstate release readiness.

## Verification evidence

- `docker compose version` -> `v5.1.1`.
- Local static config -> parsed and rendered to JSON with exact env/mount/volume assertions green.
- Production static config -> parsed and rendered to JSON with the same assertions green.
- Host codec probe -> AVIF and WebP full `BufferedImage` decode returned the pinned observations above.
- Linux/amd64 runtime-base codec probe -> same pinned observations, exit 0.
- Implementer-relayed targeted evidence: decoder exact-container/polyglot 8/8 green in 22s; storage plus manifest 7/7; service compensation/KST 7/7 green in 17s; HTTP security 5/5 with `BUILD SUCCESSFUL` in 1m11s.
- Implementer-relayed PostgreSQL evidence: V30 `TIMESTAMPTZ`/`Instant` round-trip `BUILD SUCCESSFUL` in 1m08s; two authenticated concurrent multipart requests returned exactly one `200` and one `409`, with one managed `.png` and one timestamp persisted, `BUILD SUCCESSFUL` in 1m28s.
- Implementer-relayed pre-review full gateway suite: 101 tests, zero skipped/failures/errors, `BUILD SUCCESSFUL` in 2m01s; scoped `git diff --check` was clean.
- A security reviewer subsequently required partial-write cleanup, a bounded GIF parser, stronger secure-root TOCTOU handling, mandatory explicit manifest license status, exactly one fallback, and crash-safe file/DB reconciliation. The durable design avoids deleting the old upload before commit, writes PII-free operation markers beneath the configured storage root, reconciles in `afterCompletion` and at startup, and retains plus logs a marker when reconciliation fails. The compose named volume persists those markers across container recreation. These requirements invalidated the pre-review suite; the post-fix evidence is recorded below.
- Post-review RED evidence: `ProfileIconDecoderTest` ran 14 tests with exactly one failure and zero errors. The new `gif trailer must terminate the parsed block stream rather than merely be the last byte` case proved that the old last-byte-only GIF bound accepted a crafted block stream. The bounded parser then closed this RED on the same sheet.
- Post-review focused GREEN observed directly from Gradle XML after the final storage changes: `LocalProfileIconStorageTest` 8/8, `ProfileIconDecoderTest` 14/14, `ProfileIconHttpSecurityTest` 6/6, `ProfileIconOperationReconcilerTest` 3/3, `ProfileIconServiceTest` 8/8, and `SharedProfileIconCatalogTest` 11/11. All 50 tests have zero skips/failures/errors, including the previously RED GIF parser case; the final composite follows below.
- One post-review `ProfileIconMultipartLimitIT` run overlapped the catalog-schema/manifest edits and failed application-context startup because Gradle's copied manifest lacked a newly required integer field. This stale-resource run is isolated rather than retried by this lane; only a clean rerun after the manifest and all negative fixtures settle can replace it.
- A later final full-gateway attempt collided with another process writing the shared Gradle build outputs, producing missing runtime classes and XML-write conflicts. This lane did not launch Gradle; it stopped reading build outputs when notified. The collided attempt is invalid evidence and must be replaced by one clean exclusive full run.
- Final isolated gateway invocation rebuilt all 13 tasks under JDK 21 with `--no-daemon --no-parallel --max-workers=1 --no-build-cache --no-configuration-cache` and ran 126 tests in 11m03s. It recorded 125 passes; only `ProfileIconPostgresConcurrencyIT` failed during Testcontainers initialization, before assertions, because docker-java received a blank `Status 500:` from container creation.
- After a sandbox-approved Docker health check showed client/server `29.3.1` healthy and no residual containers, the PostgreSQL class was rerun exactly once under the same isolation flags. It completed `BUILD SUCCESSFUL` in 2m22s; XML records `tests=1 skipped=0 failures=0 errors=0`.
- Composite code-test evidence is therefore 126/126, while the monolithic invocation is honestly retained as not fully green because of the Docker infrastructure transient. The scoped `git diff --check -- app/gateway-api infra gradle/libs.versions.toml` result is clean.
- Final independent re-review verdict: `SPEC PASS`, `SECURITY PASS`, and `TESTS PASS`, with no residual findings; its separate disposition is `LICENSE BLOCKED`.
- Post-security focused evidence: secure/log boundary 25/25 and `ProfileIconRecoveryIT` 2/2. The final remaining regression sheet records HTTP 6/6, multipart 1/1, PostgreSQL concurrency 1/1 after the single allowed pre-assertion blank-`Status 500` Docker retry, and V30 migration 1/1; all are green.
- Final verifier matrix: infra fresh 140/140 green; gateway 131 non-PostgreSQL tests green plus isolated PostgreSQL 1/1 green after the recorded initial pre-assertion blank Docker `Status 500`; web typecheck and scoped diff check green.
- Backend parity exited 1 solely because `GameApiApplicationTests` failed application initialization before assertions on another blank Docker `Status 500`. The sheet records game-api 394 total with one initialization failure; common 208/208 green; logic 3110/3110 green; game-engine 557 tests with zero failures/errors and one skip. Per verifier policy this case was not retried, so D10 is `environment-failed/inconclusive`, not pass and not a product-code failure.
- Strict agent-system verification now reports only the pre-existing `.codex/config.toml` personal-model pin. Cross-agent critique is cleared by review SHA prefix `49e3`.
- `./tools/smoke.sh` remains unrun because A4/A5 are blocked. D10, the legal gate, and A4/A5 remain open.

## Approval waiting

- A2 independent security/backend re-review: `SPEC PASS`, `SECURITY PASS`, `TESTS PASS`, `fix-required=0`.
- Legal-review release gate above.
- A4 commit/push/PR and A5 deploy; Jira/GitHub/external state remains unchanged.
