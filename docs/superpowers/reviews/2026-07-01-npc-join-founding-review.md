# 2026-07-01 NPC join and founding review

## Scope

- Engine reserved-turn execution for `che_임관`, `che_장수대상임관`, and `che_랜덤임관`.
- The NPC loop where wandering factions repeatedly raise/disband because `gennum` never reaches the `che_건국` follower requirement.
- `common/src` game constants touched in this branch for the user-approved 36-turn/year and 1-year opening-limit model.

## PHP Evidence

- `legacy/devsam-core/hwe/sammo/Command/General/che_랜덤임관.php:136-229`: random join queries live candidate nations; no candidate logs `임관 가능한 국가가 없습니다.`.
- `legacy/devsam-core/hwe/sammo/Command/General/che_랜덤임관.php:263-277`: successful random join moves the general and updates `nation.gennum = gennum + 1`.
- `legacy/devsam-core/hwe/sammo/Command/General/che_임관.php:86-99`: specified join preloads destination nation with `gennum` and `scout`, then evaluates `AllowJoinDestNation($relYear)`.
- `legacy/devsam-core/hwe/sammo/Command/General/che_임관.php:157-172`: specified join moves the general, resets `troop`, and increments destination `gennum`.
- `legacy/devsam-core/hwe/sammo/Command/General/che_장수대상임관.php:85-99`: target-general join preloads the target general's nation before `AllowJoinDestNation($relYear)`.
- `legacy/devsam-core/hwe/sammo/Command/General/che_장수대상임관.php:152-167`: target-general join follows the target general's city, keeps troop, and increments destination `gennum`.
- `legacy/devsam-core/hwe/sammo/Command/General/che_건국.php:100-104`: founding requires wandering nation `gennum >= 2`.

## Implementation Claim

- `ReservedTurnHandler` now augments join command args with `relYear`, `actorNpcType`, and the resolved destination nation id before full constraint evaluation.
- Join commands preload `draft.destNation`, `draft.destGeneral`, and the destination lord city from the live in-memory world.
- `che_랜덤임관` resolves with live candidate nations instead of the registry's empty-candidate default.
- Successful random join backfills the selected destination nation into `draft.destNation`, so the existing `ChangeRecorder.diffNation` path persists `gennum + 1`.
- `applyGeneralPatch` now carries `post.troop` back to the engine row, so `che_임관`'s troop reset reaches world state and flush payload.

## Critique

- Strongest parity risk: random join candidate weighting depends on `rank_data` and nation affinity. The current engine snapshot carries rank values through general meta when present and otherwise matches PHP LEFT JOIN zero behavior; nation affinity is read from nation meta when present. A future loader pass should explicitly rehydrate any missing persisted affinity/rank fields if long-sim evidence shows drift.
- Strongest data-integrity risk: destination `gennum` is a secondary nation mutation, not the actor's `draft.nation`. The new drain uses the same `ChangeRecorder.diffNation` plus `applyNationDirtyFree` pattern as existing founding/nation updates, avoiding a second dirty source.
- Strongest behavioral risk: `che_임관` and `che_장수대상임관` differ on troop reset and destination city. Tests lock both differences.

`code_review_graph.detect_changes_tool` reported risk score `0.65` because the diff touches the daemon `handle` flow. Affected flows were `handle`, `updateTurnTime`, `applyKillturnDecrement`, and `processBlocked`; the targeted gate below covers the touched `handle` path plus adjacent founding and drain lifecycle regressions.

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests '*ReservedTurnHandlerTest*'`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests '*ReservedTurnHandlerTest*' --tests '*FoundingHandlerSeamTest*' --tests '*DrainTailAdvanceTest*'`

Both commands passed through `ctx_execute`; the second run ended with `BUILD SUCCESSFUL`.

## Result

Verdict: cleared
