# Cross-agent critique — OPENSAM-91 profile icon final re-review

- Date: 2026-07-17 KST
- Reviewer: Codex subagent `/root/opensam_91_security_review`, independent security/adversarial lane
- Independence: this reviewer did not implement or modify the OPENSAM-91 code or tests.

Scope: OPENSAM-91 final re-review across app/, infra/, and web/ changes, with related configuration, tests, and release evidence

## Review basis

The reviewer independently inspected the current storage, Spring wiring, reconciliation, logging, and focused test sources. The review targeted the three findings left by the prior adversarial pass; implementer conclusions were not reused as proof.

## Resolved findings

1. **BLOCKER — final-file overwrite through secure move: resolved.**
   `LocalProfileIconStorage.writeManagedFile` now creates the final name directly through the anchored root stream with `CREATE_NEW`, writes and size-checks the bytes, forces the file, and forces the anchored root directory. Collision retry preserves the pre-existing file, while failed partial writes remove only the newly created file.
2. **MAJOR — optional insecure fallback and path-based journal operations: resolved.**
   `rootAnchor` is non-null and construction fails before `.ops` mutation when the provider does not supply `SecureDirectoryStream`. Runtime `.ops` enumeration, attributes, reads, deletes, and directory force all use a nested anchored secure stream whose file key is revalidated. Spring tests explicitly inject a `@Primary` secure fixture; production keeps the fail-closed provider check.
3. **MAJOR — sensitive exception logging: resolved.**
   All five failure sites log only `operation_id` and `e.javaClass.simpleName`; none passes a `Throwable`, message, path, username, or filename. Log-capture tests assert two arguments, no throwable proxy/stack trace, and absence of supplied sensitive values.

## Evidence

- Storage source: `app/gateway-api/src/main/kotlin/opensamguk/gateway/profile/LocalProfileIconStorage.kt`.
- Production/test wiring: `app/gateway-api/src/main/kotlin/opensamguk/gateway/profile/ProfileIconConfiguration.kt` and `app/gateway-api/src/test/kotlin/opensamguk/gateway/profile/ProfileIconSecureStorageTestConfiguration.kt`.
- Adversarial cases: forced overwrite collision, unsupported-provider pre-journal failure, concurrent `.ops` swap with an external sentinel, partial-write cleanup, and sanitized log capture in `app/gateway-api/src/test/kotlin/opensamguk/gateway/profile/`.
- Relayed verifier evidence: the three focused classes passed 25/25, `ProfileIconRecoveryIT` passed 2/2, and test compilation was green. This lane also observed the RecoveryIT XML with `tests="2"`, `failures="0"`, and `errors="0"`; it did not rerun Gradle or Docker.
- No residual fix-required finding remains in the reviewed OPENSAM-91 code/security/test scope.

Docker smoke and `tools/parity/gate.sh backend` are not claimed by this review; they remain for the verifier to report.

Verdict: cleared

## LICENSE BLOCKED — A4/A5 no release

This code-review clearance is not release authorization. `docs/loops/opensam-91-profile-icon/LEDGER.md` keeps A4/A5 blocked: external portrait redistribution status remains unknown, and human legal/release owners must settle LGPL/BSD notices, complete transitive SBOM obligations, final immutable image/base digests, and native-library CVE ownership. Do not commit/release or activate the 91b/NPC roster on this verdict alone.
