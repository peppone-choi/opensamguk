# Claimable JSONB Review - 2026-06-10

## Scope

- Production symptom: `GET /api/game/api/generals/claimable` returned 500.
- Runtime error: `column "pick_result" is of type jsonb but expression is of type character varying`.
- Changed files:
  - `app/game-api/src/main/kotlin/opensamguk/gameapi/owner/SelectNpcTokenRepository.kt`
  - `app/game-api/src/test/kotlin/opensamguk/gameapi/owner/SelectNpcTokenRepositoryIT.kt`

## Source Of Truth

- Database schema: `infra/src/main/resources/db/migration/V12__select_npc_token.sql` defines `pick_result JSONB`.
- Existing converter note: `MetaJsonConverter` states that converted strings need an explicit cast for Postgres JSONB writes.
- The fix keeps `MetaJsonConverter` and adds a Hibernate write cast only for the writeable `select_npc_token.pick_result` field.

## Review Result

Verdict: cleared

- Correctness reviewer found no blocking defects.
- `@Convert(MetaJsonConverter::class)` still owns Map encoding and decoding.
- `@ColumnTransformer(write = "?::jsonb")` only changes the SQL write expression so Postgres receives the existing encoded value with an explicit JSONB cast.
- Residual risk: the new test covers insert and readback, not mutation of an existing `pickResult`; the production failure path is insert.

## Verification

- Red: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.owner.SelectNpcTokenRepositoryIT --rerun-tasks`
  - Failed with `column "pick_result" is of type jsonb but expression is of type character varying`.
- Green: same command after the cast fix.
  - `BUILD SUCCESSFUL`.
- Focused regression:
  - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.controller.PossessionControllerTest --tests opensamguk.gameapi.owner.SelectNpcTokenRepositoryIT`
  - `BUILD SUCCESSFUL`.
- Full module regression:
  - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --max-workers=1`
  - `BUILD SUCCESSFUL`.

## Documentation Decision

No README, AGENTS, or CLAUDE update is needed. This is a narrow runtime bug fix to make an existing schema/entity contract behave as already documented by the migration and converter comment.
