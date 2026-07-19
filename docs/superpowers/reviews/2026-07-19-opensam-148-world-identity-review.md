# OPENSAM-148 canonical world identity remediation review

Scope: `common/src/main/kotlin/opensamguk/common/world/WorldId.kt`, `common/src/test/kotlin/opensamguk/common/world/WorldIdTest.kt`, `docs/superpowers/specs/2026-07-19-canonical-world-identity-contract.md`, and the OPENSAM-148 sections of `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md`.

Verdict: cleared

## Remediation verified

The prior MAJOR is resolved. `WorldId` now uses an explicit `KSerializer` that declares an `INT` primitive descriptor, writes with `encodeInt`, accepts only a non-string `JsonPrimitive` whose value is an `Int`, and then constructs `WorldId`. It rejects every other JSON shape before an identity can enter the system ([WorldId.kt](/Users/apple/Desktop/개인프로젝트/opensamguk/common/src/main/kotlin/opensamguk/common/world/WorldId.kt:23)).

Direct Java 21.0.10 execution through the public `WorldId.Companion.serializer()` observed exactly the required boundary behavior:

```text
descriptor=INT
42 => ACCEPTED WorldId(value=42)
"42" => REJECTED kotlinx.serialization.SerializationException
{"value":42} => REJECTED kotlinx.serialization.SerializationException
0 => REJECTED kotlinx.serialization.SerializationException
-1 => REJECTED kotlinx.serialization.SerializationException
```

The focused tests independently cover the accepted positive scalar, quoted numeric, object, zero/negative values, scalar encoding, and descriptor kind ([WorldIdTest.kt](/Users/apple/Desktop/개인프로젝트/opensamguk/common/src/test/kotlin/opensamguk/common/world/WorldIdTest.kt:28)).

## Contract and scope checks

- The remediation is confined to the identity type and its focused tests. It does not introduce a default, alias, profile/server/ng-games conversion, schema migration, daemon write path, or activation behavior.
- The contract remains aligned with the implementation: canonical `world_state.id`, scalar `worldId`, alias/absence/mismatch rejection, composite scoping, and exactly-one-row fail-closed backfill ([contract §§1–5](/Users/apple/Desktop/개인프로젝트/opensamguk/docs/superpowers/specs/2026-07-19-canonical-world-identity-contract.md:10)).
- The plan remains unchanged by the remediation and continues to make OPENSAM-148 block OPENSAM-43 and OPENSAM-126, preserve OPENSAM-43's broad scope, sequence build-only identity → S2 → S3 → S4, and retain OPENSAM-123/124 as activation/cutover gates ([plan waves](/Users/apple/Desktop/개인프로젝트/opensamguk/docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md:359)).
- No new Critical or Major defect was found. Restricting decode to `JsonDecoder` is consistent with the explicitly JSON-only wire contract and avoids silently accepting a non-JSON representation.

## Fresh Java 21 evidence

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests opensamguk.common.world.WorldIdTest --rerun-tasks` completed with `BUILD SUCCESSFUL in 22s`.
- Root separately ran `:common:test --rerun-tasks --no-configuration-cache` to eliminate a configuration-cache false positive. It reported `BUILD SUCCESSFUL`, 37 suites / 217 tests, and no nonzero failure or error count. I independently aggregated the resulting common XML files and observed the same `37` suites, `217` tests, `0` failures, and `0` errors.
- Root also reported a post-fix `:logic:test` result of 270 suites / 3,110 tests with no nonzero failure or error count.
- [Java 21 WorldId XML](/Users/apple/Desktop/개인프로젝트/opensamguk/common/build/test-results/test/TEST-opensamguk.common.world.WorldIdTest.xml:2) is fresh from the no-cache common run (`2026-07-19T07:55:52`) and records `tests="9" skipped="0" failures="0" errors="0"`.

## Tooling note

The earlier synthetic-class JShell name-resolution attempt remains documented as an exploratory failure only. This re-review used the public serializer API and completed the direct Java 21 reproduction successfully.
