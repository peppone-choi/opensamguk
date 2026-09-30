// 서신(P-Q02) 목록 모델 — 본인 봉투 `GET /api/mailbox/recent` 한 경로만 쓴다(작전실의 `/api/mailbox/{id}` 직접 조회는
// 소유 검사가 없는 경로라 버린다 — 계약판 S-01). K6 설계서 §3.8.
//
// - 탭: 개인 · 세력 · 전체(외교는 P-K02로 옮김). 요청 탭은 lib/requests.ts.
// - 방향: 보낸 장수 id가 나면 「보냄」, 아니면 「받음」(서버 src 라우팅 키 대신 보낸 사람 블록의 장수 id).
// - 엔진의 지우기 표식 행(text = 'req_del_msg')은 서신이 아니므로 뺀다. 지운 서신(option.invalid)은 「지운 서신입니다」.
import { isMessageDeletable, MAILBOX_NATIONAL_BASE, MAILBOX_PUBLIC } from '../mailbox';
import type { MailboxMessage } from '../../types/game';

export type MailScope = 'private' | 'national' | 'public';
export const MAIL_SCOPES: readonly MailScope[] = ['private', 'national', 'public'];
export const MAIL_SCOPE_LABEL: Readonly<Record<MailScope, string>> = { private: '개인', national: '세력', public: '전체' };

/** 봉투 한 행(서버 MessageArrayItem 그대로). */
export interface RecentMailRow {
    readonly id: number | null;
    readonly msgType: string;
    readonly src: RecentMailParty | null;
    readonly dest: RecentMailParty | null;
    readonly text: string;
    readonly option: Record<string, unknown> | null;
    readonly time: string;
}
export interface RecentMailParty {
    readonly id: number;
    readonly name: string;
    readonly nation_id: number;
    readonly nation: string;
    readonly color: string;
    readonly icon?: string | null;
}
export interface RecentMailEnvelope {
    readonly private: readonly RecentMailRow[];
    readonly public: readonly RecentMailRow[];
    readonly national: readonly RecentMailRow[];
    readonly diplomacy?: readonly RecentMailRow[];
    readonly sequence: number;
}

export interface MailParty {
    readonly generalId: number;
    readonly name: string;
    /** 재야 = null(서버가 nation_id 0으로 준 사실). */
    readonly nation: { readonly id: number; readonly name: string; readonly color: string } | null;
}

export interface MailItem {
    readonly id: number;
    readonly scope: MailScope;
    readonly direction: 'sent' | 'received';
    readonly from: MailParty | null;
    readonly to: MailParty | null;
    /** 서버가 정리한 서식 글(SafeHtml로 그린다). 지운 서신이면 null. */
    readonly html: string | null;
    readonly time: string;
    readonly deletable: boolean;
}

export const DELETE_MARKER_TEXT = 'req_del_msg';

function party(p: RecentMailParty | null): MailParty | null {
    if (!p || p.id <= 0) return null;
    return { generalId: p.id, name: p.name, nation: p.nation_id > 0 ? { id: p.nation_id, name: p.nation, color: p.color } : null };
}

export function mailboxIdOf(scope: MailScope, me: { generalId: number; nationId: number }): number | null {
    if (scope === 'public') return MAILBOX_PUBLIC;
    if (scope === 'national') return me.nationId > 0 ? MAILBOX_NATIONAL_BASE + me.nationId : null;
    return me.generalId;
}

export function toMailItems(env: RecentMailEnvelope, scope: MailScope, me: { generalId: number; nationId: number }, now = Date.now()): MailItem[] {
    const mailbox = mailboxIdOf(scope, me) ?? 0;
    const out: MailItem[] = [];
    for (const row of env[scope] ?? []) {
        if (row.id == null || row.text === DELETE_MARKER_TEXT) continue;
        const from = party(row.src);
        const option = row.option ?? {};
        const legacy: MailboxMessage = {
            id: row.id, mailbox, type: scope, src: row.src?.id ?? 0, dest: row.dest?.id ?? 0, time: row.time,
            validUntil: '', message: '', text: row.text, srcTarget: null, destTarget: null, option: row.option,
        };
        out.push({
            id: row.id,
            scope,
            direction: from?.generalId === me.generalId ? 'sent' : 'received',
            from,
            to: party(row.dest),
            html: option.invalid ? null : row.text,
            time: row.time,
            deletable: isMessageDeletable(legacy, me.generalId, now),
        });
    }
    return out;
}

/** 읽음 표시에 쓸 가장 큰 id(개인 탭 — 받은 서신만). 없으면 null. */
export function latestReceivedId(items: readonly MailItem[]): number | null {
    let max = 0;
    for (const it of items) if (it.direction === 'received' && it.id > max) max = it.id;
    return max > 0 ? max : null;
}
