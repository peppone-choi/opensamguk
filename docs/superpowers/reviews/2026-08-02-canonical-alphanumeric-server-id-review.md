# Canonical Alphanumeric Game-Server ID Review

- Date: 2026-08-02
Scope: `.github/workflows/`, `app/`, and `web/` canonical public server-ID
  normalization, internal Compose/container derivation, and deployment
  verification.
- Exact reviewed source SHA:
  `8d1a64fee0651b2977f13af27eb6b91b43577342`

Verdict: cleared

## Canonical contract

- Accept raw public input matching `[A-Za-z0-9]+` and canonicalize it to
  lowercase `[a-z0-9]+`.
- Derive the internal Compose/container identifier as `s` + canonical public
  ID: `pep` → `spep`, `s1` → `ss1`, and `A1` → `sa1`.
- `all`, `main`, and current top-level game route names are reserved to avoid
  control and URL collisions. `current` is not a server-ID sentinel and remains
  a valid public ID.

## Findings and remediations carried into this review

- Raw casing and public/internal prefix handling needed one canonical contract;
  the reviewed source normalizes at the public boundary and derives the
  internal `s`-prefixed form once.
- Control and URL collisions were remediated by rejecting `all`, `main`, and
  current top-level game route names before deployment; `current` remains
  valid and is covered by source/UI tests.
- Deployment verification previously assumed `s1`; the reviewed source checks
  registered game-server environment entries and their corresponding API,
  engine, web, and clock surfaces instead.

## Recorded evidence

- Gateway tests: 64 passing.
- Game tests: 220 passing.
- FrontInfo XML: `27/0/0`.
- Admin XML: `26/0/0`.
- Workflow and Compose contract checks: recorded as passing for this reviewed
  source.

## Release boundary

This verdict clears the reviewed source contract only. Remaining gates are
Docker repository review/fix, three `@codex` PR review rounds, merge,
deployment, and live-production verification. No deployment or live-production
result is claimed by this artifact.
