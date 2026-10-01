// 서신 받는 사람 — 장수 목록(`GET /api/generals`)을 K3 사람 고르기(PersonOption)로. NPC 포함(D4).
//
// 엔진은 아직 순수 NPC(npc ≥ 2) 수신을 거절한다(MessageHandler — 계약판 U-03 서버 대기). 그래서 NPC 행은 보이되
// 「준비 중」 사유로 막는다(보내면 엔진이 거절하므로). 서버가 풀면 NPC_MAIL_OPEN만 바꾼다.
// 소속 · 자리는 서버가 준 것만(재야 = nationId 0, 자리 = cityName). 자기 자신은 뺀다.
import type { PersonOption } from '@opensamguk/ui';
import type { PublicGeneral } from '../../types/game';

export const NPC_MAIL_OPEN = false;
export const NPC_MAIL_WAITING = 'NPC에게 보내는 서신은 서버 준비 중입니다';
/** officerLevel 12 = 군주. */
const RULER_LEVEL = 12;

export function toRecipientOptions(list: readonly PublicGeneral[], me: { generalId: number; nationId: number }): PersonOption[] {
    return list
        .filter((g) => g.generalId !== me.generalId)
        .map((g) => {
            const groups: ('nation' | 'rulers')[] = [];
            if (me.nationId > 0 && g.nationId === me.nationId) groups.push('nation');
            if (g.officerLevel === RULER_LEVEL && g.nationId !== me.nationId) groups.push('rulers');
            const npc = g.npc >= 2;
            return {
                generalId: g.generalId,
                name: g.name,
                isHuman: g.npc < 2,
                picture: g.picture,
                imageServer: g.imageServer,
                nation: g.nationId > 0 ? { id: g.nationId, name: g.nationName, color: g.nationColor } : null,
                ...(g.cityName ? { location: g.cityName } : {}),
                groups,
                ...(npc && !NPC_MAIL_OPEN ? { blockedReason: NPC_MAIL_WAITING } : {}),
            } satisfies PersonOption;
        })
        .sort((a, b) => a.name.localeCompare(b.name, 'ko'));
}
