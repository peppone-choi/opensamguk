# OPENSAM-91 profile icon GOLDENSET

- Status: `frozen`
- Approval: `docs/superpowers/plans/2026-07-17-opensam-90-91-102-109-113-execution-contract.md:1-43` (`A0 + A1 bounded lane`)
- Scope: OPENSAM-91a authenticated profile-icon API/storage plus the catalog-only 91b shared-icon contract
- Out of scope: Next.js upload UI (OPENSAM-92), file serving/nginx route (OPENSAM-93), game-engine consumption (OPENSAM-94), and NPC gameplay-roster activation
- Grader: the dedicated gateway-api tests and compose/codec gates below; no expectation may be weakened during the loop

## Frozen behavior

1. Upload and delete are authenticated, principal-only operations. Unauthenticated requests return JSON `401` without filesystem paths, stack traces, tokens, or PII.
2. AVIF, WebP, JPEG, PNG, and GIF are selected from bytes and fully decoded. Client MIME, filename, path, and extension are not trusted.
3. Accepted input is non-empty, at most `51,200` bytes, square, and `64..128` pixels on both axes. Corrupt, unsupported, multi-frame, oversized, non-square, and out-of-range inputs fail closed.
4. Uploaded files receive a server-generated CSPRNG lowercase eight-hex stem and the decoder-derived canonical extension. Managed-file operations stay beneath the configured root and never follow symlinks.
5. File and database changes compensate partial failure. Delete physically unlinks only the current managed upload; shared/default/arbitrary paths are never unlinked.
6. Upload and delete share one Asia/Seoul calendar-day limit backed by the dedicated `profile_icon_changed_at` column. Generic `updatedAt` is not the rate-limit truth.
7. Two concurrent PostgreSQL changes produce exactly one success and one persisted daily-limit state. A Docker-skipped Testcontainers case is not A2 proof.
8. JSON selection resolves only a closed shared-icon catalog ID. Existing external entries pin repository, revision, path, delivery URL, media metadata, and digest; bundled entries additionally require cleared redistribution status plus classpath byte/decode verification. Uploaded-looking names, arbitrary paths, duplicate/unsafe manifest entries, and unproven external assets are rejected.
9. Local and production compose give `gateway-api` a writable named volume at `/var/lib/opensamguk/profile-icons` and set `PROFILE_ICON_STORAGE_ROOT` to that exact path. nginx receives no profile-icon mount or serving route in this ticket.
10. Existing v1 scenario, RNG, AI, Korean logs, and daemon flush behavior are unchanged. 91b remains catalog-only until the separately approved roster/seed/parity wave.

## Dedicated score

The deterministic product score is the number of green dimensions above out of ten. The evidence map is:

- HTTP/auth/error boundary: `ProfileIconHttpSecurityTest`
- byte decode and size/dimension/format boundaries: `ProfileIconDecoderTest`
- storage naming, root, collision, symlink, backup/delete/restore: `LocalProfileIconStorageTest`
- transaction compensation, daily limit, delete boundary, catalog selection: `ProfileIconServiceTest`
- PostgreSQL race: `ProfileIconPostgresConcurrencyIT`
- shared catalog: `SharedProfileIconCatalogTest`
- existing account API regression: `AccountControllerTest`
- persistence topology and OPENSAM-93 fence: both rendered compose configs
- broad regression: full gateway-api tests followed by the backend parity gate

## Compose score

The compose subscore is seven fixed assertions:

1. local `gateway-api.environment.PROFILE_ICON_STORAGE_ROOT` equals `/var/lib/opensamguk/profile-icons`;
2. local `gateway-api` has a writable named-volume mount at that target;
3. local top-level `profile-icons` volume exists;
4. production has the same environment value;
5. production has the same writable named-volume mount;
6. production top-level `profile-icons` volume exists;
7. neither rendered nginx service mounts that target.

## Codec and release evidence contract

- Record exact Maven coordinates, resolved direct-JAR SHA-256 values, declared licenses, embedded native licenses, fixture SHA-256 values, and the tested Linux runtime-image digest.
- A successful decoder probe proves compatibility only for the pinned synthetic fixtures and runtime image. It does not prove decoder safety for hostile inputs or satisfy license-distribution obligations.
- A4/A5 remain blocked until the legal-review release gate in `LEDGER.md` is cleared or the dependency choice changes through a separately approved A1 decision.

## Current gate disposition

- Independent re-review: `SPEC PASS`, `SECURITY PASS`, `TESTS PASS`, with no residual code/security findings.
- Dimension 10 remains unscored because the backend parity gate was not run; full-stack smoke was also not run.
- `LICENSE BLOCKED`: codec distribution obligations and the external icons' unknown rights continue to block A4/A5. This disposition does not change any frozen behavior above.
