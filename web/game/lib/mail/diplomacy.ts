// 외교 서신 쓰기 — 누가 쓸 수 있고 어느 세력에 보낼 수 있는지. 연락처(`GET /api/contacts`) 한 읽기만 쓴다.
//
// 서버가 준 것만 옮긴다: 장수 flags 4 = 외교권자(ContactController.flagsOf — 군주 · 외교관, secretPermission 4).
// 엔진도 같은 기준(권한 4)으로 타국 서신함 쓰기를 받는다(MessageHandler 「외교 권한이 없습니다.」). 받는 세력은 세력
// 서신함(9000 + 세력)이 있는 다른 세력 — 우리 세력 서신함은 세력 서신이고, 재야(9000)는 세력이 아니라서 뺀다.
import { MAILBOX_NATIONAL_BASE } from '../mailbox';

/** 연락처 한 묶음(서버 ContactListResponse.nation[] 그대로) — general = [장수 id, 이름, flags]. */
export interface ContactGroup {
    readonly mailbox: number;
    readonly name: string;
    readonly color?: string;
    readonly general: readonly (readonly [number, string, number])[];
}
export interface ContactList { readonly nation: readonly ContactGroup[] }

export const FLAG_DIPLOMAT = 4;

export interface DiplomacyTarget {
    readonly nationId: number;
    readonly mailbox: number;
    readonly name: string;
}

export interface DiplomacyWrite {
    /** 내가 외교권자인가(flags 4). */
    readonly canWrite: boolean;
    readonly targets: readonly DiplomacyTarget[];
}

export function toDiplomacyWrite(list: ContactList, me: { generalId: number; nationId: number }): DiplomacyWrite {
    const canWrite = me.nationId > 0 && list.nation.some((g) => g.general.some(([id, , flags]) => id === me.generalId && (flags & FLAG_DIPLOMAT) === FLAG_DIPLOMAT));
    const own = MAILBOX_NATIONAL_BASE + me.nationId;
    const targets = list.nation
        .filter((g) => g.mailbox > MAILBOX_NATIONAL_BASE && g.mailbox !== own)
        .map((g) => ({ nationId: g.mailbox - MAILBOX_NATIONAL_BASE, mailbox: g.mailbox, name: g.name }))
        .sort((a, b) => a.name.localeCompare(b.name, 'ko'));
    return { canWrite, targets };
}
