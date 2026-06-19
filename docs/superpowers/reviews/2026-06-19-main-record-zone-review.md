# 2026-06-19 main RecordZone review

Verdict: cleared after mixed-version guard fix

## Source of truth

- `legacy/devsam-core/hwe/ts/PageFront.vue:34-135`: main board order is map, reserved command, mini plate, city/nation/general info, command toolbar, then `RecordZone`.
- `legacy/devsam-core/hwe/ts/PageFront.vue:113-135`: `RecordZone` has three feeds: `장수 동향`, `개인 기록`, `중원 정세`.
- `legacy/devsam-core/hwe/sammo/API/General/GetFrontInfo.php:65-155`: `recentRecord` returns `history`, `global`, `general` tuple rows plus `flushHistory`, `flushGlobal`, `flushGeneral`.
- `legacy/devsam-core/hwe/sammo/API/General/GetFrontInfo.php:117-155`: overflow rows are popped back to 15 rows, while the three flush flags remain unset.
- `legacy/devsam-core/hwe/ts/defs/API/Global.ts:93-107`: frontend API type expects tuple rows `[number, string][]` inside `recentRecord`.

## Implementation review

- `FrontInfoController` now fills `recentRecord` from existing read-only `LogFeedReadRepository` methods instead of the previous empty placeholder.
- `web/game/app/game/page.tsx` renders a PageFront-style `MainRecordZone` instead of the unrelated 4-panel `MyInfoLogPanel`.
- `GameChrome` assigns direct `ib-city`, `ib-nation`, and `ib-general` slots so the existing 500/200/300 desktop board grid actually applies.
- `MainRecordZone` accepts the new object shape and tolerates the old `[]` shape during a mixed-version deploy by normalizing it to empty feeds.

## Cross-Agent Critique

Reviewer: `ce-api-contract-reviewer` (`019edffc-4d65-7400-b95b-ac6f49440816`)

Finding: HIGH, fixed. The reviewer flagged that changing `front-info.recentRecord` from `[]` to an object could crash `/game` if `web-game` is deployed before `game-api`.

Resolution: `MainRecordZone.normalizeRecentRecord` now treats old `[]`, nullish, malformed, or partial values as empty feeds. `MainRecordZone.test.tsx` covers the mixed-version `[]` case.

Reviewer: `ce-security-reviewer` (`019ee01b-f183-79a3-a64e-a29d5b3c12b9`)

Finding: HIGH, fixed. The reviewer flagged stored XSS because `formatLog` passed non-legacy HTML through unchanged before `dangerouslySetInnerHTML`.

Resolution: `formatLog` now escapes all text outside the legacy color tokens and a small allowlist of safe legacy tags (`b`, `ev_failed`, and hex-color flag spans). `utilGame.test.ts` covers `<img>`/`script` escaping and preserved legacy tags.

Finding: MEDIUM, fixed. The reviewer flagged unauthenticated `?generalId=` fallback exposing `recentRecord.general`.

Resolution: `FrontInfoController` now includes the personal recent-record feed only when the general was resolved from the authenticated principal. The transition `?generalId=` fallback still renders the public main shell but returns an empty personal feed. `FrontInfoControllerTest` covers this.

Residual risk: the UI still refetches the latest rows rather than maintaining the full legacy cursor deque in React state. This is visually acceptable for the main page because the backend returns the newest 15 rows on each refresh when no cursor is supplied, but a future live-toast/cursor parity pass should preserve `lastGeneralRecordID` and `lastWorldHistoryID` client-side.

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.controller.FrontInfoControllerTest --rerun-tasks`
- `pnpm --dir web/game test -- utilGame MainRecordZone GameChrome.main-map --run`
- `pnpm --dir web/game typecheck`
- `pnpm --dir web/game build`
