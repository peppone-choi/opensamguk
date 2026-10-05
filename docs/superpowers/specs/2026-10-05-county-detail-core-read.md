# County detail core read

`GET /api/counties/{cityId}?generalId=` is the first core slice of K4-04.
The verified JWT principal and selected body must agree before a target or artifact read.
The read uses a repeatable-read transaction, the process world, selected artifact pin and
complete current-stamp vision. An absent or non-administrative county returns 404.

The core exposes `status, cityId, name, nameCh, grade, commandery, owner,
visibility, intelAgeTurns, indicators, specialties, garrison, income`.
`grade:{code,label}|null` uses the stored rank code and its existing label, never
the B-plan castle footprint width. An unrecognized rank stays null.
`indicators` contains exactly population, agriculture, commerce, security, trust,
defence and wall. Each indicator is `{value,max,trend}|null`; all values/maxima
are stored integers except trust, which retains its stored Double and maximum
100.0. First trends are explicit null because no change producer is supplied.
Zero is a known value. Negative/non-finite source values stay null independently;
positive inherited over-cap values remain observable without clamping.
Garrison uses persisted `cityMilitary` headcount/training/morale; missing metadata is
unknown and never uses the fortification score or the codec's initial values as observed troops.
Income is current-state gross production per game month, not an already credited receipt.
The canonical county income rules and integer rounding are reused.

FULL permits the seven current indicators and persisted garrison. INTEL/FOG omit
current values through explicit nulls. Only the viewer's same positive nation with FULL
receives income and actual specialty allocation; other viewers retain public design
specialty quantities. Known absence of a warehouse or loss of supply produces zero income;
damaged source state produces null. READY denotes an available core; PARTIAL denotes
missing core source values; UNAVAILABLE/UNSUPPORTED_WORLD_FORMAT carries no county values.
The response is not cacheable. ADMIN role does not bypass ownership of the selected body.

Income is accompanied by `period=GAME_MONTH`, `basis=CURRENT_STATE_FORECAST`,
and the verified current world stamp. `peopleHere`, `front` and `seasonalEvent`
remain explicit null until their source and permission contracts are implemented;
null is not a verified empty roster. FULL does not grant private person locations,
and an OTHER affiliation is not evidence of an enemy relationship.
`unavailableReasons` maps JSON field pointers to NO_SOURCE, INVALID_SOURCE or
NOT_AUTHORIZED. Optional unimplemented sections do not alone make the core PARTIAL.
K4's actual acceptance is recorded in the central contract board; tests for this
shape must pass on its exact head before ready and independent review.
This core does not claim complete K4-04 delivery. Existing singular county/list routes
and their consumers are preserved; no frontend, operating database or deployment changes.

County detail files live in `opensamguk.gameapi.city`. The controller calls the
application query, which delegates to the reader. Existing frozen horizontal
packages and their baselines are not expanded; wire and read permissions are unchanged.

## 실제 sandbox gate 대조 후 패키지 정정

현 상세는 현행 campaign 조회다. `gameapi.city`는 SandboxGate가 실험 기능 전체 패키지로 분류하므로 현행 CountyDetail의 production bean을 그 아래 두지 않는다. 소유5파일을 `gameapi.county` 도메인으로 옮기며 Controller→Query→Reader, 기존 wire·권한·원천·시험23 및 sandbox gate 자체는 보존한다.
