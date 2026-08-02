# Canonical Alphanumeric Game-Server ID Review

- Date: 2026-08-02
Scope: `.github/workflows/`, `app/`, `infra/`, and `web/`.
- Review focus: PR #354 canonical public server-ID normalization, internal
  Compose/container derivation, Nginx compatibility routing, and deployment
  workflow reserved-ID parity.
- PR: `https://github.com/peppone-choi/opensamguk/pull/354`
- First Codex review source SHA:
  `8d1a64fee0651b2977f13af27eb6b91b43577342`
- Remediated code SHA:
  `1683100447be91abc6eb2629969c9ee3c16bac5e`
- Independent re-review: **CLEARED** for exact remediated code SHA
  `1683100447be91abc6eb2629969c9ee3c16bac5e`.

Verdict: cleared

## Canonical contract

- Accept raw public input matching `[A-Za-z0-9]+` and canonicalize it to
  lowercase `[a-z0-9]+`.
- Derive the internal Compose/container identifier as `s` + canonical public
  ID: `pep` → `spep`, `s1` → `ss1`, and `A1` → `sa1`.
- `all`, `main`, and current top-level game route names are reserved to avoid
  control and URL collisions. `current` is not a server-ID sentinel and remains
  a valid public ID.

## First Codex review findings and remediation

1. **Compatibility Nginx public/internal mapping.** The first review found
   that a canonical public path needed to resolve to an internal
   `s<public>` container without requiring an `s` prefix in the public URL.
   Fixed SHA `1683100447be91abc6eb2629969c9ee3c16bac5e` accepts the lowercase
   public ID in the route, selects `s$server_id-web-game`, and preserves the
   canonical public value in the rewritten game request.
2. **Workflow reserved-ID parity.** The first review found that deployment
   workflows had a one-off `all` check that could diverge from the control and
   route collision guard. The fixed SHA gives deploy, promote, and reset a
   matching `RESERVED_PUBLIC_SERVER_IDS` guard. `all`, `main`, and current
   top-level game route names are rejected; `current` remains a valid public
   ID.

The fixed-SHA diff is the remediation evidence for both findings. It also
retains the earlier removal of the hard-coded `s1` verification assumption in
favor of registered server-container checks.

## Recorded evidence

- Gateway tests: 64 passing.
- Game tests: 220 passing.
- FrontInfo XML: `27/0/0`.
- Admin XML: `26/0/0`.
- Workflow, Nginx, and Compose contract inspection: remediation observed at
  `1683100447be91abc6eb2629969c9ee3c16bac5e`.

## Review and release boundary

The independent re-review clears only exact code SHA
`1683100447be91abc6eb2629969c9ee3c16bac5e`, including the two remediations and
recorded evidence above. After the next documentation commit moves current HEAD,
the required three fresh `@codex` PR review rounds restart against that exact
new HEAD. Docker repository review/fix, merge, deployment, and live-production
verification also remain pending. No final PR-review clearance, merge,
deployment, or live production result is claimed by this artifact.
