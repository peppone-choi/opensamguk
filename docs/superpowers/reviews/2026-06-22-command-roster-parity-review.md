# Command and Roster Parity Review — 2026-06-22

Verdict: cleared

## Scope

- `web/game` command argument UI for city-target, founding, and recruitment commands.
- `app/game-api` command metadata and game const payloads required by those legacy forms.
- Main control and legacy URL routing for the authenticated nation-general page.
- Supporting main-page display fixes already covered by page-parity row 72.

## Legacy Evidence

- Nation general menu: `legacy/devsam-core/hwe/ts/components/MainControlBar.vue:28` and `MainControlDropdown.vue:27` route `v_nationGeneral.php`, not the global general list.
- Founding command: `legacy/devsam-core/hwe/ts/processing/General/che_건국.vue:1-82` submits `nationName`, `nationType`, and `colorType`.
- Recruitment command: `legacy/devsam-core/hwe/ts/processing/General/che_징병.vue:1-360` submits `crewType` plus `amount * 100` and renders the selectable troop catalog.
- City-target commands: `legacy/devsam-core/hwe/ts/processing/ProcessCity.vue:1-200` uses the map and `CitiesBasedOnDistance.vue:1-37` for nearby city shortcuts.

## Critique

- The `app/game-api` changes are metadata-only: `AvailableCommandsController` and `ChiefCenterController` now classify existing command argument sets as `founding` or `recruit`, while `GetConstController` exposes legacy nation colors and nation type labels/info already defined in logic. No daemon write path or command execution semantics changed.
- The UI now sends the legacy composite payloads instead of reducing every argument to a scalar `destCityID`/`destGeneralID`/`destNationID`/`amount` field. This directly closes the user-reported reservation failures for commands whose PHP forms carry multiple arguments.
- `세력 장수` is intentionally routed to `/game/my-generals`. Keeping it on `/game/generals` would show the global list and hide the legacy `b_myGenInfo` authenticated page semantics.
- City selection keeps the searchable list as a fallback, but adds the legacy map click and nearby-city rows. This is an additive interaction surface over the same `destCityID` payload, not a new game rule.
- No PHP golden capture is needed for these UI metadata changes because the compared surface is `hwe/ts` form payload and route structure, not deterministic engine math or log output.

## Verification

- `git diff --check`
- `pnpm typecheck` in `web/game`
- `pnpm test -- command-arg-types.test.ts MapViewer.interaction.test.tsx my-page-route.test.tsx serverGameUrl.test.ts MessagePlate.test.tsx PartialReservedCommand.test.tsx GeneralBasicCard.test.tsx mailbox.test.ts` in `web/game`
- `pnpm build` in `web/game`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --rerun-tasks`

## Residual Risk

- Live `s1` browser QA is still pending until the merged image is deployed and the server is promoted or recreated.
- Recruitment images intentionally use the legacy CDN `<img>` path and therefore keep the existing Next no-img warning class.
