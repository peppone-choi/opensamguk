# Legacy Command Catalog

This file lists the command keys available under `legacy/hwe/sammo/Command/`.
Command keys match class names and file names; they are the values stored in
`general_turn.action` and `nation_turn.action`.

## Command Resolution

- General: `buildGeneralCommandClass($type)` in `legacy/hwe/func_converter.php`
- Nation: `buildNationCommandClass($type)` in `legacy/hwe/func_converter.php`
- `null` or empty `type` defaults to `휴식`
- Post-req turn cooldowns are stored in:
  - `next_execute` KV (per general)
  - `nation_env` KV (per nation)

## Execution Semantics (BaseCommand)

- Conditions are checked via constraint sets (permission/min/full).
- `getPreReqTurn()` is enforced by `LastTurn` term stacking.
- `getPostReqTurn()` is enforced by `next_execute` (cooldown).
- If `run()` returns `false`, `getAlternativeCommand()` may be used.

## Command Prefixes and Scenarios

Command keys are scenario-driven. The prefixes (e.g. `che_`, `cr_`, `event_`)
identify the rule set or scenario extension, and the active scenario chooses
which commands are available via `GameConst` overrides. See
`docs/architecture/legacy-scenarios.md` for details.

## General Commands (`legacy/hwe/sammo/Command/General/`)

| Key                  | Display name (actionName) |
| -------------------- | ------------------------- | ------ |
| `che_NPC능동`        | `NPC능동`                 | Ported |
| `che_강행`           | `강행`                    | Ported |
| `che_거병`           | `거병`                    | Ported |
| `che_건국`           | `건국`                    | Ported |
| `che_견문`           | `견문`                    | Ported |
| `che_군량매매`       | `군량매매`                | Ported |
| `che_귀환`           | `귀환`                    | Ported |
| `che_기술연구`       | `기술 연구`               | Ported |
| `che_내정특기초기화` | `내정 특기 초기화`        | Ported |
| `che_농지개간`       | `농지 개간`               | Ported |
| `che_단련`           | `단련`                    | Ported |
| `che_등용`           | `등용`                    | Ported |
| `che_등용수락`       | `등용 수락`               | Ported |
| `che_랜덤임관`       | `무작위 국가로 임관`      | Ported |
| `che_모반시도`       | `모반시도`                | Ported |
| `che_모병`           | `모병`                    | Ported |
| `che_무작위건국`     | `무작위 도시 건국`        | Ported |
| `che_물자조달`       | `물자조달`                | Ported |
| `che_방랑`           | `방랑`                    | Ported |
| `che_사기진작`       | `사기진작`                | Ported |
| `che_상업투자`       | `상업 투자`               | Ported |
| `che_선동`           | `선동`                    | Ported |
| `che_선양`           | `선양`                    | Ported |
| `che_성벽보수`       | `성벽 보수`               | Ported |
| `che_소집해제`       | `소집해제`                | Ported |
| `che_수비강화`       | `수비 강화`               | Ported |
| `che_숙련전환`       | `숙련전환`                | Ported |
| `che_요양`           | `요양`                    | Ported |
| `che_은퇴`           | `은퇴`                    | Ported |
| `che_이동`           | `이동`                    | Ported |
| `che_인재탐색`       | `인재탐색`                | Ported |
| `che_임관`           | `임관`                    | Ported |
| `che_장비매매`       | `장비매매`                | Ported |
| `che_장수대상임관`   | `장수를 따라 임관`        | Ported |
| `che_전투태세`       | `전투태세`                | Ported |
| `che_전투특기초기화` | `전투 특기 초기화`        | Ported |
| `che_접경귀환`       | `접경귀환`                | Ported |
| `che_정착장려`       | `정착 장려`               | Ported |
| `che_주민선정`       | `주민 선정`               | Ported |
| `che_증여`           | `증여`                    | Ported |
| `che_집합`           | `집합`                    | Ported |
| `che_징병`           | `징병`                    | Ported |
| `che_첩보`           | `첩보`                    | Ported |
| `che_출병`           | `출병`                    | Ported |
| `che_치안강화`       | `치안 강화`               | Ported |
| `che_탈취`           | `탈취`                    | Ported |
| `che_파괴`           | `파괴`                    | Ported |
| `che_하야`           | `하야`                    | Ported |
| `che_해산`           | `해산`                    | Ported |
| `che_헌납`           | `헌납`                    | Ported |
| `che_화계`           | `화계`                    | Ported |
| `che_훈련`           | `훈련`                    | Ported |
| `cr_건국`            | `건국`                    | Ported |
| `cr_맹훈련`          | `맹훈련`                  | Ported |
| `휴식`               | `휴식`                    | Ported |

## Nation Commands (`legacy/hwe/sammo/Command/Nation/`)

| Key                  | Display name (actionName) |
| -------------------- | ------------------------- |
| `che_감축`           | `감축`                    |
| `che_국기변경`       | `국기변경`                |
| `che_국호변경`       | `국호변경`                |
| `che_급습`           | `급습`                    |
| `che_몰수`           | `몰수`                    |
| `che_무작위수도이전` | `무작위 수도 이전`        |
| `che_물자원조`       | `원조`                    |
| `che_발령`           | `발령`                    |
| `che_백성동원`       | `백성동원`                |
| `che_부대탈퇴지시`   | `부대 탈퇴 지시`          |
| `che_불가침수락`     | `불가침 수락`             |
| `che_불가침제의`     | `불가침 제의`             |
| `che_불가침파기수락` | `불가침 파기 수락`        |
| `che_불가침파기제의` | `불가침 파기 제의`        |
| `che_선전포고`       | `선전포고`                |
| `che_수몰`           | `수몰`                    |
| `che_의병모집`       | `의병모집`                |
| `che_이호경식`       | `이호경식`                |
| `che_종전수락`       | `종전 수락`               |
| `che_종전제의`       | `종전 제의`               |
| `che_증축`           | `증축`                    |
| `che_천도`           | `천도`                    |
| `che_초토화`         | `초토화`                  |
| `che_포상`           | `포상`                    |
| `che_피장파장`       | `피장파장`                |
| `che_필사즉생`       | `필사즉생`                |
| `che_허보`           | `허보`                    |
| `cr_인구이동`        | `인구이동`                |
| `event_극병연구`     | `극병 연구`               |
| `event_대검병연구`   | `대검병 연구`             |
| `event_무희연구`     | `무희 연구`               |
| `event_산저병연구`   | `산저병 연구`             |
| `event_상병연구`     | `상병 연구`               |
| `event_원융노병연구` | `원융노병 연구`           |
| `event_음귀병연구`   | `음귀병 연구`             |
| `event_화륜차연구`   | `화륜차 연구`             |
| `event_화시병연구`   | `화시병 연구`             |
| `휴식`               | `휴식`                    |

## Open Questions / Follow-ups

- Prefix semantics (`che_`, `cr_`, `event_`) are not documented here and may be
  scenario- or mode-specific.
