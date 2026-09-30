// 서신(P-Q02) 목록 모델 — 본인 봉투 `GET /api/mailbox/recent` 한 경로만 쓴다(작전실의 `/api/mailbox/{id}` 직접 조회는
// 소유 검사가 없는 경로라 버린다 — 계약판 S-01). K6 설계서 §3.8.
//
// - 탭: 개인 · 세력 · 전체. 요청 탭은 lib/requests.ts. 외교 서신(diplomacy)은 P-K02 외교 화면의 칸이다 — 같은 모델을 쓴다.
// - 방향: 보낸 장수 id가 나면 「보냄」, 아니면 「받음」(서버 src 라우팅 키 대신 보낸 사람 블록의 장수 id).
//   외교 서신은 세력끼리 주고받으므로 보낸 세력이 우리 세력이면 「보냄」이다.
// - 엔진의 지우기 표식 행(text = 'req_del_msg')은 서신이 아니므로 뺀다. 지운 서신(option.invalid)은 「지운 서신입니다」.
// - 외교 서신은 서버가 권한(< 3)으로 가린다: 본문 '(외교 메시지입니다)' + option.invalid(MailboxController.applyDiplomacyMask).
//   가린 행은 지운 서신과 다르다 — hidden. 서버 가림은 option.used를 보지 않으므로 답한 제의(used + invalid)도 가려질 수 있다.
//   가린 행은 본문도 제의 종류도 보이지 않는다. 가리지 않은 답한 제의는 본문이 남는다(DiplomaticMessageHandler — used + invalid).
import { isMessageDeletable, MAILBOX_NATIONAL_BASE, MAILBOX_PUBLIC } from '../mailbox';
import type { MailboxMessage } from '../../types/game';

export type MailScope = 'private' | 'national' | 'public' | 'diplomacy';
/** 서신 화면의 기본 탭(외교는 따로 — 외교 화면 칸). */
export const MAIL_SCOPES: readonly MailScope[] = ['private', 'national', 'public'];
export const MAIL_SCOPE_LABEL: Readonly<Record<MailScope, string>> = { private: '개인', national: '세력', public: '전체', diplomacy: '외교' };

/** 외교 서신에 붙은 제의(option.action) — 엔진이 수락 명령을 아는 세 가지(즉시 국가 명령 표 acceptCommandKeyFor). */
export type DiplomacyProposal = 'no_aggression' | 'stop_war' | 'cancel_na';
export const PROPOSAL_LABEL: Readonly<Record<DiplomacyProposal, string>> = {
    no_aggression: '불가침 제의', stop_war: '종전 제의', cancel_na: '불가침 파기 제의',
};
/** 서버가 권한 없는 사람에게 외교 서신 본문 대신 주는 글자(MailboxController.applyDiplomacyMask). */
export const DIPLOMACY_MASK_TEXT = '(외교 메시지입니다)';

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
    /** 받는 세력 — 외교 서신은 받는 쪽이 장수 없이 세력만 온다(엔진 MsgTarget(0, "", 세력)). 재야 · 없음 = null. */
    readonly toNation: MailParty['nation'];
    /** 서버가 정리한 서식 글(SafeHtml로 그린다). 지운 서신이면 null. */
    readonly html: string | null;
    readonly time: string;
    readonly deletable: boolean;
    /** 외교 서신을 볼 권한이 없어 서버가 가린 행(본문 없음). */
    readonly hidden: boolean;
    /** 외교 서신에 붙은 제의 — 없으면 null. handled = 이미 수락 · 거절됨. */
    readonly proposal: { readonly kind: DiplomacyProposal; readonly handled: boolean } | null;
}

export const DELETE_MARKER_TEXT = 'req_del_msg';

function party(p: RecentMailParty | null): MailParty | null {
    if (!p || p.id <= 0) return null;
    return { generalId: p.id, name: p.name, nation: p.nation_id > 0 ? { id: p.nation_id, name: p.nation, color: p.color } : null };
}

export function mailboxIdOf(scope: MailScope, me: { generalId: number; nationId: number }): number | null {
    if (scope === 'public') return MAILBOX_PUBLIC;
    // 외교 서신도 우리 세력 서신함(9000 + 세력)에 쌓인다(MailboxController — diplomacy 칸).
    if (scope === 'national' || scope === 'diplomacy') return me.nationId > 0 ? MAILBOX_NATIONAL_BASE + me.nationId : null;
    return me.generalId;
}

const PROPOSALS: readonly string[] = ['no_aggression', 'stop_war', 'cancel_na'];
const truthy = (v: unknown) => v != null && v !== false && v !== 0 && v !== '0' && v !== '';

export function toMailItems(env: RecentMailEnvelope, scope: MailScope, me: { generalId: number; nationId: number }, now = Date.now()): MailItem[] {
    const mailbox = mailboxIdOf(scope, me) ?? 0;
    const out: MailItem[] = [];
    for (const row of env[scope] ?? []) {
        if (row.id == null || row.text === DELETE_MARKER_TEXT) continue;
        const from = party(row.src);
        const option = row.option ?? {};
        const diplomacy = scope === 'diplomacy';
        const used = diplomacy && truthy(option.used);
        const hidden = diplomacy && truthy(option.invalid) && row.text === DIPLOMACY_MASK_TEXT;
        const action = diplomacy && !hidden && typeof option.action === 'string' && PROPOSALS.includes(option.action) ? option.action as DiplomacyProposal : null;
        const mine = diplomacy ? from?.nation?.id === me.nationId && me.nationId > 0 : from?.generalId === me.generalId;
        const legacy: MailboxMessage = {
            id: row.id, mailbox, type: scope, src: row.src?.id ?? 0, dest: row.dest?.id ?? 0, time: row.time,
            validUntil: '', message: '', text: row.text, srcTarget: null, destTarget: null, option: row.option,
        };
        out.push({
            id: row.id,
            scope,
            direction: mine ? 'sent' : 'received',
            from,
            to: party(row.dest),
            toNation: row.dest && row.dest.nation_id > 0 ? { id: row.dest.nation_id, name: row.dest.nation, color: row.dest.color } : null,
            html: hidden ? null : used ? row.text : truthy(option.invalid) ? null : row.text,
            time: row.time,
            deletable: isMessageDeletable(legacy, me.generalId, now),
            hidden,
            proposal: action ? { kind: action, handled: used } : null,
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
