// Command key → arg field-type inference (spec §5.2 table). game-api's available-commands endpoint may
// ship `reqArg` WITHOUT a structured `argType`; this map mirrors the legacy SelectCity/SelectGeneral/
// SelectNation/SelectAmount routing so the modal knows which sub-form to open. Keys are matched against
// the command `value` by SUFFIX (legacy keys are prefixed by map set, e.g. `che_이동`). When a reqArg
// command is unknown to this map we fall back to a free numeric `target` input (never crash).

import type { CommandArgType } from '@/types/game';

// suffix → argType. The first matching suffix wins (longest-first so 부대_탈퇴 beats 탈퇴).
const ARG_TYPE_BY_SUFFIX: [string, CommandArgType][] = [
    // city (destCityID): 강행/이동/출병/첩보/화계/탈취/파괴/선동/수몰/백성동원/천도/초토화
    ['강행', 'city'],
    ['이동', 'city'],
    ['출병', 'city'],
    ['첩보', 'city'],
    ['화계', 'city'],
    ['탈취', 'city'],
    ['파괴', 'city'],
    ['선동', 'city'],
    ['수몰', 'city'],
    ['백성동원', 'city'],
    ['천도', 'city'],
    ['초토화', 'city'],
    // nation (destNationID): 선전포고/급습/불가침파기제의/이호경식/종전제의/허보
    ['선전포고', 'nation'],
    ['급습', 'nation'],
    ['불가침파기제의', 'nation'],
    ['불가침파기', 'nation'],
    ['이호경식', 'nation'],
    ['종전제의', 'nation'],
    ['허보', 'nation'],
    // general (destGeneralID): 부대탈퇴지시 / 부대_탈퇴 등
    ['부대탈퇴지시', 'general'],
    ['부대_탈퇴', 'general'],
    ['탈퇴', 'general'],
    // amount: 세금/헌납/기부
    ['세금', 'amount'],
    ['헌납', 'amount'],
    ['기부', 'amount'],
];

const ARG_FIELD: Record<CommandArgType, string> = {
    city: 'destCityID',
    general: 'destGeneralID',
    nation: 'destNationID',
    amount: 'amount',
};

/** Best-effort argType for a reqArg command whose key lacks a server-provided `argType`. */
export function inferArgType(commandKey: string): CommandArgType | null {
    for (const [suffix, type] of ARG_TYPE_BY_SUFFIX) {
        if (commandKey.endsWith(suffix)) return type;
    }
    return null;
}

/** The JSON body field name for a given argType (matches the spec §5.4 payload table). */
export function argFieldName(type: CommandArgType): string {
    return ARG_FIELD[type];
}
