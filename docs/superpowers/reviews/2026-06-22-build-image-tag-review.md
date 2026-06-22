# Build Image Tag Review

**Date:** 2026-06-22
**Scope:** backend Docker image build-info tag propagation
Verdict: cleared

## Finding

### `[P1] Admin version panel reports backend image tags as dev` — CLEARED

Live admin status showed the deployer/env pin at the promoted commit SHA while the backend actuator build-info
reported `image.tag=dev`. The cause is that `app/*/build.gradle.kts` reads `System.getenv("IMAGE_TAG")` during
`bootJar`, but the Docker build stages did not receive that env. The pushed image tag was correct; only build-info
inside the jar was stale.

## Change

- `docker/gateway-api.Dockerfile`, `docker/game-api.Dockerfile`, and `docker/game-engine.Dockerfile` now accept
  `ARG IMAGE_TAG=dev` and export it to the Gradle build stage.
- `.github/workflows/deploy.yml` passes `${{ env.IMAGE_TAG }}` as `--build-arg IMAGE_TAG` for all three backend images.

## Verification

- `git diff --check`
- Static trace from workflow `env.IMAGE_TAG=${{ github.sha }}` -> Docker build arg -> build-stage env -> Gradle
  build-info `additional["image.tag"]`.

## Residual Risk

Existing already-built images keep their old build-info until the next backend image build/deploy.
