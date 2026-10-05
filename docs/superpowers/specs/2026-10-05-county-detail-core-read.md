# County detail core read

`GET /api/counties/{cityId}?generalId=` is the first flat slice of K4-04.
The verified JWT principal and selected body must agree before a target or artifact read.
The read uses a repeatable-read transaction, the process world, selected artifact pin and
complete current-stamp vision. An absent or non-administrative county returns 404.

The core exposes `status, cityId, name, nameCh, level, levelLabel, commandery, owner,
visibility, intelAgeTurns, population, defense, specialties, garrison, income`.
`level` is the stored rank code, not the B-plan castle footprint width.
Population and defense keep the existing flat contract names and scalar storage units.
Garrison uses persisted `cityMilitary` headcount/training/morale; missing metadata is
unknown and never uses the fortification score or the codec's initial values as observed troops.
Income is current-state gross production per game month, not an already credited receipt.
The canonical county income rules and integer rounding are reused.

FULL permits the current population, defense and persisted garrison. INTEL/FOG omit
current values through explicit nulls. Only the viewer's same positive nation with FULL
receives income and actual specialty allocation; other viewers retain public design
specialty quantities. Known absence of a warehouse or loss of supply produces zero income;
damaged source state produces null. READY denotes an available core; PARTIAL denotes
missing core source values; UNAVAILABLE/UNSUPPORTED_WORLD_FORMAT carries no county values.
The response is not cacheable. ADMIN role does not bypass ownership of the selected body.

The proposed seven-indicator wrapper, grade object, trend, peopleHere, front, seasonal
events and other detail sections are deferred pending their wire/source contracts.
This core does not claim complete K4-04 delivery. Existing singular county/list routes
and their consumers are preserved; no frontend, operating database or deployment changes.
