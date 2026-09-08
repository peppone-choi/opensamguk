# Portrait Manual Crop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. User approved the design in conversation; execute without additional design approval.

**Goal:** Independently edit and persist all three portrait compositions, preserving source and existing portraits.

**Architecture:** Existing account multipart endpoint accepts original file and crop JSON. Server stores a single atomic `.portrait` archive using existing secure storage and journal lifecycle. Shared resolver selects public rendered variants; authenticated source/crops GET enables editing again.

**Tech Stack:** Kotlin/Spring/ImageIO; Next.js/React/TypeScript; existing CSS tokens; Vitest/JUnit. No new browser dependencies.

**Spec:** `docs/superpowers/specs/2026-09-08-portrait-manual-crop-design.md`

## Global Constraints

User-approved 633×900/148×210/96×96 frames, original preserved, independent crops. Same-origin authenticated write. Existing transaction, reconciliation, daily limit and game profile sync retained. User authorized commit and merge on2026-09-08 after validation; deployment is separate. Code only in this worktree; retain dirty worktree.

### Task 1: Backend bundle lifecycle and crop rendering
Owner: portrait_backend. Files `app/gateway-api/src/main/kotlin/opensamguk/gateway/profile/*`, relevant controller/config/tests; nginx routing.
- [x] Add failing crop pixel, invalid bounds, old-upload compatibility and atomic recovery tests.
- [x] Implement `crops` multipart JSON `{hero,card,icon}` normalized `{x,y,width,height}`.
- [x] Single `8hex.portrait` archive stores validated source, crop JSON, three variants; old files remain valid.
- [x] Public `/profile-icons/{picture}/{hero|card|icon}.jpg`; authenticated GET `/auth/account/profile-icon/source` and `/crops`.
- [x] Verify auth boundaries, no source/archive publication, EXIF, failed-save cleanup, replacement/deletion/reconciliation.
- [x] Run affected gateway backend suite and review.

### Task 2: Manual editor, BFF, shared rendering
Owner: parent. Files `web/gateway/components/account/PortraitCropEditor.tsx`, `web/gateway/lib/portraitCrop.ts`, account page/client/BFF; `web/shared/src/portraitResolver.ts`; CSS and tests.
- [x] Add failing crop-geometry tests for aspect ratios, bounds, pan/zoom and independent variants.
- [x] Implement original preview; pointer drag/pinch/wheel, accessible zoom and axis sliders, reset, simultaneous3 previews.
- [x] Send original plus JSON, retain state on failure; load own source and crops for repeat editing.
- [x] BFF explicitly forwards file/crops only; source/crops only authenticated cookie.
- [x] Resolver maps new archive original→hero, portrait→card, icon→icon; old URLs unchanged.
- [x] Account integration, BFF, shared resolver tests; web typecheck/build and browser desktop/mobile verification.

### Task 3: Documentation and final independent review
- [x] Update player/admin docs with verified workflow and storage/backup limits.
- [x] Independent whole-change review, fix findings, rerun affected checks.
- [x] Record exact tests, screenshots, commit status and remaining risks in meta report. Preserve dirty worktree.

## Rulings

- Single archive chosen over independently published sidecars: existing one-file journal remains the atomic unit and deletion cannot leave orphan variants.
- No face detector: user-approved manual controls determine all three compositions.


## Final evidence

- Backend gateway full275/0fail/0skip before JPEG review; after marker-aware JPEG fix, affected70/0fail/0skip including PostgreSQL concurrency/recovery.
- Gateway web263pass (35files), shared56pass, game portrait51pass; gateway typecheckpass.
- Actual Chrome headless desktop1280 and mobile390: pan, wheel and pinch, independent previews, original54403bytes preserved,409retains state, nohorizontaloverflow/pageerror. Browser API mocked; backend endpoint tests are separate real MockMvc and PostgreSQL evidence.
- Independent reviewer CLEAN after JPEG thumbnail fix and scoped9MiB nginx fix in sibling authorized docker worktree `portrait-upload-limit`.
- JPEG original retention required replacing legacy first-EOI scan with marker-aware container validation; trailingjunk still rejected.
- Docker nginx config contractRED→GREEN and isolatednginx1.27syntaxpass. No live deployment claim.
- Gateway production build final result recorded in meta report.
