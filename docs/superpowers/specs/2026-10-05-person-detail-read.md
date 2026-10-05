# Person detail read

`GET /api/people/{targetGeneralId}?generalId=` is the K4-13 read behind the person detail screen (P-R03).
The verified JWT principal must own the selected acting body before the target is read; a borrowed body is
403 for USER and ADMIN alike. The read uses a repeatable-read transaction and the process world. An absent
target returns 404, a non-positive target 400, and a target, retainer card or nation from another world 409.
A world that is not the retainer campaign returns `UNSUPPORTED_WORLD_FORMAT`/`UNAVAILABLE` with no person values.

## Relation

`relation` is decided by the server, in this order: SELF (the acting body), RETINUE (the target's single
retainer card belongs to the acting body), SAME_NATION (both in the same positive nation), OTHER.
Nation 0 is never shared. Duplicate cards for one general cannot decide the relation, so it is UNKNOWN and
is never guessed as RETINUE. OTHER is not evidence of an enemy relationship and no enemy flag is exposed.

## Fields

Public for every relation, under the people directory's existing rules:
`name`, `portrait{picture,imageServer}`, `affiliation{nationId,name,color}|null` (null = no positive nation),
`stats` (a verified person policy and five non-negative abilities, else null) and `aptitudes` (computed from
those stats).

Private, opened only for SELF and the direct RETINUE:
- `role` LORD/RETAINER/FREE and `lordGeneralId` (the card's master).
- `location{cityId,name}` resolved in the selected world; the display name comes from the active artifact's
  place table, else the stored city name. A missing, ambiguous or blank city is null.
- `injured`: whether the stored 0..100 injury rate is above zero. It is not a recovery countdown.
- `bonds` in the retinue `BondDto` shape (kind, label, nativeCountyName, nativeCountyHanja, sameAsLord) and
  `retinue{retainerId,loyalty,cost,roleLabel,taskLabel,departureOrder}` (cost is renown upkeep, not salary).
  Both come from the acting body's own retinue read, for RETINUE only. SELF has no retinue-shaped bond or card
  producer, so both stay null with NO_SOURCE.

SAME_NATION, OTHER and UNKNOWN receive null for every private field with NOT_AUTHORIZED.
`placement` and `offices` are always explicit null with CONTRACT_PENDING until C1 placement and C5 office
producers and permissions are agreed. Salary, treasures and lifespan are not part of this read.

## Nulls and status

`unavailableReasons` maps JSON pointers of null fields to NOT_AUTHORIZED, NO_SOURCE, NO_SNAPSHOT,
INVALID_SOURCE or CONTRACT_PENDING. Null values are serialized explicitly. READY means every field the
relation is entitled to is available or structurally absent; PARTIAL means at least one damaged source
(INVALID_SOURCE). No number, label, relation or permission is invented to fill a gap. The response is not
cacheable.

The contract is K4's reply and C10's accepted fields (`2026-10-05-k4-c10-dto-reply.md`,
`2026-10-05-c10-k4-dto-accepted-fields.md`) and the central contract board row 335. This read does not
claim delivery of offices, placement or same-nation private fields.
